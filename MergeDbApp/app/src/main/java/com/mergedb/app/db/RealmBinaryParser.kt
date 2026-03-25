package com.mergedb.app.db

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

// ── Gap-detection fallback (used when no route-ID column is found) ────────────

/** Latitude jump threshold that separates route segments in the flat coord array. */
internal const val GAP_THRESHOLD = 2.5

data class SegmentSplit(
    val segments: List<List<LatLon>>,
    val gapPoints: List<LatLon?>   // null = no gap before this segment (first segment)
)

fun splitByGap(lats: List<Double>, lons: List<Double>): SegmentSplit {
    if (lats.isEmpty()) return SegmentSplit(emptyList(), emptyList())
    val segments = mutableListOf<List<LatLon>>()
    val gapPoints = mutableListOf<LatLon?>()
    var current = mutableListOf<LatLon>()
    gapPoints.add(null)
    for (i in lats.indices) {
        val pt = LatLon(lats[i], lons[i])
        if (current.isNotEmpty() && abs(lats[i] - current.last().lat) > GAP_THRESHOLD) {
            segments.add(current.toList())
            current = mutableListOf()
            gapPoints.add(pt)
        } else {
            current.add(pt)
        }
    }
    if (current.isNotEmpty()) segments.add(current.toList())
    return SegmentSplit(segments, gapPoints)
}

/**
 * Parses GPS Joystick Realm .db files at the binary level.
 *
 * File format key points:
 * - "T-DB" header at offset 0x10
 * - Nodes marked by ASCII "AAAA" (0x41414141)
 * - Node header: [AAAA: 4 bytes] [type: 1 byte] [count: 3 bytes big-endian]
 * - B-tree index nodes (0xC5 uses uint16 refs, 0xC6 uses uint32 refs)
 * - 0x46 nodes reference B-tree roots for each column of a table
 * - Float64 data values are Little-Endian
 */
object RealmBinaryParser {

    private val TDB_HEADER = byteArrayOf(0x54, 0x2D, 0x44, 0x42) // "T-DB"

    fun isValidDb(data: ByteArray): Boolean {
        if (data.size < 0x14) return false
        return data[0x10] == TDB_HEADER[0] &&
                data[0x11] == TDB_HEADER[1] &&
                data[0x12] == TDB_HEADER[2] &&
                data[0x13] == TDB_HEADER[3]
    }

    /**
     * Read the 3-byte big-endian count field from a node header.
     * Bytes at offset+5, offset+6, offset+7 form a 24-bit BE integer.
     */
    private fun readNodeCount(data: ByteArray, offset: Int): Int {
        return ((data[offset + 5].toInt() and 0xFF) shl 16) or
                ((data[offset + 6].toInt() and 0xFF) shl 8) or
                (data[offset + 7].toInt() and 0xFF)
    }

    /**
     * Find all AAAA marker offsets in the data.
     * Skips consecutive 0x41 bytes that aren't real markers.
     */
    fun findAllMarkers(data: ByteArray): List<Int> {
        val markers = mutableListOf<Int>()
        var pos = 0
        val limit = data.size - 4
        while (pos <= limit) {
            if (data[pos] == 0x41.toByte() &&
                data[pos + 1] == 0x41.toByte() &&
                data[pos + 2] == 0x41.toByte() &&
                data[pos + 3] == 0x41.toByte()
            ) {
                val nextByte = if (pos + 4 < data.size) data[pos + 4] else 0
                if (nextByte != 0x41.toByte()) {
                    markers.add(pos)
                    pos += 4
                } else {
                    pos += 1
                }
            } else {
                pos += 1
            }
        }
        return markers
    }

    /**
     * Parse all Float64 leaf nodes (type 0x0c) and string nodes (type 0x11).
     */
    fun parse(data: ByteArray): Pair<List<RealmNode.Float64Leaf>, List<RealmNode.StringNode>> {
        val markers = findAllMarkers(data)
        val floatNodes = mutableListOf<RealmNode.Float64Leaf>()
        val stringNodes = mutableListOf<RealmNode.StringNode>()

        for (offset in markers) {
            if (offset + 8 > data.size) continue
            val type = data[offset + 4].toInt() and 0xFF
            val count = readNodeCount(data, offset)

            when (type) {
                0x0c -> {
                    val dataOffset = offset + 8
                    if (count > 0 && dataOffset + count * 8 <= data.size) {
                        floatNodes.add(RealmNode.Float64Leaf(offset, count, dataOffset))
                    }
                }
                0x11 -> {
                    val dataOffset = offset + 8
                    if (count > 0 && dataOffset + count <= data.size) {
                        stringNodes.add(RealmNode.StringNode(offset, count, dataOffset))
                    }
                }
            }
        }

        return Pair(floatNodes, stringNodes)
    }

    /**
     * Find latitude and longitude leaf node sets by tracing B-tree index structure.
     *
     * The Realm file stores each column (latitude, longitude, altitude, etc.) in a
     * separate B-tree "Run". The 0x46 nodes reference B-tree roots for each column.
     * - 0xC5 nodes are B-tree roots with uint16 leaf offsets
     * - 0xC6 nodes are B-tree index nodes with uint32 child offsets
     * - In both, the first and last entries are metadata (not actual child offsets)
     *
     * For class_CoordinateData, the column order is:
     *   [latitude, longitude, altitude, ...]
     * The 0x46 node with 4 entries references these 4 column B-trees.
     */
    fun findLatLonLeafNodes(
        data: ByteArray
    ): Pair<List<RealmNode.Float64Leaf>, List<RealmNode.Float64Leaf>> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val markers = findAllMarkers(data)
        val (allFloatNodes, _) = parse(data)

        // Build a set of known 0x0c node offsets for quick lookup
        val floatNodeByOffset = allFloatNodes.associateBy { it.offset }

        // Find all 0x46 nodes (table column references)
        val node46List = mutableListOf<Pair<Int, List<Int>>>()
        for (offset in markers) {
            if (offset + 8 > data.size) continue
            val type = data[offset + 4].toInt() and 0xFF
            if (type == 0x46) {
                val count = readNodeCount(data, offset)
                if (count in 2..20 && offset + 8 + count * 4 <= data.size) {
                    val children = mutableListOf<Int>()
                    for (i in 0 until count) {
                        children.add(buf.getInt(offset + 8 + i * 4))
                    }
                    node46List.add(Pair(offset, children))
                }
            }
        }

        // Find the 0x46 with 4 entries whose children are B-tree roots (C5/C6)
        // referencing 0x0c leaf nodes with coordinate data
        for ((_, children) in node46List) {
            if (children.size != 4) continue

            // Track which column index (position in 0x46's child list) produced
            // each leaf set, so we don't need to reverse-look-up the root later.
            val columnLeavesWithIdx = children.mapIndexed { i, childOffset ->
                i to traceBTreeLeaves(data, buf, childOffset, floatNodeByOffset)
            }

            // The CoordinateData table has lat and lon as the two columns
            // with the most 0x0c leaf nodes (equal count).
            val validColumns = columnLeavesWithIdx
                .filter { it.second.isNotEmpty() }
                .sortedByDescending { it.second.size }

            if (validColumns.size >= 2 && validColumns[0].second.size == validColumns[1].second.size) {
                val col0Idx = validColumns[0].first
                val col1Idx = validColumns[1].first

                // Schema order: column 0 = latitude, column 1 = longitude
                return if (col0Idx < col1Idx) {
                    Pair(validColumns[0].second, validColumns[1].second)
                } else {
                    Pair(validColumns[1].second, validColumns[0].second)
                }
            }
        }

        // Fallback: split all float nodes in half
        val half = allFloatNodes.size / 2
        return Pair(
            allFloatNodes.subList(0, half),
            allFloatNodes.subList(half, minOf(half * 2, allFloatNodes.size))
        )
    }

    /**
     * Trace a B-tree from its root to find all 0x0c leaf node offsets.
     * Handles both C5 (uint16 refs) and C6 (uint32 refs) index nodes.
     */
    private fun traceBTreeLeaves(
        data: ByteArray,
        buf: ByteBuffer,
        rootOffset: Int,
        floatNodeByOffset: Map<Int, RealmNode.Float64Leaf>
    ): List<RealmNode.Float64Leaf> {
        if (rootOffset < 0 || rootOffset + 8 > data.size) return emptyList()
        if (data[rootOffset] != 0x41.toByte() ||
            data[rootOffset + 1] != 0x41.toByte() ||
            data[rootOffset + 2] != 0x41.toByte() ||
            data[rootOffset + 3] != 0x41.toByte()
        ) return emptyList()

        val type = data[rootOffset + 4].toInt() and 0xFF
        val count = readNodeCount(data, rootOffset)

        when (type) {
            0xC5 -> {
                // uint16 references, skip first and last (metadata sentinels)
                if (count < 3 || rootOffset + 8 + count * 2 > data.size) return emptyList()
                val leaves = mutableListOf<RealmNode.Float64Leaf>()
                for (i in 1 until count - 1) {
                    val childOffset = buf.getShort(rootOffset + 8 + i * 2).toInt() and 0xFFFF
                    val leaf = floatNodeByOffset[childOffset]
                    if (leaf != null) leaves.add(leaf)
                }
                return leaves
            }
            0xC6 -> {
                // uint32 references, skip first and last (metadata sentinels)
                if (count < 3 || rootOffset + 8 + count * 4 > data.size) return emptyList()
                val leaves = mutableListOf<RealmNode.Float64Leaf>()
                for (i in 1 until count - 1) {
                    val childOffset = buf.getInt(rootOffset + 8 + i * 4)
                    val leaf = floatNodeByOffset[childOffset]
                    if (leaf != null) {
                        leaves.add(leaf)
                    } else {
                        // Deeper B-tree level - recurse
                        val subLeaves = traceBTreeLeaves(data, buf, childOffset, floatNodeByOffset)
                        leaves.addAll(subLeaves)
                    }
                }
                return leaves
            }
            else -> return emptyList()
        }
    }

    fun readFloat64Values(data: ByteArray, node: RealmNode.Float64Leaf): DoubleArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val values = DoubleArray(node.count)
        for (i in 0 until node.count) {
            values[i] = buf.getDouble(node.dataOffset + i * 8)
        }
        return values
    }

    fun readStrings(data: ByteArray, node: RealmNode.StringNode): List<String> {
        val raw = data.copyOfRange(node.dataOffset, node.dataOffset + node.maxLength)
        val strings = mutableListOf<String>()
        var start = 0
        for (i in raw.indices) {
            if (raw[i] == 0.toByte()) {
                if (i > start) {
                    try {
                        strings.add(String(raw, start, i - start, Charsets.UTF_8))
                    } catch (_: Exception) {}
                }
                start = i + 1
            }
        }
        if (start < raw.size) {
            val remaining = raw.copyOfRange(start, raw.size)
            val trimmed = remaining.takeWhile { it != 0.toByte() }.toByteArray()
            if (trimmed.isNotEmpty()) {
                try {
                    strings.add(String(trimmed, Charsets.UTF_8))
                } catch (_: Exception) {}
            }
        }
        return strings
    }

    fun extractRouteUuids(data: ByteArray, stringNodes: List<RealmNode.StringNode>): List<String> {
        val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val uuids = mutableListOf<String>()
        for (node in stringNodes) {
            val strings = readStrings(data, node)
            for (s in strings) {
                if (s.length == 36 && uuidPattern.matches(s)) {
                    uuids.add(s)
                }
            }
        }
        return uuids.distinct()
    }

    /**
     * Structural information about the CoordinateData B-Tree in a Realm .db file.
     * Used by RealmFileExtender to perform lossless merges by appending new leaf nodes.
     *
     * [routeIdLeaves] is non-null when a non-Float64 column with the same entry count
     * as lat/lon is found; it holds integer leaf nodes that encode which route each
     * coordinate belongs to.  Null means no such column was detected.
     */
    data class CoordinateBTreeInfo(
        val node46Offset: Int,        // absolute offset of the 0x46 field-pointer node
        val latColIndexIn46: Int,     // which entry (0-3) in 0x46 points to the lat B-Tree root
        val lonColIndexIn46: Int,     // which entry (0-3) in 0x46 points to the lon B-Tree root
        val latLeaves: List<RealmNode.Float64Leaf>,
        val lonLeaves: List<RealmNode.Float64Leaf>,
        val routeIdLeaves: List<RealmNode.IntLeaf>? = null
    )

    /**
     * Like findLatLonLeafNodes, but also returns the 0x46 node offset and column indices
     * so that callers can update the B-Tree root pointers in-place when extending the file.
     *
     * Also tries to detect a non-Float64 column in the same table (routeId / link column):
     * any column whose B-tree leaves have a type byte ≠ 0x0c but carry the same number of
     * entries as lat/lon is treated as a candidate route-ID column.
     */
    fun findCoordinateBTreeInfo(data: ByteArray): CoordinateBTreeInfo? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val markers = findAllMarkers(data)
        val (allFloatNodes, _) = parse(data)
        val floatNodeByOffset = allFloatNodes.associateBy { it.offset }

        // Build a map of ALL non-index, non-table leaf nodes keyed by offset,
        // storing raw type byte, count and dataOffset.
        // We infer bytesPerEntry from the gap to the next marker.
        data class RawLeaf(val offset: Int, val typeByte: Int, val count: Int,
                           val dataOffset: Int, val bytesPerEntry: Int)
        val rawLeafByOffset = mutableMapOf<Int, RawLeaf>()
        for (i in markers.indices) {
            val off = markers[i]
            if (off + 8 > data.size) continue
            val t = data[off + 4].toInt() and 0xFF
            if (t == 0xC5 || t == 0xC6 || t == 0x46) continue   // index / table nodes
            val cnt = readNodeCount(data, off)
            val dataStart = off + 8
            val dataEnd = if (i + 1 < markers.size) markers[i + 1] else data.size
            val bpe = if (cnt > 0 && dataEnd > dataStart) (dataEnd - dataStart) / cnt else -1
            rawLeafByOffset[off] = RawLeaf(off, t, cnt, dataStart, bpe)
        }

        // Walk B-tree collecting ALL leaf nodes (any type).
        // Returns pairs of (typeByte, RawLeaf).
        fun traceAllRawLeaves(rootOffset: Int, depth: Int = 0): List<RawLeaf> {
            if (depth > 8 || rootOffset < 0 || rootOffset + 8 > data.size) return emptyList()
            if (data[rootOffset] != 0x41.toByte() || data[rootOffset+1] != 0x41.toByte() ||
                data[rootOffset+2] != 0x41.toByte() || data[rootOffset+3] != 0x41.toByte()
            ) return emptyList()
            val t = data[rootOffset + 4].toInt() and 0xFF
            val c = readNodeCount(data, rootOffset)
            return when (t) {
                0xC5 -> {
                    if (c < 3 || rootOffset + 8 + c * 2 > data.size) emptyList()
                    else (1 until c - 1).flatMap { i ->
                        val child = buf.getShort(rootOffset + 8 + i * 2).toInt() and 0xFFFF
                        rawLeafByOffset[child]?.let { listOf(it) }
                            ?: traceAllRawLeaves(child, depth + 1)
                    }
                }
                0xC6 -> {
                    if (c < 3 || rootOffset + 8 + c * 4 > data.size) emptyList()
                    else (1 until c - 1).flatMap { i ->
                        val child = buf.getInt(rootOffset + 8 + i * 4)
                        rawLeafByOffset[child]?.let { listOf(it) }
                            ?: traceAllRawLeaves(child, depth + 1)
                    }
                }
                else -> rawLeafByOffset[rootOffset]?.let { listOf(it) } ?: emptyList()
            }
        }

        // Collect all 0x46 nodes
        val node46List = mutableListOf<Pair<Int, List<Int>>>()
        for (offset in markers) {
            if (offset + 8 > data.size) continue
            if (data[offset + 4].toInt() and 0xFF != 0x46) continue
            val count = readNodeCount(data, offset)
            if (count in 2..20 && offset + 8 + count * 4 <= data.size) {
                node46List.add(offset to (0 until count).map { i ->
                    buf.getInt(offset + 8 + i * 4)
                })
            }
        }

        for ((node46Offset, children) in node46List) {
            if (children.size != 4) continue

            // Trace all 4 columns, collecting both Float64 and any other type
            val columnLeavesWithIdx = children.mapIndexed { i, childOffset ->
                i to traceBTreeLeaves(data, buf, childOffset, floatNodeByOffset)
            }

            val validColumns = columnLeavesWithIdx.filter { it.second.isNotEmpty() }
                .sortedByDescending { it.second.size }
            if (validColumns.size < 2 || validColumns[0].second.size != validColumns[1].second.size) continue

            val idx0 = validColumns[0].first
            val idx1 = validColumns[1].first

            val latColIdx: Int; val lonColIdx: Int
            val latLeaves: List<RealmNode.Float64Leaf>; val lonLeaves: List<RealmNode.Float64Leaf>
            if (idx0 < idx1) {
                latColIdx = idx0; lonColIdx = idx1
                latLeaves = validColumns[0].second; lonLeaves = validColumns[1].second
            } else {
                latColIdx = idx1; lonColIdx = idx0
                latLeaves = validColumns[1].second; lonLeaves = validColumns[0].second
            }

            // Try to find a non-Float64 column in the same table with the same total
            // entry count as lat — this is the route-ID / object-link column.
            val coordEntryCount = latLeaves.sumOf { it.count }
            val routeIdLeaves: List<RealmNode.IntLeaf>? = run {
                for ((colIdx, childOffset) in children.withIndex()) {
                    if (colIdx == latColIdx || colIdx == lonColIdx) continue
                    val rawLeaves = traceAllRawLeaves(childOffset)
                    // Only consider columns whose leaves are all non-Float64
                    if (rawLeaves.isEmpty()) continue
                    if (rawLeaves.any { it.typeByte == 0x0c }) continue   // skip Float64 (altitude)
                    val totalEntries = rawLeaves.sumOf { it.count }
                    if (totalEntries != coordEntryCount) continue
                    // All leaves in this column must have the same bytesPerEntry (4 or 8)
                    val bpe = rawLeaves.map { it.bytesPerEntry }.distinct()
                    if (bpe.size != 1 || bpe[0] !in listOf(4, 8)) continue
                    return@run rawLeaves.map { l ->
                        RealmNode.IntLeaf(l.offset, l.typeByte.toByte(), l.count,
                            l.dataOffset, bpe[0])
                    }
                }
                null
            }

            return CoordinateBTreeInfo(
                node46Offset = node46Offset,
                latColIndexIn46 = latColIdx,
                lonColIndexIn46 = lonColIdx,
                latLeaves = latLeaves,
                lonLeaves = lonLeaves,
                routeIdLeaves = routeIdLeaves
            )
        }
        return null
    }

    /** Read all integer values from a list of IntLeaf nodes (Int32 or Int64 → Long). */
    fun readIntLeafValues(data: ByteArray, leaves: List<RealmNode.IntLeaf>): List<Long> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return buildList {
            for (leaf in leaves) {
                repeat(leaf.count) { i ->
                    val pos = leaf.dataOffset + i * leaf.bytesPerEntry
                    if (pos + leaf.bytesPerEntry <= data.size) {
                        add(if (leaf.bytesPerEntry == 4) buf.getInt(pos).toLong() and 0xFFFFFFFFL
                            else buf.getLong(pos))
                    }
                }
            }
        }
    }

    /**
     * Derive per-route coordinate index ranges directly from the DB structure.
     *
     * Strategy (in priority order):
     * 1. Route-ID column: read the integer column that links each coordinate to its
     *    route row key.  When the value changes, a new route starts.
     * 2. Gap detection fallback: delegate to [splitByGap] with 2.5° threshold.
     *
     * Returns a list of [IntRange] (inclusive start .. inclusive end) into the flat
     * coordinate array.  Returns null only if the coordinate data itself is unreadable.
     */
    fun findRouteSegments(data: ByteArray): List<IntRange>? {
        val bTreeInfo = findCoordinateBTreeInfo(data) ?: return null

        fun readFinite(leaves: List<RealmNode.Float64Leaf>) = buildList<Double> {
            for (leaf in leaves) for (v in readFloat64Values(data, leaf)) if (v.isFinite()) add(v)
        }
        val lats = readFinite(bTreeInfo.latLeaves)
        val lons = readFinite(bTreeInfo.lonLeaves)
        val pairs = minOf(lats.size, lons.size)
        if (pairs == 0) return emptyList()

        // ── Strategy 1: route-ID column ──────────────────────────────────────
        val routeIdLeaves = bTreeInfo.routeIdLeaves
        if (routeIdLeaves != null) {
            val ids = readIntLeafValues(data, routeIdLeaves)
            if (ids.size == pairs) {
                val ranges = mutableListOf<IntRange>()
                var start = 0
                for (i in 1..pairs) {
                    if (i == pairs || ids[i] != ids[i - 1]) {
                        ranges.add(start until i)
                        start = i
                    }
                }
                if (ranges.isNotEmpty()) return ranges
            }
        }

        // ── Strategy 2: gap fallback ──────────────────────────────────────────
        // Gap points occupy a position in the flat array; each segment's range
        // must skip over the gap point that precedes it (gapPoints[0] is always null).
        val split = splitByGap(lats.take(pairs), lons.take(pairs))
        var cursor = 0
        return split.segments.mapIndexed { idx, seg ->
            if (split.gapPoints[idx] != null) cursor++   // skip gap-point position
            val range = cursor until (cursor + seg.size)
            cursor += seg.size
            range
        }
    }

    data class NodeParentRef(
        val parentNodeOffset: Int,  // absolute offset of the AAAA node that holds the ref
        val refByteOffset: Int,     // absolute byte position of the reference value in the file
        val isUint16: Boolean       // true = C5 (2-byte ref), false = C6/0x46 (4-byte ref)
    )

    /**
     * Scan all B-tree nodes (C5, C6, 0x46) to find which one holds a reference
     * to [targetOffset]. Returns the location of that reference in the file, or
     * null if none found.
     */
    fun findNodeParentRef(data: ByteArray, targetOffset: Int): NodeParentRef? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val markers = findAllMarkers(data)

        for (nodeOffset in markers) {
            if (nodeOffset + 8 > data.size) continue
            val type = data[nodeOffset + 4].toInt() and 0xFF
            val count = readNodeCount(data, nodeOffset)

            when (type) {
                0xC5 -> {
                    if (targetOffset > 0xFFFF) continue  // uint16 can't address high offsets
                    if (count < 1 || nodeOffset + 8 + count * 2 > data.size) continue
                    for (i in 0 until count) {
                        val refPos = nodeOffset + 8 + i * 2
                        val ref = buf.getShort(refPos).toInt() and 0xFFFF
                        if (ref == targetOffset)
                            return NodeParentRef(nodeOffset, refPos, isUint16 = true)
                    }
                }
                0xC6, 0x46 -> {
                    if (count < 1 || nodeOffset + 8 + count * 4 > data.size) continue
                    for (i in 0 until count) {
                        val refPos = nodeOffset + 8 + i * 4
                        val ref = buf.getInt(refPos)
                        if (ref == targetOffset)
                            return NodeParentRef(nodeOffset, refPos, isUint16 = false)
                    }
                }
            }
        }
        return null
    }

    // ── Structural exploration ────────────────────────────────────────────────

    /**
     * Scan every AAAA node in the file and group them by type byte.
     * For each type, infer [bytesPerEntry] by measuring the gap between
     * consecutive nodes: gap = (nextMarker - dataStart) / count.
     * This lets us distinguish Int32 (4 B), Int64/Float64 (8 B), etc.
     */
    fun scanNodeTypes(data: ByteArray): List<NodeTypeSummary> {
        val markers = findAllMarkers(data)
        // group: typeByte → list of (count, dataStart, dataEnd)
        data class Entry(val count: Int, val dataStart: Int, val dataEnd: Int)
        val groups = mutableMapOf<Int, MutableList<Entry>>()

        for (i in markers.indices) {
            val offset = markers[i]
            if (offset + 8 > data.size) continue
            val typeByte = data[offset + 4].toInt() and 0xFF
            val count = readNodeCount(data, offset)
            val dataStart = offset + 8
            val dataEnd = if (i + 1 < markers.size) markers[i + 1] else data.size
            groups.getOrPut(typeByte) { mutableListOf() }
                .add(Entry(count, dataStart, dataEnd))
        }

        return groups.map { (typeByte, entries) ->
            val totalEntries = entries.sumOf { it.count }
            // infer bytes-per-entry from the most common non-zero count node
            val bpe = entries
                .filter { it.count > 0 && it.dataEnd > it.dataStart }
                .map { (it.dataEnd - it.dataStart) / it.count }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: -1
            NodeTypeSummary(typeByte, entries.size, totalEntries, bpe)
        }.sortedBy { it.typeByte }
    }

    /**
     * Find every 0x46 node (table descriptor) in the file and describe
     * what type of leaf nodes each column's B-tree contains.
     * Walks the B-tree of each column and records ALL node types encountered,
     * not just Float64 leaves—so unknown integer/link types also show up.
     */
    fun probeAllTables(data: ByteArray): List<TableInfo> {
        val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val markers = findAllMarkers(data)
        val (allFloatNodes, _) = parse(data)
        val floatNodeByOffset = allFloatNodes.associateBy { it.offset }

        // Collect all leaf nodes keyed by offset (any type)
        data class AnyLeaf(val offset: Int, val typeByte: Int, val count: Int)
        val anyLeafByOffset = mutableMapOf<Int, AnyLeaf>()
        for (offset in markers) {
            if (offset + 8 > data.size) continue
            val t = data[offset + 4].toInt() and 0xFF
            // Not a B-tree index node and not a column pointer — treat as leaf
            if (t != 0xC5 && t != 0xC6 && t != 0x46) {
                val c = readNodeCount(data, offset)
                anyLeafByOffset[offset] = AnyLeaf(offset, t, c)
            }
        }

        // Walk B-tree collecting ALL leaf types (not just Float64)
        fun traceAllLeaves(rootOffset: Int, depth: Int = 0): List<AnyLeaf> {
            if (depth > 8 || rootOffset < 0 || rootOffset + 8 > data.size) return emptyList()
            if (data[rootOffset] != 0x41.toByte() || data[rootOffset+1] != 0x41.toByte() ||
                data[rootOffset+2] != 0x41.toByte() || data[rootOffset+3] != 0x41.toByte()
            ) return emptyList()
            val t = data[rootOffset + 4].toInt() and 0xFF
            val c = readNodeCount(data, rootOffset)
            return when (t) {
                0xC5 -> {
                    if (c < 3 || rootOffset + 8 + c * 2 > data.size) emptyList()
                    else (1 until c - 1).flatMap { i ->
                        val child = buf.getShort(rootOffset + 8 + i * 2).toInt() and 0xFFFF
                        anyLeafByOffset[child]?.let { listOf(it) }
                            ?: traceAllLeaves(child, depth + 1)
                    }
                }
                0xC6 -> {
                    if (c < 3 || rootOffset + 8 + c * 4 > data.size) emptyList()
                    else (1 until c - 1).flatMap { i ->
                        val child = buf.getInt(rootOffset + 8 + i * 4)
                        anyLeafByOffset[child]?.let { listOf(it) }
                            ?: traceAllLeaves(child, depth + 1)
                    }
                }
                else -> anyLeafByOffset[rootOffset]?.let { listOf(it) } ?: emptyList()
            }
        }

        val tables = mutableListOf<TableInfo>()
        for (offset in markers) {
            if (offset + 8 > data.size) continue
            if (data[offset + 4].toInt() and 0xFF != 0x46) continue
            val colCount = readNodeCount(data, offset)
            if (colCount < 1 || offset + 8 + colCount * 4 > data.size) continue

            val columns = (0 until colCount).map { i ->
                val childOffset = buf.getInt(offset + 8 + i * 4)
                val leaves = traceAllLeaves(childOffset)
                TableColumnInfo(
                    columnIndex  = i,
                    childTypes   = leaves.map { it.typeByte }.distinct().sorted(),
                    leafCount    = leaves.size,
                    entryCount   = leaves.sumOf { it.count }
                )
            }
            tables.add(TableInfo(offset, columns))
        }
        return tables
    }

    fun buildFileInfo(fileName: String, data: ByteArray): DbFileInfo {
        val (floatNodes, stringNodes) = parse(data)
        val markerCount = findAllMarkers(data).size

        val (latLeaves, lonLeaves) = findLatLonLeafNodes(data)

        var totalFloats = 0
        for (node in floatNodes) {
            totalFloats += node.count
        }

        val latCapacity = latLeaves.sumOf { it.count }

        val routeUuids = extractRouteUuids(data, stringNodes)

        // Compute structure-based route count (route-ID column preferred, gap fallback)
        val routeSegmentCount = findRouteSegments(data)?.size ?: 0

        return DbFileInfo(
            fileName = fileName,
            fileSize = data.size,
            markerCount = markerCount,
            float64Nodes = floatNodes,
            stringNodes = stringNodes,
            totalFloatEntries = totalFloats,
            coordinateCapacity = latCapacity,
            routeUuids = routeUuids,
            routeSegmentCount = routeSegmentCount
        )
    }
}
