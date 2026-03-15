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
}

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
    val routeUuids: List<String>
)
