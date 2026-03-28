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
import com.mergedb.app.ui.TutorialScreen
import com.mergedb.app.ui.theme.MergeDbTheme

class MainActivity : ComponentActivity() {

    // ── Merge launchers ───────────────────────────────────────────────────────
    private var pendingFileSlot = 0

    private val mergeFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { mergeVm.onFileSelected(pendingFileSlot, it, this) } }

    // ── TSP launchers ─────────────────────────────────────────────────────────
    private val tspFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { tspVm.loadFile(it, this) } }

    private val tspGpxPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { tspVm.loadGpxFile(it, this) } }

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
                val tspIsGpx by tspVm.inputIsGpx.collectAsState()
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
                            onMerge = { mergeVm.onMerge(this@MainActivity) },
                            onToggleApplyTsp = { mergeVm.toggleApplyTspOnMerge() },
                            onDismissError = { mergeVm.resetState() },
                            modifier = Modifier.weight(1f)
                        )
                        1 -> TspScreen(
                            state = tspState,
                            config = tspConfig,
                            isGpxInput = tspIsGpx,
                            onSelectFile = { tspFilePickerLauncher.launch(arrayOf("*/*")) },
                            onSelectGpxFile = { tspGpxPickerLauncher.launch(arrayOf("*/*", "application/gpx+xml")) },
                            onSetStrategy = { tspVm.setStrategy(it) },
                            onSetOptimizer = { tspVm.setOptimizer(it) },
                            onSetSkipThreshold = { tspVm.setSkipLargeThreshold(it) },
                            onSetImprovementThreshold = { tspVm.setImprovementThreshold(it) },
                            onSetTimeout = { tspVm.setTimeoutMs(it) },
                            onSetMaxJump = { tspVm.setMaxConsecutiveJumpKm(it) },
                            onRunOptimize = { tspVm.runOptimize() },
                            onExport = { tspVm.exportResult(this@MainActivity) },
                            onReset = { tspVm.reset() },
                            onDismissError = { tspVm.dismissError() },
                            onAnalyzeStructure = { tspVm.analyzeDbStructure() },
                            onCancelOptimize = { tspVm.cancelOptimize() },
                            modifier = Modifier.weight(1f)
                        )
                        2 -> TutorialScreen(modifier = Modifier.weight(1f))
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
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Text("📚") },
                            label = { Text("演算法教學") }
                        )
                    }
                }
            }
        }
    }
}
