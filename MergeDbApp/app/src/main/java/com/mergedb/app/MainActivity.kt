package com.mergedb.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mergedb.app.tsp.TspViewModel
import com.mergedb.app.ui.MergeScreen
import com.mergedb.app.ui.TspScreen
import com.mergedb.app.ui.theme.MergeDbTheme

class MainActivity : ComponentActivity() {

    // ── Merge launchers ───────────────────────────────────────────────────────
    private var pendingFileSlot = 0

    private val mergeFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { mergeVm.onFileSelected(pendingFileSlot, it, this) } }

    private val mergeOutputLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { mergeVm.onMerge(it, this) } }

    // ── TSP launchers ─────────────────────────────────────────────────────────
    private val tspFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { tspVm.loadFile(it, this) } }

    private val tspOutputLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { tspVm.exportResult(it, this) } }

    private lateinit var mergeVm: MainViewModel
    private lateinit var tspVm: TspViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MergeDbTheme {
                mergeVm = viewModel()
                tspVm = viewModel()

                var selectedTab by remember { mutableIntStateOf(0) }

                val fileAInfo by mergeVm.fileAInfo.collectAsState()
                val fileBInfo by mergeVm.fileBInfo.collectAsState()
                val mergeState by mergeVm.mergeState.collectAsState()
                val mergeCheck by mergeVm.mergeCheck.collectAsState()

                val tspState by tspVm.state.collectAsState()
                val tspConfig by tspVm.config.collectAsState()
                val applyTspOnMerge by mergeVm.applyTspOnMerge.collectAsState()

                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Page content ──────────────────────────────────────────
                    when (selectedTab) {
                        0 -> MergeScreen(
                            fileAInfo = fileAInfo,
                            fileBInfo = fileBInfo,
                            mergeState = mergeState,
                            mergeCheck = mergeCheck,
                            applyTspOnMerge = applyTspOnMerge,
                            onSelectFileA = {
                                pendingFileSlot = 0
                                mergeFilePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onSelectFileB = {
                                pendingFileSlot = 1
                                mergeFilePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onMerge = {
                                val hostName = mergeCheck?.hostFileName ?: "merged"
                                mergeOutputLauncher.launch("merged_$hostName")
                            },
                            onToggleApplyTsp = { mergeVm.toggleApplyTspOnMerge() },
                            onDismissError = { mergeVm.resetState() },
                            modifier = Modifier.weight(1f)
                        )
                        1 -> TspScreen(
                            state = tspState,
                            config = tspConfig,
                            onSelectFile = { tspFilePickerLauncher.launch(arrayOf("*/*")) },
                            onSetStrategy = { tspVm.setStrategy(it) },
                            onSetOptimizer = { tspVm.setOptimizer(it) },
                            onSetSkipThreshold = { tspVm.setSkipLargeThreshold(it) },
                            onSetImprovementThreshold = { tspVm.setImprovementThreshold(it) },
                            onSetTimeout = { tspVm.setTimeoutMs(it) },
                            onSetMaxJump = { tspVm.setMaxConsecutiveJumpKm(it) },
                            onRunOptimize = { tspVm.runOptimize() },
                            onExport = {
                                val tspFileName = (tspState as? com.mergedb.app.tsp.TspState.Done)?.let {
                                    "tsp_optimized.db"
                                } ?: "tsp_optimized.db"
                                tspOutputLauncher.launch(tspFileName)
                            },
                            onReset = { tspVm.reset() },
                            onDismissError = { tspVm.dismissError() },
                            onAnalyzeStructure = { tspVm.analyzeDbStructure() },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // ── Bottom navigation ─────────────────────────────────────
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Text("⇄") },
                            label = { Text("合併") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Text("✦") },
                            label = { Text("TSP 優化") }
                        )
                    }
                }
            }
        }
    }
}
