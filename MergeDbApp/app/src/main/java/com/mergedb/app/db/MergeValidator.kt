package com.mergedb.app.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Post-merge validation for GPS Joystick .db files.
 *
 * Supports two merge modes:
 *  - In-place merge : output size == original size, marker count unchanged.
 *  - Extension merge: output size  > original size, marker count increased by
 *                     the number of newly appended nodes.
 */
object MergeValidator {

    data class ValidationResult(
        val sizeOk: Boolean,
        val markerCountOk: Boolean,
        val allFinite: Boolean,
        val coordSeqOk: Boolean,
        val details: List<String>
    ) {
        val passed: Boolean get() = sizeOk && markerCountOk && allFinite && coordSeqOk
    }

    /**
     * Validate [mergedData] against its [originalData] (the host file before merging).
     *
     * When [guestData] is provided, performs a 4th check that verifies:
     *   - The first hostUsed lat/lon values in the merged output match the original host coords.
     *   - The gap point at index hostUsed has a latitude ≥ last_host_lat + 2.9° (≈300 km).
     *   - The next guestUsed values match the original guest coords.
     *   - Route UUID counts are reported (truncation is expected and non-fatal).
     *
     * For an extension merge the output will be larger than the original, so size and
     * marker-count checks are relaxed to "must not decrease".
     */
    fun validate(
        originalData: ByteArray,
        mergedData: ByteArray,
        guestData: ByteArray? = null
    ): ValidationResult {
        val details = mutableListOf<String>()

        // ── 1. Size check ─────────────────────────────────────────────────────
        val sizeOk: Boolean
        when {
            mergedData.size == originalData.size -> {
                details.add("OK 檔案大小一致: ${mergedData.size} bytes")
                sizeOk = true
            }
            mergedData.size > originalData.size -> {
                val delta = mergedData.size - originalData.size
                details.add(
                    "OK 檔案延伸合併: ${originalData.size} → ${mergedData.size} bytes " +
                    "(+$delta bytes, ${delta / 1024} KB)"
                )
                sizeOk = true
            }
            else -> {
                details.add(
                    "FAIL 檔案大小縮小: 原始=${originalData.size}, 合併後=${mergedData.size}"
                )
                sizeOk = false
            }
        }

        // ── 2. AAAA marker count check ────────────────────────────────────────
        val origMarkers   = RealmBinaryParser.findAllMarkers(originalData).size
        val mergedMarkers = RealmBinaryParser.findAllMarkers(mergedData).size
        val markerCountOk: Boolean
        when {
            mergedMarkers == origMarkers -> {
                details.add("OK AAAA 標記數量一致: $mergedMarkers")
                markerCountOk = true
            }
            mergedMarkers > origMarkers -> {
                val added = mergedMarkers - origMarkers
                details.add(
                    "OK AAAA 標記增加 (延伸節點): $origMarkers → $mergedMarkers (+$added 個新節點)"
                )
                markerCountOk = true
            }
            else -> {
                details.add(
                    "FAIL AAAA 標記數量減少: 原始=$origMarkers, 合併後=$mergedMarkers " +
                    "(可能位移錯位)"
                )
                markerCountOk = false
            }
        }

        // ── 3. Float64 finiteness check (lat/lon nodes only) ─────────────────
        // Altitude nodes (column 2) legitimately contain NaN in GPS Joystick
        // files. Checking all Float64 nodes would produce false failures, so we
        // restrict the check to the lat/lon leaf nodes identified by B-tree
        // traversal.
        val (latLeaves, lonLeaves) = RealmBinaryParser.findLatLonLeafNodes(mergedData)
        val coordLeafOffsets = (latLeaves + lonLeaves).map { it.offset }.toSet()
        val (floatNodes, _) = RealmBinaryParser.parse(mergedData)
        val buf = ByteBuffer.wrap(mergedData).order(ByteOrder.LITTLE_ENDIAN)
        var nanCount = 0
        var infCount = 0
        var checkedCount = 0
        for (node in floatNodes) {
            if (node.offset !in coordLeafOffsets) continue   // skip altitude/other columns
            checkedCount += node.count
            for (i in 0 until node.count) {
                val v = buf.getDouble(node.dataOffset + i * 8)
                if (v.isNaN())           nanCount++
                else if (v.isInfinite()) infCount++
            }
        }
        val allFinite = (nanCount == 0 && infCount == 0)
        if (allFinite) {
            details.add("OK 所有座標浮點數值均有效 (isFinite), 共 $checkedCount 個 (lat+lon)")
        } else {
            details.add("FAIL 座標中發現無效浮點數: NaN=$nanCount, Infinity=$infCount")
        }

        // ── 4. Coordinate sequence & UUID integrity check ─────────────────────
        // When guestData is available, verify the merged output actually contains
        // the original host coordinates, the 300 km gap point, and the original
        // guest coordinates in the correct order.
        val coordSeqOk: Boolean
        if (guestData != null) {
            val hostLats  = readFiniteLatValues(originalData)
            val guestLats = readFiniteLatValues(guestData)
            val mergedLats = readAllLatValues(mergedData)   // all slots, including padding

            val hostUsed  = hostLats.size
            val guestUsed = guestLats.size
            val expectedTotal = hostUsed + 1 + guestUsed   // +1 for the gap point

            // Sub-check A: first hostUsed values in merged == original host coords
            val hostMatch = hostUsed == 0 ||
                (mergedLats.size >= hostUsed &&
                 (0 until hostUsed).all { mergedLats[it] == hostLats[it] })

            // Sub-check B: gap point is ≥ 2.9° north of the last host coord (≈322 km)
            val gapLat = mergedLats.getOrNull(hostUsed)
            val gapOk = hostUsed == 0 || guestUsed == 0 ||
                (gapLat != null && (gapLat - hostLats.last()) >= 2.9)

            // Sub-check C: values at [hostUsed+1 .. hostUsed+guestUsed] == original guest coords
            val guestMatch = guestUsed == 0 ||
                (mergedLats.size >= hostUsed + 1 + guestUsed &&
                 (0 until guestUsed).all { mergedLats[hostUsed + 1 + it] == guestLats[it] })

            coordSeqOk = hostMatch && gapOk && guestMatch
            if (coordSeqOk) {
                details.add(
                    "OK 座標序列驗證通過: Host $hostUsed 點 + 跳轉點 + Guest $guestUsed 點 " +
                    "= $expectedTotal 點"
                )
            } else {
                val issues = buildList {
                    if (!hostMatch) add("Host 座標序列不符")
                    if (!gapOk)     add("跳轉點緯度差不足 2.9°")
                    if (!guestMatch) add("Guest 座標序列不符")
                }
                details.add("FAIL 座標序列驗證失敗: ${issues.joinToString(", ")}")
            }

            // UUID count report (truncation is expected behaviour, not a failure)
            val (_, hostStrNodes)   = RealmBinaryParser.parse(originalData)
            val (_, guestStrNodes)  = RealmBinaryParser.parse(guestData)
            val (_, mergedStrNodes) = RealmBinaryParser.parse(mergedData)
            val hostUuidCount   = RealmBinaryParser.extractRouteUuids(originalData, hostStrNodes).size
            val guestUuidCount  = RealmBinaryParser.extractRouteUuids(guestData,    guestStrNodes).size
            val mergedUuidCount = RealmBinaryParser.extractRouteUuids(mergedData,   mergedStrNodes).size
            val expectedUuidCount = hostUuidCount + guestUuidCount
            if (mergedUuidCount >= expectedUuidCount) {
                details.add("OK 路線 UUID 全數保留: $mergedUuidCount 條")
            } else {
                details.add(
                    "INFO 路線 UUID 部分截斷: 預期 $expectedUuidCount, " +
                    "實際 $mergedUuidCount 條 (受限於字串節點容量，不影響座標軌跡)"
                )
            }
        } else {
            coordSeqOk = true   // skip when guest data is not supplied
        }

        return ValidationResult(
            sizeOk = sizeOk,
            markerCountOk = markerCountOk,
            allFinite = allFinite,
            coordSeqOk = coordSeqOk,
            details = details
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Read only the finite latitude values from a file's lat B-tree leaves. */
    private fun readFiniteLatValues(data: ByteArray): List<Double> {
        val (latLeaves, _) = RealmBinaryParser.findLatLonLeafNodes(data)
        return buildList {
            for (leaf in latLeaves) {
                for (v in RealmBinaryParser.readFloat64Values(data, leaf)) {
                    if (v.isFinite()) add(v)
                }
            }
        }
    }

    /**
     * Read ALL latitude values from a file's lat B-tree leaves (including padding slots).
     * Used to index into the merged array at exact positions for sequence verification.
     */
    private fun readAllLatValues(data: ByteArray): List<Double> {
        val (latLeaves, _) = RealmBinaryParser.findLatLonLeafNodes(data)
        return buildList {
            for (leaf in latLeaves) {
                addAll(RealmBinaryParser.readFloat64Values(data, leaf).toList())
            }
        }
    }
}
