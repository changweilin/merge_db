package com.mergedb.app

import com.mergedb.app.db.*
import org.junit.Test
import java.io.File

/**
 * JVM test — run with:  ./gradlew :app:test  (or  .\gradlew :app:test  on Windows)
 *
 * Prints segmentation info and TSP results for every .db file found under
 * the project-root /data/ directory.
 */
class TspRunnerTest {

    @Test
    fun runTsp() {
        // Locate .db files relative to this module's working directory (project root)
        val dataDir = File("../../data")
        val dbFiles = dataDir.listFiles { f -> f.extension == "db" }
            ?: error("data/ directory not found at ${dataDir.absolutePath}")

        if (dbFiles.isEmpty()) {
            println("No .db files found in ${dataDir.absolutePath}")
            return
        }

        for (dbFile in dbFiles.sorted()) {
            println("\n" + "=".repeat(70))
            println("FILE: ${dbFile.name}  (${dbFile.length() / 1024} KB)")
            println("=".repeat(70))

            val data = dbFile.readBytes()
            if (!RealmBinaryParser.isValidDb(data)) {
                println("  ⚠ Not a valid T-DB file, skipping")
                continue
            }

            // ── Segmentation info ────────────────────────────────────────────
            val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(data)
            if (bTreeInfo == null) {
                println("  ERROR: cannot parse B-Tree")
                continue
            }

            val ridLeaf = bTreeInfo.routeIdLeaves?.firstOrNull()
            val strategy = if (ridLeaf != null)
                "Strategy 1 (route-ID column  type=0x%02X  bpe=%d)"
                    .format(ridLeaf.type.toInt() and 0xFF, ridLeaf.bytesPerEntry)
            else "Strategy 2 (gap fallback)"
            println("Segmentation: $strategy")

            fun readFinite(leaves: List<RealmNode.Float64Leaf>) = buildList<Double> {
                for (leaf in leaves)
                    for (v in RealmBinaryParser.readFloat64Values(data, leaf))
                        if (v.isFinite()) add(v)
            }
            val lats = readFinite(bTreeInfo.latLeaves)
            val lons = readFinite(bTreeInfo.lonLeaves)
            val pairs = minOf(lats.size, lons.size)

            val (_, stringNodes) = RealmBinaryParser.parse(data)
            val uuidCount = RealmBinaryParser.extractRouteUuids(data, stringNodes).size
            val segments  = RealmBinaryParser.findRouteSegments(data)
            println("UUID 數: $uuidCount   Segments: ${segments?.size ?: "null"}   總座標: $pairs")

            if (uuidCount != (segments?.size ?: -1)) {
                println("  ⚠  UUID 數與 segment 數不一致！")
            }

            println()
            segments?.forEachIndexed { i, r ->
                val indices = (r.first..r.last).filter { it < pairs }
                if (indices.isEmpty()) {
                    println("  [%2d] 空段 range=${r.first}..${r.last}".format(i))
                    return@forEachIndexed
                }
                val sl = indices.map { lats[it] }
                val so = indices.map { lons[it] }
                val pts = indices.map { com.mergedb.app.db.LatLon(lats[it], lons[it]) }
                val maxJumpKm = TspEngine.maxConsecutiveJump(pts) / 1000.0
                val jumpFlag = if (maxJumpKm > 500.0) "  ⚠ 最大跳躍 ${"%.0f".format(maxJumpKm)} km" else ""
                println("  [%2d] %4d pts  lat=[%8.4f, %8.4f]  lon=[%9.4f, %9.4f]$jumpFlag"
                    .format(i, sl.size, sl.min(), sl.max(), so.min(), so.max()))
            }

            // ── Route-ID adjacency analysis ──────────────────────────────────
            println()
            println("--- 空 UUID 與大跳躍路線關聯分析 ---")
            val ridLeaves = bTreeInfo.routeIdLeaves
            if (ridLeaves == null) {
                println("  (Strategy 2，無路線 ID 欄可分析)")
            } else {
                val ids = RealmBinaryParser.readIntLeafValues(data, ridLeaves)
                if (ids.size != pairs) {
                    println("  ⚠ ID 數 (${ids.size}) ≠ pairs ($pairs)，略過")
                } else {
                    // 重建 segIdx → routeId 對映，與 findRouteSegments Strategy 1 邏輯相同
                    val idOrder2   = mutableListOf<Long>()
                    val idFirstMap = linkedMapOf<Long, Int>()
                    val idLastMap  = linkedMapOf<Long, Int>()
                    val idCount    = linkedMapOf<Long, Int>()
                    for (i in ids.indices) {
                        val id = ids[i]
                        if (!idFirstMap.containsKey(id)) { idOrder2.add(id); idFirstMap[id] = i }
                        idLastMap[id] = i
                        idCount[id] = (idCount[id] ?: 0) + 1
                    }

                    // 找出大跳躍段索引（同前）
                    val largeJumpIdx = mutableSetOf<Int>()
                    segments?.forEachIndexed { si, r ->
                        val idx2 = (r.first..r.last).filter { it < pairs }
                        if (idx2.isEmpty()) return@forEachIndexed
                        val pts = idx2.map { LatLon(lats[it], lons[it]) }
                        if (TspEngine.maxConsecutiveJump(pts) / 1000.0 > 500.0) largeJumpIdx.add(si)
                    }

                    if (largeJumpIdx.isEmpty()) {
                        println("  無大跳躍路線 (所有相鄰跳躍 ≤ 500 km)")
                    } else {
                        for (si in largeJumpIdx.sorted()) {
                            val routeId = idOrder2.getOrNull(si) ?: continue
                            val rangeSize = (idLastMap[routeId] ?: 0) - (idFirstMap[routeId] ?: 0) + 1
                            val actualCount = idCount[routeId] ?: 0
                            val foreignCount = rangeSize - actualCount

                            println()
                            println("  大跳躍段 [%3d]  routeId=%-6d  range=%d..%d  range大小=%d  實際自身點=%d  外來點=%d"
                                .format(si, routeId,
                                    idFirstMap[routeId] ?: -1, idLastMap[routeId] ?: -1,
                                    rangeSize, actualCount, foreignCount))

                            // 外來點屬於哪些路線？
                            if (foreignCount > 0) {
                                val r = segments?.getOrNull(si) ?: continue
                                val foreignIds = (r.first..r.last)
                                    .filter { it < pairs && ids[it] != routeId }
                                    .map { ids[it] }
                                    .groupingBy { it }.eachCount()
                                    .entries.sortedByDescending { it.value }
                                    .take(5)
                                print("    外來 ID：")
                                for ((fid, cnt) in foreignIds) {
                                    val fidSegIdx = idOrder2.indexOf(fid)
                                    print(" id=$fid(段[$fidSegIdx], ${cnt}點)")
                                }
                                println()
                            }

                            // 印出 ±2 鄰段資訊
                            for (neighbor in (si - 2)..(si + 2)) {
                                val nr = segments?.getOrNull(neighbor) ?: continue
                                val nid = idOrder2.getOrNull(neighbor) ?: continue
                                val nIdx = (nr.first..nr.last).filter { it < pairs }
                                if (nIdx.isEmpty()) { println("    [%3d] 空段".format(neighbor)); continue }
                                val nPts = nIdx.map { LatLon(lats[it], lons[it]) }
                                val nJump = TspEngine.maxConsecutiveJump(nPts) / 1000.0
                                val nRange = (idLastMap[nid] ?: 0) - (idFirstMap[nid] ?: 0) + 1
                                val nActual = idCount[nid] ?: 0
                                val marker = if (neighbor == si) ">>>" else "   "
                                println("  $marker [%3d] %4d pts  foreign=%d  maxJump=%.0f km"
                                    .format(neighbor, nIdx.size, nRange - nActual, nJump))
                            }
                        }
                    }

                    // UUID vs segment 差異摘要
                    val diff = uuidCount - (segments?.size ?: 0)
                    if (diff != 0) {
                        println()
                        val totalForeign = largeJumpIdx.sumOf { si ->
                            val rid = idOrder2.getOrNull(si) ?: return@sumOf 0
                            val rangeSize2 = (idLastMap[rid] ?: 0) - (idFirstMap[rid] ?: 0) + 1
                            rangeSize2 - (idCount[rid] ?: rangeSize2)
                        }
                        println("  UUID 超出 segment 數 $diff 個")
                        println("  大跳躍段中「外來座標」合計: $totalForeign 點")
                        println("  → ${if (totalForeign > 0) "外來座標可能來自空 UUID 路線（成因相同）" else "與空 UUID 無直接關聯（獨立問題）"}")
                    }
                }
            }

            // ── DB structure probe (why route-ID column may not be found) ────
            println()
            println("--- DB Structure ---")
            val nodeTypes = RealmBinaryParser.scanNodeTypes(data)
            for (t in nodeTypes) {
                println("  type=0x%02X  nodes=%3d  totalEntries=%6d  bpe=%d"
                    .format(t.typeByte, t.count, t.totalEntries, t.bytesPerEntry))
            }
            println()
            val tables = RealmBinaryParser.probeAllTables(data)
            for (tbl in tables) {
                println("  Table@0x%X  (${tbl.columns.size} cols)".format(tbl.node46Offset))
                for (col in tbl.columns) {
                    val types2 = col.childTypes.joinToString(",") { "0x%02X".format(it) }
                    println("    col[${col.columnIndex}]  leaves=${col.leafCount}  entries=${col.entryCount}  types=[$types2]")
                }
            }

            // ── Dump raw bytes of 0x44 nodes to understand link-column format ──
            println()
            println("--- 0x44 node raw dump (first 5 nodes, first 40 bytes each) ---")
            val markers = RealmBinaryParser.findAllMarkers(data)
            var dumped = 0
            for (i in markers.indices) {
                if (dumped >= 5) break
                val off = markers[i]
                if (off + 8 > data.size) continue
                val t = data[off + 4].toInt() and 0xFF
                if (t != 0x44) continue
                val cnt = ((data[off+5].toInt() and 0xFF) shl 16) or
                          ((data[off+6].toInt() and 0xFF) shl  8) or
                          (data[off+7].toInt() and 0xFF)
                val dataStart = off + 8
                val dataEnd   = if (i + 1 < markers.size) markers[i + 1] else data.size
                val span      = dataEnd - dataStart
                println("  off=0x%X  count=$cnt  dataSpan=$span  bpe=${if(cnt>0) span/cnt else -1}"
                    .format(off))
                val preview = data.copyOfRange(dataStart, minOf(dataStart + 40, data.size))
                println("  bytes: " + preview.joinToString(" ") { "0x%02X".format(it.toInt() and 0xFF) })
                dumped++
            }

            // ── TSP ──────────────────────────────────────────────────────────
            println()
            println("--- TSP (NearestNeighbor + 2-Opt, threshold=0%) ---")
            val config = TspConfig(
                strategy = TspStrategy.NEAREST_NEIGHBOR,
                optimizer = TspOptimizer.OPT_2,
                skipLargeThreshold = 1024,
                improvementThreshold = 0.0,
                timeoutMs = 120_000L,
                maxConsecutiveJumpKm = 500.0
            )

            try {
                val result = TspEngine.optimizeDb(
                    data = data,
                    config = config,
                    isCancelled = { false },
                    onProgress = { done, total -> print("\r  優化中 $done/$total ...") }
                )
                println("\r  優化完成" + " ".repeat(20))
                println("  總路線: ${result.totalRoutes}   改善: ${result.improvedRoutes}   略過: ${result.skippedRoutes}")
                println()
                result.routeResults.forEach { r ->
                    val pct = if (r.originalLength > 0)
                        (r.originalLength - r.optimizedLength) / r.originalLength * 100 else 0.0
                    val origKm = r.originalLength / 1000.0
                    val optKm  = r.optimizedLength / 1000.0
                    val pctStr = "%.1f".format(pct)
                    val status = when {
                        r.skipped  -> "略過       ${r.reason}"
                        r.improved -> "改善 ${pctStr}%  ${"%.2f".format(origKm)} → ${"%.2f".format(optKm)} km"
                        else       -> "未採用 ${pctStr}%  ${"%.2f".format(origKm)} → ${"%.2f".format(optKm)} km  ${r.reason}"
                    }
                    println("  [${r.index.toString().padStart(3)}] $status")
                }
            } catch (e: Exception) {
                println("  TSP 失敗: ${e.message}")
            }
        }
        println("\n" + "=".repeat(70))
        println("完成")
    }
}
