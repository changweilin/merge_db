package com.mergedb.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mergedb.app.db.*
import com.mergedb.app.tsp.TspState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TspScreen(
    state: TspState,
    config: TspConfig,
    onSelectFile: () -> Unit,
    onSetStrategy: (TspStrategy) -> Unit,
    onSetOptimizer: (TspOptimizer) -> Unit,
    onSetSkipThreshold: (Int) -> Unit,
    onSetImprovementThreshold: (Double) -> Unit,
    onSetTimeout: (Long) -> Unit,
    onSetMaxJump: (Double) -> Unit,
    onRunOptimize: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
    onDismissError: () -> Unit,
    onAnalyzeStructure: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("TSP 路徑優化") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── File selection ────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("選擇 .db 檔案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = onSelectFile,
                            enabled = state !is TspState.Loading && state !is TspState.Optimizing
                        ) { Text("選擇檔案") }
                    }

                    when (state) {
                        is TspState.Loading -> {
                            Spacer(Modifier.height(8.dp))
                            Text("正在載入 ${state.fileName}...")
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        is TspState.Ready -> {
                            Spacer(Modifier.height(8.dp))
                            Text("檔案: ${state.info.fileName}")
                            Text("大小: ${state.info.fileSize / 1024} KB")
                            Text("路線數 (UUID): ${state.info.routeUuids.size}  座標段數: ${state.routeCount}")
                            Text("座標點: ${state.totalPoints} 點")
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = onAnalyzeStructure,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("分析 DB 結構") }
                        }
                        is TspState.Optimizing -> {
                            Spacer(Modifier.height(8.dp))
                            Text("優化中… 路線 ${state.current} / ${state.total}")
                            LinearProgressIndicator(
                                progress = { if (state.total > 0) state.current.toFloat() / state.total else 0f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                        is TspState.Done -> {
                            Spacer(Modifier.height(8.dp))
                            Text("優化完成  已改善 ${state.result.improvedRoutes} / ${state.result.totalRoutes} 條路線")
                        }
                        else -> {}
                    }
                }
            }

            // ── Strategy ─────────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("基本策略", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.selectableGroup()) {
                        TspStrategy.entries.forEach { strategy ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = config.strategy == strategy,
                                        onClick = { onSetStrategy(strategy) },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = config.strategy == strategy,
                                    onClick = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(strategy.label)
                            }
                        }
                    }
                }
            }

            // ── Optimizer ─────────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("優化方法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.selectableGroup()) {
                        TspOptimizer.entries.forEach { opt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = config.optimizer == opt,
                                        onClick = { onSetOptimizer(opt) },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = config.optimizer == opt,
                                    onClick = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(opt.label)
                            }
                        }
                    }
                }
            }

            // ── Parameters ────────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("參數設定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))

                    // Skip large routes
                    var skipText by remember(config.skipLargeThreshold) {
                        mutableStateOf(config.skipLargeThreshold.toString())
                    }
                    OutlinedTextField(
                        value = skipText,
                        onValueChange = { v ->
                            skipText = v
                            v.toIntOrNull()?.let { if (it > 0) onSetSkipThreshold(it) }
                        },
                        label = { Text("略過大型路線 (點數上限)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // Max consecutive jump filter
                    var jumpText by remember(config.maxConsecutiveJumpKm) {
                        mutableStateOf(config.maxConsecutiveJumpKm.toInt().toString())
                    }
                    OutlinedTextField(
                        value = jumpText,
                        onValueChange = { v ->
                            jumpText = v
                            v.toDoubleOrNull()?.let { if (it >= 0) onSetMaxJump(it) }
                        },
                        label = { Text("異常跳躍門檻 (km，0 = 不過濾)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // Improvement threshold slider
                    val impPct = config.improvementThreshold.toFloat()
                    Text("最低改善門檻: ${"%.1f".format(config.improvementThreshold)}%")
                    Slider(
                        value = impPct,
                        onValueChange = { onSetImprovementThreshold(it.toDouble()) },
                        valueRange = 0f..20f,
                        steps = 39,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // Timeout
                    Text("每條路線超時限制")
                    val timeoutOptions = listOf(
                        60_000L to "1 分鐘",
                        180_000L to "3 分鐘",
                        600_000L to "10 分鐘",
                        Long.MAX_VALUE / 2 to "不限時"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        timeoutOptions.forEach { (ms, label) ->
                            FilterChip(
                                selected = config.timeoutMs == ms,
                                onClick = { onSetTimeout(ms) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            // ── Action buttons ────────────────────────────────────────────────
            val canRun = state is TspState.Ready || state is TspState.Done
            Button(
                onClick = onRunOptimize,
                enabled = canRun,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("開始 TSP 優化", style = MaterialTheme.typography.titleMedium)
            }

            if (state is TspState.Done) {
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("匯出優化後 .db", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (state !is TspState.Idle) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("重設") }
            }

            // ── Results ───────────────────────────────────────────────────────
            if (state is TspState.Done) {
                ResultCard(result = state.result)
            }

            // ── DB Structure report ───────────────────────────────────────────
            if (state is TspState.DbStructure) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("DB 結構分析", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.report,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            // ── Error ─────────────────────────────────────────────────────────
            if (state is TspState.Error) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onDismissError) { Text("關閉") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: TspResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "優化結果",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text("總路線: ${result.totalRoutes} 條")
            Text("已改善: ${result.improvedRoutes} 條")
            Text("已略過: ${result.skippedRoutes} 條")
            Spacer(Modifier.height(8.dp))

            result.routeResults.forEach { r ->
                val pct = if (r.originalLength > 0)
                    (r.originalLength - r.optimizedLength) / r.originalLength * 100 else 0.0
                val origKm = r.originalLength / 1000.0
                val optKm  = r.optimizedLength / 1000.0
                val status = when {
                    r.skipped  -> "略過: ${r.reason}"
                    r.improved -> "改善 %.1f%%  (%.2f→%.2f km)".format(pct, origKm, optKm)
                    else       -> "未採用 %.1f%%  (%.2f→%.2f km)  ${r.reason}".format(pct, origKm, optKm)
                }
                Text(
                    "路線 ${r.index + 1}: $status",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
