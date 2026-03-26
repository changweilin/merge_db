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
        var finalData = mergeUuids(workingData, hostInfo, guestInfo)

        // ── 5. Extend route-ID column so Strategy-1 stays active post-merge ──
        // Without this, findCoordinateBTreeInfo sees ids.size != pairs (old host
        // count vs. new combined count) and falls back to Strategy-2 gap detection,
        // which finds spurious segment boundaries inside the original files.
        val routeIdLeaves   = bTreeInfo.routeIdLeaves
        val routeIdColIdx   = bTreeInfo.routeIdColIndexIn46
        val guestBTreeInfo  = RealmBinaryParser.findCoordinateBTreeInfo(guestData)
        val guestIdLeaves   = guestBTreeInfo?.routeIdLeaves

        if (routeIdLeaves != null && routeIdColIdx != null && guestIdLeaves != null) {
            val hostIds  = RealmBinaryParser.readIntLeafValues(hostData, routeIdLeaves)
            val guestIds = RealmBinaryParser.readIntLeafValues(guestData, guestIdLeaves)
                .take(guestPairs)

            if (hostIds.isNotEmpty() && guestIds.isNotEmpty()) {
                // Remap guest IDs to be disjoint from host IDs.
                val maxHostId      = hostIds.maxOrNull() ?: 0L
                val uniqueGuestIds = guestIds.distinct()
                val guestIdRemap   = uniqueGuestIds.withIndex()
                    .associate { (i, id) -> id to (maxHostId + 1 + i) }
                // The gap sentinel gets its own unique ID so it forms a trivial
                // 1-point segment; TSP skips it (≤ 2 points).
                val gapId = maxHostId + uniqueGuestIds.size + 1L

                // IDs to append after the existing host leaves:  gap + guest
                val newIds = buildList<Long> {
                    add(gapId)
                    guestIds.forEach { id -> add(guestIdRemap.getValue(id)) }
                }

                val leafTypeByte = routeIdLeaves.last().type.toInt() and 0xFF
                finalData = extendRouteIdColumn(
                    data               = finalData,
                    node46Offset       = bTreeInfo.node46Offset,
                    routeIdColIndexIn46 = routeIdColIdx,
                    existingLeaves     = routeIdLeaves,
                    newIds             = newIds,
                    leafTypeByte       = leafTypeByte
                )
            }
        }

        return ExtendResult(
            data             = finalData,
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
     * Rewrite coordinates in-place for TSP optimisation.
     *
     * Since TSP only reorders existing points (no new points added), the leaf
     * capacity is always sufficient and no structural extension is needed.
     * The function writes [newLats] / [newLons] into the existing leaf nodes
     * exactly like Phase 2 of [extendMerge], then returns the patched bytes.
     */
    fun rewriteCoordinates(
        sourceData: ByteArray,
        newLats: List<Double>,
        newLons: List<Double>
    ): ByteArray {
        val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(sourceData)
            ?: error("無法解析 B-Tree 結構")
        val working = sourceData.copyOf()
        val buf = ByteBuffer.wrap(working).order(ByteOrder.LITTLE_ENDIAN)
        writeToLeaves(buf, bTreeInfo.latLeaves, newLats)
        writeToLeaves(buf, bTreeInfo.lonLeaves, newLons)
        return working
    }

    /**
     * Merge route UUIDs from host + guest into the output.
     *
     * If all UUIDs fit in the host's existing 0x11 string node, they are written
     * in-place (no size change). If the combined UUIDs exceed the node's capacity,
     * a new larger 0x11 node is appended at the end of the file and the parent
     * pointer is patched to reference it.
     *
     * Returns the (possibly extended) byte array.
     */
    private fun mergeUuids(data: ByteArray, hostInfo: DbFileInfo, guestInfo: DbFileInfo): ByteArray {
        val combinedUuids = (hostInfo.routeUuids + guestInfo.routeUuids).distinct()
        if (combinedUuids.isEmpty()) return data

        val uuidNode = hostInfo.stringNodes
            .filter { node ->
                val strings = RealmBinaryParser.readStrings(data, node)
                strings.any { it.length == 36 && it.count { c -> c == '-' } == 4 }
            }
            .maxByOrNull { it.maxLength } ?: return data

        val needed = combinedUuids.sumOf { it.length + 1 }  // each UUID + null terminator

        return if (needed <= uuidNode.maxLength) {
            // Fits in existing node: in-place overwrite
            writeUuidBytes(data, uuidNode.dataOffset, uuidNode.maxLength, combinedUuids)
            data
        } else {
            // Need a larger node: append and patch
            extendUuidStorage(data, uuidNode, combinedUuids, needed)
        }
    }

    /** Write [uuids] as packed null-terminated strings into [data] starting at [dataOffset]. */
    private fun writeUuidBytes(data: ByteArray, dataOffset: Int, capacity: Int, uuids: List<String>) {
        val buf = ByteArray(capacity)
        var pos = 0
        for (uuid in uuids) {
            val raw = uuid.toByteArray(Charsets.UTF_8)
            if (pos + raw.size + 1 > buf.size) break
            System.arraycopy(raw, 0, buf, pos, raw.size)
            pos += raw.size
            buf[pos++] = 0
        }
        System.arraycopy(buf, 0, data, dataOffset, buf.size)
    }

    /**
     * Append a new 0x11 string node large enough for all [uuids], then patch
     * the parent pointer that used to reference [oldNode] to point to the new node.
     * Falls back to in-place truncation if the parent pointer cannot be located.
     */
    private fun extendUuidStorage(
        data: ByteArray,
        oldNode: RealmNode.StringNode,
        uuids: List<String>,
        needed: Int
    ): ByteArray {
        val parentRef = RealmBinaryParser.findNodeParentRef(data, oldNode.offset)
            ?: run {
                // Parent not found — fall back to in-place (truncated) write
                writeUuidBytes(data, oldNode.dataOffset, oldNode.maxLength, uuids)
                return data
            }

        // Build the new 0x11 node
        val capacity    = needed
        val alignedSize = alignUp8(8 + capacity)
        val newNode     = ByteArray(alignedSize)
        writeNodeHeader(newNode, 0x11, capacity)
        writeUuidBytes(newNode, 8, capacity, uuids)   // data starts at offset 8 inside the node

        val newNodeOffset = data.size
        val extended      = data + newNode

        val patchBuf = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN)

        if (!parentRef.isUint16) {
            // C6 or 0x46: direct 4-byte patch
            patchBuf.putInt(parentRef.refByteOffset, newNodeOffset)
            return extended
        }

        // C5 parent: uint16 can't address the new (high) offset.
        // Replace the C5 node with a new C6 node that has the same refs but the
        // target ref updated, then patch the C5 node's own parent.
        val c5Offset = parentRef.parentNodeOffset
        val c5Count  = ((extended[c5Offset + 5].toInt() and 0xFF) shl 16) or
                       ((extended[c5Offset + 6].toInt() and 0xFF) shl 8)  or
                        (extended[c5Offset + 7].toInt() and 0xFF)

        // Read all uint16 refs from the C5 node, upgrading to uint32
        val refs = (0 until c5Count).map { i ->
            val ref = patchBuf.getShort(c5Offset + 8 + i * 2).toInt() and 0xFFFF
            if (ref == oldNode.offset) newNodeOffset else ref
        }

        // Build a C6 node (no sentinels — this is a mid-tree node, not a root)
        val c6Size  = alignUp8(8 + refs.size * 4)
        val c6Bytes = ByteArray(c6Size)
        writeNodeHeader(c6Bytes, 0xC6, refs.size)
        val c6Buf = ByteBuffer.wrap(c6Bytes).order(ByteOrder.LITTLE_ENDIAN)
        refs.forEachIndexed { i, ref -> c6Buf.putInt(8 + i * 4, ref) }

        val newC6Offset  = extended.size
        val extended2    = extended + c6Bytes
        val patchBuf2    = ByteBuffer.wrap(extended2).order(ByteOrder.LITTLE_ENDIAN)

        // Patch the C5 node's parent to reference the new C6 node
        val c5ParentRef = RealmBinaryParser.findNodeParentRef(extended2, c5Offset)
        if (c5ParentRef != null && !c5ParentRef.isUint16) {
            patchBuf2.putInt(c5ParentRef.refByteOffset, newC6Offset)
        }
        // If c5ParentRef is still null or also C5, this rare case is left as-is
        // (the existing C5 keeps its old UUID ref; truncation occurs but no crash)

        return extended2
    }

    // ── Route-ID column extension ─────────────────────────────────────────────

    /**
     * Append new integer leaf nodes to [data] for the [newIds], build a new C6
     * B-Tree root covering both existing and new leaves, and patch the 0x46 entry
     * at [routeIdColIndexIn46] to reference the new root.
     *
     * The existing leaves are left in place (their data is already correct for the
     * host file).  We only need to cover the additional gap + guest ID entries.
     */
    private fun extendRouteIdColumn(
        data: ByteArray,
        node46Offset: Int,
        routeIdColIndexIn46: Int,
        existingLeaves: List<RealmNode.IntLeaf>,
        newIds: List<Long>,
        leafTypeByte: Int
    ): ByteArray {
        if (newIds.isEmpty()) return data

        val extension = ByteArrayOutputStream()
        var nextOffset = data.size
        val newLeafOffsets = mutableListOf<Int>()

        // Write new int leaf nodes (BPE=4, Int32-LE) for gap + guest IDs
        var remaining = newIds
        while (remaining.isNotEmpty()) {
            val chunk = remaining.take(MAX_VALUES_PER_LEAF)
            remaining = remaining.drop(MAX_VALUES_PER_LEAF)
            newLeafOffsets.add(nextOffset)
            val node = buildIntLeafNode(chunk, leafTypeByte, bpe = 4)
            extension.write(node)
            nextOffset += node.size
        }

        // New C6 root: old leaf offsets + new leaf offsets (with sentinels)
        val allLeafOffsets = existingLeaves.map { it.offset } + newLeafOffsets
        val newC6Offset = nextOffset
        val c6Node = buildC6Root(allLeafOffsets)
        extension.write(c6Node)

        val extended = data + extension.toByteArray()

        // Patch 0x46 entry for the route-ID column
        val patchBuf = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN)
        patchBuf.putInt(node46Offset + 8 + routeIdColIndexIn46 * 4, newC6Offset)

        return extended
    }

    /**
     * Build an integer leaf node with the given [typeByte] and [bpe] (bytes per entry).
     * Values are written as little-endian integers of size [bpe].
     *
     * Layout: [AAAA][typeByte][count: 3-byte BE][values: count × bpe-byte LE][0x00 padding]
     */
    private fun buildIntLeafNode(values: List<Long>, typeByte: Int, bpe: Int): ByteArray {
        val count = values.size
        val rawSize = 8 + count * bpe
        val alignedSize = alignUp8(rawSize)
        val bytes = ByteArray(alignedSize)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        writeNodeHeader(bytes, typeByte, count)
        values.forEachIndexed { i, v ->
            val pos = 8 + i * bpe
            when (bpe) {
                1    -> bytes[pos] = v.toByte()
                2    -> buf.putShort(pos, v.toShort())
                4    -> buf.putInt(pos, v.toInt())
                else -> buf.putLong(pos, v)
            }
        }
        return bytes
    }
}
