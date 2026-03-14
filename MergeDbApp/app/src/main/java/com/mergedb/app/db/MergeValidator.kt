package com.mergedb.app.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Post-merge validation to ensure the merged .db file is structurally sound.
 */
object MergeValidator {

    data class ValidationResult(
        val sizeMatch: Boolean,
        val markerCountMatch: Boolean,
        val allFinite: Boolean,
        val details: List<String>
    ) {
        val passed: Boolean get() = sizeMatch && markerCountMatch && allFinite
    }

    fun validate(originalData: ByteArray, mergedData: ByteArray): ValidationResult {
        val details = mutableListOf<String>()

        // Check 1: File size must be identical
        val sizeMatch = originalData.size == mergedData.size
        if (sizeMatch) {
            details.add("OK 檔案大小一致: ${mergedData.size} bytes")
        } else {
            details.add("FAIL 檔案大小不一致: 原始=${originalData.size}, 合併後=${mergedData.size}")
        }

        // Check 2: AAAA marker count must be unchanged
        val originalMarkers = RealmBinaryParser.findAllMarkers(originalData).size
        val mergedMarkers = RealmBinaryParser.findAllMarkers(mergedData).size
        val markerCountMatch = originalMarkers == mergedMarkers
        if (markerCountMatch) {
            details.add("OK AAAA 標記數量一致: $mergedMarkers")
        } else {
            details.add("FAIL AAAA 標記數量變動: 原始=$originalMarkers, 合併後=$mergedMarkers (可能位移錯位)")
        }

        // Check 3: All Float64 values must be finite (no NaN or Infinity)
        val (floatNodes, _) = RealmBinaryParser.parse(mergedData)
        val buf = ByteBuffer.wrap(mergedData).order(ByteOrder.LITTLE_ENDIAN)
        var allFinite = true
        var nanCount = 0
        var infCount = 0
        for (node in floatNodes) {
            for (i in 0 until node.count) {
                val value = buf.getDouble(node.dataOffset + i * 8)
                if (value.isNaN()) {
                    nanCount++
                    allFinite = false
                } else if (value.isInfinite()) {
                    infCount++
                    allFinite = false
                }
            }
        }
        if (allFinite) {
            details.add("OK 所有浮點數值均有效 (isFinite)")
        } else {
            details.add("FAIL 發現無效浮點數: NaN=$nanCount, Infinity=$infCount")
        }

        return ValidationResult(
            sizeMatch = sizeMatch,
            markerCountMatch = markerCountMatch,
            allFinite = allFinite,
            details = details
        )
    }
}
