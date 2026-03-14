package com.mergedb.app.db

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Performs a lossless merge of two GPS Joystick Realm .db files by extending
 * the host file rather than truncating guest data to fit existing capacity.
 *
 * Strategy (Realm format 7, binary-level):
 *   1. Fill all existing lat/lon leaf nodes with host + gap + guest data.
 *   2. If guest data overflows the existing capacity, append new 0x0C leaf nodes
 *      at the end of the file.
 *   3. Build a new 0xC6 B-Tree root that references all leaf nodes (old + new).
 *   4. Update the two 4-byte pointers in the 0x46 field-pointer node to point
 *      to the new C6 roots — only 8 bytes are modified in the existing file body.
 *
 * The output is a valid Realm format 7 file (same T-DB header, same schema,
 * 8-byte-aligned nodes) that GPS Joystick can read without any SDK migration.
 *
 * Sentinel values confirmed from actual binary inspection of GPS Joystick .db files:
 *   C6 refs[0]       = 0x000007D1  (LE: D1 07 00 00)
 *   C6 refs[count-1] = 0x00002485  (LE: 85 24 00 00)
 */
object RealmFileExtender {

    private const val MAX_VALUES_PER_LEAF = 1000

    // Realm format-7 B-Tree sentinel constants (confirmed by binary inspection)
    private const val SENTINEL_START = 0x000007D1
    private const val SENTINEL_END   = 0x00002485

    data class ExtendResult(
        val data: ByteArray,
        val wasExtended: Boolean,      // true when new leaf nodes were appended
        val totalCoordinates: Int,     // total lat/lon pairs in output
        val addedCoordinates: Int      // how many guest coordinates were added
    )

    /**
     * Merge guestData into hostData without any data loss.
     *
     * When the combined coordinate count exceeds the host's existing leaf capacity,
     * new leaf nodes are appended to the file and the B-Tree root pointers are updated.
     * Otherwise the merge is performed entirely in-place (same as MergeEngine.merge).
     */
    fun extendMerge(
        hostData: ByteArray,
        guestData: ByteArray,
        hostInfo: DbFileInfo,
        guestInfo: DbFileInfo
    ): ExtendResult {
        // ── 1. Locate the B-Tree structure in the host file ──────────────────
        val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(hostData)
            ?: error("無法解析 Host 檔案的 B-Tree 結構")

        // ── 2. Read all finite coordinate values from both files ──────────────
        val hostLatValues = readFiniteValues(hostData, bTreeInfo.latLeaves)
        val hostLonValues = readFiniteValues(hostData, bTreeInfo.lonLeaves)

        val (guestLatLeaves, guestLonLeaves) = RealmBinaryParser.findLatLonLeafNodes(guestData)
        val guestLatValues = readFiniteValues(guestData, guestLatLeaves)
        val guestLonValues = readFiniteValues(guestData, guestLonLeaves)

        // ── 3. Build combined arrays with 300 km gap (no truncation) ──────────
        val combinedLat = ArrayList<Double>(hostLatValues.size + guestLatValues.size + 1)
        val combinedLon = ArrayList<Double>(hostLonValues.size + guestLonValues.size + 1)

        val hostPairs = minOf(hostLatValues.size, hostLonValues.size)
        for (i in 0 until hostPairs) {
            combinedLat.add(hostLatValues[i])
            combinedLon.add(hostLonValues[i])
        }
        val guestPairs = minOf(guestLatValues.size, guestLonValues.size)
        if (hostPairs > 0 && guestPairs > 0) {
            // Insert a gap point that is >300 km away so GPS Joystick treats them
            // as separate route segments.
            combinedLat.add(combinedLat.last() + 3.0)  // +3° lat ≈ 333 km
            combinedLon.add(combinedLon.last())
        }
        for (i in 0 until guestPairs) {
            combinedLat.add(guestLatValues[i])
            combinedLon.add(guestLonValues[i])
        }

        val existingCapacity = bTreeInfo.latLeaves.sumOf { it.count }
        val totalNeeded = combinedLat.size

        // ── 4. Always write combined data into existing leaf nodes ────────────
        val output = hostData.copyOf()
        val outBuf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        writeToLeaves(outBuf, bTreeInfo.latLeaves, combinedLat)
        writeToLeaves(outBuf, bTreeInfo.lonLeaves, combinedLon)

        // Update UUIDs in the host's string node (best-effort, same as before)
        mergeUuids(output, hostInfo, guestInfo)

        // ── 5. If everything fits, return the in-place result ─────────────────
        if (totalNeeded <= existingCapacity) {
            return ExtendResult(
                data = output,
                wasExtended = false,
                totalCoordinates = totalNeeded,
                addedCoordinates = guestPairs
            )
        }

        // ── 6. Extension needed: build overflow leaf nodes ────────────────────
        val overflowLat = combinedLat.subList(existingCapacity, totalNeeded)
        val overflowLon = combinedLon.subList(existingCapacity, totalNeeded)

        val extension = ByteArrayOutputStream()
        var nextOffset = hostData.size  // new nodes are appended after the original file

        // New lat leaf nodes
        val newLatOffsets = mutableListOf<Int>()
        var vi = 0
        while (vi < overflowLat.size) {
            val chunk = overflowLat.subList(vi, minOf(vi + MAX_VALUES_PER_LEAF, overflowLat.size))
            newLatOffsets.add(nextOffset)
            val node = buildLeafNode(chunk)
            extension.write(node)
            nextOffset += node.size
            vi += chunk.size
        }

        // New lon leaf nodes
        val newLonOffsets = mutableListOf<Int>()
        vi = 0
        while (vi < overflowLon.size) {
            val chunk = overflowLon.subList(vi, minOf(vi + MAX_VALUES_PER_LEAF, overflowLon.size))
            newLonOffsets.add(nextOffset)
            val node = buildLeafNode(chunk)
            extension.write(node)
            nextOffset += node.size
            vi += chunk.size
        }

        // New C6 B-Tree roots  (all old leaf offsets + new leaf offsets)
        val allLatOffsets = bTreeInfo.latLeaves.map { it.offset } + newLatOffsets
        val newLatC6Offset = nextOffset
        val latC6 = buildC6Root(allLatOffsets)
        extension.write(latC6)
        nextOffset += latC6.size

        val allLonOffsets = bTreeInfo.lonLeaves.map { it.offset } + newLonOffsets
        val newLonC6Offset = nextOffset
        val lonC6 = buildC6Root(allLonOffsets)
        extension.write(lonC6)

        // ── 7. Patch the 0x46 entries to point at the new C6 roots ───────────
        // Each entry in the 0x46 data area is a 4-byte LE uint32.
        val lat46Pos = bTreeInfo.node46Offset + 8 + bTreeInfo.latColIndexIn46 * 4
        val lon46Pos = bTreeInfo.node46Offset + 8 + bTreeInfo.lonColIndexIn46 * 4
        outBuf.putInt(lat46Pos, newLatC6Offset)
        outBuf.putInt(lon46Pos, newLonC6Offset)

        // ── 8. Concatenate original (patched) + extension ─────────────────────
        val finalBytes = output + extension.toByteArray()
        return ExtendResult(
            data = finalBytes,
            wasExtended = true,
            totalCoordinates = totalNeeded,
            addedCoordinates = guestPairs
        )
    }

    // ── Node builders ─────────────────────────────────────────────────────────

    /**
     * Build a 0x0C Float64 leaf node for [values], padded to an 8-byte boundary.
     *
     * Layout:
     *   [AAAA] [0x0C] [count: 3-byte BE] [count × 8-byte LE Float64] [0x00 padding]
     */
    private fun buildLeafNode(values: List<Double>): ByteArray {
        val count = values.size
        val rawSize = 8 + count * 8
        val alignedSize = alignUp8(rawSize)
        val bytes = ByteArray(alignedSize)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        writeNodeHeader(bytes, 0x0C, count)
        for (i in values.indices) {
            buf.putDouble(8 + i * 8, values[i])
        }
        return bytes
    }

    /**
     * Build a 0xC6 B-Tree index node referencing [leafOffsets], padded to 8 bytes.
     *
     * Layout:
     *   [AAAA] [0xC6] [count: 3-byte BE]
     *   [SENTINEL_START: 4-byte LE] [leafOffset×4-byte LE …] [SENTINEL_END: 4-byte LE]
     *   [0x00 padding]
     *
     * count = leafOffsets.size + 2  (includes both sentinels)
     */
    private fun buildC6Root(leafOffsets: List<Int>): ByteArray {
        val count = leafOffsets.size + 2
        val rawSize = 8 + count * 4
        val alignedSize = alignUp8(rawSize)
        val bytes = ByteArray(alignedSize)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        writeNodeHeader(bytes, 0xC6, count)
        buf.putInt(8, SENTINEL_START)
        for (i in leafOffsets.indices) {
            buf.putInt(8 + (i + 1) * 4, leafOffsets[i])
        }
        buf.putInt(8 + (leafOffsets.size + 1) * 4, SENTINEL_END)
        return bytes
    }

    /** Write the 8-byte node header: [AAAA][type][count as 3-byte BE]. */
    private fun writeNodeHeader(bytes: ByteArray, type: Int, count: Int) {
        bytes[0] = 0x41; bytes[1] = 0x41; bytes[2] = 0x41; bytes[3] = 0x41
        bytes[4] = type.toByte()
        bytes[5] = ((count shr 16) and 0xFF).toByte()
        bytes[6] = ((count shr 8)  and 0xFF).toByte()
        bytes[7] = (count           and 0xFF).toByte()
    }

    /** Round [n] up to the nearest multiple of 8. */
    private fun alignUp8(n: Int): Int = (n + 7) and -8  // equivalent to ((n+7)/8)*8

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun readFiniteValues(
        data: ByteArray,
        leaves: List<RealmNode.Float64Leaf>
    ): List<Double> = buildList {
        for (leaf in leaves) {
            for (v in RealmBinaryParser.readFloat64Values(data, leaf)) {
                if (v.isFinite()) add(v)
            }
        }
    }

    /**
     * Write [values] sequentially into the leaf nodes' data slots.
     * Remaining slots (beyond values.size) are padded with the last valid value.
     */
    private fun writeToLeaves(
        buf: ByteBuffer,
        leaves: List<RealmNode.Float64Leaf>,
        values: List<Double>
    ) {
        var idx = 0
        for (leaf in leaves) {
            for (j in 0 until leaf.count) {
                val v = when {
                    idx < values.size     -> values[idx++]
                    values.isNotEmpty()   -> values.last()
                    else                  -> 0.0
                }
                buf.putDouble(leaf.dataOffset + j * 8, v)
            }
        }
    }

    /**
     * Merge route UUIDs from host + guest into the host's largest UUID string node.
     * Excess UUIDs that don't fit in the fixed-size node are silently dropped
     * (same behaviour as MergeEngine.merge — the routes are still visible via the
     * 300 km gap segmentation).
     */
    private fun mergeUuids(output: ByteArray, hostInfo: DbFileInfo, guestInfo: DbFileInfo) {
        val combinedUuids = hostInfo.routeUuids + guestInfo.routeUuids
        val uuidNode = hostInfo.stringNodes
            .filter { node ->
                val strings = RealmBinaryParser.readStrings(output, node)
                strings.any { it.length == 36 && it.count { c -> c == '-' } == 4 }
            }
            .maxByOrNull { it.maxLength } ?: return

        val uuidBytes = ByteArray(uuidNode.maxLength)
        var pos = 0
        for (uuid in combinedUuids) {
            val raw = uuid.toByteArray(Charsets.UTF_8)
            if (pos + raw.size + 1 > uuidBytes.size) break
            System.arraycopy(raw, 0, uuidBytes, pos, raw.size)
            pos += raw.size
            uuidBytes[pos++] = 0
        }
        System.arraycopy(uuidBytes, 0, output, uuidNode.dataOffset, uuidBytes.size)
    }
}
