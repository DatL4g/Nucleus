import Foundation

// 1:1 port of Kernels.kt — see ../../BENCHMARK-SPEC.md. Keep constants byte-identical.
// Idiomatic safe arrays: bounds-check elimination at `-O` is part of what we're measuring.

/// Strided sum over a pixel buffer — a cheap sink so the render can't be dead-code-eliminated
/// (see BENCHMARK-SPEC.md). Kept identical in every port.
func checksum(_ buf: [UInt32]) -> Double {
    var cs: Int64 = 0
    var k = 0
    while k < buf.count { cs &+= Int64(buf[k]); k += 101 }
    return Double(cs)
}

/// Same anti-DCE sink for double buffers.
func checksumD(_ buf: [Double]) -> Double {
    var cs = 0.0
    var k = 0
    while k < buf.count { cs += buf[k]; k += 101 }
    return cs
}

func coreCount() -> Int { ProcessInfo.processInfo.activeProcessorCount }

// MARK: - Shared deterministic RNG (MMIX LCG)

struct Lcg {
    private var state: UInt64
    init(_ seed: UInt64) { state = seed }
    mutating func nextDouble() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double(state >> 11) * (1.0 / 9007199254740992.0)
    }
    mutating func range(_ lo: Double, _ hi: Double) -> Double { lo + (hi - lo) * nextDouble() }
}

// MARK: - K1 Mandelbrot

enum Mandelbrot {
    static let WIDTH = 800, HEIGHT = 800, MAX_ITER = 1000
    static let CENTER_X = -0.743643887037151, CENTER_Y = 0.13182590420533
    static let SPAN_Y = 0.0070

    static func render(_ argb: inout [UInt32]) -> Double {
        argb.withUnsafeMutableBufferPointer { buf in renderRows(buf, 0, HEIGHT) }
        return checksum(argb)
    }

    static func renderMT(_ argb: inout [UInt32], _ threads: Int) -> Double {
        let per = (HEIGHT + threads - 1) / threads
        argb.withUnsafeMutableBufferPointer { buf in
            DispatchQueue.concurrentPerform(iterations: threads) { t in
                renderRows(buf, t * per, min(HEIGHT, (t + 1) * per))
            }
        }
        return checksum(argb)
    }

    private static func renderRows(_ buf: UnsafeMutableBufferPointer<UInt32>, _ y0: Int, _ y1: Int) {
        let halfSpan = SPAN_Y * 0.5
        let minY = CENTER_Y - halfSpan
        let minX = CENTER_X - halfSpan
        let step = SPAN_Y / Double(HEIGHT)
        for py in y0..<y1 {
            let cy = minY + Double(py) * step
            var idx = py * WIDTH
            for px in 0..<WIDTH {
                let cx = minX + Double(px) * step
                var zx = 0.0, zy = 0.0, i = 0
                while i < MAX_ITER {
                    let zx2 = zx * zx, zy2 = zy * zy
                    if zx2 + zy2 > 4.0 { break }
                    zy = 2.0 * zx * zy + cy
                    zx = zx2 - zy2 + cx
                    i += 1
                }
                buf[idx] = color(i); idx += 1
            }
        }
    }

    static func color(_ i: Int) -> UInt32 {
        if i >= MAX_ITER { return 0xFF00_0000 }
        let t = Double(i) / Double(MAX_ITER)
        let r = UInt32(max(0, min(255, Int(9 * (1 - t) * t * t * t * 255))))
        let g = UInt32(max(0, min(255, Int(15 * (1 - t) * (1 - t) * t * t * 255))))
        let b = UInt32(max(0, min(255, Int(8.5 * (1 - t) * (1 - t) * (1 - t) * t * 255))))
        return (0xFF << 24) | (r << 16) | (g << 8) | b
    }
}

// MARK: - K2 N-body

enum NBody {
    static let N = 1500, STEPS = 120
    static let DT = 0.001, G = 1.0, SOFTENING2 = 0.05
    static let SEED: UInt64 = 0x9E37_79B9_7F4A_7C15

    final class State {
        var x = [Double](repeating: 0, count: N), y = [Double](repeating: 0, count: N), z = [Double](repeating: 0, count: N)
        var vx = [Double](repeating: 0, count: N), vy = [Double](repeating: 0, count: N), vz = [Double](repeating: 0, count: N)
        var mass = [Double](repeating: 0, count: N)
        func reset() {
            var rng = Lcg(SEED)
            for i in 0..<N {
                x[i] = rng.range(-1, 1); y[i] = rng.range(-1, 1); z[i] = rng.range(-1, 1)
                vx[i] = 0; vy[i] = 0; vz[i] = 0
                mass[i] = rng.range(0.5, 1.5)
            }
        }
    }

    static func simulate(_ s: State) -> Double {
        var ax = [Double](repeating: 0, count: N), ay = [Double](repeating: 0, count: N), az = [Double](repeating: 0, count: N)
        for _ in 0..<STEPS {
            for i in 0..<N { ax[i] = 0; ay[i] = 0; az[i] = 0 }
            for i in 0..<N {
                let xi = s.x[i], yi = s.y[i], zi = s.z[i]
                var axi = 0.0, ayi = 0.0, azi = 0.0
                for j in 0..<N where j != i {
                    let dx = s.x[j] - xi, dy = s.y[j] - yi, dz = s.z[j] - zi
                    let dist2 = dx * dx + dy * dy + dz * dz + SOFTENING2
                    let invDist = 1.0 / dist2.squareRoot()
                    let f = G * s.mass[j] * invDist * invDist * invDist
                    axi += f * dx; ayi += f * dy; azi += f * dz
                }
                ax[i] = axi; ay[i] = ayi; az[i] = azi
            }
            for i in 0..<N {
                s.vx[i] += ax[i] * DT; s.vy[i] += ay[i] * DT; s.vz[i] += az[i] * DT
                s.x[i] += s.vx[i] * DT; s.y[i] += s.vy[i] * DT; s.z[i] += s.vz[i] * DT
            }
        }
        var cs = 0.0
        for i in 0..<N { cs += s.x[i] + s.y[i] + s.z[i] }
        return cs
    }
}

// MARK: - K3 Ray tracer

enum RayTracer {
    static let WIDTH = 600, HEIGHT = 600, MAX_DEPTH = 4

    struct Sphere { let cx, cy, cz, r: Double; let color: UInt32 }
    static let spheres: [Sphere] = {
        var out: [Sphere] = []
        for gx in -1...1 { for gz in -1...1 {
            out.append(Sphere(cx: Double(gx) * 1.2, cy: 0, cz: -3 + Double(gz) * 1.2, r: 0.5, color: colorFor(gx, gz)))
        } }
        return out
    }()
    static let lightX = 5.0, lightY = 5.0, lightZ = 0.0

    static func colorFor(_ gx: Int, _ gz: Int) -> UInt32 {
        switch (gx + gz + 4) % 3 { case 0: return 0xFFE0_6C75; case 1: return 0xFF98_C379; default: return 0xFF61_AFEF }
    }

    static func render(_ argb: inout [UInt32]) -> Double {
        argb.withUnsafeMutableBufferPointer { buf in renderRows(buf, 0, HEIGHT) }
        return checksum(argb)
    }

    static func renderMT(_ argb: inout [UInt32], _ threads: Int) -> Double {
        let per = (HEIGHT + threads - 1) / threads
        argb.withUnsafeMutableBufferPointer { buf in
            DispatchQueue.concurrentPerform(iterations: threads) { t in
                renderRows(buf, t * per, min(HEIGHT, (t + 1) * per))
            }
        }
        return checksum(argb)
    }

    private static func renderRows(_ buf: UnsafeMutableBufferPointer<UInt32>, _ y0: Int, _ y1: Int) {
        let aspect = Double(WIDTH) / Double(HEIGHT)
        for py in y0..<y1 {
            let sy = 1.0 - 2.0 * (Double(py) + 0.5) / Double(HEIGHT)
            var idx = py * WIDTH
            for px in 0..<WIDTH {
                let sx = (2.0 * (Double(px) + 0.5) / Double(WIDTH) - 1.0) * aspect
                let c = trace(0, 0, 0, sx, sy, -1, 0)
                let r = UInt32(max(0, min(255, Int(c.0 * 255))))
                let g = UInt32(max(0, min(255, Int(c.1 * 255))))
                let b = UInt32(max(0, min(255, Int(c.2 * 255))))
                buf[idx] = (0xFF << 24) | (r << 16) | (g << 8) | b; idx += 1
            }
        }
    }

    static func trace(_ ox: Double, _ oy: Double, _ oz: Double,
                      _ dxIn: Double, _ dyIn: Double, _ dzIn: Double, _ depth: Int) -> (Double, Double, Double) {
        let inv = 1.0 / (dxIn * dxIn + dyIn * dyIn + dzIn * dzIn).squareRoot()
        let dx = dxIn * inv, dy = dyIn * inv, dz = dzIn * inv

        var hitT = Double.greatestFiniteMagnitude
        var hit: Sphere?
        for s in spheres {
            let t = intersect(ox, oy, oz, dx, dy, dz, s)
            if t > 1e-4 && t < hitT { hitT = t; hit = s }
        }
        var groundT = Double.greatestFiniteMagnitude
        if dy < -1e-6 {
            let t = (-0.5 - oy) / dy
            if t > 1e-4 && t < hitT { groundT = t }
        }
        if hit == nil && groundT == Double.greatestFiniteMagnitude {
            let t = 0.5 * (dy + 1.0)
            return (0.5 + 0.3 * t, 0.7 * t + 0.2, 0.9 * t + 0.1)
        }

        let px, py, pz, nx, ny, nz, albedoR, albedoG, albedoB, reflectivity: Double
        if groundT < hitT {
            px = ox + dx * groundT; py = oy + dy * groundT; pz = oz + dz * groundT
            nx = 0; ny = 1; nz = 0
            let checker = (Int64(floor(px)) + Int64(floor(pz))) & 1 == 0
            let c = checker ? 0.9 : 0.3
            albedoR = c; albedoG = c; albedoB = c; reflectivity = 0.2
        } else {
            let s = hit!
            px = ox + dx * hitT; py = oy + dy * hitT; pz = oz + dz * hitT
            let lnx = px - s.cx, lny = py - s.cy, lnz = pz - s.cz
            let ninv = 1.0 / (lnx * lnx + lny * lny + lnz * lnz).squareRoot()
            nx = lnx * ninv; ny = lny * ninv; nz = lnz * ninv
            albedoR = Double((s.color >> 16) & 0xFF) / 255.0
            albedoG = Double((s.color >> 8) & 0xFF) / 255.0
            albedoB = Double(s.color & 0xFF) / 255.0
            reflectivity = 0.5
        }

        var ldx = lightX - px, ldy = lightY - py, ldz = lightZ - pz
        let linv = 1.0 / (ldx * ldx + ldy * ldy + ldz * ldz).squareRoot()
        ldx *= linv; ldy *= linv; ldz *= linv
        let diff = max(0.0, nx * ldx + ny * ldy + nz * ldz)
        let ambient = 0.15
        let shade = ambient + (1 - ambient) * diff

        var r = albedoR * shade, g = albedoG * shade, b = albedoB * shade
        if depth < MAX_DEPTH && reflectivity > 0.0 {
            let dot = dx * nx + dy * ny + dz * nz
            let refl = trace(px, py, pz, dx - 2 * dot * nx, dy - 2 * dot * ny, dz - 2 * dot * nz, depth + 1)
            r = r * (1 - reflectivity) + refl.0 * reflectivity
            g = g * (1 - reflectivity) + refl.1 * reflectivity
            b = b * (1 - reflectivity) + refl.2 * reflectivity
        }
        return (r, g, b)
    }

    static func intersect(_ ox: Double, _ oy: Double, _ oz: Double,
                          _ dx: Double, _ dy: Double, _ dz: Double, _ s: Sphere) -> Double {
        let ocx = ox - s.cx, ocy = oy - s.cy, ocz = oz - s.cz
        let b = ocx * dx + ocy * dy + ocz * dz
        let c = ocx * ocx + ocy * ocy + ocz * ocz - s.r * s.r
        let disc = b * b - c
        if disc < 0 { return Double.greatestFiniteMagnitude }
        let sq = disc.squareRoot()
        let t0 = -b - sq
        if t0 > 1e-4 { return t0 }
        let t1 = -b + sq
        if t1 > 1e-4 { return t1 }
        return Double.greatestFiniteMagnitude
    }
}

// MARK: - K4 MatMul (SGEMM-style, f64, N=512, ikj order)

enum MatMul {
    static let N = 512

    static func fill(_ a: inout [Double], _ b: inout [Double]) {
        for i in 0..<N { for j in 0..<N {
            a[i * N + j] = Double((i * 31 + j) % 100) * 0.01
            b[i * N + j] = Double((i * 17 + j) % 100) * 0.01
        } }
    }

    static func multiply(_ a: [Double], _ b: [Double], _ c: inout [Double]) -> Double {
        for i in 0..<(N * N) { c[i] = 0 }
        c.withUnsafeMutableBufferPointer { cb in
            a.withUnsafeBufferPointer { ab in b.withUnsafeBufferPointer { bb in
                mulRows(ab, bb, cb, 0, N)
            } }
        }
        return checksumD(c)
    }

    static func multiplyMT(_ a: [Double], _ b: [Double], _ c: inout [Double], _ threads: Int) -> Double {
        for i in 0..<(N * N) { c[i] = 0 }
        let per = (N + threads - 1) / threads
        c.withUnsafeMutableBufferPointer { cb in
            a.withUnsafeBufferPointer { ab in b.withUnsafeBufferPointer { bb in
                DispatchQueue.concurrentPerform(iterations: threads) { t in
                    mulRows(ab, bb, cb, t * per, min(N, (t + 1) * per))
                }
            } }
        }
        return checksumD(c)
    }

    private static func mulRows(_ a: UnsafeBufferPointer<Double>, _ b: UnsafeBufferPointer<Double>,
                                _ c: UnsafeMutableBufferPointer<Double>, _ i0: Int, _ i1: Int) {
        for i in i0..<i1 {
            let ai = i * N
            for k in 0..<N {
                let r = a[ai + k]
                let bk = k * N
                for j in 0..<N { c[ai + j] += r * b[bk + j] }
            }
        }
    }
}

// MARK: - K5 Gaussian blur (separable, radius 8, 1536×1536 f64)

enum Blur {
    static let WIDTH = 1536, HEIGHT = 1536, RADIUS = 8
    static let SEED: UInt64 = 0xB100_5EED

    static let weights: [Double] = {
        var w = [Double](repeating: 0, count: 2 * RADIUS + 1)
        let sigma = Double(RADIUS) / 2.0
        var sum = 0.0
        for i in 0..<w.count {
            let d = Double(i - RADIUS)
            w[i] = exp(-d * d / (2 * sigma * sigma)); sum += w[i]
        }
        for i in 0..<w.count { w[i] /= sum }
        return w
    }()

    static func reset(_ img: inout [Double]) {
        var rng = Lcg(SEED)
        for i in 0..<img.count { img[i] = rng.nextDouble() }
    }

    static func convolve(_ img: inout [Double], _ tmp: inout [Double]) -> Double {
        withBufs(&img, &tmp) { ib, tb in
            hPass(ib, tb, 0, HEIGHT)
            vPass(tb, ib, 0, HEIGHT)
        }
        return checksumD(img)
    }

    static func convolveMT(_ img: inout [Double], _ tmp: inout [Double], _ threads: Int) -> Double {
        let per = (HEIGHT + threads - 1) / threads
        withBufs(&img, &tmp) { ib, tb in
            DispatchQueue.concurrentPerform(iterations: threads) { t in
                hPass(ib, tb, t * per, min(HEIGHT, (t + 1) * per))
            }
            DispatchQueue.concurrentPerform(iterations: threads) { t in
                vPass(tb, ib, t * per, min(HEIGHT, (t + 1) * per))
            }
        }
        return checksumD(img)
    }

    private static func withBufs(_ img: inout [Double], _ tmp: inout [Double],
                                 _ body: (UnsafeMutableBufferPointer<Double>, UnsafeMutableBufferPointer<Double>) -> Void) {
        img.withUnsafeMutableBufferPointer { ib in
            tmp.withUnsafeMutableBufferPointer { tb in body(ib, tb) }
        }
    }

    private static func hPass(_ src: UnsafeMutableBufferPointer<Double>, _ dst: UnsafeMutableBufferPointer<Double>,
                              _ y0: Int, _ y1: Int) {
        for y in y0..<y1 {
            let row = y * WIDTH
            for x in 0..<WIDTH {
                var acc = 0.0
                for k in -RADIUS...RADIUS {
                    var xx = x + k
                    if xx < 0 { xx = 0 } else if xx >= WIDTH { xx = WIDTH - 1 }
                    acc += src[row + xx] * weights[k + RADIUS]
                }
                dst[row + x] = acc
            }
        }
    }

    private static func vPass(_ src: UnsafeMutableBufferPointer<Double>, _ dst: UnsafeMutableBufferPointer<Double>,
                              _ y0: Int, _ y1: Int) {
        for y in y0..<y1 {
            for x in 0..<WIDTH {
                var acc = 0.0
                for k in -RADIUS...RADIUS {
                    var yy = y + k
                    if yy < 0 { yy = 0 } else if yy >= HEIGHT { yy = HEIGHT - 1 }
                    acc += src[yy * WIDTH + x] * weights[k + RADIUS]
                }
                dst[y * WIDTH + x] = acc
            }
        }
    }
}

// MARK: - K6 SHA-256 (hand-rolled FIPS 180-4, 8 MiB input)

enum Sha256 {
    static let BYTES = 8 * 1024 * 1024
    static let SEED: UInt64 = 0xFEED_FACE_CAFE_BEEF

    private static let k: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    static func fill(_ data: inout [UInt8]) {
        var s = SEED
        for i in 0..<data.count {
            s = s &* 6364136223846793005 &+ 1442695040888963407
            data[i] = UInt8(truncatingIfNeeded: s >> 56)
        }
    }

    static func digest(_ data: [UInt8]) -> Double {
        var h: [UInt32] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
        var w = [UInt32](repeating: 0, count: 64)
        data.withUnsafeBufferPointer { buf in
            var off = 0
            while off + 64 <= buf.count {
                processBlock(buf, off, &w, &h)
                off += 64
            }
        }
        var pad = [UInt8](repeating: 0, count: 64)
        pad[0] = 0x80
        let bits = UInt64(data.count) * 8
        for i in 0..<8 { pad[56 + i] = UInt8(truncatingIfNeeded: bits >> UInt64(56 - 8 * i)) }
        pad.withUnsafeBufferPointer { processBlock($0, 0, &w, &h) }
        var cs = 0.0
        for v in h { cs += Double(v) }
        return cs
    }

    private static func rotr(_ x: UInt32, _ n: UInt32) -> UInt32 { (x >> n) | (x << (32 - n)) }

    private static func processBlock(_ block: UnsafeBufferPointer<UInt8>, _ off: Int,
                                     _ w: inout [UInt32], _ h: inout [UInt32]) {
        for t in 0..<16 {
            let i = off + t * 4
            w[t] = (UInt32(block[i]) << 24) | (UInt32(block[i + 1]) << 16) | (UInt32(block[i + 2]) << 8) | UInt32(block[i + 3])
        }
        for t in 16..<64 {
            let x = w[t - 15], y = w[t - 2]
            let s0 = rotr(x, 7) ^ rotr(x, 18) ^ (x >> 3)
            let s1 = rotr(y, 17) ^ rotr(y, 19) ^ (y >> 10)
            w[t] = w[t - 16] &+ s0 &+ w[t - 7] &+ s1
        }
        var a = h[0], b = h[1], c = h[2], d = h[3]
        var e = h[4], f = h[5], g = h[6], hh = h[7]
        for t in 0..<64 {
            let s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
            let ch = (e & f) ^ (~e & g)
            let t1 = hh &+ s1 &+ ch &+ k[t] &+ w[t]
            let s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
            let maj = (a & b) ^ (a & c) ^ (b & c)
            let t2 = s0 &+ maj
            hh = g; g = f; f = e; e = d &+ t1; d = c; c = b; b = a; a = t1 &+ t2
        }
        h[0] &+= a; h[1] &+= b; h[2] &+= c; h[3] &+= d
        h[4] &+= e; h[5] &+= f; h[6] &+= g; h[7] &+= hh
    }
}

// MARK: - K7 Sieve of Eratosthenes (limit 20,000,000)

enum Sieve {
    static let LIMIT = 20_000_000

    static func count(_ composite: inout [UInt8]) -> Double {
        for i in 0..<composite.count { composite[i] = 0 }
        var result = 0
        composite.withUnsafeMutableBufferPointer { buf in
            var i = 2
            while i * i <= LIMIT {
                if buf[i] == 0 {
                    var j = i * i
                    while j <= LIMIT { buf[j] = 1; j += i }
                }
                i += 1
            }
            var count = 0
            for n in 2...LIMIT where buf[n] == 0 { count += 1 }
            result = count
        }
        return Double(result)
    }
}

// MARK: - K8 FFT (iterative radix-2 Cooley-Tukey, 2^20 points)

enum Fft {
    static let LOG_N = 20
    static let N = 1 << LOG_N
    static let SEED: UInt64 = 0x0123_4567_89AB_CDEF

    static func reset(_ re: inout [Double], _ im: inout [Double]) {
        var rng = Lcg(SEED)
        for i in 0..<N { re[i] = rng.range(-1, 1); im[i] = 0 }
    }

    static func transform(_ re: inout [Double], _ im: inout [Double]) -> Double {
        var result = 0.0
        re.withUnsafeMutableBufferPointer { r in
            im.withUnsafeMutableBufferPointer { m in
                var j = 0
                for i in 1..<N {
                    var bit = N >> 1
                    while j & bit != 0 { j ^= bit; bit >>= 1 }
                    j |= bit
                    if i < j {
                        var t = r[i]; r[i] = r[j]; r[j] = t
                        t = m[i]; m[i] = m[j]; m[j] = t
                    }
                }
                var len = 2
                while len <= N {
                    let ang = -2.0 * Double.pi / Double(len)
                    let wr = cos(ang), wi = sin(ang)
                    var i = 0
                    while i < N {
                        var cwr = 1.0, cwi = 0.0
                        let half = len >> 1
                        for t in 0..<half {
                            let ur = r[i + t], ui = m[i + t]
                            let vr = r[i + t + half] * cwr - m[i + t + half] * cwi
                            let vi = r[i + t + half] * cwi + m[i + t + half] * cwr
                            r[i + t] = ur + vr; m[i + t] = ui + vi
                            r[i + t + half] = ur - vr; m[i + t + half] = ui - vi
                            let nwr = cwr * wr - cwi * wi
                            cwi = cwr * wi + cwi * wr; cwr = nwr
                        }
                        i += len
                    }
                    len <<= 1
                }
                var cs = 0.0
                var s = 0
                while s < N { cs += r[s] + m[s]; s += 101 }
                result = cs
            }
        }
        return result
    }
}

// MARK: - K9 π to 10,000 digits (Machin formula, fixed-point bignum base 10^9)

enum Pi {
    static let DIGITS = 10_000
    private static let BASE: Int64 = 1_000_000_000
    private static let W = DIGITS / 9 + 3

    private static func divSmall(_ a: inout [Int64], _ d: Int64) {
        var rem: Int64 = 0
        for i in 0..<W {
            let cur = rem * BASE + a[i]
            a[i] = cur / d
            rem = cur % d
        }
    }

    private static func mulSmall(_ a: inout [Int64], _ m: Int64) {
        var carry: Int64 = 0
        for i in stride(from: W - 1, through: 0, by: -1) {
            let p = a[i] * m + carry
            a[i] = p % BASE
            carry = p / BASE
        }
    }

    private static func addInPlace(_ a: inout [Int64], _ b: [Int64]) {
        var carry: Int64 = 0
        for i in stride(from: W - 1, through: 0, by: -1) {
            let s = a[i] + b[i] + carry
            a[i] = s % BASE
            carry = s / BASE
        }
    }

    private static func subInPlace(_ a: inout [Int64], _ b: [Int64]) {
        var borrow: Int64 = 0
        for i in stride(from: W - 1, through: 0, by: -1) {
            var s = a[i] - b[i] - borrow
            if s < 0 { s += BASE; borrow = 1 } else { borrow = 0 }
            a[i] = s
        }
    }

    private static func isZero(_ a: [Int64]) -> Bool {
        for v in a where v != 0 { return false }
        return true
    }

    private static func arctanInv(_ out: inout [Int64], _ x: Int64) {
        var term = [Int64](repeating: 0, count: W)
        var tmp = [Int64](repeating: 0, count: W)
        for i in 0..<W { out[i] = 0 }
        term[0] = 1
        divSmall(&term, x)
        addInPlace(&out, term)
        var n: Int64 = 1
        var subtract = true
        let x2 = x * x
        while !isZero(term) {
            divSmall(&term, x2)
            n += 2
            tmp.replaceSubrange(0..<W, with: term)
            divSmall(&tmp, n)
            if subtract { subInPlace(&out, tmp) } else { addInPlace(&out, tmp) }
            subtract = !subtract
        }
    }

    /// π = 16·arctan(1/5) − 4·arctan(1/239). Self-verifying; returns a strided word checksum.
    static func compute() -> Double {
        var a = [Int64](repeating: 0, count: W)
        var b = [Int64](repeating: 0, count: W)
        arctanInv(&a, 5)
        mulSmall(&a, 16)
        arctanInv(&b, 239)
        mulSmall(&b, 4)
        subInPlace(&a, b)
        precondition(a[0] == 3 && a[1] == 141_592_653 && a[2] == 589_793_238,
                     "pi self-check failed: \(a[0]).\(a[1]) \(a[2])")
        var cs = 0.0
        var k = 0
        while k < W { cs += Double(a[k]); k += 101 }
        return cs
    }
}
