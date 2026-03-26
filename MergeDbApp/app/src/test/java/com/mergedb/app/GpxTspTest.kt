package com.mergedb.app

import com.mergedb.app.db.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM test — extracts a few route segments from every .db in /data/,
 * saves each as a .gpx file, then runs TspEngine.optimizePoints() on them.
 *
 * Output GPX files land in data/gpx_routes/.
 */
class GpxTspTest {

    @Test
    fun extractAndOptimizeGpx() {
        val dataDir = File("../../data")
        val outDir  = File(dataDir, "gpx_routes").also { it.mkdirs() }

        val dbFiles = dataDir.listFiles { f -> f.extension == "db" }
            ?: error("data/ not found at ${dataDir.absolutePath}")

        if (dbFiles.isEmpty()) { println("No .db files found"); return }

        val config = TspConfig(
            strategy  = TspStrategy.NEAREST_NEIGHBOR,
            optimizer = TspOptimizer.OPT_2,
            skipLargeThreshold  = 1024,
            improvementThreshold = 0.0,
            timeoutMs = 60_000L,
            maxConsecutiveJumpKm = 0.0  // no jump filter for GPX test
        )

        for (dbFile in dbFiles.sorted()) {
            println("\n" + "=".repeat(70))
            println("FILE: ${dbFile.name}")
            println("=".repeat(70))

            val data = dbFile.readBytes()
            if (!RealmBinaryParser.isValidDb(data)) {
                println("  ⚠ 不是有效 T-DB 檔案，略過")
                continue
            }

            val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(data) ?: run {
                println("  ERROR: 無法解析 B-Tree")
                continue
            }

            fun readFinite(leaves: List<RealmNode.Float64Leaf>) = buildList<Double> {
                for (leaf in leaves)
                    for (v in RealmBinaryParser.readFloat64Values(data, leaf))
                        if (v.isFinite()) add(v)
            }
            val lats = readFinite(bTreeInfo.latLeaves)
            val lons = readFinite(bTreeInfo.lonLeaves)

            val segments = RealmBinaryParser.findRouteSegmentIndices(data) ?: run {
                println("  ERROR: 無法取得分段索引")
                continue
            }

            println("共 ${segments.size} 段")

            // Pick up to 5 non-empty segments (largest first, to get interesting routes)
            val picked = segments
                .mapIndexed { i, idx -> i to idx }
                .filter { (_, idx) -> idx.size >= 5 }
                .sortedByDescending { (_, idx) -> idx.size }
                .take(5)

            println("選取 ${picked.size} 段進行 GPX 測試")

            val baseName = dbFile.nameWithoutExtension

            for ((segIdx, indices) in picked) {
                val points = indices.map { LatLon(lats[it], lons[it]) }
                val trackName = "${baseName}_seg${segIdx}"

                // ── Write input GPX ──────────────────────────────────────────
                val inputGpx = File(outDir, "${trackName}.gpx")
                inputGpx.writeText(buildGpx(points, trackName))
                println("\n  [段 $segIdx] ${points.size} 點 → ${inputGpx.name}")

                // ── Read back and verify ──────────────────────────────────────
                val readBack = parseGpxFile(inputGpx)
                check(readBack.size == points.size) {
                    "GPX round-trip 失敗：寫入 ${points.size} 點，讀回 ${readBack.size} 點"
                }
                val latErr = readBack.zip(points).maxOf { (a, b) -> Math.abs(a.lat - b.lat) }
                val lonErr = readBack.zip(points).maxOf { (a, b) -> Math.abs(a.lon - b.lon) }
                println("  round-trip OK  最大誤差: lat=%.9f  lon=%.9f".format(latErr, lonErr))

                // ── TSP optimisation ──────────────────────────────────────────
                val result = TspEngine.optimizePoints(
                    points   = readBack,
                    config   = config,
                    isCancelled = { false },
                    onProgress  = { done, total -> print("\r  TSP 進度 $done/$total") }
                )
                println()

                val r = result.routeResults.first()
                val pct    = if (r.originalLength > 0)
                    (r.originalLength - r.optimizedLength) / r.originalLength * 100 else 0.0
                val origKm = r.originalLength / 1000.0
                val optKm  = r.optimizedLength / 1000.0

                val status = when {
                    r.skipped  -> "略過: ${r.reason}"
                    r.improved -> "改善 %.1f%%  (%.2f → %.2f km)".format(pct, origKm, optKm)
                    else       -> "未採用 %.1f%%  (%.2f → %.2f km)  ${r.reason}".format(pct, origKm, optKm)
                }
                println("  TSP: $status")

                // ── Write optimised GPX ───────────────────────────────────────
                val optPoints = result.reorderedLats.zip(result.reorderedLons)
                    .map { (lat, lon) -> LatLon(lat, lon) }
                val optGpx = File(outDir, "${trackName}_tsp.gpx")
                optGpx.writeText(buildGpx(optPoints, "$trackName (TSP)"))
                println("  → 優化後輸出: ${optGpx.name}")
            }
        }

        println("\n" + "=".repeat(70))
        println("完成  GPX 輸出目錄: ${outDir.absolutePath}")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildGpx(points: List<LatLon>, trackName: String): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="MergeDB GpxTspTest">""")
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>${trackName.xmlEscape()}</name>")
        sb.appendLine("    <trkseg>")
        for (pt in points) {
            sb.appendLine("""      <trkpt lat="${pt.lat}" lon="${pt.lon}"></trkpt>""")
        }
        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    private fun parseGpxFile(file: File): List<LatLon> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        doc.documentElement.normalize()
        val nodeList = doc.getElementsByTagName("trkpt")
        return (0 until nodeList.length).map { i ->
            val el = nodeList.item(i) as Element
            val lat = el.getAttribute("lat").toDouble()
            val lon = el.getAttribute("lon").toDouble()
            LatLon(lat, lon)
        }
    }

    private fun String.xmlEscape() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
