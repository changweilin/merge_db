package com.mergedb.app.db

import kotlin.math.*
import kotlin.random.Random

// ── Data classes ─────────────────────────────────────────────────────────────

data class LatLon(val lat: Double, val lon: Double)

enum class TspStrategy(val label: String) {
    NEAREST_NEIGHBOR("最近鄰居法 (Nearest Neighbor)"),
    GREEDY("貪婪演算法 (Greedy)"),
    INSERTION("插入法 (Insertion)")
}

enum class TspOptimizer(val label: String) {
    NONE("不優化"),
    OPT_2("2-Opt"),
    LK("Lin-Kernighan"),
    SA("模擬退火 (SA)"),
    GA("遺傳演算法 (GA)")
}

data class TspConfig(
    val strategy: TspStrategy = TspStrategy.NEAREST_NEIGHBOR,
    val optimizer: TspOptimizer = TspOptimizer.OPT_2,
    val skipLargeThreshold: Int = 256,
    val improvementThreshold: Double = 0.0,
    val timeoutMs: Long = 60_000L,
    /** Skip any segment whose maximum consecutive-point jump exceeds this threshold (km).
     *  Catches mis-segmented routes whose coordinate range spans multiple geographic regions. */
    val maxConsecutiveJumpKm: Double = 500.0
)

data class TspRouteResult(
    val index: Int,
    val originalLength: Double,
    val optimizedLength: Double,
    val improved: Boolean,
    val skipped: Boolean,
    val reason: String = ""
)

data class TspResult(
    val reorderedLats: List<Double>,
    val reorderedLons: List<Double>,
    val routeResults: List<TspRouteResult>,
    val totalRoutes: Int,
    val improvedRoutes: Int,
    val skippedRoutes: Int
)

// ── Core TSP object ───────────────────────────────────────────────────────────

object TspEngine {

    // ── Distance ─────────────────────────────────────────────────────────────

    fun haversine(a: LatLon, b: LatLon): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val h = sinLat * sinLat +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sinLon * sinLon
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun buildDistMatrix(points: List<LatLon>): Array<DoubleArray> {
        val n = points.size
        return Array(n) { i -> DoubleArray(n) { j -> haversine(points[i], points[j]) } }
    }

    fun tourLength(points: List<LatLon>, order: IntArray, dist: Array<DoubleArray>): Double {
        val n = order.size
        if (n < 2) return 0.0
        var len = 0.0
        for (i in 0 until n) {
            len += dist[order[i]][order[(i + 1) % n]]
        }
        return len
    }

    // ── Base strategies ───────────────────────────────────────────────────────

    /** Nearest-neighbour construction — O(n²). */
    fun nearestNeighbor(points: List<LatLon>, dist: Array<DoubleArray>): IntArray {
        val n = points.size
        val visited = BooleanArray(n)
        val tour = IntArray(n)
        tour[0] = 0
        visited[0] = true
        for (i in 1 until n) {
            val current = tour[i - 1]
            var nearest = -1
            var minD = Double.MAX_VALUE
            for (j in 0 until n) {
                if (!visited[j] && dist[current][j] < minD) {
                    minD = dist[current][j]
                    nearest = j
                }
            }
            tour[i] = nearest
            visited[nearest] = true
        }
        return tour
    }

    /** Greedy edge-based construction — O(n² log n). */
    fun greedy(points: List<LatLon>, dist: Array<DoubleArray>): IntArray {
        val n = points.size
        // Build all edges sorted by distance
        val edges = ArrayList<Triple<Double, Int, Int>>(n * (n - 1) / 2)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                edges.add(Triple(dist[i][j], i, j))
            }
        }
        edges.sortBy { it.first }

        val degree = IntArray(n)
        val adj = Array(n) { mutableListOf<Int>() }

        fun createsCycle(u: Int, v: Int): Boolean {
            // DFS from u to v without using the would-be new edge
            val visited = BooleanArray(n)
            val stack = ArrayDeque<Int>()
            stack.addLast(u)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (cur == v) return true
                if (visited[cur]) continue
                visited[cur] = true
                for (nb in adj[cur]) stack.addLast(nb)
            }
            return false
        }

        var edgeCount = 0
        for ((_, u, v) in edges) {
            if (edgeCount == n) break
            if (degree[u] >= 2 || degree[v] >= 2) continue
            if (edgeCount < n - 1 && createsCycle(u, v)) continue
            adj[u].add(v); adj[v].add(u)
            degree[u]++; degree[v]++
            edgeCount++
        }

        // Build tour from adjacency
        val tour = IntArray(n)
        val visited = BooleanArray(n)
        // Find a starting endpoint (degree 1) or just start at 0
        var start = (0 until n).firstOrNull { degree[it] == 1 } ?: 0
        tour[0] = start; visited[start] = true
        for (i in 1 until n) {
            val prev = tour[i - 1]
            val next = adj[prev].firstOrNull { !visited[it] }
            if (next == null) {
                // Fill remaining unvisited in order (fallback for disconnected graph)
                var filled = i
                for (j in 0 until n) {
                    if (!visited[j]) { tour[filled++] = j; if (filled >= n) break }
                }
                break
            }
            tour[i] = next; visited[next] = true
        }
        return tour
    }

    /** Cheapest insertion construction — O(n²). */
    fun insertion(points: List<LatLon>, dist: Array<DoubleArray>): IntArray {
        val n = points.size
        val inTour = BooleanArray(n)
        val tourList = mutableListOf(0, 1)
        inTour[0] = true; inTour[1] = true

        for (k in 2 until n) {
            // Find cheapest insertion position for k
            var bestCost = Double.MAX_VALUE
            var bestPos = 0
            for (pos in 0 until tourList.size) {
                val i = tourList[pos]
                val j = tourList[(pos + 1) % tourList.size]
                val cost = dist[i][k] + dist[k][j] - dist[i][j]
                if (cost < bestCost) { bestCost = cost; bestPos = pos + 1 }
            }
            tourList.add(bestPos, k)
            inTour[k] = true
        }
        return tourList.toIntArray()
    }

    // ── Optimizers ────────────────────────────────────────────────────────────

    /** 2-Opt edge swap — O(n²) per pass. */
    fun opt2(
        points: List<LatLon>,
        tour: IntArray,
        dist: Array<DoubleArray>,
        timeoutMs: Long = 60_000L,
        isCancelled: () -> Boolean = { false }
    ): IntArray {
        val n = tour.size
        if (n < 4) return tour
        val t = tour.copyOf()
        val deadline = System.currentTimeMillis() + timeoutMs
        var improved = true
        var iter = 0
        val maxIter = n * 100

        while (improved && iter++ < maxIter) {
            if (System.currentTimeMillis() > deadline || isCancelled()) break
            improved = false
            for (i in 0 until n - 1) {
                for (j in i + 2 until n) {
                    if (j == n - 1 && i == 0) continue
                    val delta = dist[t[i]][t[j]] + dist[t[i + 1]][t[(j + 1) % n]] -
                            dist[t[i]][t[i + 1]] - dist[t[j]][t[(j + 1) % n]]
                    if (delta < -1e-5) {
                        // Reverse segment t[i+1..j]
                        var l = i + 1; var r = j
                        while (l < r) { val tmp = t[l]; t[l] = t[r]; t[r] = tmp; l++; r-- }
                        improved = true
                    }
                }
            }
        }
        return t
    }

    /** Double-bridge 4-opt perturbation for Lin-Kernighan. */
    private fun doubleBridge(tour: IntArray, rng: Random): IntArray {
        val n = tour.size
        if (n < 8) {
            // Swap two random positions
            val t = tour.copyOf()
            val a = rng.nextInt(n); val b = rng.nextInt(n)
            val tmp = t[a]; t[a] = t[b]; t[b] = tmp
            return t
        }
        val p1 = 1 + rng.nextInt(n / 4)
        val p2 = p1 + 1 + rng.nextInt(n / 4)
        val p3 = p2 + 1 + rng.nextInt(n / 4)
        val a = tour.slice(0 until p1)
        val b = tour.slice(p1 until p2)
        val c = tour.slice(p2 until p3)
        val d = tour.slice(p3 until n)
        return (a + c + b + d).toIntArray()
    }

    /** Lin-Kernighan with 50 Double-Bridge perturbations. */
    fun linKernighan(
        points: List<LatLon>,
        tour: IntArray,
        dist: Array<DoubleArray>,
        timeoutMs: Long = 180_000L,
        isCancelled: () -> Boolean = { false }
    ): IntArray {
        val rng = Random(42)
        var best = opt2(points, tour, dist, timeoutMs / 51, isCancelled)
        var bestLen = tourLength(points, best, dist)

        for (i in 0 until 50) {
            if (isCancelled()) break
            val perturbed = doubleBridge(best, rng)
            val candidate = opt2(points, perturbed, dist, timeoutMs / 51, isCancelled)
            val candidateLen = tourLength(points, candidate, dist)
            if (candidateLen < bestLen) {
                best = candidate; bestLen = candidateLen
            }
        }
        return best
    }

    /** Simulated annealing with geometric cooling. */
    fun simulatedAnnealing(
        points: List<LatLon>,
        tour: IntArray,
        dist: Array<DoubleArray>,
        isCancelled: () -> Boolean = { false }
    ): IntArray {
        val n = tour.size
        if (n < 4) return tour

        val rng = Random(42)
        var current = tour.copyOf()
        var currentLen = tourLength(points, current, dist)
        var best = current.copyOf()
        var bestLen = currentLen

        val T0 = 10_000.0
        val coolingRate = 0.995
        val Tmin = 0.001
        val itersPerTemp = minOf(n * 2, 100)

        var T = T0
        while (T > Tmin) {
            if (isCancelled()) break
            repeat(itersPerTemp) {
                val i = 1 + rng.nextInt(n - 1)
                val j = 1 + rng.nextInt(n - 1)
                // Swap move
                val tmp = current[i]; current[i] = current[j]; current[j] = tmp
                val newLen = tourLength(points, current, dist)
                val delta = newLen - currentLen
                if (delta < 0 || rng.nextDouble() < exp(-delta / T)) {
                    currentLen = newLen
                    if (newLen < bestLen) { best = current.copyOf(); bestLen = newLen }
                } else {
                    // Revert
                    val t2 = current[i]; current[i] = current[j]; current[j] = t2
                }
            }
            T *= coolingRate
        }
        return best
    }

    /** Genetic algorithm with OX crossover and swap mutation. */
    fun geneticAlgorithm(
        points: List<LatLon>,
        tour: IntArray,
        dist: Array<DoubleArray>,
        timeoutMs: Long = 300_000L,
        isCancelled: () -> Boolean = { false }
    ): IntArray {
        val n = tour.size
        if (n < 4) return tour

        val rng = Random(42)
        val popSize = maxOf(50, n * 2)
        val generations = 200
        val mutationRate = 0.1

        // Initialise population: seed with given tour, rest are random permutations
        val population = Array(popSize) {
            if (it == 0) tour.copyOf()
            else {
                val t = tour.copyOf()
                for (i in n - 1 downTo 1) {
                    val j = rng.nextInt(i + 1)
                    val tmp = t[i]; t[i] = t[j]; t[j] = tmp
                }
                t
            }
        }

        fun fitness(t: IntArray) = -tourLength(points, t, dist) // higher = better

        // OX crossover
        fun crossover(p1: IntArray, p2: IntArray): IntArray {
            val child = IntArray(n) { -1 }
            val s = rng.nextInt(n); val e = rng.nextInt(n)
            val from = minOf(s, e); val to = maxOf(s, e)
            for (i in from..to) child[i] = p1[i]
            val present = child.filter { it >= 0 }.toHashSet()
            var pos = (to + 1) % n
            for (v in p2) {
                if (v !in present) {
                    child[pos] = v; present.add(v)
                    pos = (pos + 1) % n
                }
            }
            return child
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var scored = population.sortedByDescending { fitness(it) }.toMutableList()

        for (gen in 0 until generations) {
            if (System.currentTimeMillis() > deadline || isCancelled()) break
            val next = mutableListOf(scored[0].copyOf(), scored[1].copyOf()) // elitism
            while (next.size < popSize) {
                // Tournament selection with cubic bias
                val p1 = scored[(rng.nextDouble().pow(3) * popSize).toInt().coerceAtMost(popSize - 1)]
                val p2 = scored[(rng.nextDouble().pow(3) * popSize).toInt().coerceAtMost(popSize - 1)]
                var child = crossover(p1, p2)
                if (rng.nextDouble() < mutationRate) {
                    val i = rng.nextInt(n); val j = rng.nextInt(n)
                    val tmp = child[i]; child[i] = child[j]; child[j] = tmp
                }
                next.add(child)
            }
            scored = next.sortedByDescending { fitness(it) }.toMutableList()
        }
        return scored[0]
    }

    // ── Master solve ──────────────────────────────────────────────────────────

    fun solve(
        points: List<LatLon>,
        config: TspConfig,
        isCancelled: () -> Boolean = { false }
    ): IntArray {
        if (points.size <= 2) return IntArray(points.size) { it }
        val dist = buildDistMatrix(points)

        val base = when (config.strategy) {
            TspStrategy.NEAREST_NEIGHBOR -> nearestNeighbor(points, dist)
            TspStrategy.GREEDY           -> greedy(points, dist)
            TspStrategy.INSERTION        -> insertion(points, dist)
        }

        return when (config.optimizer) {
            TspOptimizer.NONE -> base
            TspOptimizer.OPT_2 -> opt2(points, base, dist, config.timeoutMs, isCancelled)
            TspOptimizer.LK    -> linKernighan(points, base, dist, config.timeoutMs, isCancelled)
            TspOptimizer.SA    -> simulatedAnnealing(points, base, dist, isCancelled)
            TspOptimizer.GA    -> geneticAlgorithm(points, base, dist, config.timeoutMs, isCancelled)
        }
    }

    // ── Segment diagnostics ───────────────────────────────────────────────────

    /** Maximum haversine distance (metres) between consecutive points in [points]. */
    fun maxConsecutiveJump(points: List<LatLon>): Double {
        if (points.size < 2) return 0.0
        var maxD = 0.0
        for (i in 0 until points.size - 1) {
            val d = haversine(points[i], points[i + 1])
            if (d > maxD) maxD = d
        }
        return maxD
    }

    // ── DB-level optimisation ─────────────────────────────────────────────────

    /**
     * Parse [data], split coordinates into route segments, apply TSP to each
     * segment per [config], and return the reordered coordinate lists.
     *
     * [onProgress] is called with (finishedRoutes, totalRoutes) after each segment.
     */
    fun optimizeDb(
        data: ByteArray,
        config: TspConfig,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): TspResult {
        val bTreeInfo = RealmBinaryParser.findCoordinateBTreeInfo(data)
            ?: error("無法解析 B-Tree 結構")

        fun readFinite(leaves: List<RealmNode.Float64Leaf>): List<Double> = buildList {
            for (leaf in leaves) {
                for (v in RealmBinaryParser.readFloat64Values(data, leaf)) {
                    if (v.isFinite()) add(v)
                }
            }
        }

        val lats = readFinite(bTreeInfo.latLeaves)
        val lons = readFinite(bTreeInfo.lonLeaves)
        val pairs = minOf(lats.size, lons.size)

        // findRouteSegments: strategy 1 = route-ID column (distinct grouping), strategy 2 = gap fallback.
        // Each IntRange covers positions [first..last] inclusive in the flat coordinate array.
        // Positions not covered by any range (gap sentinel points) are left untouched.
        val ranges = RealmBinaryParser.findRouteSegments(data)
            ?: error("無法分析路線結構")
        val totalRoutes = ranges.size

        // Work on mutable copies; untouched positions (gap points) stay as-is.
        val outLat = lats.take(pairs).toMutableList()
        val outLon = lons.take(pairs).toMutableList()
        val routeResults = mutableListOf<TspRouteResult>()
        var improvedCount = 0
        var skippedCount = 0

        for ((idx, range) in ranges.withIndex()) {
            val n = range.last - range.first + 1   // range is start until end (exclusive)

            if (n <= 2) {
                routeResults.add(TspRouteResult(idx, 0.0, 0.0, false, false, "點數 ≤ 2，略過"))
                onProgress(idx + 1, totalRoutes)
                continue
            }

            if (n > config.skipLargeThreshold) {
                routeResults.add(TspRouteResult(idx, 0.0, 0.0, false, true,
                    "超過 ${config.skipLargeThreshold} 點上限"))
                skippedCount++
                onProgress(idx + 1, totalRoutes)
                continue
            }

            val seg = (range).map { LatLon(lats[it], lons[it]) }

            // Detect mis-segmented routes: if any two consecutive points are more than
            // maxConsecutiveJumpKm apart, this segment likely spans unrelated geographic
            // regions (interleaved route-ID data) and should not be TSP-optimised.
            if (config.maxConsecutiveJumpKm > 0) {
                val jumpM = maxConsecutiveJump(seg)
                val jumpKm = jumpM / 1000.0
                if (jumpKm > config.maxConsecutiveJumpKm) {
                    routeResults.add(TspRouteResult(idx, 0.0, 0.0, false, true,
                        "相鄰跳躍 ${"%.0f".format(jumpKm)} km 超過門檻 ${"%.0f".format(config.maxConsecutiveJumpKm)} km"))
                    skippedCount++
                    onProgress(idx + 1, totalRoutes)
                    continue
                }
            }
            val dist = buildDistMatrix(seg)
            val identity = IntArray(n) { it }
            val origLen = tourLength(seg, identity, dist)
            val order = solve(seg, config, isCancelled)
            val newLen = tourLength(seg, order, dist)

            val improvement = if (origLen > 0) (origLen - newLen) / origLen * 100 else 0.0
            val apply = improvement >= config.improvementThreshold

            if (apply) {
                // Write reordered coordinates back into the same positions in-place
                order.forEachIndexed { i, srcIdx ->
                    outLat[range.first + i] = seg[srcIdx].lat
                    outLon[range.first + i] = seg[srcIdx].lon
                }
            }

            val actuallyImproved = apply && newLen < origLen
            val reason = when {
                !apply -> "改善幅度 %.1f%% < 門檻 %.1f%%".format(improvement, config.improvementThreshold)
                newLen >= origLen -> "優化後路徑未縮短 (%.1f%%)".format(improvement)
                else -> ""
            }
            routeResults.add(TspRouteResult(
                index = idx,
                originalLength = origLen,
                optimizedLength = newLen,
                improved = actuallyImproved,
                skipped = false,
                reason = reason
            ))
            if (actuallyImproved) improvedCount++
            onProgress(idx + 1, totalRoutes)
        }

        return TspResult(
            reorderedLats = outLat,
            reorderedLons = outLon,
            routeResults = routeResults,
            totalRoutes = totalRoutes,
            improvedRoutes = improvedCount,
            skippedRoutes = skippedCount
        )
    }
}
