package com.mergedb.app.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Merges two GPS Joystick .db files using the "Template In-place Merge" strategy.
 *
 * The larger file serves as the "host" (template). Its binary structure is preserved
 * exactly, with only Float64 coordinate leaf nodes and string nodes being overwritten.
 *
 * The host's total capacity equals its lat leaf node count (all slots are pre-allocated).
 * Both host and guest data must fit within this capacity. If there isn't enough room,
 * guest coordinates are truncated to fit.
 */
object MergeEngine {

    data class MergeCheck(
        val canMerge: Boolean,
        val hostFileName: String,
        val guestFileName: String,
        val hostCapacity: Int,
        val hostUsed: Int,
        val guestUsed: Int,
        val guestFit: Int,
        val hostNameSlots: Int,
        val totalRoutes: Int,
        val message: String
    )

    /**
     * Analyze both files and determine merge feasibility.
     * The host capacity is the total number of slots in its lat Run.
     * Both host and guest data are placed sequentially into these slots.
     */
    fun checkMerge(
        infoA: DbFileInfo,
        infoB: DbFileInfo,
        dataA: ByteArray,
        dataB: ByteArray
    ): MergeCheck {
        val host: DbFileInfo
        val guest: DbFileInfo
        val hostData: ByteArray
        val guestData: ByteArray

        if (infoA.fileSize >= infoB.fileSize) {
            host = infoA; guest = infoB; hostData = dataA; guestData = dataB
        } else {
            host = infoB; guest = infoA; hostData = dataB; guestData = dataA
        }

        val hostCapacity = host.coordinateCapacity

        // Count actual finite values used in each file
        val (hostLatLeaves, _) = RealmBinaryParser.findLatLonLeafNodes(hostData)
        val (guestLatLeaves, _) = RealmBinaryParser.findLatLonLeafNodes(guestData)

        val hostUsed = countFiniteValues(hostData, hostLatLeaves)
        val guestUsed = countFiniteValues(guestData, guestLatLeaves)

        val spaceAvailable = hostCapacity - hostUsed - 1 // -1 for gap point
        val guestFit = minOf(guestUsed, maxOf(0, spaceAvailable))

        val canMerge = guestFit > 0
        val hostNameSlots = host.routeUuids.size
        val totalRoutes = host.routeUuids.size + guest.routeUuids.size

        val message = when {
            spaceAvailable <= 0 ->
                "空間不足: 容器已滿 ($hostUsed/$hostCapacity)。\n" +
                "請先在 GPS Joystick 中建立一條空的長路徑以擴張資料庫。"
            guestFit >= guestUsed ->
                "可完整合併: 容器 $hostCapacity 容量, 已用 $hostUsed, " +
                "來源 $guestUsed 點全部可放入"
            else ->
                "部分合併: 容器剩餘 $spaceAvailable 空間, " +
                "來源 $guestUsed 點中僅能放入 $guestFit 點"
        }

        return MergeCheck(
            canMerge = canMerge,
            hostFileName = host.fileName,
            guestFileName = guest.fileName,
            hostCapacity = hostCapacity,
            hostUsed = hostUsed,
            guestUsed = guestUsed,
            guestFit = guestFit,
            hostNameSlots = hostNameSlots,
            totalRoutes = totalRoutes,
            message = message
        )
    }

    /**
     * Overloaded checkMerge without data (for UI display before merge).
     * Uses coordinateCapacity as an approximation.
     */
    fun checkMerge(infoA: DbFileInfo, infoB: DbFileInfo): MergeCheck {
        val host = if (infoA.fileSize >= infoB.fileSize) infoA else infoB
        val guest = if (infoA.fileSize >= infoB.fileSize) infoB else infoA

        val hostCapacity = host.coordinateCapacity
        val guestCapacity = guest.coordinateCapacity
        val hostNameSlots = host.routeUuids.size
        val totalRoutes = host.routeUuids.size + guest.routeUuids.size

        // Without raw data, assume all capacity is used
        return MergeCheck(
            canMerge = true, // Will be rechecked at merge time
            hostFileName = host.fileName,
            guestFileName = guest.fileName,
            hostCapacity = hostCapacity,
            hostUsed = hostCapacity,
            guestUsed = guestCapacity,
            guestFit = guestCapacity, // Optimistic estimate
            hostNameSlots = hostNameSlots,
            totalRoutes = totalRoutes,
            message = "容器容量: $hostCapacity, 來源座標: $guestCapacity (合併時詳細檢查)"
        )
    }

    private fun countFiniteValues(data: ByteArray, leaves: List<RealmNode.Float64Leaf>): Int {
        var count = 0
        for (node in leaves) {
            val values = RealmBinaryParser.readFloat64Values(data, node)
            count += values.count { it.isFinite() }
        }
        return count
    }

    /**
     * Perform the merge. Returns the merged byte array (same size as hostData).
     * Guest coordinates are truncated if capacity is insufficient.
     */
    fun merge(
        hostData: ByteArray,
        guestData: ByteArray,
        hostInfo: DbFileInfo,
        guestInfo: DbFileInfo
    ): ByteArray {
        val output = hostData.copyOf()
        val outBuf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

        // Step 1: Find lat/lon leaf nodes via B-tree tracing
        val (hostLatLeaves, hostLonLeaves) = RealmBinaryParser.findLatLonLeafNodes(hostData)
        val (guestLatLeaves, guestLonLeaves) = RealmBinaryParser.findLatLonLeafNodes(guestData)

        val hostCapacity = hostLatLeaves.sumOf { it.count }

        // Step 2: Read all coordinate values
        val hostLatValues = readAllFiniteValues(hostData, hostLatLeaves)
        val hostLonValues = readAllFiniteValues(hostData, hostLonLeaves)
        val guestLatValues = readAllFiniteValues(guestData, guestLatLeaves)
        val guestLonValues = readAllFiniteValues(guestData, guestLonLeaves)

        // Step 3: Build combined arrays with 300km gap
        val combinedLat = mutableListOf<Double>()
        val combinedLon = mutableListOf<Double>()

        // Add host coordinates
        val hostPairs = minOf(hostLatValues.size, hostLonValues.size)
        for (i in 0 until hostPairs) {
            combinedLat.add(hostLatValues[i])
            combinedLon.add(hostLonValues[i])
        }

        // Calculate how many guest coords can fit
        val remainingSlots = hostCapacity - combinedLat.size - 1 // -1 for gap
        val guestPairs = minOf(
            minOf(guestLatValues.size, guestLonValues.size),
            maxOf(0, remainingSlots)
        )

        // Insert gap point (latitude +3 degrees ≈ 333km)
        if (combinedLat.isNotEmpty() && guestPairs > 0) {
            combinedLat.add(combinedLat.last() + 3.0)
            combinedLon.add(combinedLon.last())
        }

        // Add guest coordinates (truncated to fit)
        for (i in 0 until guestPairs) {
            combinedLat.add(guestLatValues[i])
            combinedLon.add(guestLonValues[i])
        }

        // Step 4: Write combined lat values to host's lat leaf nodes
        writeValuesToLeaves(outBuf, hostLatLeaves, combinedLat)
        writeValuesToLeaves(outBuf, hostLonLeaves, combinedLon)

        // Step 5: Update route name UUIDs
        val combinedUuids = hostInfo.routeUuids + guestInfo.routeUuids
        val uuidNodes = hostInfo.stringNodes.filter { node ->
            val strings = RealmBinaryParser.readStrings(hostData, node)
            strings.any { it.length == 36 && it.count { c -> c == '-' } == 4 }
        }

        if (uuidNodes.isNotEmpty()) {
            val mainUuidNode = uuidNodes.maxByOrNull { it.maxLength } ?: uuidNodes[0]
            val uuidBytes = ByteArray(mainUuidNode.maxLength)
            var writePos = 0
            for (uuid in combinedUuids) {
                val uuidByteArray = uuid.toByteArray(Charsets.UTF_8)
                if (writePos + uuidByteArray.size + 1 <= uuidBytes.size) {
                    System.arraycopy(uuidByteArray, 0, uuidBytes, writePos, uuidByteArray.size)
                    writePos += uuidByteArray.size
                    uuidBytes[writePos] = 0
                    writePos++
                } else {
                    break
                }
            }
            System.arraycopy(uuidBytes, 0, output, mainUuidNode.dataOffset, uuidBytes.size)
        }

        return output
    }

    private fun readAllFiniteValues(
        data: ByteArray,
        leaves: List<RealmNode.Float64Leaf>
    ): List<Double> {
        val values = mutableListOf<Double>()
        for (node in leaves) {
            val nodeValues = RealmBinaryParser.readFloat64Values(data, node)
            for (v in nodeValues) {
                if (v.isFinite()) values.add(v)
            }
        }
        return values
    }

    private fun writeValuesToLeaves(
        buf: ByteBuffer,
        leaves: List<RealmNode.Float64Leaf>,
        values: List<Double>
    ) {
        var idx = 0
        for (node in leaves) {
            for (j in 0 until node.count) {
                val value = if (idx < values.size) {
                    values[idx++]
                } else if (values.isNotEmpty()) {
                    values.last() // pad with last valid value
                } else {
                    0.0
                }
                buf.putDouble(node.dataOffset + j * 8, value)
            }
        }
    }
}
