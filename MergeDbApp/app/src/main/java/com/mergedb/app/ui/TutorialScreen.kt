package com.mergedb.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// ── Main Tutorial Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(modifier: Modifier = Modifier) {
    var selectedSubTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("演算法教學") },
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
        ) {
            TabRow(selectedTabIndex = selectedSubTab) {
                Tab(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    text = { Text("基礎策略") }
                )
                Tab(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    text = { Text("優化疊加") }
                )
            }

            when (selectedSubTab) {
                0 -> BaseStrategiesPage()
                1 -> OptimizersPage()
            }
        }
    }
}

// ── Sub-pages ───────────────────────────────────────────────────────────────

@Composable
private fun BaseStrategiesPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AlgorithmCard(
            title = "1. 最近鄰居法 (Nearest Neighbor)",
            description = "從起點開始，每次都尋找距離目前位置最近的未訪問點，直到走完全部點。速度極快，但末段常因只剩遠處的點而產生路徑交叉。",
            animation = { NNAnimation() },
            steps = listOf(
                "設起始點 s = 0，目前位置 c = s",
                "從未訪問集合 U 中選最近點: next = argmin d(c, j)",
                "加入路徑，c ← next，U ← U ∖ {next}",
                "重複直到 U 為空，連回起點形成迴圈"
            ),
            formula = "next = argmin_{j∈U} d(c, j)\nd(i,j) = 2R · arctan2(√a, √(1−a))",
            complexity = "O(n²)",
            note = "速度極快，適合大量路線批量處理。末段常出現大跨越，品質通常比 Greedy 差 15–25%。"
        )

        AlgorithmCard(
            title = "2. 貪婪演算法 (Greedy)",
            description = "不決定起點，將所有可能的連線按長短排序，優先選擇最短線段。只要不形成提早封閉的迴圈或讓點連出三條線就加入。比 NN 稍慢但結果較好。",
            animation = { GreedyAnimation() },
            steps = listOf(
                "列出所有 n(n−1)/2 條邊，以距離升序排列",
                "逐一嘗試加入最短邊 (i, j)",
                "條件: deg(i)<2 且 deg(j)<2，且不提早封閉迴圈",
                "重複直到加入 n 條邊，構成 Hamiltonian 迴圈"
            ),
            formula = "加入 (i,j) ⟺ deg(i)<2 ∧ deg(j)<2\n          ∧ (|E|=n−1 ∨ ¬cycle(i,j))",
            complexity = "O(n² log n)",
            note = "排序 O(n² log n) 為主要瓶頸；結果通常比 NN 好 10–20%。"
        )

        AlgorithmCard(
            title = "3. 插入法 (Insertion)",
            description = "先從少數點構成小迴圈，再把其餘的點以「增加距離最少」的方式插入到既有迴圈線段之間。非常穩定，很少有嚴重的交叉錯誤。",
            animation = { InsertionAnimation() },
            steps = listOf(
                "初始化路徑 T = [0, 1, 0]",
                "每輪取未訪問集合 U 中下一個點 k",
                "對 T 中每條相鄰邊計算插入代價 Δ(i,k,j)",
                "選代價最小的位置插入 k，重複直到 U 為空"
            ),
            formula = "Δ(i, k, j) = d(i,k) + d(k,j) − d(i,j)\npos* = argmin_{(i,j)∈T} Δ(i, k, j)",
            complexity = "O(n²)",
            note = "結果品質穩定，幾乎不出現嚴重交叉，適合做為 2-Opt 的高品質初始解。"
        )
    }
}

@Composable
private fun OptimizersPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AlgorithmCard(
            title = "A. 2-Opt 優化",
            description = "專門用來消除路線交叉的局部優化法。枚舉所有不相鄰邊對，若反轉其間的片段能縮短總距離，就執行交換，反覆迭代直到無法再改善。",
            animation = { TwoOptAnimation() },
            steps = listOf(
                "枚舉所有邊對 (i,j)，1≤i<j≤n，且 j−i > 1",
                "計算「反轉 T[i..j] 片段」後的新路徑長度",
                "若新長度縮短超過門檻 → 接受，標記 improved = true",
                "重複直到 improved = false 或達到迭代上限"
            ),
            formula = "Δ = [d(t₁,t₃) + d(t₂,t₄)] − [d(t₁,t₂) + d(t₃,t₄)]\nΔ < 0 → 接受交換（路徑縮短）",
            complexity = "O(n²) 每輪",
            note = "改善門檻: 新長度 < 舊長度 − 0.00001 m；maxIterations = n × 100。"
        )

        AlgorithmCard(
            title = "B. Lin-Kernighan (L-K)",
            description = "比 2-Opt 強大，以 Double-Bridge Kick（4-Opt 擾動）為核心，每次隨機切割路徑為四段後重組，再對結果執行 2-Opt 精化。能跳出 2-Opt 容易卡住的局部最佳解。",
            animation = { LKAnimation() },
            steps = listOf(
                "對初始路徑執行完整 2-Opt 優化，得 bestTour",
                "重複 50 次擾動循環",
                "若 n≥8：隨機選 p₁<p₂<p₃，執行 Double-Bridge: A+D+C+B",
                "對擾動路徑再執行 2-Opt；若新長度更短 → 更新 bestTour"
            ),
            formula = "T = A | B | C | D  (以 p₁, p₂, p₃ 切割)\nT' = A + D + C + B  (重組順序)",
            complexity = "O(50 × n²)",
            note = "n < 8 時改用隨機 Swap 擾動替代 Double-Bridge。"
        )

        AlgorithmCard(
            title = "C. 模擬退火 (Simulated Annealing)",
            description = "靈感來自冶金退火。初期高溫時有機率接受變差的解以跳出局部最佳陷阱；隨溫度按幾何級數冷卻，接受爛解的機率趨近於零，最終精細收斂。",
            animation = { SAAnimation() },
            steps = listOf(
                "初始化：T = T₀ = 10000，currentTour = 輸入路徑",
                "每輪溫度：執行 iterationsPerTemp 次隨機 2-Opt 反轉",
                "若 Δ < 0：直接接受；若 Δ ≥ 0：以機率 P = e^(−Δ/T) 接受",
                "降溫：T ← 0.995 × T，直到 T ≤ 0.001"
            ),
            formula = "P(接受較差解) = e^(−Δ/T)\nT_{k+1} = α · T_k，α = 0.995",
            complexity = "≈ 1,840 × iterationsPerTemp 次迭代",
            note = "T₀=10000, α=0.995, T_min=0.001, iterationsPerTemp = min(n×2, 100)。"
        )

        AlgorithmCard(
            title = "D. 遺傳演算法 (Genetic Algorithm)",
            description = "模擬生物演化。同時維護一個族群（多條路徑），每世代計算適應度後，透過錦標賽選擇、Order Crossover 交叉與隨機突變產生下一代，逐代收斂至最佳解。",
            animation = { GAAnimation() },
            steps = listOf(
                "初始化族群：保留初始路徑，其餘以隨機 Swap 變異產生",
                "計算所有個體適應度 f(T)，精英保留前 2 名",
                "錦標賽選擇兩個親代，執行 Order Crossover (OX) 產生子代",
                "以機率 10% 對子代執行 Swap 突變，重複 200 世代"
            ),
            formula = "f(T) = Σ d(T[i], T[(i+1) mod n])\npopSize = max(50, n×2)，generations = 200",
            complexity = "O(generations × popSize × n)",
            note = "popSize=max(50,n×2), generations=200, mutationRate=0.1, 精英保留 2 個。"
        )
    }
}

// ── Algorithm Card Composable ───────────────────────────────────────────────

@Composable
private fun AlgorithmCard(
    title: String,
    description: String,
    animation: @Composable () -> Unit,
    steps: List<String>,
    formula: String,
    complexity: String,
    note: String
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header (clickable to toggle details)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (expanded) "▼" else "▶",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))

            // Animation box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                animation()
            }

            // Collapsible details
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Steps
                SectionLabel("演算法步驟")
                Spacer(Modifier.height(4.dp))
                steps.forEachIndexed { i, step ->
                    Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall)
                    if (i < steps.lastIndex) Spacer(Modifier.height(2.dp))
                }

                Spacer(Modifier.height(10.dp))

                // Formula
                SectionLabel("核心公式")
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        formula,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Complexity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "時間複雜度：",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            complexity,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

// ── Algorithm Animations ────────────────────────────────────────────────────

/** Nearest Neighbor: dots connected one by one */
@Composable
private fun NNAnimation() {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    val infiniteTransition = rememberInfiniteTransition(label = "nn")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height

        val dots = listOf(
            Offset(w * 0.08f, h * 0.55f),
            Offset(w * 0.32f, h * 0.18f),
            Offset(w * 0.57f, h * 0.30f),
            Offset(w * 0.82f, h * 0.65f)
        )
        val connections = listOf(0 to 1, 1 to 2, 2 to 3)
        val seg = 1f / connections.size

        connections.forEachIndexed { i, (from, to) ->
            val alpha = ((phase - i * seg) / seg).coerceIn(0f, 1f)
            if (alpha > 0f) {
                drawLine(
                    color = primaryColor.copy(alpha = alpha),
                    start = dots[from],
                    end = lerp(dots[from], dots[to], alpha),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        dots.forEach { pos ->
            drawCircle(Color.White, radius = 8.dp.toPx(), center = pos)
            drawCircle(errorColor, radius = 6.dp.toPx(), center = pos)
        }
    }
}

/** Greedy: short edges appear first */
@Composable
private fun GreedyAnimation() {
    val greenColor = Color(0xFF34D399)
    val errorColor = MaterialTheme.colorScheme.error

    val infiniteTransition = rememberInfiniteTransition(label = "greedy")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height

        val dots = listOf(
            Offset(w * 0.35f, h * 0.20f),
            Offset(w * 0.52f, h * 0.25f),
            Offset(w * 0.15f, h * 0.72f),
            Offset(w * 0.35f, h * 0.65f),
            Offset(w * 0.60f, h * 0.55f),
            Offset(w * 0.65f, h * 0.15f)
        )
        // Edges ordered by approximate length (short first)
        val edges = listOf(0 to 1, 3 to 2, 4 to 5)
        val seg = 1f / edges.size

        edges.forEachIndexed { i, (from, to) ->
            val alpha = ((phase - i * seg) / seg).coerceIn(0f, 1f)
            if (alpha > 0f) {
                drawLine(
                    color = greenColor.copy(alpha = alpha),
                    start = dots[from],
                    end = lerp(dots[from], dots[to], alpha),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        dots.forEach { pos ->
            drawCircle(Color.White, radius = 8.dp.toPx(), center = pos)
            drawCircle(errorColor, radius = 6.dp.toPx(), center = pos)
        }
    }
}

/** Insertion: 2 base nodes, new node pops in and gets inserted */
@Composable
private fun InsertionAnimation() {
    val purpleColor = Color(0xFFC084FC)
    val grayColor = Color(0xFF94A3B8)
    val errorColor = MaterialTheme.colorScheme.error

    val infiniteTransition = rememberInfiniteTransition(label = "insertion")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height

        val left = Offset(w * 0.12f, h * 0.55f)
        val right = Offset(w * 0.60f, h * 0.55f)
        val newNode = Offset(w * 0.36f, h * 0.20f)

        val newAlpha = ((phase - 0.2f) / 0.2f).coerceIn(0f, 1f)
        val lineAlpha = ((phase - 0.4f) / 0.3f).coerceIn(0f, 1f)
        val baseAlpha = (1f - ((phase - 0.4f) / 0.3f)).coerceIn(0f, 1f)

        // Base dashed line (fades out)
        if (baseAlpha > 0f) {
            drawLine(
                color = grayColor.copy(alpha = baseAlpha),
                start = left, end = right,
                strokeWidth = 2.dp.toPx()
            )
        }
        // New connection lines (fade in)
        if (lineAlpha > 0f && newAlpha > 0.5f) {
            drawLine(
                color = purpleColor.copy(alpha = lineAlpha),
                start = left, end = newNode,
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
            )
            drawLine(
                color = purpleColor.copy(alpha = lineAlpha),
                start = newNode, end = right,
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
            )
        }

        // Fixed base nodes
        listOf(left, right).forEach { pos ->
            drawCircle(Color.White, radius = 8.dp.toPx(), center = pos)
            drawCircle(errorColor, radius = 6.dp.toPx(), center = pos)
        }
        // New node (pops in)
        if (newAlpha > 0f) {
            val radius = 6.dp.toPx() * newAlpha
            drawCircle(Color.White, radius = radius + 2.dp.toPx(), center = newNode)
            drawCircle(purpleColor, radius = radius, center = newNode)
        }
    }
}

/** 2-Opt: crossed lines → uncrossed */
@Composable
private fun TwoOptAnimation() {
    val redColor = Color(0xFFEF4444)
    val greenColor = Color(0xFF34D399)
    val errorColor = MaterialTheme.colorScheme.error

    val infiniteTransition = rememberInfiniteTransition(label = "2opt")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height

        val tl = Offset(w * 0.18f, h * 0.15f)
        val tr = Offset(w * 0.48f, h * 0.15f)
        val bl = Offset(w * 0.18f, h * 0.82f)
        val br = Offset(w * 0.48f, h * 0.82f)

        // Bad (crossed) lines fade out after phase=0.4
        val badAlpha = (1f - ((phase - 0.35f) / 0.2f)).coerceIn(0f, 1f)
        // Good (uncrossed) lines fade in after phase=0.5
        val goodAlpha = ((phase - 0.5f) / 0.2f).coerceIn(0f, 1f)

        if (badAlpha > 0f) {
            drawLine(redColor.copy(alpha = badAlpha), tl, br, strokeWidth = 3.dp.toPx())
            drawLine(redColor.copy(alpha = badAlpha), bl, tr, strokeWidth = 3.dp.toPx())
        }
        if (goodAlpha > 0f) {
            drawLine(greenColor.copy(alpha = goodAlpha), tl, bl, strokeWidth = 3.dp.toPx())
            drawLine(greenColor.copy(alpha = goodAlpha), tr, br, strokeWidth = 3.dp.toPx())
        }

        listOf(tl, tr, bl, br).forEach { pos ->
            drawCircle(Color.White, radius = 8.dp.toPx(), center = pos)
            drawCircle(errorColor, radius = 6.dp.toPx(), center = pos)
        }
    }
}

/** Lin-Kernighan: 4-segment double-bridge visual */
@Composable
private fun LKAnimation() {
    val redColor = Color(0xFFEF4444)
    val yellowColor = Color(0xFFFACC15)
    val errorColor = MaterialTheme.colorScheme.error

    val infiniteTransition = rememberInfiniteTransition(label = "lk")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.38f

        // 6 nodes arranged in a hexagon
        val nodes = (0 until 6).map { i ->
            val angle = Math.toRadians(i * 60.0 - 90.0)
            Offset(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
        }

        val badAlpha = (1f - ((phase - 0.3f) / 0.25f)).coerceIn(0f, 1f)
        val goodAlpha = ((phase - 0.5f) / 0.25f).coerceIn(0f, 1f)

        // Bad connections (original crossing path)
        if (badAlpha > 0f) {
            listOf(0 to 3, 1 to 4, 2 to 5).forEach { (a, b) ->
                drawLine(redColor.copy(alpha = badAlpha), nodes[a], nodes[b], strokeWidth = 2.dp.toPx())
            }
        }
        // Good connections (double-bridge re-wired)
        if (goodAlpha > 0f) {
            listOf(0 to 1, 1 to 2, 3 to 4, 4 to 5, 0 to 5, 2 to 3).forEach { (a, b) ->
                drawLine(yellowColor.copy(alpha = goodAlpha), nodes[a], nodes[b], strokeWidth = 2.5f.dp.toPx())
            }
        }

        nodes.forEach { pos ->
            drawCircle(Color.White, radius = 7.dp.toPx(), center = pos)
            drawCircle(errorColor, radius = 5.dp.toPx(), center = pos)
        }
    }
}

/** Simulated Annealing: cool-down gradient + bouncing ball */
@Composable
private fun SAAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "sa")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height

        // Background gradient from hot (red) to cool (blue) as phase progresses
        val hotColor = Color(0xFFEF4444)
        val coldColor = Color(0xFF3B82F6)
        val bgColor = lerp(hotColor, coldColor, phase).copy(alpha = 0.3f)
        drawRect(bgColor, size = size)

        // Ball moves less erratically as temperature cools
        val amplitude = h * 0.35f * (1f - phase * 0.8f)
        val ballY = h * 0.5f + amplitude * sin(phase * 20f).toFloat()
        val ballColor = lerp(hotColor, coldColor, phase)

        drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(w / 2f, ballY))
        drawCircle(ballColor, radius = 8.dp.toPx(), center = Offset(w / 2f, ballY))

        // Temperature label area (just a subtle bar)
        val barWidth = w * 0.6f
        val barLeft = (w - barWidth) / 2f
        val barTop = h * 0.88f
        drawRect(
            Color.White.copy(alpha = 0.15f),
            topLeft = Offset(barLeft, barTop),
            size = androidx.compose.ui.geometry.Size(barWidth, 6.dp.toPx())
        )
        drawRect(
            ballColor.copy(alpha = 0.8f),
            topLeft = Offset(barLeft, barTop),
            size = androidx.compose.ui.geometry.Size(barWidth * (1f - phase), 6.dp.toPx())
        )
    }
}

/** Genetic Algorithm: two colored bars with crossover animation */
@Composable
private fun GAAnimation() {
    val blueColor = Color(0xFF3B82F6)
    val purpleColor = Color(0xFFC084FC)

    val infiniteTransition = rememberInfiniteTransition(label = "ga")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height
        val barH = 14.dp.toPx()
        val barW = w * 0.75f
        val startX = (w - barW) / 2f

        val crossPt = barW * ((phase * 0.6f + 0.2f).coerceIn(0.1f, 0.9f))

        // Parent 1 (top): blue | purple
        val p1y = h * 0.28f
        drawRect(blueColor.copy(alpha = 0.9f), topLeft = Offset(startX, p1y), size = androidx.compose.ui.geometry.Size(crossPt, barH))
        drawRect(purpleColor.copy(alpha = 0.5f), topLeft = Offset(startX + crossPt, p1y), size = androidx.compose.ui.geometry.Size(barW - crossPt, barH))

        // Parent 2 (middle): purple | blue
        val p2y = h * 0.50f
        drawRect(purpleColor.copy(alpha = 0.9f), topLeft = Offset(startX, p2y), size = androidx.compose.ui.geometry.Size(crossPt, barH))
        drawRect(blueColor.copy(alpha = 0.5f), topLeft = Offset(startX + crossPt, p2y), size = androidx.compose.ui.geometry.Size(barW - crossPt, barH))

        // Child (bottom): blue | blue (best from both)
        val childAlpha = ((phase - 0.5f) / 0.3f).coerceIn(0f, 1f)
        if (childAlpha > 0f) {
            val cy = h * 0.75f
            drawRect(blueColor.copy(alpha = childAlpha), topLeft = Offset(startX, cy), size = androidx.compose.ui.geometry.Size(crossPt, barH))
            drawRect(blueColor.copy(alpha = childAlpha * 0.75f), topLeft = Offset(startX + crossPt, cy), size = androidx.compose.ui.geometry.Size(barW - crossPt, barH))
        }

        // Crossover marker line
        drawLine(
            Color.White.copy(alpha = 0.7f),
            start = Offset(startX + crossPt, h * 0.20f),
            end = Offset(startX + crossPt, h * 0.65f),
            strokeWidth = 2.dp.toPx()
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun lerp(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red   = a.red   + (b.red   - a.red)   * t,
    green = a.green + (b.green - a.green) * t,
    blue  = a.blue  + (b.blue  - a.blue)  * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

private fun DrawScope.cos(angleRad: Double) = kotlin.math.cos(angleRad)
private fun DrawScope.sin(angleRad: Double) = kotlin.math.sin(angleRad)
