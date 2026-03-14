package com.mergedb.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mergedb.app.MergeState
import com.mergedb.app.db.DbFileInfo
import com.mergedb.app.db.MergeEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    fileAInfo: DbFileInfo?,
    fileBInfo: DbFileInfo?,
    mergeState: MergeState,
    mergeCheck: MergeEngine.MergeCheck?,
    onSelectFileA: () -> Unit,
    onSelectFileB: () -> Unit,
    onMerge: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GPS Joystick DB 合併工具") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // File A selection
            FileSelectionCard(
                label = "檔案 A",
                info = fileAInfo,
                isHost = mergeCheck?.hostFileName == fileAInfo?.fileName,
                onSelect = onSelectFileA,
                enabled = mergeState !is MergeState.Merging
            )

            // File B selection
            FileSelectionCard(
                label = "檔案 B",
                info = fileBInfo,
                isHost = mergeCheck?.hostFileName == fileBInfo?.fileName,
                onSelect = onSelectFileB,
                enabled = mergeState !is MergeState.Merging
            )

            // Merge check info
            mergeCheck?.let { check ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (check.canMerge)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "合併檢查",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("主容器: ${check.hostFileName}")
                        Text("來源檔: ${check.guestFileName}")
                        Text("容器容量: ${check.hostCapacity} 座標點")
                        Text("主容器已用: ${check.hostUsed} 座標點")
                        Text("來源座標: ${check.guestUsed} 點 (全數合併，無資料遺失)")
                        if (check.requiresExtension) {
                            Text("合併模式: 延伸合併 (輸出檔案將變大)")
                        }
                        Text("路線名稱槽位: ${check.hostNameSlots} / 總路線: ${check.totalRoutes}")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = check.message,
                            fontWeight = FontWeight.Bold,
                            color = if (check.canMerge)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Merge button
            Button(
                onClick = onMerge,
                modifier = Modifier.fillMaxWidth(),
                enabled = mergeCheck?.canMerge == true &&
                        mergeState !is MergeState.Merging &&
                        mergeState !is MergeState.Parsing
            ) {
                Text("合併", style = MaterialTheme.typography.titleMedium)
            }

            // Status display
            when (mergeState) {
                is MergeState.Parsing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在解析檔案 ${if (mergeState.slot == 0) "A" else "B"}...")
                }
                is MergeState.Merging -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在合併...")
                }
                is MergeState.Success -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (mergeState.validation.passed) "合併成功!" else "合併完成 (有警告)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val er = mergeState.extendResult
                            if (er.wasExtended) {
                                Text("合併模式: 延伸合併 (檔案已擴大)")
                            } else {
                                Text("合併模式: 原地合併 (檔案大小不變)")
                            }
                            Text("合併座標總數: ${er.totalCoordinates} 點 (新增 ${er.addedCoordinates} 點)")
                            Spacer(modifier = Modifier.height(8.dp))
                            for (detail in mergeState.validation.details) {
                                Text(detail)
                            }
                        }
                    }
                }
                is MergeState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = mergeState.message,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            TextButton(onClick = onDismissError) {
                                Text("關閉")
                            }
                        }
                    }
                }
                is MergeState.Idle -> {}
            }
        }
    }
}

@Composable
fun FileSelectionCard(
    label: String,
    info: DbFileInfo?,
    isHost: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isHost)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label + if (isHost) " (主容器)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = onSelect, enabled = enabled) {
                    Text("選擇檔案")
                }
            }

            if (info != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("檔名: ${info.fileName}")
                Text("大小: ${info.fileSize / 1024} KB")
                Text("AAAA 標記: ${info.markerCount}")
                Text("Float64 節點: ${info.float64Nodes.size}")
                Text("座標容量: ${info.coordinateCapacity} 點")
                Text("路線數: ${info.routeUuids.size}")
                if (info.routeUuids.isNotEmpty()) {
                    Text(
                        "UUID: ${info.routeUuids.first().take(16)}... 等 ${info.routeUuids.size} 條",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
