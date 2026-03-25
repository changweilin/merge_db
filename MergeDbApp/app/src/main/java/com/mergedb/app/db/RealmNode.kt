package com.mergedb.app.db

/**
 * Represents a parsed node from a Realm binary .db file.
 * Nodes are identified by the ASCII "AAAA" marker (0x41414141).
 * The type byte is at marker_offset + 4.
 */
sealed class RealmNode(val offset: Int, val type: Byte) {

    /**
     * Type 0x0c: Float64 leaf node containing coordinate data.
     * Header: AAAA [type=0x0c] [count: 3-byte Big-Endian]  (total = 8 bytes)
     * Data starts at offset + 8, each entry is 8 bytes (Float64 LE).
     */
    class Float64Leaf(
        offset: Int,
        val count: Int,
        val dataOffset: Int   // absolute offset where Float64 values begin
    ) : RealmNode(offset, 0x0c)

    /**
     * Type 0x11: String node containing route names, UUIDs, URLs, etc.
     * Header: AAAA [type=0x11] [maxLength: 3-byte Big-Endian]  (total = 8 bytes)
     * String data starts at offset + 8.
     */
    class StringNode(
        offset: Int,
        val maxLength: Int,   // total byte capacity of this string slot
        val dataOffset: Int   // absolute offset where string bytes begin
    ) : RealmNode(offset, 0x11)

    /**
     * Integer leaf node of unknown type (e.g. route-ID / link column).
     * [bytesPerEntry] is inferred from (nextMarkerOffset - dataOffset) / count;
     * 4 → Int32, 8 → Int64.  Values are read as unsigned for safety.
     */
    class IntLeaf(
        offset: Int,
        typeByte: Byte,
        val count: Int,
        val dataOffset: Int,
        val bytesPerEntry: Int
    ) : RealmNode(offset, typeByte)
}

/**
 * One distinct node-type entry found during a full scan.
 * [bytesPerEntry] = (data bytes between this node and the next AAAA) / count;
 * helps distinguish Int32 (4), Int64/Float64 (8), etc.
 */
data class NodeTypeSummary(
    val typeByte: Int,
    val count: Int,            // how many nodes of this type exist
    val totalEntries: Int,     // sum of all entry counts across those nodes
    val bytesPerEntry: Int     // inferred from spacing; -1 if ambiguous
)

/**
 * Description of one column inside a 0x46 table node.
 * [childTypes] = distinct type bytes of leaf nodes reachable from this column's B-tree.
 * [leafCount]  = number of leaf nodes in the column's B-tree.
 * [entryCount] = total number of data entries across all leaves.
 */
data class TableColumnInfo(
    val columnIndex: Int,
    val childTypes: List<Int>,
    val leafCount: Int,
    val entryCount: Int
)

/**
 * One table discovered in the DB (one 0x46 node).
 */
data class TableInfo(
    val node46Offset: Int,
    val columns: List<TableColumnInfo>
)

/**
 * Summary of a parsed .db file.
 */
data class DbFileInfo(
    val fileName: String,
    val fileSize: Int,
    val markerCount: Int,
    val float64Nodes: List<RealmNode.Float64Leaf>,
    val stringNodes: List<RealmNode.StringNode>,
    val totalFloatEntries: Int,
    val coordinateCapacity: Int,  // totalFloatEntries / 2 (lat + lon pairs)
    val routeUuids: List<String>,
    val routeSegmentCount: Int    // segments from route-ID column (or gap fallback)
)
