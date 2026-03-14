package com.mergedb.app.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses GPS Joystick Realm .db files at the binary level.
 *
 * File format key points:
 * - "T-DB" header at offset 0x10
 * - Nodes marked by ASCII "AAAA" (0x41414141)
 * - Node type at marker+4, count/length at marker+7 (single byte)
 * - B-tree index nodes (0xC5 uses uint16 refs, 0xC6 uses uint32 refs)
 * - 0x46 nodes reference B-tree roots for each column of a table
 * - All values are Little-Endian
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

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        for (offset in markers) {
            if (offset + 8 > data.size) continue
            val type = data[offset + 4].toInt() and 0xFF

            when (type) {
                0x0c -> {
                    val count = buf.getShort(offset + 5).toInt() and 0xFFFF
                    val dataOffset = offset + 8
                    if (count > 0 && dataOffset + count * 8 <= data.size) {
                        floatNodes.add(RealmNode.Float64Leaf(offset, count, dataOffset))
                    }
                }
                0x11 -> {
                    val maxLength = buf.getShort(offset + 5).toInt() and 0xFFFF
                    val dataOffset = offset + 8
                    if (dataOffset + maxLength <= data.size) {
                        stringNodes.add(RealmNode.StringNode(offset, maxLength, dataOffset))
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
        val node46List = mutableListOf<Pair<Int, List<Int>>>() // offset, child offsets
        for (offset in markers) {
            if (offset + 8 > data.size) continue
            val type = data[offset + 4].toInt() and 0xFF
            if (type == 0x46) {
                val count = data[offset + 7].toInt() and 0xFF
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

            // Try to trace each child as a B-tree root and collect leaf nodes
            val columnLeaves = mutableListOf<List<RealmNode.Float64Leaf>>()
            for (childOffset in children) {
                val leaves = traceBTreeLeaves(data, buf, childOffset, floatNodeByOffset)
                columnLeaves.add(leaves)
            }

            // The CoordinateData table should have lat and lon as the two columns
            // with the most 0x0c leaf nodes. Find the two largest.
            val validColumns = columnLeaves
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.size }

            if (validColumns.size >= 2 && validColumns[0].size == validColumns[1].size) {
                // Two columns with equal number of leaves = lat and lon
                // Determine which is lat and which is lon by checking value ranges
                val col0FirstVal = readFloat64Values(data, validColumns[0].first()).firstOrNull { it.isFinite() } ?: 0.0
                val col1FirstVal = readFloat64Values(data, validColumns[1].first()).firstOrNull { it.isFinite() } ?: 0.0

                // The column whose first child is listed first in the 0x46 is latitude
                // (schema order: latitude, longitude, altitude)
                val col0Offset = children.indexOf(findBTreeRoot(data, buf, validColumns[0]))
                val col1Offset = children.indexOf(findBTreeRoot(data, buf, validColumns[1]))

                return if (col0Offset < col1Offset) {
                    Pair(validColumns[0], validColumns[1])
                } else {
                    Pair(validColumns[1], validColumns[0])
                }
            }
        }

        // Fallback: split all float nodes in half (first half = lat, second half = lon)
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
        val count = data[rootOffset + 7].toInt() and 0xFF

        when (type) {
            0xC5 -> {
                // uint16 references, skip first and last (metadata)
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
                // uint32 references, skip first and last (metadata)
                if (count < 3 || rootOffset + 8 + count * 4 > data.size) return emptyList()
                val leaves = mutableListOf<RealmNode.Float64Leaf>()
                for (i in 1 until count - 1) {
                    val childOffset = buf.getInt(rootOffset + 8 + i * 4)
                    // Check if child is another C6 (multi-level tree) or a leaf
                    val leaf = floatNodeByOffset[childOffset]
                    if (leaf != null) {
                        leaves.add(leaf)
                    } else {
                        // Might be a deeper C6 node - recurse
                        val subLeaves = traceBTreeLeaves(data, buf, childOffset, floatNodeByOffset)
                        leaves.addAll(subLeaves)
                    }
                }
                return leaves
            }
            else -> return emptyList()
        }
    }

    /**
     * Find which B-tree root offset corresponds to a given set of leaves.
     */
    private fun findBTreeRoot(
        data: ByteArray,
        buf: ByteBuffer,
        leaves: List<RealmNode.Float64Leaf>
    ): Int {
        if (leaves.isEmpty()) return -1
        // The root's children include the first leaf
        val firstLeafOffset = leaves.first().offset
        val markers = findAllMarkers(data)
        for (offset in markers) {
            if (offset + 8 > data.size) continue
            val type = data[offset + 4].toInt() and 0xFF
            val count = data[offset + 7].toInt() and 0xFF
            when (type) {
                0xC5 -> {
                    if (count >= 3 && offset + 8 + count * 2 <= data.size) {
                        for (i in 1 until count - 1) {
                            val child = buf.getShort(offset + 8 + i * 2).toInt() and 0xFFFF
                            if (child == firstLeafOffset) return offset
                        }
                    }
                }
                0xC6 -> {
                    if (count >= 3 && offset + 8 + count * 4 <= data.size) {
                        for (i in 1 until count - 1) {
                            val child = buf.getInt(offset + 8 + i * 4)
                            if (child == firstLeafOffset) return offset
                        }
                    }
                }
            }
        }
        return -1
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

    fun buildFileInfo(fileName: String, data: ByteArray): DbFileInfo {
        val (floatNodes, stringNodes) = parse(data)
        val markerCount = findAllMarkers(data).size

        // Find lat/lon leaf nodes via B-tree tracing
        val (latLeaves, lonLeaves) = findLatLonLeafNodes(data)

        var totalFloats = 0
        for (node in floatNodes) {
            totalFloats += node.count
        }

        // Coordinate capacity is based on lat (or lon) leaf count
        val latCapacity = latLeaves.sumOf { it.count }

        val routeUuids = extractRouteUuids(data, stringNodes)

        return DbFileInfo(
            fileName = fileName,
            fileSize = data.size,
            markerCount = markerCount,
            float64Nodes = floatNodes,
            stringNodes = stringNodes,
            totalFloatEntries = totalFloats,
            coordinateCapacity = latCapacity,
            routeUuids = routeUuids
        )
    }
}
