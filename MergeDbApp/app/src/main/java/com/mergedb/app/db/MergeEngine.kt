package com.mergedb.app.db

/**
 * Analyses GPS Joystick .db file pairs and delegates the actual merge to
 * RealmFileExtender, which performs a lossless binary extension merge.
 *
 * checkMerge() classifies the merge scenario so the UI can give the user
 * accurate information before they confirm.
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
        /** True when the output file will be larger than the host (extension merge). */
        val requiresExtension: Boolean,
        val message: String
    )

    /**
     * Full analysis using actual finite-value counts from both raw data arrays.
     */
    fun checkMerge(
        infoA: DbFileInfo,
        infoB: DbFileInfo,
        dataA: ByteArray,
        dataB: ByteArray
    ): MergeCheck {
        val isAHost = infoA.fileSize >= infoB.fileSize
        val host      = if (isAHost) infoA else infoB
        val guest     = if (isAHost) infoB else infoA
        val hostData  = if (isAHost) dataA else dataB
        val guestData = if (isAHost) dataB else dataA

        val hostCapacity = host.coordinateCapacity

        val (hostLatLeaves, _)  = RealmBinaryParser.findLatLonLeafNodes(hostData)
        val (guestLatLeaves, _) = RealmBinaryParser.findLatLonLeafNodes(guestData)

        val hostUsed  = countFinite(hostData,  hostLatLeaves)
        val guestUsed = countFinite(guestData, guestLatLeaves)

        // Extension merge: all guest data always fits (file grows as needed)
        val requiresExtension = (hostUsed + 1 + guestUsed) > hostCapacity

        val message = if (!requiresExtension) {
            "可完整合併 (無資料遺失): 容量 $hostCapacity, 已用 $hostUsed, " +
            "來源 $guestUsed 點全數放入"
        } else {
            val extra = hostUsed + 1 + guestUsed - hostCapacity
            "容量不足，將自動延伸檔案 (無資料遺失): " +
            "容量 $hostCapacity, 已用 $hostUsed, 來源 $guestUsed 點 → 新增 $extra 個槽位"
        }

        return MergeCheck(
            canMerge = true,   // always possible with extension merge
            hostFileName = host.fileName,
            guestFileName = guest.fileName,
            hostCapacity = hostCapacity,
            hostUsed = hostUsed,
            guestUsed = guestUsed,
            guestFit = guestUsed,
            hostNameSlots = host.routeUuids.size,
            totalRoutes = host.routeUuids.size + guest.routeUuids.size,
            requiresExtension = requiresExtension,
            message = message
        )
    }

    /**
     * Quick analysis without raw data (used for UI before both files are loaded).
     */
    fun checkMerge(infoA: DbFileInfo, infoB: DbFileInfo): MergeCheck {
        val host  = if (infoA.fileSize >= infoB.fileSize) infoA else infoB
        val guest = if (infoA.fileSize >= infoB.fileSize) infoB else infoA
        return MergeCheck(
            canMerge = true,
            hostFileName = host.fileName,
            guestFileName = guest.fileName,
            hostCapacity = host.coordinateCapacity,
            hostUsed = host.coordinateCapacity,
            guestUsed = guest.coordinateCapacity,
            guestFit = guest.coordinateCapacity,
            hostNameSlots = host.routeUuids.size,
            totalRoutes = host.routeUuids.size + guest.routeUuids.size,
            requiresExtension = true, // conservative estimate
            message = "容器容量: ${host.coordinateCapacity}, 來源座標: ${guest.coordinateCapacity} (合併時詳細檢查)"
        )
    }

    /**
     * Perform a lossless merge via RealmFileExtender.
     * Returns an ExtendResult (merged bytes + merge statistics).
     */
    fun merge(
        hostData: ByteArray,
        guestData: ByteArray,
        hostInfo: DbFileInfo,
        guestInfo: DbFileInfo
    ): RealmFileExtender.ExtendResult =
        RealmFileExtender.extendMerge(hostData, guestData, hostInfo, guestInfo)

    private fun countFinite(data: ByteArray, leaves: List<RealmNode.Float64Leaf>): Int {
        var n = 0
        for (leaf in leaves) {
            n += RealmBinaryParser.readFloat64Values(data, leaf).count { it.isFinite() }
        }
        return n
    }
}
