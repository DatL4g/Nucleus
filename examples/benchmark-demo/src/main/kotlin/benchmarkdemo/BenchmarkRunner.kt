package benchmarkdemo

import java.io.File

/*
 * Measurement protocol. Warmup runs are discarded (lets the JIT reach steady state so the
 * JVM-vs-AOT comparison is fair), then we take the best-of-N wall time = peak throughput.
 * The warmup is NOT optional — dropping it would silently penalise the JVM.
 */

const val WARMUP_RUNS = 3
const val MEASURE_RUNS = 5

// UI bench spec constants — shared across ports (see BENCHMARK-SPEC.md).
// Render ramps: grow the workload each 1s window while avg fps ≥ RAMP_MIN_FPS, up to the max.
// Metric = largest sustained count — shows where the CPU/render pipeline actually collapses.
const val RAMP_WINDOW_S = 1.0
const val RAMP_MIN_FPS = 55.0

// Ramp 1: particles (points pipeline).
const val RAMP_START = 25_000
const val RAMP_STEP = 25_000
const val RAMP_MAX = 500_000

// Ramp 2: rotating 12-segment stars, 50% alpha (vector path tessellation + blending).
const val STAR_START = 200
const val STAR_STEP = 200
const val STAR_MAX = 200_000

// Ramp 3: drifting text labels, 100 distinct strings (glyph/text pipeline).
const val TEXT_START = 500
const val TEXT_STEP = 500
const val TEXT_MAX = 200_000

const val LIST_ROWS = 50_000

val CORES: Int = Runtime.getRuntime().availableProcessors()

data class BenchResult(
    val name: String,
    val threads: Int,
    val unit: String,
    val workUnits: Long,
    val bestSeconds: Double,
) {
    /** Throughput in millions of work-units per second (higher = faster). */
    val throughputM: Double get() = workUnits / bestSeconds / 1_000_000.0
}

/** Black-hole sink: consuming the kernel checksum here stops GraalVM -O3 from deleting the kernel. */
@Volatile
private var blackHole = 0.0

/**
 * Runs one kernel: [WARMUP_RUNS] discarded, then best (min) of [MEASURE_RUNS]. [block] returns a
 * checksum (accumulated into [blackHole] so the compiler can't prove the work dead).
 */
fun measureKernel(
    name: String,
    threads: Int,
    unit: String,
    workUnits: Long,
    block: () -> Double,
): BenchResult {
    var sink = 0.0
    repeat(WARMUP_RUNS) { sink += block() }
    var best = Double.MAX_VALUE
    repeat(MEASURE_RUNS) {
        val t0 = System.nanoTime()
        sink += block()
        val secs = (System.nanoTime() - t0) / 1e9
        if (secs < best) best = secs
    }
    blackHole = sink
    return BenchResult(name, threads, unit, workUnits, best)
}

/** The 13-bench CPU suite (9 single-thread + 4 multi-thread). [onResult] fires after each bench. */
fun runCpuSuite(onResult: (BenchResult) -> Unit = {}): List<BenchResult> {
    val results = ArrayList<BenchResult>(13)

    fun emit(r: BenchResult) {
        results.add(r)
        onResult(r)
    }

    // Buffers + deterministic inputs (fills are outside timing; see spec for what each run includes).
    val mandel = IntArray(Mandelbrot.WIDTH * Mandelbrot.HEIGHT)
    val nbody = NBody.State()
    val ray = IntArray(RayTracer.WIDTH * RayTracer.HEIGHT)
    val a = DoubleArray(MatMul.N * MatMul.N)
    val b = DoubleArray(MatMul.N * MatMul.N)
    val c = DoubleArray(MatMul.N * MatMul.N)
    MatMul.fill(a, b)
    val img = DoubleArray(Blur.WIDTH * Blur.HEIGHT)
    val tmp = DoubleArray(Blur.WIDTH * Blur.HEIGHT)
    Blur.reset(img)
    val sha = ByteArray(Sha256.BYTES)
    Sha256.fill(sha)
    val sieveBuf = ByteArray(Sieve.LIMIT + 1)
    val re = DoubleArray(Fft.N)
    val im = DoubleArray(Fft.N)

    val mandelWork = Mandelbrot.WIDTH.toLong() * Mandelbrot.HEIGHT
    val nbodyWork = NBody.N.toLong() * (NBody.N - 1) * NBody.STEPS
    val rayWork = RayTracer.WIDTH.toLong() * RayTracer.HEIGHT
    val matmulWork = 2L * MatMul.N * MatMul.N * MatMul.N
    val blurWork = Blur.WIDTH.toLong() * Blur.HEIGHT
    val shaWork = Sha256.BYTES.toLong()
    val sieveWork = Sieve.LIMIT.toLong()
    val fftWork = (Fft.N / 2).toLong() * Fft.LOG_N

    // Single-thread.
    emit(measureKernel("mandelbrot", 1, "Mpix/s", mandelWork) { Mandelbrot.render(mandel) })
    emit(
        measureKernel("nbody", 1, "M-inter/s", nbodyWork) {
            nbody.reset()
            NBody.simulate(nbody)
        },
    )
    emit(measureKernel("raytracer", 1, "Mrays/s", rayWork) { RayTracer.render(ray) })
    emit(measureKernel("matmul", 1, "MFLOP/s", matmulWork) { MatMul.multiply(a, b, c) })
    emit(measureKernel("blur", 1, "Mpix/s", blurWork) { Blur.convolve(img, tmp) })
    emit(
        measureKernel("sha256", 1, "MB/s", shaWork) {
            // Mutate one input byte per run: digest() is a pure function of an otherwise-constant
            // buffer, and LLVM-class AOT compilers hoist it out of the measurement loop (observed
            // in the Rust port: 0.000s). Identical cost, defeats memoization in every port.
            sha[0]++
            Sha256.digest(sha)
        },
    )
    emit(measureKernel("sieve", 1, "Mn/s", sieveWork) { Sieve.count(sieveBuf) })
    emit(
        measureKernel("fft", 1, "Mbf/s", fftWork) {
            Fft.reset(re, im)
            Fft.transform(re, im)
        },
    )
    emit(measureKernel("pi", 1, "Mdig/s", Pi.DIGITS.toLong()) { Pi.compute() })

    // Multi-thread (all cores, row-parallel).
    emit(measureKernel("mandelbrot_mt", CORES, "Mpix/s", mandelWork) { Mandelbrot.renderMt(mandel, CORES) })
    emit(measureKernel("raytracer_mt", CORES, "Mrays/s", rayWork) { RayTracer.renderMt(ray, CORES) })
    emit(measureKernel("matmul_mt", CORES, "MFLOP/s", matmulWork) { MatMul.multiplyMt(a, b, c, CORES) })
    emit(measureKernel("blur_mt", CORES, "Mpix/s", blurWork) { Blur.convolveMt(img, tmp, CORES) })

    return results
}

/** Composite = geometric mean of per-bench throughputs. Higher = faster runtime. */
fun compositeScore(cpu: List<BenchResult>): Double = Math.exp(cpu.map { Math.log(it.throughputM) }.average())

/**
 * Graphics score, Geekbench-style anchor: 1000 × geometric mean of each ramp's sustained count
 * relative to its START count. A machine that only sustains the starting workloads scores 1000.
 */
fun graphicsScore(
    particles: Int,
    stars: Int,
    texts: Int,
): Double =
    1000.0 *
        Math.cbrt(
            (maxOf(1, particles).toDouble() / RAMP_START) *
                (maxOf(1, stars).toDouble() / STAR_START) *
                (maxOf(1, texts).toDouble() / TEXT_START),
        )

private fun isNativeImage() = System.getProperty("org.graalvm.nativeimage.imagecode") != null

fun runtimeId(): String = if (isNativeImage()) "graalvm" else "jvm"

fun runtimeLabel(): String =
    if (isNativeImage()) {
        "GraalVM Native Image (AOT · -O3)"
    } else {
        "JVM JIT · ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
    }

fun osLabel(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}"

/**
 * Writes the results JSON to ~/nucleus-benchmarks/<runtimeId>.json and returns the path.
 * Hand-rolled serialization: every value is a number or known-safe ASCII, no escaping needed.
 * Double.toString is locale-independent (always a dot) — never use String.format here.
 */
fun writeResultsJson(
    cpu: List<BenchResult>,
    composite: Double,
    maxParticles: Int?,
    maxStars: Int? = null,
    maxTexts: Int? = null,
    listLoadMs: Double?,
): String {
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"schema\": 1,\n")
    sb.append("  \"runtime\": \"${runtimeId()}\",\n")
    sb.append("  \"runtimeLabel\": \"${runtimeLabel()}\",\n")
    sb.append("  \"os\": \"${osLabel()}\",\n")
    sb.append("  \"cpus\": $CORES,\n")
    sb.append("  \"timestampMs\": ${System.currentTimeMillis()},\n")
    sb.append("  \"cpu\": [\n")
    cpu.forEachIndexed { i, r ->
        sb.append(
            "    {\"name\": \"${r.name}\", \"threads\": ${r.threads}, \"unit\": \"${r.unit}\", " +
                "\"workUnits\": ${r.workUnits}, \"bestSeconds\": ${r.bestSeconds}, " +
                "\"throughputM\": ${r.throughputM}}",
        )
        sb.append(if (i < cpu.size - 1) ",\n" else "\n")
    }
    sb.append("  ],\n")
    sb.append("  \"compositeCpuScore\": $composite,\n")
    if (maxParticles != null && maxStars != null && maxTexts != null) {
        sb.append("  \"compositeGraphicsScore\": ${graphicsScore(maxParticles, maxStars, maxTexts)},\n")
    }
    sb.append("  \"ui\": [")
    val ui = ArrayList<String>(4)
    if (maxParticles != null) {
        ui.add(
            "\n    {\"name\": \"max_particles_55fps\", \"unit\": \"particles\", " +
                "\"value\": $maxParticles, \"lowerIsBetter\": false}",
        )
    }
    if (maxStars != null) {
        ui.add(
            "\n    {\"name\": \"max_stars_55fps\", \"unit\": \"stars\", " +
                "\"value\": $maxStars, \"lowerIsBetter\": false}",
        )
    }
    if (maxTexts != null) {
        ui.add(
            "\n    {\"name\": \"max_texts_55fps\", \"unit\": \"texts\", " +
                "\"value\": $maxTexts, \"lowerIsBetter\": false}",
        )
    }
    if (listLoadMs != null) {
        ui.add("\n    {\"name\": \"list_load\", \"unit\": \"ms\", \"value\": $listLoadMs, \"lowerIsBetter\": true}")
    }
    sb.append(ui.joinToString(","))
    sb.append(if (ui.isEmpty()) "]\n" else "\n  ]\n")
    sb.append("}\n")

    val dir = File(System.getProperty("user.home"), "nucleus-benchmarks")
    dir.mkdirs()
    val file = File(dir, "${runtimeId()}.json")
    file.writeText(sb.toString())
    return file.absolutePath
}

/** Rotating-star field for the vector ramp — LCG seed 7: x, y ∈ [0,1), ω ∈ [-2,2) rad/s. */
class Stars(
    val capacity: Int,
) {
    var active = STAR_START
    var t = 0.0 // seconds since ramp start — θ_i = ω_i · t
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val omega = FloatArray(capacity)

    fun reset() {
        active = STAR_START
        t = 0.0
        val rng = Lcg(7uL)
        for (i in 0 until capacity) {
            x[i] = rng.nextDouble().toFloat()
            y[i] = rng.nextDouble().toFloat()
            omega[i] = rng.range(-2.0, 2.0).toFloat()
        }
    }
}

/** Drifting text field for the glyph ramp — LCG seed 11; label i is "Bench#${i % 100}". */
class Texts(
    val capacity: Int,
) {
    var active = TEXT_START
    var t = 0.0 // seconds — y_i = (y0_i + 0.03·t) mod 1
    val x = FloatArray(capacity)
    val y0 = FloatArray(capacity)

    fun reset() {
        active = TEXT_START
        t = 0.0
        val rng = Lcg(11uL)
        for (i in 0 until capacity) {
            x[i] = rng.nextDouble().toFloat()
            y0[i] = rng.nextDouble().toFloat()
        }
    }
}

/**
 * Particle field for the ramp bench — spec-fixed init (LCG seed 42, full capacity filled once)
 * and wall-bounce physics. [active] is the currently simulated/drawn count, grown by the ramp.
 */
class Particles(
    val capacity: Int,
) {
    var active = RAMP_START
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)

    fun reset() {
        active = RAMP_START
        val rng = Lcg(42uL)
        for (i in 0 until capacity) {
            x[i] = rng.nextDouble().toFloat()
            y[i] = rng.nextDouble().toFloat()
            vx[i] = rng.range(-0.2, 0.2).toFloat()
            vy[i] = rng.range(-0.2, 0.2).toFloat()
        }
    }

    fun step(dt: Float) {
        val n = active
        for (i in 0 until n) {
            var nx = x[i] + vx[i] * dt
            var ny = y[i] + vy[i] * dt
            if (nx < 0f) {
                nx = 0f
                vx[i] = -vx[i]
            } else if (nx > 1f) {
                nx = 1f
                vx[i] = -vx[i]
            }
            if (ny < 0f) {
                ny = 0f
                vy[i] = -vy[i]
            } else if (ny > 1f) {
                ny = 1f
                vy[i] = -vy[i]
            }
            x[i] = nx
            y[i] = ny
        }
    }
}
