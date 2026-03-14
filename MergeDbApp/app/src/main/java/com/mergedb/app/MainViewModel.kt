package com.mergedb.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mergedb.app.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class MergeState {
    data object Idle : MergeState()
    data class Parsing(val slot: Int) : MergeState()
    data object Merging : MergeState()
    data class Success(
        val validation: MergeValidator.ValidationResult,
        val outputUri: Uri? = null
    ) : MergeState()
    data class Error(val message: String) : MergeState()
}

class MainViewModel : ViewModel() {

    private val _fileAInfo = MutableStateFlow<DbFileInfo?>(null)
    val fileAInfo: StateFlow<DbFileInfo?> = _fileAInfo

    private val _fileBInfo = MutableStateFlow<DbFileInfo?>(null)
    val fileBInfo: StateFlow<DbFileInfo?> = _fileBInfo

    private val _mergeState = MutableStateFlow<MergeState>(MergeState.Idle)
    val mergeState: StateFlow<MergeState> = _mergeState

    private val _mergeCheck = MutableStateFlow<MergeEngine.MergeCheck?>(null)
    val mergeCheck: StateFlow<MergeEngine.MergeCheck?> = _mergeCheck

    // Raw file data kept in memory for merge operation
    private var fileAData: ByteArray? = null
    private var fileBData: ByteArray? = null

    fun onFileSelected(slot: Int, uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _mergeState.value = MergeState.Parsing(slot)
            try {
                val fileName = getFileName(uri, context) ?: "unknown.db"
                val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("無法讀取檔案")

                if (!RealmBinaryParser.isValidDb(data)) {
                    throw Exception("不是有效的 GPS Joystick .db 檔案 (缺少 T-DB 標頭)")
                }

                val info = RealmBinaryParser.buildFileInfo(fileName, data)

                when (slot) {
                    0 -> {
                        fileAData = data
                        _fileAInfo.value = info
                    }
                    1 -> {
                        fileBData = data
                        _fileBInfo.value = info
                    }
                }

                // Update merge check if both files loaded
                val a = _fileAInfo.value
                val b = _fileBInfo.value
                val dA = fileAData
                val dB = fileBData
                if (a != null && b != null && dA != null && dB != null) {
                    _mergeCheck.value = MergeEngine.checkMerge(a, b, dA, dB)
                }

                _mergeState.value = MergeState.Idle
            } catch (e: Exception) {
                _mergeState.value = MergeState.Error("解析失敗: ${e.message}")
            }
        }
    }

    fun onMerge(outputUri: Uri, context: Context) {
        val a = _fileAInfo.value ?: return
        val b = _fileBInfo.value ?: return
        val dataA = fileAData ?: return
        val dataB = fileBData ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _mergeState.value = MergeState.Merging
            try {
                // Determine host (larger file) and guest
                val hostData: ByteArray
                val guestData: ByteArray
                val hostInfo: DbFileInfo
                val guestInfo: DbFileInfo

                if (a.fileSize >= b.fileSize) {
                    hostData = dataA
                    guestData = dataB
                    hostInfo = a
                    guestInfo = b
                } else {
                    hostData = dataB
                    guestData = dataA
                    hostInfo = b
                    guestInfo = a
                }

                // Perform merge
                val merged = MergeEngine.merge(hostData, guestData, hostInfo, guestInfo)

                // Validate
                val validation = MergeValidator.validate(hostData, merged)

                // Write output
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    out.write(merged)
                } ?: throw Exception("無法寫入輸出檔案")

                _mergeState.value = MergeState.Success(validation, outputUri)
            } catch (e: Exception) {
                _mergeState.value = MergeState.Error("合併失敗: ${e.message}")
            }
        }
    }

    fun resetState() {
        _mergeState.value = MergeState.Idle
    }

    private fun getFileName(uri: Uri, context: Context): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }
}
