package benchmarkdemo

/*
 * The benchmark kernels — pure arithmetic, zero external dependencies, deterministic.
 * Geekbench-style spread: fractal, physics, ray tracing, SGEMM, image filter, crypto,
 * memory/integer, FFT — each single-threaded, plus row-parallel MT variants.
 *
 * Every parameter here is a fairness contract: the SwiftUI and Tauri ports MUST use the exact
 * same constants (see BENCHMARK-SPEC.md) so the work performed is identical across runtimes and
 * the numbers are comparable. Anything stdlib-provided (sort, hashing, JSON) is deliberately
 * excluded — it would benchmark the language's library, not the compiler.
 */

/**
 * Strided sum over a pixel buffer — a cheap sink so the render (compute + stores) can't be
 * optimised away as dead code by an aggressive AOT compiler (GraalVM -O3 / rustc LTO delete the
 * whole kernel otherwise → a bogus 0ms). Kept identical in every port (see BENCHMARK-SPEC.md).
 */
internal fun checksum(buf: IntArray): Double {
    var cs = 0L
    var k = 0
    while (k < buf.size) {
        cs += buf[k].toLong() and 0xFFFFFFFFL
        k += 101
    }
    return cs.toDouble()
}

/** Same anti-DCE sink for double buffers. */
internal fun checksumD(buf: DoubleArray): Double {
    var cs = 0.0
    var k = 0
    while (k < buf.size) {
        cs += buf[k]
        k += 101
    }
    return cs
}

/**
 * Fork-join helper for the MT kernels: splits [total] rows into [threads] contiguous chunks,
 * one plain Thread each, join all. Single parallel region per run so thread-spawn overhead is
 * amortized identically across ports (Swift uses its shared pool, Rust scoped threads).
 */
internal inline fun parallelChunks(
    total: Int,
    threads: Int,
    crossinline body: (Int, Int) -> Unit,
) {
    val per = (total + threads - 1) / threads
    val pool = ArrayList<Thread>(threads)
    for (t in 0 until threads) {
        val s = t * per
        val e = minOf(total, s + per)
        if (s >= e) break
        pool.add(Thread { body(s, e) }.apply { start() })
    }
    for (th in pool) th.join()
}

// ---------------------------------------------------------------------------
// Shared deterministic RNG — MMIX LCG (Knuth). Identical output in Kotlin/Swift/Rust.
// ---------------------------------------------------------------------------

/** 64-bit LCG so all ports generate byte-identical initial state. */
class Lcg(
    private var state: ULong,
) {
    fun nextDouble(): Double {
        // Advance, then take the top 53 bits as a double in [0, 1).
        state = state * 6364136223846793005uL + 1442695040888963407uL
        return (state shr 11).toDouble() * (1.0 / 9007199254740992.0)
    }

    /** Uniform in [min, max). */
    fun range(
        min: Double,
        max: Double,
    ): Double = min + (max - min) * nextDouble()
}

// ---------------------------------------------------------------------------
// K1 — Mandelbrot escape-time (seahorse valley zoom). Double precision, tight loop.
// ---------------------------------------------------------------------------

object Mandelbrot {
    const val WIDTH = 800
    const val HEIGHT = 800
    const val MAX_ITER = 1000
    const val CENTER_X = -0.743643887037151
    const val CENTER_Y = 0.13182590420533
    const val SPAN_Y = 0.0070 // full vertical span of the view (half above/below center)

    /** Renders into [argb] (size WIDTH*HEIGHT). Returns a checksum (consumed to defeat DCE). */
    fun render(argb: IntArray): Double {
        renderRows(argb, 0, HEIGHT)
        return checksum(argb)
    }

    /** Row-parallel variant over [threads] threads. Same work, disjoint output slices. */
    fun renderMt(
        argb: IntArray,
        threads: Int,
    ): Double {
        parallelChunks(HEIGHT, threads) { s, e -> renderRows(argb, s, e) }
        return checksum(argb)
    }

    private fun renderRows(
        argb: IntArray,
        yStart: Int,
        yEnd: Int,
    ) {
        val halfSpan = SPAN_Y * 0.5
        val minY = CENTER_Y - halfSpan
        val minX = CENTER_X - halfSpan // square view
        val step = SPAN_Y / HEIGHT
        for (py in yStart until yEnd) {
            val cy = minY + py * step
            var idx = py * WIDTH
            for (px in 0 until WIDTH) {
                val cx = minX + px * step
                var zx = 0.0
                var zy = 0.0
                var i = 0
                while (i < MAX_ITER) {
                    val zx2 = zx * zx
                    val zy2 = zy * zy
                    if (zx2 + zy2 > 4.0) break
                    zy = 2.0 * zx * zy + cy
                    zx = zx2 - zy2 + cx
                    i++
                }
                argb[idx++] = color(i)
            }
        }
    }

    private fun color(i: Int): Int {
        if (i >= MAX_ITER) return 0xFF000000.toInt()
        val t = i.toDouble() / MAX_ITER
        val r = (9 * (1 - t) * t * t * t * 255).toInt()
        val g = (15 * (1 - t) * (1 - t) * t * t * 255).toInt()
        val b = (8.5 * (1 - t) * (1 - t) * (1 - t) * t * 255).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

// ---------------------------------------------------------------------------
// K2 — N-body gravity, O(N^2) direct summation. Softened Newtonian, Euler integration.
// ---------------------------------------------------------------------------

object NBody {
    const val N = 1500
    const val STEPS = 120
    const val DT = 0.001
    const val G = 1.0
    const val SOFTENING2 = 0.05 // epsilon^2, avoids singularities
    val SEED = 0x9E3779B97F4A7C15uL

    class State {
        val x = DoubleArray(N)
        val y = DoubleArray(N)
        val z = DoubleArray(N)
        val vx = DoubleArray(N)
        val vy = DoubleArray(N)
        val vz = DoubleArray(N)
        val mass = DoubleArray(N)

        fun reset() {
            val rng = Lcg(SEED)
            for (i in 0 until N) {
                x[i] = rng.range(-1.0, 1.0)
                y[i] = rng.range(-1.0, 1.0)
                z[i] = rng.range(-1.0, 1.0)
                vx[i] = 0.0
                vy[i] = 0.0
                vz[i] = 0.0
                mass[i] = rng.range(0.5, 1.5)
            }
        }
    }

    /** Advances [s] by STEPS. Returns a checksum (consumed to defeat DCE). */
    fun simulate(s: State): Double {
        val ax = DoubleArray(N)
        val ay = DoubleArray(N)
        val az = DoubleArray(N)
        for (step in 0 until STEPS) {
            java.util.Arrays.fill(ax, 0.0)
            java.util.Arrays.fill(ay, 0.0)
            java.util.Arrays.fill(az, 0.0)
            for (i in 0 until N) {
                val xi = s.x[i]
                val yi = s.y[i]
                val zi = s.z[i]
                var axi = 0.0
                var ayi = 0.0
                var azi = 0.0
                for (j in 0 until N) {
                    if (j == i) continue
                    val dx = s.x[j] - xi
                    val dy = s.y[j] - yi
                    val dz = s.z[j] - zi
                    val dist2 = dx * dx + dy * dy + dz * dz + SOFTENING2
                    val invDist = 1.0 / Math.sqrt(dist2)
                    val f = G * s.mass[j] * invDist * invDist * invDist
                    axi += f * dx
                    ayi += f * dy
                    azi += f * dz
                }
                ax[i] = axi
                ay[i] = ayi
                az[i] = azi
            }
            for (i in 0 until N) {
                s.vx[i] += ax[i] * DT
                s.vy[i] += ay[i] * DT
                s.vz[i] += az[i] * DT
                s.x[i] += s.vx[i] * DT
                s.y[i] += s.vy[i] * DT
                s.z[i] += s.vz[i] * DT
            }
        }
        var cs = 0.0
        for (i in 0 until N) cs += s.x[i] + s.y[i] + s.z[i]
        return cs
    }
}

// ---------------------------------------------------------------------------
// K3 — Recursive ray tracer: ground plane + sphere grid + reflections.
// ---------------------------------------------------------------------------

object RayTracer {
    const val WIDTH = 600
    const val HEIGHT = 600
    const val MAX_DEPTH = 4

    private val spheres =
        buildList {
            for (gx in -1..1) {
                for (gz in -1..1) {
                    add(Sphere(gx * 1.2, 0.0, -3.0 + gz * 1.2, 0.5, colorFor(gx, gz)))
                }
            }
        }
    private val lightX = 5.0
    private val lightY = 5.0
    private val lightZ = 0.0

    private class Sphere(
        val cx: Double,
        val cy: Double,
        val cz: Double,
        val r: Double,
        val color: Long,
    )

    private fun colorFor(
        gx: Int,
        gz: Int,
    ): Long =
        when ((gx + gz + 4) % 3) {
            0 -> 0xFFE06C75
            1 -> 0xFF98C379
            else -> 0xFF61AFEF
        }

    /** Renders into [argb] (size WIDTH*HEIGHT). Returns a checksum (consumed to defeat DCE). */
    fun render(argb: IntArray): Double {
        renderRows(argb, 0, HEIGHT)
        return checksum(argb)
    }

    /** Row-parallel variant over [threads] threads. */
    fun renderMt(
        argb: IntArray,
        threads: Int,
    ): Double {
        parallelChunks(HEIGHT, threads) { s, e -> renderRows(argb, s, e) }
        return checksum(argb)
    }

    private fun renderRows(
        argb: IntArray,
        yStart: Int,
        yEnd: Int,
    ) {
        val aspect = WIDTH.toDouble() / HEIGHT
        for (py in yStart until yEnd) {
            val sy = (1.0 - 2.0 * (py + 0.5) / HEIGHT)
            var idx = py * WIDTH
            for (px in 0 until WIDTH) {
                val sx = (2.0 * (px + 0.5) / WIDTH - 1.0) * aspect
                // Camera at origin looking down -Z.
                val (r, g, b) = trace(0.0, 0.0, 0.0, sx, sy, -1.0, 0)
                argb[idx++] = (0xFF shl 24) or
                    ((r * 255).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255).toInt().coerceIn(0, 255) shl 8) or
                    (b * 255).toInt().coerceIn(0, 255)
            }
        }
    }

    private data class Rgb(
        val r: Double,
        val g: Double,
        val b: Double,
    )

    private fun trace(
        ox: Double,
        oy: Double,
        oz: Double,
        dxIn: Double,
        dyIn: Double,
        dzIn: Double,
        depth: Int,
    ): Rgb {
        // Normalize direction.
        val inv = 1.0 / Math.sqrt(dxIn * dxIn + dyIn * dyIn + dzIn * dzIn)
        val dx = dxIn * inv
        val dy = dyIn * inv
        val dz = dzIn * inv

        var hitT = Double.MAX_VALUE
        var hit: Sphere? = null
        for (s in spheres) {
            val t = intersect(ox, oy, oz, dx, dy, dz, s)
            if (t > 1e-4 && t < hitT) {
                hitT = t
                hit = s
            }
        }

        // Ground plane at y = -0.5.
        var groundT = Double.MAX_VALUE
        if (dy < -1e-6) {
            val t = (-0.5 - oy) / dy
            if (t > 1e-4 && t < hitT) groundT = t
        }

        if (hit == null && groundT == Double.MAX_VALUE) {
            // Sky gradient.
            val t = 0.5 * (dy + 1.0)
            return Rgb(0.5 + 0.3 * t, 0.7 * t + 0.2, 0.9 * t + 0.1)
        }

        val px: Double
        val py: Double
        val pz: Double
        val nx: Double
        val ny: Double
        val nz: Double
        val albedoR: Double
        val albedoG: Double
        val albedoB: Double
        val reflectivity: Double

        if (groundT < hitT) {
            px = ox + dx * groundT
            py = oy + dy * groundT
            pz = oz + dz * groundT
            nx = 0.0
            ny = 1.0
            nz = 0.0
            val checker = ((Math.floor(px) + Math.floor(pz)).toLong() and 1L) == 0L
            val c = if (checker) 0.9 else 0.3
            albedoR = c
            albedoG = c
            albedoB = c
            reflectivity = 0.2
        } else {
            val s = hit!!
            px = ox + dx * hitT
            py = oy + dy * hitT
            pz = oz + dz * hitT
            val lnx = px - s.cx
            val lny = py - s.cy
            val lnz = pz - s.cz
            val ninv = 1.0 / Math.sqrt(lnx * lnx + lny * lny + lnz * lnz)
            nx = lnx * ninv
            ny = lny * ninv
            nz = lnz * ninv
            albedoR = ((s.color shr 16) and 0xFF) / 255.0
            albedoG = ((s.color shr 8) and 0xFF) / 255.0
            albedoB = (s.color and 0xFF) / 255.0
            reflectivity = 0.5
        }

        // Diffuse lighting toward the point light.
        var ldx = lightX - px
        var ldy = lightY - py
        var ldz = lightZ - pz
        val linv = 1.0 / Math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz)
        ldx *= linv
        ldy *= linv
        ldz *= linv
        val diff = (nx * ldx + ny * ldy + nz * ldz).coerceAtLeast(0.0)
        val ambient = 0.15
        val shade = ambient + (1 - ambient) * diff

        var r = albedoR * shade
        var g = albedoG * shade
        var b = albedoB * shade

        // Reflection.
        if (depth < MAX_DEPTH && reflectivity > 0.0) {
            val dot = dx * nx + dy * ny + dz * nz
            val rx = dx - 2 * dot * nx
            val ry = dy - 2 * dot * ny
            val rz = dz - 2 * dot * nz
            val refl = trace(px, py, pz, rx, ry, rz, depth + 1)
            r = r * (1 - reflectivity) + refl.r * reflectivity
            g = g * (1 - reflectivity) + refl.g * reflectivity
            b = b * (1 - reflectivity) + refl.b * reflectivity
        }
        return Rgb(r, g, b)
    }

    private fun intersect(
        ox: Double,
        oy: Double,
        oz: Double,
        dx: Double,
        dy: Double,
        dz: Double,
        s: Sphere,
    ): Double {
        val ocx = ox - s.cx
        val ocy = oy - s.cy
        val ocz = oz - s.cz
        val b = ocx * dx + ocy * dy + ocz * dz
        val c = ocx * ocx + ocy * ocy + ocz * ocz - s.r * s.r
        val disc = b * b - c
        if (disc < 0) return Double.MAX_VALUE
        val sq = Math.sqrt(disc)
        val t0 = -b - sq
        if (t0 > 1e-4) return t0
        val t1 = -b + sq
        if (t1 > 1e-4) return t1
        return Double.MAX_VALUE
    }
}

// ---------------------------------------------------------------------------
// K4 — MatMul (SGEMM-style, f64): C = A×B, N=512, cache-friendly ikj order.
// ---------------------------------------------------------------------------

object MatMul {
    const val N = 512

    /** Deterministic operands — filled once, outside timing. */
    fun fill(
        a: DoubleArray,
        b: DoubleArray,
    ) {
        for (i in 0 until N) {
            for (j in 0 until N) {
                a[i * N + j] = ((i * 31 + j) % 100) * 0.01
                b[i * N + j] = ((i * 17 + j) % 100) * 0.01
            }
        }
    }

    fun multiply(
        a: DoubleArray,
        b: DoubleArray,
        c: DoubleArray,
    ): Double {
        java.util.Arrays.fill(c, 0.0)
        mulRows(a, b, c, 0, N)
        return checksumD(c)
    }

    fun multiplyMt(
        a: DoubleArray,
        b: DoubleArray,
        c: DoubleArray,
        threads: Int,
    ): Double {
        java.util.Arrays.fill(c, 0.0)
        parallelChunks(N, threads) { s, e -> mulRows(a, b, c, s, e) }
        return checksumD(c)
    }

    private fun mulRows(
        a: DoubleArray,
        b: DoubleArray,
        c: DoubleArray,
        i0: Int,
        i1: Int,
    ) {
        for (i in i0 until i1) {
            val ai = i * N
            for (k in 0 until N) {
                val r = a[ai + k]
                val bk = k * N
                for (j in 0 until N) {
                    c[ai + j] += r * b[bk + j]
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// K5 — Gaussian blur (separable, radius 8) on a 1536×1536 grayscale f64 image.
// ---------------------------------------------------------------------------

object Blur {
    const val WIDTH = 1536
    const val HEIGHT = 1536
    const val RADIUS = 8
    val SEED = 0xB1005EEDuL

    private val weights =
        DoubleArray(2 * RADIUS + 1).also { w ->
            val sigma = RADIUS / 2.0
            var sum = 0.0
            for (i in w.indices) {
                val d = (i - RADIUS).toDouble()
                w[i] = Math.exp(-d * d / (2 * sigma * sigma))
                sum += w[i]
            }
            for (i in w.indices) w[i] /= sum
        }

    /** Deterministic image — filled once, outside timing (blur cost is data-independent). */
    fun reset(img: DoubleArray) {
        val rng = Lcg(SEED)
        for (i in img.indices) img[i] = rng.nextDouble()
    }

    fun convolve(
        img: DoubleArray,
        tmp: DoubleArray,
    ): Double {
        hPass(img, tmp, 0, HEIGHT)
        vPass(tmp, img, 0, HEIGHT)
        return checksumD(img)
    }

    fun convolveMt(
        img: DoubleArray,
        tmp: DoubleArray,
        threads: Int,
    ): Double {
        parallelChunks(HEIGHT, threads) { s, e -> hPass(img, tmp, s, e) }
        parallelChunks(HEIGHT, threads) { s, e -> vPass(tmp, img, s, e) }
        return checksumD(img)
    }

    private fun hPass(
        src: DoubleArray,
        dst: DoubleArray,
        y0: Int,
        y1: Int,
    ) {
        for (y in y0 until y1) {
            val row = y * WIDTH
            for (x in 0 until WIDTH) {
                var acc = 0.0
                for (k in -RADIUS..RADIUS) {
                    var xx = x + k
                    if (xx < 0) {
                        xx = 0
                    } else if (xx >= WIDTH) {
                        xx = WIDTH - 1
                    }
                    acc += src[row + xx] * weights[k + RADIUS]
                }
                dst[row + x] = acc
            }
        }
    }

    private fun vPass(
        src: DoubleArray,
        dst: DoubleArray,
        y0: Int,
        y1: Int,
    ) {
        for (y in y0 until y1) {
            for (x in 0 until WIDTH) {
                var acc = 0.0
                for (k in -RADIUS..RADIUS) {
                    var yy = y + k
                    if (yy < 0) {
                        yy = 0
                    } else if (yy >= HEIGHT) {
                        yy = HEIGHT - 1
                    }
                    acc += src[yy * WIDTH + x] * weights[k + RADIUS]
                }
                dst[y * WIDTH + x] = acc
            }
        }
    }
}

// ---------------------------------------------------------------------------
// K6 — SHA-256 (hand-rolled FIPS 180-4) over an 8 MiB deterministic buffer.
// Integer/crypto workload. Hand-rolled so every port runs the same instructions.
// ---------------------------------------------------------------------------

object Sha256 {
    const val BYTES = 8 * 1024 * 1024
    val SEED = 0xFEEDFACECAFEBEEFuL

    private val k =
        longArrayOf(
            0x428a2f98,
            0x71374491,
            0xb5c0fbcf,
            0xe9b5dba5,
            0x3956c25b,
            0x59f111f1,
            0x923f82a4,
            0xab1c5ed5,
            0xd807aa98,
            0x12835b01,
            0x243185be,
            0x550c7dc3,
            0x72be5d74,
            0x80deb1fe,
            0x9bdc06a7,
            0xc19bf174,
            0xe49b69c1,
            0xefbe4786,
            0x0fc19dc6,
            0x240ca1cc,
            0x2de92c6f,
            0x4a7484aa,
            0x5cb0a9dc,
            0x76f988da,
            0x983e5152,
            0xa831c66d,
            0xb00327c8,
            0xbf597fc7,
            0xc6e00bf3,
            0xd5a79147,
            0x06ca6351,
            0x14292967,
            0x27b70a85,
            0x2e1b2138,
            0x4d2c6dfc,
            0x53380d13,
            0x650a7354,
            0x766a0abb,
            0x81c2c92e,
            0x92722c85,
            0xa2bfe8a1,
            0xa81a664b,
            0xc24b8b70,
            0xc76c51a3,
            0xd192e819,
            0xd6990624,
            0xf40e3585,
            0x106aa070,
            0x19a4c116,
            0x1e376c08,
            0x2748774c,
            0x34b0bcb5,
            0x391c0cb3,
            0x4ed8aa4a,
            0x5b9cca4f,
            0x682e6ff3,
            0x748f82ee,
            0x78a5636f,
            0x84c87814,
            0x8cc70208,
            0x90befffa,
            0xa4506ceb,
            0xbef9a3f7,
            0xc67178f2,
        ).let { arr -> IntArray(64) { arr[it].toInt() } }

    /** Deterministic input — filled once, outside timing. */
    fun fill(data: ByteArray) {
        var s = SEED
        for (i in data.indices) {
            s = s * 6364136223846793005uL + 1442695040888963407uL
            data[i] = (s shr 56).toByte()
        }
    }

    /** Full SHA-256 of [data] (length must be a multiple of 64). Returns sum of the 8 h-words. */
    fun digest(data: ByteArray): Double {
        val h =
            intArrayOf(
                0x6a09e667,
                0xbb67ae85.toInt(),
                0x3c6ef372,
                0xa54ff53a.toInt(),
                0x510e527f,
                0x9b05688c.toInt(),
                0x1f83d9ab,
                0x5be0cd19,
            )
        val w = IntArray(64)
        var off = 0
        while (off + 64 <= data.size) {
            processBlock(data, off, w, h)
            off += 64
        }
        // Standard padding block (input length is a multiple of 64).
        val pad = ByteArray(64)
        pad[0] = 0x80.toByte()
        val bits = data.size.toLong() * 8
        for (i in 0 until 8) pad[56 + i] = (bits ushr (56 - 8 * i)).toByte()
        processBlock(pad, 0, w, h)
        var cs = 0.0
        for (v in h) cs += (v.toLong() and 0xFFFFFFFFL).toDouble()
        return cs
    }

    @Suppress("ktlint:standard:property-naming")
    private fun processBlock(
        block: ByteArray,
        off: Int,
        w: IntArray,
        h: IntArray,
    ) {
        for (t in 0 until 16) {
            val i = off + t * 4
            w[t] = ((block[i].toInt() and 0xFF) shl 24) or
                ((block[i + 1].toInt() and 0xFF) shl 16) or
                ((block[i + 2].toInt() and 0xFF) shl 8) or
                (block[i + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val x = w[t - 15]
            val y = w[t - 2]
            val s0 = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
            val s1 = Integer.rotateRight(y, 17) xor Integer.rotateRight(y, 19) xor (y ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (t in 0 until 64) {
            val s1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + k[t] + w[t]
            val s0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }
}

// ---------------------------------------------------------------------------
// K7 — Sieve of Eratosthenes up to 20,000,000. Integer + memory-bandwidth workload.
// ---------------------------------------------------------------------------

object Sieve {
    const val LIMIT = 20_000_000

    /** Returns the prime count as checksum — π(2×10⁷) = 1,270,607, asserted in self-checks. */
    fun count(composite: ByteArray): Double {
        java.util.Arrays.fill(composite, 0)
        var i = 2
        while (i * i <= LIMIT) {
            if (composite[i] == 0.toByte()) {
                var j = i * i
                while (j <= LIMIT) {
                    composite[j] = 1
                    j += i
                }
            }
            i++
        }
        var count = 0
        for (n in 2..LIMIT) {
            if (composite[n] == 0.toByte()) count++
        }
        return count.toDouble()
    }
}

// ---------------------------------------------------------------------------
// K9 — π to 10,000 digits: Machin formula with fixed-point bignum (base 10^9).
// Pure integer array arithmetic — the SuperPI-style classic. Self-verifying:
// the computed words must start 3.141592653…, else compute() throws.
// ---------------------------------------------------------------------------

object Pi {
    const val DIGITS = 10_000
    private const val BASE = 1_000_000_000L
    private const val W = DIGITS / 9 + 3

    private fun divSmall(
        a: LongArray,
        d: Long,
    ) {
        var rem = 0L
        for (i in 0 until W) {
            val cur = rem * BASE + a[i]
            a[i] = cur / d
            rem = cur % d
        }
    }

    private fun mulSmall(
        a: LongArray,
        m: Long,
    ) {
        var carry = 0L
        for (i in W - 1 downTo 0) {
            val p = a[i] * m + carry
            a[i] = p % BASE
            carry = p / BASE
        }
    }

    private fun addInPlace(
        a: LongArray,
        b: LongArray,
    ) {
        var carry = 0L
        for (i in W - 1 downTo 0) {
            val s = a[i] + b[i] + carry
            a[i] = s % BASE
            carry = s / BASE
        }
    }

    private fun subInPlace(
        a: LongArray,
        b: LongArray,
    ) {
        var borrow = 0L
        for (i in W - 1 downTo 0) {
            var s = a[i] - b[i] - borrow
            if (s < 0) {
                s += BASE
                borrow = 1
            } else {
                borrow = 0
            }
            a[i] = s
        }
    }

    private fun isZero(a: LongArray): Boolean {
        for (v in a) if (v != 0L) return false
        return true
    }

    /** arctan(1/x) as a Gregory series into [out]. */
    private fun arctanInv(
        out: LongArray,
        x: Long,
    ) {
        val term = LongArray(W)
        val tmp = LongArray(W)
        java.util.Arrays.fill(out, 0)
        term[0] = 1
        divSmall(term, x)
        addInPlace(out, term)
        var n = 1L
        var subtract = true
        val x2 = x * x
        while (!isZero(term)) {
            divSmall(term, x2)
            n += 2
            System.arraycopy(term, 0, tmp, 0, W)
            divSmall(tmp, n)
            if (subtract) subInPlace(out, tmp) else addInPlace(out, tmp)
            subtract = !subtract
        }
    }

    /** π = 16·arctan(1/5) − 4·arctan(1/239). Returns a strided word checksum. */
    fun compute(): Double {
        val a = LongArray(W)
        val b = LongArray(W)
        arctanInv(a, 5)
        mulSmall(a, 16)
        arctanInv(b, 239)
        mulSmall(b, 4)
        subInPlace(a, b)
        check(a[0] == 3L && a[1] == 141592653L && a[2] == 589793238L) {
            "pi self-check failed: ${a[0]}.${a[1]} ${a[2]}"
        }
        var cs = 0.0
        var k = 0
        while (k < W) {
            cs += a[k]
            k += 101
        }
        return cs
    }
}

// ---------------------------------------------------------------------------
// K8 — FFT: iterative in-place radix-2 Cooley-Tukey, 2^20 complex points.
// ---------------------------------------------------------------------------

object Fft {
    const val LOG_N = 20
    const val N = 1 shl LOG_N
    val SEED = 0x0123456789ABCDEFuL

    /** Deterministic input — the transform mutates in place, so reset before each run (timed). */
    fun reset(
        re: DoubleArray,
        im: DoubleArray,
    ) {
        val rng = Lcg(SEED)
        for (i in 0 until N) {
            re[i] = rng.range(-1.0, 1.0)
            im[i] = 0.0
        }
    }

    fun transform(
        re: DoubleArray,
        im: DoubleArray,
    ): Double {
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= N) {
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang)
            val wi = Math.sin(ang)
            var i = 0
            while (i < N) {
                var cwr = 1.0
                var cwi = 0.0
                val half = len shr 1
                for (t in 0 until half) {
                    val ur = re[i + t]
                    val ui = im[i + t]
                    val vr = re[i + t + half] * cwr - im[i + t + half] * cwi
                    val vi = re[i + t + half] * cwi + im[i + t + half] * cwr
                    re[i + t] = ur + vr
                    im[i + t] = ui + vi
                    re[i + t + half] = ur - vr
                    im[i + t + half] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        var cs = 0.0
        var s = 0
        while (s < N) {
            cs += re[s] + im[s]
            s += 101
        }
        return cs
    }
}
