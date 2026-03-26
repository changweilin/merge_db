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
