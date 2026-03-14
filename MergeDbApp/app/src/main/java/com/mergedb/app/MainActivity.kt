package com.mergedb.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mergedb.app.ui.MergeScreen
import com.mergedb.app.ui.theme.MergeDbTheme

class MainActivity : ComponentActivity() {

    private var pendingFileSlot = 0

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.onFileSelected(pendingFileSlot, it, this) }
    }

    private val outputFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { vm.onMerge(it, this) }
    }

    private lateinit var vm: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MergeDbTheme {
                vm = viewModel()
                val fileAInfo by vm.fileAInfo.collectAsState()
                val fileBInfo by vm.fileBInfo.collectAsState()
                val mergeState by vm.mergeState.collectAsState()
                val mergeCheck by vm.mergeCheck.collectAsState()

                MergeScreen(
                    fileAInfo = fileAInfo,
                    fileBInfo = fileBInfo,
                    mergeState = mergeState,
                    mergeCheck = mergeCheck,
                    onSelectFileA = {
                        pendingFileSlot = 0
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onSelectFileB = {
                        pendingFileSlot = 1
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onMerge = {
                        val hostName = mergeCheck?.hostFileName ?: "merged"
                        val outputName = "merged_${hostName}"
                        outputFileLauncher.launch(outputName)
                    },
                    onDismissError = {
                        vm.resetState()
                    }
                )
            }
        }
    }
}
