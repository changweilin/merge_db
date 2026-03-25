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

                // Analyse coordinate structure
                val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(data)
                    ?: error("無法解析 B-Tree 結構")

                fun readFinite(leaves: List<RealmNode.Float64Leaf>) = buildList<Double> {
                    for (leaf in leaves) {
                        for (v in RealmBinaryParser.readFloat64Values(data, leaf)) {
                            if (v.isFinite()) add(v)
                        }
                    }
                }

                val lats = readFinite(bTreeInfo.latLeaves)
                val lons = readFinite(bTreeInfo.lonLeaves)
                val pairs = minOf(lats.size, lons.size)
                val split = splitByGap(lats.take(pairs), lons.take(pairs))

                val info = RealmBinaryParser.buildFileInfo(fileName, data)
                _state.value = TspState.Ready(
                    info = info,
                    routeCount = split.segments.size,
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
