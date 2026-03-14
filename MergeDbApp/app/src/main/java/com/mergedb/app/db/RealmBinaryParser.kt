package com.mergedb.app.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

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

            val columnLeaves = mutableListOf<List<RealmNode.Float64Leaf>>()
            for (childOffset in children) {
                val leaves = traceBTreeLeaves(data, buf, childOffset, floatNodeByOffset)
                columnLeaves.add(leaves)
            }

            // The CoordinateData table has lat and lon as the two columns
            // with the most 0x0c leaf nodes (equal count).
            val validColumns = columnLeaves
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.size }

            if (validColumns.size >= 2 && validColumns[0].size == validColumns[1].size) {
                val col0Offset = children.indexOf(findBTreeRoot(data, buf, validColumns[0]))
                val col1Offset = children.indexOf(findBTreeRoot(data, buf, validColumns[1]))

                // Schema order: column 0 = latitude, column 1 = longitude
                return if (col0Offset < col1Offset) {
                    Pair(validColumns[0], validColumns[1])
                } else {
                    Pair(validColumns[1], validColumns[0])
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

    /**
     * Find which B-tree root offset corresponds to a given set of leaves.
     */
    private fun findBTreeRoot(
        data: ByteArray,
        buf: ByteBuffer,
        leaves: List<RealmNode.Float64Leaf>
    ): Int {
        if (leaves.isEmpty()) return -1
        val firstLeafOffset = leaves.first().offset
        val markers = findAllMarkers(data)
        for (offset in markers) {
            if (offset + 8 > data.size) continue
            val type = data[offset + 4].toInt() and 0xFF
            val count = readNodeCount(data, offset)
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

    /**
     * Structural information about the CoordinateData B-Tree in a Realm .db file.
     * Used by RealmFileExtender to perform lossless merges by appending new leaf nodes.
     */
    data class CoordinateBTreeInfo(
        val node46Offset: Int,        // absolute offset of the 0x46 field-pointer node
        val latColIndexIn46: Int,     // which entry (0-3) in 0x46 points to the lat B-Tree root
        val lonColIndexIn46: Int,     // which entry (0-3) in 0x46 points to the lon B-Tree root
        val latLeaves: List<RealmNode.Float64Leaf>,
        val lonLeaves: List<RealmNode.Float64Leaf>
    )

    /**
     * Like findLatLonLeafNodes, but also returns the 0x46 node offset and column indices
     * so that callers can update the B-Tree root pointers in-place when extending the file.
     */
    fun findCoordinateBTreeInfo(data: ByteArray): CoordinateBTreeInfo? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val markers = findAllMarkers(data)
        val (allFloatNodes, _) = parse(data)
        val floatNodeByOffset = allFloatNodes.associateBy { it.offset }

        // Collect all 0x46 nodes with their absolute file offsets
        val node46List = mutableListOf<Pair<Int, List<Int>>>() // (node46Offset, children)
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

        for ((node46Offset, children) in node46List) {
            if (children.size != 4) continue

            val columnLeaves = children.map { childOffset ->
                traceBTreeLeaves(data, buf, childOffset, floatNodeByOffset)
            }

            val validColumns = columnLeaves.filter { it.isNotEmpty() }
                .sortedByDescending { it.size }
            if (validColumns.size < 2 || validColumns[0].size != validColumns[1].size) continue

            val root0Offset = findBTreeRoot(data, buf, validColumns[0])
            val root1Offset = findBTreeRoot(data, buf, validColumns[1])
            val idx0 = children.indexOf(root0Offset)
            val idx1 = children.indexOf(root1Offset)
            if (idx0 < 0 || idx1 < 0) continue

            // Lower column index in schema = latitude
            val latColIdx: Int
            val lonColIdx: Int
            val latLeaves: List<RealmNode.Float64Leaf>
            val lonLeaves: List<RealmNode.Float64Leaf>
            if (idx0 < idx1) {
                latColIdx = idx0; lonColIdx = idx1
                latLeaves = validColumns[0]; lonLeaves = validColumns[1]
            } else {
                latColIdx = idx1; lonColIdx = idx0
                latLeaves = validColumns[1]; lonLeaves = validColumns[0]
            }

            return CoordinateBTreeInfo(
                node46Offset = node46Offset,
                latColIndexIn46 = latColIdx,
                lonColIndexIn46 = lonColIdx,
                latLeaves = latLeaves,
                lonLeaves = lonLeaves
            )
        }
        return null
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
