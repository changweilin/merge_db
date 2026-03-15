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
        val details: List<String>
    ) {
        val passed: Boolean get() = sizeOk && markerCountOk && allFinite
    }

    /**
     * Validate [mergedData] against its [originalData] (the host file before merging).
     *
     * For an extension merge the output will be larger than the original, so size and
     * marker-count checks are relaxed to "must not decrease".
     */
    fun validate(originalData: ByteArray, mergedData: ByteArray): ValidationResult {
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

        return ValidationResult(
            sizeOk = sizeOk,
            markerCountOk = markerCountOk,
            allFinite = allFinite,
            details = details
        )
    }
}
