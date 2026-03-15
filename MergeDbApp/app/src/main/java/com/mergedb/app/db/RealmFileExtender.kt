package com.mergedb.app.db

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Performs a lossless merge of two GPS Joystick Realm .db files using a
 * two-phase "pre-allocate then merge" strategy.
 *
 * Phase 1 – Pre-allocate (structural change only):
 *   If the combined coordinate count exceeds the existing leaf capacity, append
 *   empty 0x0C leaf nodes, build new 0xC6 B-Tree roots that cover all leaves
 *   (original + new), and patch the two 4-byte pointers in the 0x46
 *   field-pointer node.  No coordinate values are written in this phase.
 *
 * Phase 2 – In-place data overwrite (data change only):
 *   Write the combined (host + gap + guest) lat/lon values sequentially into
 *   ALL leaf nodes starting from leaf[0].  Because Phase 1 already ensured
 *   sufficient capacity, this step is always a pure in-place operation—it
 *   never needs to append anything.
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
     * Merge [guestData] into [hostData] without any data loss.
     *
     * The two-phase approach keeps structural changes (node allocation) strictly
     * separate from data changes (value overwrite):
     *
     *   Phase 1  preallocate()  – append empty leaf nodes + patch B-Tree pointers
     *   Phase 2  writeToLeaves() – overwrite all leaf slots with combined values
     */
    fun extendMerge(
        hostData: ByteArray,
        guestData: ByteArray,
        hostInfo: DbFileInfo,
        guestInfo: DbFileInfo
    ): ExtendResult {

        // ── 1. Parse B-Tree structure and read all finite coordinate values ────
        val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(hostData)
            ?: error("無法解析 Host 檔案的 B-Tree 結構")

        val hostLatValues = readFiniteValues(hostData, bTreeInfo.latLeaves)
        val hostLonValues = readFiniteValues(hostData, bTreeInfo.lonLeaves)

        val (guestLatLeaves, guestLonLeaves) = RealmBinaryParser.findLatLonLeafNodes(guestData)
        val guestLatValues = readFiniteValues(guestData, guestLatLeaves)
        val guestLonValues = readFiniteValues(guestData, guestLonLeaves)

        val hostPairs  = minOf(hostLatValues.size,  hostLonValues.size)
        val guestPairs = minOf(guestLatValues.size, guestLonValues.size)

        // ── 2. Build combined coordinate arrays (host + 300 km gap + guest) ───
        val combinedLat = ArrayList<Double>(hostPairs + guestPairs + 1)
        val combinedLon = ArrayList<Double>(hostPairs + guestPairs + 1)

        for (i in 0 until hostPairs) {
            combinedLat.add(hostLatValues[i])
            combinedLon.add(hostLonValues[i])
        }
        if (hostPairs > 0 && guestPairs > 0) {
            // Gap point: +3° latitude ≈ 333 km — GPS Joystick treats the jump as
            // a segment boundary, so host and guest appear as separate routes.
            combinedLat.add(combinedLat.last() + 3.0)
            combinedLon.add(combinedLon.last())
        }
        for (i in 0 until guestPairs) {
            combinedLat.add(guestLatValues[i])
            combinedLon.add(guestLonValues[i])
        }

        val existingCapacity = bTreeInfo.latLeaves.sumOf { it.count }
        val totalNeeded      = combinedLat.size
        val overflow         = totalNeeded - existingCapacity

        // ── 3. Phase 1: Pre-allocate capacity (structural change only) ────────
        // Append empty leaf nodes and update B-Tree pointers so that the working
        // copy has enough leaf slots for all combined coordinates.
        // No coordinate values are written here — only the node structure is built.
        val wasExtended = overflow > 0
        val workingData: ByteArray
        val activeLatLeaves: List<RealmNode.Float64Leaf>
        val activeLonLeaves: List<RealmNode.Float64Leaf>

        if (wasExtended) {
            workingData = preallocate(hostData, bTreeInfo, overflow)
            // Re-parse to obtain the updated leaf list (old leaves + new leaves).
            val newBTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(workingData)
                ?: error("預分配後無法解析 B-Tree 結構")
            activeLatLeaves = newBTreeInfo.latLeaves
            activeLonLeaves = newBTreeInfo.lonLeaves
        } else {
            workingData     = hostData.copyOf()
            activeLatLeaves = bTreeInfo.latLeaves
            activeLonLeaves = bTreeInfo.lonLeaves
        }

        // ── 4. Phase 2: In-place data overwrite (no structural change) ────────
        // At this point workingData always has enough leaf capacity, so this
        // write is always a pure sequential overwrite — no appending needed.
        val outBuf = ByteBuffer.wrap(workingData).order(ByteOrder.LITTLE_ENDIAN)
        writeToLeaves(outBuf, activeLatLeaves, combinedLat)
        writeToLeaves(outBuf, activeLonLeaves, combinedLon)
        mergeUuids(workingData, hostInfo, guestInfo)

        return ExtendResult(
            data             = workingData,
            wasExtended      = wasExtended,
            totalCoordinates = totalNeeded,
            addedCoordinates = guestPairs
        )
    }

    // ── Phase 1 helper ────────────────────────────────────────────────────────

    /**
     * Append [overflowCount] empty lat and lon leaf nodes to [hostData], build
     * new 0xC6 B-Tree roots that cover both the original and new leaf nodes,
     * then patch the two 4-byte entries in the 0x46 field-pointer node.
     *
     * All new leaf slots are initialised to 0.0 — they will be overwritten by
     * [writeToLeaves] immediately after this function returns.
     *
     * Returns a new byte array that is a structurally valid Realm file whose
     * lat/lon leaf capacity is exactly [overflowCount] slots larger than the
     * original.
     */
    private fun preallocate(
        hostData: ByteArray,
        bTreeInfo: RealmBinaryParser.CoordinateBTreeInfo,
        overflowCount: Int
    ): ByteArray {
        val extension  = ByteArrayOutputStream()
        var nextOffset = hostData.size   // new nodes are appended after the original body

        // Empty lat leaf nodes
        val newLatOffsets = mutableListOf<Int>()
        var remaining = overflowCount
        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_VALUES_PER_LEAF)
            newLatOffsets.add(nextOffset)
            val node = buildEmptyLeafNode(chunk)
            extension.write(node)
            nextOffset += node.size
            remaining  -= chunk
        }

        // Empty lon leaf nodes
        val newLonOffsets = mutableListOf<Int>()
        remaining = overflowCount
        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_VALUES_PER_LEAF)
            newLonOffsets.add(nextOffset)
            val node = buildEmptyLeafNode(chunk)
            extension.write(node)
            nextOffset += node.size
            remaining  -= chunk
        }

        // New C6 B-Tree roots (original leaf refs + new leaf refs, in order)
        val allLatOffsets  = bTreeInfo.latLeaves.map { it.offset } + newLatOffsets
        val newLatC6Offset = nextOffset
        val latC6 = buildC6Root(allLatOffsets)
        extension.write(latC6)
        nextOffset += latC6.size

        val allLonOffsets  = bTreeInfo.lonLeaves.map { it.offset } + newLonOffsets
        val newLonC6Offset = nextOffset
        val lonC6 = buildC6Root(allLonOffsets)
        extension.write(lonC6)

        // Patch the 0x46 child-pointer entries to point at the new C6 roots
        val output   = hostData.copyOf()
        val patchBuf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        patchBuf.putInt(
            bTreeInfo.node46Offset + 8 + bTreeInfo.latColIndexIn46 * 4,
            newLatC6Offset
        )
        patchBuf.putInt(
            bTreeInfo.node46Offset + 8 + bTreeInfo.lonColIndexIn46 * 4,
            newLonC6Offset
        )

        return output + extension.toByteArray()
    }

    // ── Node builders ─────────────────────────────────────────────────────────

    /**
     * Build an empty 0x0C leaf node with [count] slots initialised to 0.0.
     * Used by [preallocate] to create placeholder nodes before data is written.
     */
    private fun buildEmptyLeafNode(count: Int): ByteArray =
        buildLeafNode(List(count) { 0.0 })

    /**
     * Build a 0x0C Float64 leaf node for [values], padded to an 8-byte boundary.
     *
     * Layout:
     *   [AAAA] [0x0C] [count: 3-byte BE] [count × 8-byte LE Float64] [0x00 padding]
     */
    private fun buildLeafNode(values: List<Double>): ByteArray {
        val count       = values.size
        val rawSize     = 8 + count * 8
        val alignedSize = alignUp8(rawSize)
        val bytes = ByteArray(alignedSize)
        val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
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
        val count       = leafOffsets.size + 2
        val rawSize     = 8 + count * 4
        val alignedSize = alignUp8(rawSize)
        val bytes = ByteArray(alignedSize)
        val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
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
    private fun alignUp8(n: Int): Int = (n + 7) and -8

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
                    idx < values.size   -> values[idx++]
                    values.isNotEmpty() -> values.last()
                    else                -> 0.0
                }
                buf.putDouble(leaf.dataOffset + j * 8, v)
            }
        }
    }

    /**
     * Merge route UUIDs from host + guest into the host's largest UUID string node.
     * Excess UUIDs that don't fit in the fixed-size node are silently dropped
     * (the routes are still visible via the 300 km gap segmentation).
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
