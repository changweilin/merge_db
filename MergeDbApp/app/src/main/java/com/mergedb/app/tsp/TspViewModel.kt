package com.mergedb.app.tsp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mergedb.app.db.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

sealed class TspState {
    data object Idle : TspState()
    data class Loading(val fileName: String) : TspState()
    data class Ready(val info: DbFileInfo, val routeCount: Int, val totalPoints: Int) : TspState()
    data class Optimizing(val current: Int, val total: Int) : TspState()
    data class Done(val result: TspResult) : TspState()
    data class Error(val message: String) : TspState()
    data class DbStructure(val report: String) : TspState()
}

class TspViewModel : ViewModel() {

    private val _state = MutableStateFlow<TspState>(TspState.Idle)
    val state: StateFlow<TspState> = _state

    private val _config = MutableStateFlow(TspConfig())
    val config: StateFlow<TspConfig> = _config

    private var fileData: ByteArray? = null
    private var optimizeJob: Job? = null

    // ── Config updates ────────────────────────────────────────────────────────

    fun setStrategy(strategy: TspStrategy) {
        _config.value = _config.value.copy(strategy = strategy)
    }

    fun setOptimizer(optimizer: TspOptimizer) {
        _config.value = _config.value.copy(optimizer = optimizer)
    }

    fun setSkipLargeThreshold(n: Int) {
        _config.value = _config.value.copy(skipLargeThreshold = n)
    }

    fun setImprovementThreshold(pct: Double) {
        _config.value = _config.value.copy(improvementThreshold = pct)
    }

    fun setTimeoutMs(ms: Long) {
        _config.value = _config.value.copy(timeoutMs = ms)
    }

    // ── File loading ──────────────────────────────────────────────────────────

    fun loadFile(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = getFileName(uri, context) ?: "unknown.db"
            _state.value = TspState.Loading(fileName)
            try {
                val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("無法讀取檔案")
                if (!RealmBinaryParser.isValidDb(data)) {
                    error("不是有效的 GPS Joystick .db 檔案 (缺少 T-DB 標頭)")
                }
                fileData = data

                val info = RealmBinaryParser.buildFileInfo(fileName, data)
                // totalPoints: count of valid finite coordinate pairs
                val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(data)
                val pairs = if (bTreeInfo != null) {
                    fun readFinite(leaves: List<RealmNode.Float64Leaf>) = buildList<Double> {
                        for (leaf in leaves)
                            for (v in RealmBinaryParser.readFloat64Values(data, leaf))
                                if (v.isFinite()) add(v)
                    }
                    minOf(readFinite(bTreeInfo.latLeaves).size,
                          readFinite(bTreeInfo.lonLeaves).size)
                } else 0

                _state.value = TspState.Ready(
                    info = info,
                    routeCount = info.routeSegmentCount,
                    totalPoints = pairs
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = TspState.Error("載入失敗: ${e.message}")
            }
        }
    }

    // ── Optimisation ──────────────────────────────────────────────────────────

    fun runOptimize() {
        val data = fileData ?: return
        optimizeJob?.cancel()
        optimizeJob = viewModelScope.launch(Dispatchers.Default) {
            _state.value = TspState.Optimizing(0, 1)
            try {
                val result = TspEngine.optimizeDb(
                    data = data,
                    config = _config.value,
                    isCancelled = { !isActive },
                    onProgress = { done, total ->
                        _state.value = TspState.Optimizing(done, total)
                    }
                )
                _state.value = TspState.Done(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = TspState.Error("優化失敗: ${e.message}")
            }
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportResult(outputUri: Uri, context: Context) {
        val data = fileData ?: return
        val result = (_state.value as? TspState.Done)?.result ?: return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rewritten = RealmFileExtender.rewriteCoordinates(
                    sourceData = data,
                    newLats = result.reorderedLats,
                    newLons = result.reorderedLons
                )
                context.contentResolver.openOutputStream(outputUri)?.use { it.write(rewritten) }
                    ?: error("無法寫入輸出檔案")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = TspState.Error("匯出失敗: ${e.message}")
            }
        }
    }

    fun reset() {
        optimizeJob?.cancel()
        fileData = null
        _state.value = TspState.Idle
    }

    fun dismissError() {
        _state.value = TspState.Idle
    }

    /** Scan DB node types and table structure; result shown in DbStructure state. */
    fun analyzeDbStructure() {
        val data = fileData ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val sb = StringBuilder()

            // ── Segmentation strategy used ───────────────────────────────────
            sb.appendLine("=== Route Segmentation ===")
            val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(data)
            if (bTreeInfo == null) {
                sb.appendLine("ERROR: cannot parse B-Tree")
            } else {
                val ridLeaf = bTreeInfo.routeIdLeaves?.firstOrNull()
                val strategy = if (ridLeaf != null)
                    "Strategy 1: route-ID column (type=0x%02X, bpe=%d)"
                        .format(ridLeaf.type.toInt() and 0xFF, ridLeaf.bytesPerEntry)
                else "Strategy 2: gap fallback (no integer column found)"
                sb.appendLine(strategy)
                val segments = RealmBinaryParser.findRouteSegments(data)
                sb.appendLine("Segments: ${segments?.size ?: "null"}")
                segments?.take(10)?.forEachIndexed { i, r ->
                    sb.appendLine("  [$i] positions ${r.first}..${r.last}  (${r.last - r.first + 1} pts)")
                }
                if ((segments?.size ?: 0) > 10) sb.appendLine("  … (showing first 10)")
            }
            sb.appendLine()

            // ── Node type summary ────────────────────────────────────────────
            sb.appendLine("=== Node Types ===")
            val types = RealmBinaryParser.scanNodeTypes(data)
            for (t in types) {
                sb.appendLine(
                    "type=0x%02X  nodes=%d  totalEntries=%d  bytesPerEntry=%d"
                        .format(t.typeByte, t.count, t.totalEntries, t.bytesPerEntry)
                )
            }

            // ── Table (0x46) structure ────────────────────────────────────────
            sb.appendLine()
            sb.appendLine("=== Tables (0x46 nodes) ===")
            val tables = RealmBinaryParser.probeAllTables(data)
            for (tbl in tables) {
                sb.appendLine("Table @ 0x%X  (${tbl.columns.size} cols)".format(tbl.node46Offset))
                for (col in tbl.columns) {
                    val types2 = col.childTypes.joinToString(",") { "0x%02X".format(it) }
                    sb.appendLine(
                        "  col[${col.columnIndex}]  leaves=${col.leafCount}  entries=${col.entryCount}  leafTypes=[$types2]"
                    )
                }
            }

            _state.value = TspState.DbStructure(sb.toString())
        }
    }

    private fun getFileName(uri: Uri, context: Context): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        }
    }
}
