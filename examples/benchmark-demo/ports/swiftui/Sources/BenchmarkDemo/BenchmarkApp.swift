import SwiftUI

// Same protocol as BenchmarkRunner.kt: 3 warmup (discarded) + best-of-5 per CPU bench,
// then the UI benches (FPS animation, list load), then JSON to ~/nucleus-benchmarks/swiftui.json.

// Render ramps: grow the workload each 1s window while avg fps ≥ RAMP_MIN_FPS, up to the max.
// Metric = largest sustained count — shows where the CPU/render pipeline actually collapses.
let RAMP_WINDOW_S = 1.0
let RAMP_MIN_FPS = 55.0
let RAMP_START = 25_000, RAMP_STEP = 25_000, RAMP_MAX = 500_000 // particles
let STAR_START = 200, STAR_STEP = 200, STAR_MAX = 200_000 // rotating alpha-blended stars
let TEXT_START = 500, TEXT_STEP = 500, TEXT_MAX = 200_000 // drifting text labels
let LIST_ROWS = 50_000

struct BenchResult: Identifiable {
    let id = UUID()
    let name: String, unit: String
    let threads: Int
    let work: Int64, best: Double
    var throughputM: Double { Double(work) / best / 1_000_000.0 }
}

func measureKernel(_ name: String, _ threads: Int, _ unit: String, _ workUnits: Int64,
                   _ kernel: () -> Double) -> BenchResult {
    var sink = 0.0
    for _ in 0..<3 { sink += kernel() }
    var best = Double.greatestFiniteMagnitude
    for _ in 0..<5 {
        let t0 = DispatchTime.now().uptimeNanoseconds
        sink += kernel()
        best = min(best, Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e9)
    }
    if sink.isNaN { fatalError() } // consume the checksum so the optimizer can't drop the kernel
    return BenchResult(name: name, unit: unit, threads: threads, work: workUnits, best: best)
}

func runCpuSuite(_ onResult: @escaping (BenchResult) -> Void) -> [BenchResult] {
    let cores = coreCount()
    var results: [BenchResult] = []
    func emit(_ r: BenchResult) { results.append(r); onResult(r) }

    var mandel = [UInt32](repeating: 0, count: Mandelbrot.WIDTH * Mandelbrot.HEIGHT)
    let nbody = NBody.State()
    var ray = [UInt32](repeating: 0, count: RayTracer.WIDTH * RayTracer.HEIGHT)
    var a = [Double](repeating: 0, count: MatMul.N * MatMul.N)
    var b = [Double](repeating: 0, count: MatMul.N * MatMul.N)
    var c = [Double](repeating: 0, count: MatMul.N * MatMul.N)
    MatMul.fill(&a, &b)
    var img = [Double](repeating: 0, count: Blur.WIDTH * Blur.HEIGHT)
    var tmp = [Double](repeating: 0, count: Blur.WIDTH * Blur.HEIGHT)
    Blur.reset(&img)
    var sha = [UInt8](repeating: 0, count: Sha256.BYTES)
    Sha256.fill(&sha)
    var sieveBuf = [UInt8](repeating: 0, count: Sieve.LIMIT + 1)
    var re = [Double](repeating: 0, count: Fft.N)
    var im = [Double](repeating: 0, count: Fft.N)

    let mandelWork = Int64(Mandelbrot.WIDTH * Mandelbrot.HEIGHT)
    let nbodyWork = Int64(NBody.N) * Int64(NBody.N - 1) * Int64(NBody.STEPS)
    let rayWork = Int64(RayTracer.WIDTH * RayTracer.HEIGHT)
    let matmulWork = 2 * Int64(MatMul.N) * Int64(MatMul.N) * Int64(MatMul.N)
    let blurWork = Int64(Blur.WIDTH * Blur.HEIGHT)
    let shaWork = Int64(Sha256.BYTES)
    let sieveWork = Int64(Sieve.LIMIT)
    let fftWork = Int64(Fft.N / 2) * Int64(Fft.LOG_N)

    emit(measureKernel("mandelbrot", 1, "Mpix/s", mandelWork) { Mandelbrot.render(&mandel) })
    emit(measureKernel("nbody", 1, "M-inter/s", nbodyWork) { nbody.reset(); return NBody.simulate(nbody) })
    emit(measureKernel("raytracer", 1, "Mrays/s", rayWork) { RayTracer.render(&ray) })
    emit(measureKernel("matmul", 1, "MFLOP/s", matmulWork) { MatMul.multiply(a, b, &c) })
    emit(measureKernel("blur", 1, "Mpix/s", blurWork) { Blur.convolve(&img, &tmp) })
    // sha[0] mutated per run so the pure digest can't be hoisted out of the loop (see spec).
    emit(measureKernel("sha256", 1, "MB/s", shaWork) { sha[0] &+= 1; return Sha256.digest(sha) })
    emit(measureKernel("sieve", 1, "Mn/s", sieveWork) { Sieve.count(&sieveBuf) })
    emit(measureKernel("fft", 1, "Mbf/s", fftWork) { Fft.reset(&re, &im); return Fft.transform(&re, &im) })
    emit(measureKernel("pi", 1, "Mdig/s", Int64(Pi.DIGITS)) { Pi.compute() })

    emit(measureKernel("mandelbrot_mt", cores, "Mpix/s", mandelWork) { Mandelbrot.renderMT(&mandel, cores) })
    emit(measureKernel("raytracer_mt", cores, "Mrays/s", rayWork) { RayTracer.renderMT(&ray, cores) })
    emit(measureKernel("matmul_mt", cores, "MFLOP/s", matmulWork) { MatMul.multiplyMT(a, b, &c, cores) })
    emit(measureKernel("blur_mt", cores, "Mpix/s", blurWork) { Blur.convolveMT(&img, &tmp, cores) })

    return results
}

func compositeScore(_ cpu: [BenchResult]) -> Double {
    exp(cpu.map { log($0.throughputM) }.reduce(0, +) / Double(cpu.count))
}

/// Graphics score, Geekbench-style anchor: 1000 × geo-mean of sustained/START per ramp.
func graphicsScore(_ particles: Int, _ stars: Int, _ texts: Int) -> Double {
    1000.0 * cbrt(Double(max(1, particles)) / Double(RAMP_START)
        * Double(max(1, stars)) / Double(STAR_START)
        * Double(max(1, texts)) / Double(TEXT_START))
}

func osLabel() -> String {
    let v = ProcessInfo.processInfo.operatingSystemVersion
    #if arch(arm64)
    let arch = "arm64"
    #else
    let arch = "x86_64"
    #endif
    return "macOS \(v.majorVersion).\(v.minorVersion).\(v.patchVersion) \(arch)"
}

func writeResultsJson(_ cpu: [BenchResult], _ composite: Double, _ maxParticles: Int?,
                      _ maxStars: Int? = nil, _ maxTexts: Int? = nil, _ listMs: Double?) -> String {
    var dict: [String: Any] = [
        "schema": 1,
        "runtime": "swiftui",
        "runtimeLabel": "Swift · LLVM (release) + SwiftUI",
        "os": osLabel(),
        "cpus": coreCount(),
        "timestampMs": Int64(Date().timeIntervalSince1970 * 1000),
        "compositeCpuScore": composite,
    ]
    dict["cpu"] = cpu.map { r -> [String: Any] in
        ["name": r.name, "threads": r.threads, "unit": r.unit, "workUnits": r.work,
         "bestSeconds": r.best, "throughputM": r.throughputM]
    }
    var ui: [[String: Any]] = []
    if let p = maxParticles {
        ui.append(["name": "max_particles_55fps", "unit": "particles", "value": p, "lowerIsBetter": false])
    }
    if let s = maxStars {
        ui.append(["name": "max_stars_55fps", "unit": "stars", "value": s, "lowerIsBetter": false])
    }
    if let t = maxTexts {
        ui.append(["name": "max_texts_55fps", "unit": "texts", "value": t, "lowerIsBetter": false])
    }
    if let l = listMs { ui.append(["name": "list_load", "unit": "ms", "value": l, "lowerIsBetter": true]) }
    if let p = maxParticles, let s = maxStars, let t = maxTexts {
        dict["compositeGraphicsScore"] = graphicsScore(p, s, t)
    }
    dict["ui"] = ui
    do {
        let data = try JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys])
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("nucleus-benchmarks")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("swiftui.json")
        try data.write(to: url)
        return url.path
    } catch {
        return "write failed: \(error)"
    }
}

// MARK: - FPS bench support

final class Particles {
    let capacity: Int
    var active = RAMP_START
    var x: [Float], y: [Float], vx: [Float], vy: [Float]
    init(_ capacity: Int) {
        self.capacity = capacity
        x = [Float](repeating: 0, count: capacity); y = [Float](repeating: 0, count: capacity)
        vx = [Float](repeating: 0, count: capacity); vy = [Float](repeating: 0, count: capacity)
    }
    func reset() {
        active = RAMP_START
        var rng = Lcg(42)
        for i in 0..<capacity {
            x[i] = Float(rng.nextDouble()); y[i] = Float(rng.nextDouble())
            vx[i] = Float(rng.range(-0.2, 0.2)); vy[i] = Float(rng.range(-0.2, 0.2))
        }
    }
    func step(_ dt: Float) {
        let n = active
        for i in 0..<n {
            var nx = x[i] + vx[i] * dt
            var ny = y[i] + vy[i] * dt
            if nx < 0 { nx = 0; vx[i] = -vx[i] } else if nx > 1 { nx = 1; vx[i] = -vx[i] }
            if ny < 0 { ny = 0; vy[i] = -vy[i] } else if ny > 1 { ny = 1; vy[i] = -vy[i] }
            x[i] = nx; y[i] = ny
        }
    }
}

/// Rotating-star field for the vector ramp — LCG seed 7: x, y ∈ [0,1), ω ∈ [-2,2) rad/s.
final class Stars {
    var active = STAR_START
    var t = 0.0
    var x = [Float](repeating: 0, count: STAR_MAX)
    var y = [Float](repeating: 0, count: STAR_MAX)
    var omega = [Float](repeating: 0, count: STAR_MAX)
    func reset() {
        active = STAR_START
        t = 0
        var rng = Lcg(7)
        for i in 0..<STAR_MAX {
            x[i] = Float(rng.nextDouble()); y[i] = Float(rng.nextDouble())
            omega[i] = Float(rng.range(-2, 2))
        }
    }
}

/// Drifting text field for the glyph ramp — LCG seed 11; label i is "Bench#(i % 100)".
final class Texts {
    var active = TEXT_START
    var t = 0.0
    var x = [Float](repeating: 0, count: TEXT_MAX)
    var y0 = [Float](repeating: 0, count: TEXT_MAX)
    func reset() {
        active = TEXT_START
        t = 0
        var rng = Lcg(11)
        for i in 0..<TEXT_MAX {
            x[i] = Float(rng.nextDouble()); y0[i] = Float(rng.nextDouble())
        }
    }
}

/// Generic render ramp driven from the Canvas draw closure: 1s windows; while a window averages
/// ≥ RAMP_MIN_FPS, grow the active count by `step` (up to `maxCount`).
final class RampRunner {
    private var step = 0
    private var maxCount = 0
    private var getActive: () -> Int = { 0 }
    private var setActive: (Int) -> Void = { _ in }
    private var advance: (Float) -> Void = { _ in }
    private var frames = 0
    private var windowStart: Date?
    private var last: Date?
    private var warmup = true // first window absorbs startup jank — never judged
    private var failedOnce = false // failure must be confirmed by a second consecutive window
    private(set) var sustained = 0
    private(set) var lastWindow = ""
    var running = false

    func begin(step: Int, maxCount: Int, unit: String,
               getActive: @escaping () -> Int, setActive: @escaping (Int) -> Void,
               advance: @escaping (Float) -> Void) {
        self.step = step
        self.maxCount = maxCount
        self.unitName = unit
        self.getActive = getActive
        self.setActive = setActive
        self.advance = advance
        frames = 0; windowStart = nil; last = nil; sustained = 0; lastWindow = ""
        warmup = true; failedOnce = false
        running = true
    }

    private var unitName = ""

    func tick(_ date: Date) {
        guard running else { return }
        if let l = last { advance(Float(min(date.timeIntervalSince(l), 1.0 / 30.0))) } else { windowStart = date }
        last = date
        frames += 1
        guard let ws = windowStart, date.timeIntervalSince(ws) >= RAMP_WINDOW_S, frames > 1 else { return }
        if warmup {
            warmup = false
            frames = 0
            windowStart = date
            return
        }
        let fps = Double(frames - 1) / date.timeIntervalSince(ws)
        lastWindow = String(format: "%d %@ — %.1f fps", getActive(), unitName, fps)
        if fps >= RAMP_MIN_FPS {
            failedOnce = false
            sustained = getActive()
            if getActive() >= maxCount { running = false; return }
            // Geometric growth (+25%/window, min = step) — see BENCHMARK-SPEC.md.
            setActive(min(maxCount, getActive() + max(step, getActive() / 4)))
            frames = 0
            windowStart = date
        } else if !failedOnce {
            failedOnce = true // transient hitch? re-run the same count once
            frames = 0
            windowStart = date
        } else {
            running = false
        }
    }
}

// MARK: - App

@main
enum Main {
    static func main() {
        if CommandLine.arguments.contains("--headless") {
            runHeadless()
            return
        }
        BenchmarkApp.main()
    }

    static func runHeadless() {
        print("Runtime: Swift · LLVM (release) — \(coreCount()) cores")
        // Self-checks: known prime count + SHA digest sum (must equal the Kotlin reference value).
        var sieveBuf = [UInt8](repeating: 0, count: Sieve.LIMIT + 1)
        let primes = Sieve.count(&sieveBuf)
        precondition(primes == 1_270_607.0, "sieve self-check failed: \(primes)")
        var sha = [UInt8](repeating: 0, count: Sha256.BYTES)
        Sha256.fill(&sha)
        let digestSum = Sha256.digest(sha)
        precondition(digestSum == 16_225_487_432.0, "sha256 cross-check failed: \(digestSum)")
        print("Self-checks OK — pi(2e7)=1270607, sha256 digestSum matches Kotlin reference")
        let cpu = runCpuSuite { r in
            print(String(format: "  %-14@ x%-2d %10.2f %@  (best %.3fs)",
                         r.name as NSString, r.threads, r.throughputM, r.unit as NSString, r.best))
        }
        let composite = compositeScore(cpu)
        print(String(format: "Composite CPU score (geo-mean): %.2f", composite))
        let path = writeResultsJson(cpu, composite, nil, nil, nil, nil)
        print("Results JSON: \(path)")
    }
}

struct BenchmarkApp: App {
    var body: some Scene {
        WindowGroup("SwiftUI Benchmark — LLVM -O") { ContentView() }
    }
}

struct ContentView: View {
    @State private var phase = "cpu"
    @State private var cpuResults: [BenchResult] = []
    @State private var composite: Double?
    @State private var maxParticles: Int?
    @State private var maxStars: Int?
    @State private var maxTexts: Int?
    @State private var rampStatus = ""
    @State private var listMs: Double?
    @State private var savedPath: String?
    @State private var listItems: [String] = []
    @State private var runId = 0
    private let ramp = RampRunner()
    private let particles = Particles(RAMP_MAX)
    private let stars = Stars()
    private let texts = Texts()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("SwiftUI Benchmark Suite").font(.title)
                Text("Swift · LLVM (release) — \(coreCount()) cores").font(.subheadline).foregroundColor(.secondary)
                Text(statusLine).font(.headline)

                if phase == "particles" {
                    TimelineView(.animation) { tl in
                        Canvas { ctx, size in
                            ramp.tick(tl.date)
                            let p = particles
                            let n = p.active
                            var path = Path()
                            for i in 0..<n {
                                path.addRect(CGRect(x: CGFloat(p.x[i]) * (size.width - 3),
                                                    y: CGFloat(p.y[i]) * (size.height - 3),
                                                    width: 3, height: 3))
                            }
                            ctx.fill(path, with: .color(Color(red: 0.15, green: 0.39, blue: 0.92)))
                        }
                    }
                    .frame(width: 600, height: 400)
                    .background(Color(red: 0.93, green: 0.94, blue: 0.96))
                }
                if phase == "stars" {
                    TimelineView(.animation) { tl in
                        Canvas { ctx, size in
                            ramp.tick(tl.date)
                            let color = Color(red: 0.15, green: 0.39, blue: 0.92).opacity(0.5)
                            let t = stars.t
                            for i in 0..<stars.active {
                                let cx = CGFloat(stars.x[i]) * size.width
                                let cy = CGFloat(stars.y[i]) * size.height
                                let theta = Double(stars.omega[i]) * t
                                var path = Path()
                                for k in 0..<12 {
                                    let r: CGFloat = k % 2 == 0 ? 14 : 6
                                    let a = theta + Double(k) * (Double.pi / 6)
                                    let vx = cx + r * CGFloat(cos(a))
                                    let vy = cy + r * CGFloat(sin(a))
                                    if k == 0 { path.move(to: CGPoint(x: vx, y: vy)) } else { path.addLine(to: CGPoint(x: vx, y: vy)) }
                                }
                                path.closeSubpath()
                                ctx.fill(path, with: .color(color))
                            }
                        }
                    }
                    .frame(width: 600, height: 400)
                    .background(Color(red: 0.93, green: 0.94, blue: 0.96))
                }
                if phase == "texts" {
                    TimelineView(.animation) { tl in
                        Canvas { ctx, size in
                            ramp.tick(tl.date)
                            let resolved = (0..<100).map {
                                ctx.resolve(Text("Bench#\($0)").font(.system(size: 12))
                                    .foregroundColor(Color(red: 0.2, green: 0.26, blue: 0.33)))
                            }
                            let t = Float(texts.t)
                            for i in 0..<texts.active {
                                let ty = (texts.y0[i] + 0.03 * t).truncatingRemainder(dividingBy: 1)
                                ctx.draw(resolved[i % 100],
                                         at: CGPoint(x: CGFloat(texts.x[i]) * size.width,
                                                     y: CGFloat(ty) * size.height),
                                         anchor: .topLeading)
                            }
                        }
                    }
                    .frame(width: 600, height: 400)
                    .background(Color(red: 0.93, green: 0.94, blue: 0.96))
                }
                if phase == "list" {
                    List(listItems, id: \.self) { Text($0) }
                        .frame(height: 300)
                }

                VStack(alignment: .leading, spacing: 2) {
                    ForEach(cpuResults) { r in
                        HStack {
                            Text(r.name).frame(width: 150, alignment: .leading)
                            Text("×\(r.threads)").frame(width: 50, alignment: .leading)
                            Text(String(format: "%.2f %@", r.throughputM, r.unit)).frame(width: 180, alignment: .leading)
                            Text(String(format: "best %.3fs", r.best))
                        }
                    }
                    if let c = composite {
                        Text(String(format: "Composite CPU score: %.1f", c)).font(.title2).padding(.top, 6)
                    }
                    if let p = maxParticles {
                        Text(String(format: "Particles: %d sustained @ ≥%.0f fps", p, RAMP_MIN_FPS))
                    }
                    if let s = maxStars {
                        Text(String(format: "Stars: %d sustained @ ≥%.0f fps", s, RAMP_MIN_FPS))
                    }
                    if let t = maxTexts {
                        Text(String(format: "Texts: %d sustained @ ≥%.0f fps", t, RAMP_MIN_FPS))
                    }
                    if let p = maxParticles, let s = maxStars, let t = maxTexts {
                        Text(String(format: "Graphics score: %.0f", graphicsScore(p, s, t))).font(.title2)
                    }
                    if let l = listMs { Text(String(format: "List load: %.1f ms (%d rows, best of 3)", l, LIST_ROWS)) }
                }
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.gray.opacity(0.12)))

                Button(phase == "done" ? "Run again" : "Running…") { runId += 1 }
                    .disabled(phase != "done")
                Spacer()
            }
            .padding(20)
        }
        .frame(minWidth: 860, minHeight: 720)
        .preferredColorScheme(.light)
        .task(id: runId) { await runAll() }
    }

    private var statusLine: String {
        switch phase {
        case "cpu": return "Running CPU benchmarks… (\(cpuResults.count)/13)"
        case "particles", "stars", "texts":
            return "Render ramp (\(phase)) — pushing until fps drops below \(Int(RAMP_MIN_FPS))…  \(rampStatus)"
        case "list": return "List-load benchmark — \(LIST_ROWS) rows"
        case "save": return "Saving results…"
        default: return "Done — JSON: \(savedPath ?? "")"
        }
    }

    private func runAll() async {
        cpuResults = []; composite = nil; maxParticles = nil; maxStars = nil; maxTexts = nil
        rampStatus = ""; listMs = nil; savedPath = nil
        phase = "cpu"
        let cpu = await Task.detached(priority: .userInitiated) {
            runCpuSuite { r in Task { @MainActor in cpuResults.append(r) } }
        }.value
        composite = compositeScore(cpu)

        func runRamp(_ phaseName: String, step: Int, maxCount: Int, unit: String,
                     getActive: @escaping () -> Int, setActive: @escaping (Int) -> Void,
                     advance: @escaping (Float) -> Void) async -> Int {
            phase = phaseName
            ramp.begin(step: step, maxCount: maxCount, unit: unit,
                       getActive: getActive, setActive: setActive, advance: advance)
            while ramp.running {
                try? await Task.sleep(nanoseconds: 200_000_000)
                rampStatus = ramp.lastWindow
            }
            return ramp.sustained
        }

        particles.reset()
        maxParticles = await runRamp("particles", step: RAMP_STEP, maxCount: RAMP_MAX, unit: "particles",
                                     getActive: { [particles] in particles.active },
                                     setActive: { [particles] in particles.active = $0 },
                                     advance: { [particles] in particles.step($0) })
        stars.reset()
        maxStars = await runRamp("stars", step: STAR_STEP, maxCount: STAR_MAX, unit: "stars",
                                 getActive: { [stars] in stars.active },
                                 setActive: { [stars] in stars.active = $0 },
                                 advance: { [stars] in stars.t += Double($0) })
        texts.reset()
        maxTexts = await runRamp("texts", step: TEXT_STEP, maxCount: TEXT_MAX, unit: "texts",
                                 getActive: { [texts] in texts.active },
                                 setActive: { [texts] in texts.active = $0 },
                                 advance: { [texts] in texts.t += Double($0) })

        phase = "list"
        var best = Double.greatestFiniteMagnitude
        for _ in 0..<3 {
            listItems = []
            await runLoopHop(); await runLoopHop()
            let t0 = DispatchTime.now().uptimeNanoseconds
            listItems = Self.makeItems()
            await runLoopHop(); await runLoopHop()
            best = min(best, Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e6)
        }
        listItems = []
        listMs = best

        phase = "save"
        savedPath = writeResultsJson(cpu, compositeScore(cpu), maxParticles, maxStars, maxTexts, listMs)
        phase = "done"
    }

    private static func makeItems() -> [String] {
        (0..<LIST_ROWS).map { i in
            "Item \(i) — payload \(UInt32(truncatingIfNeeded: i &* 2654435761))"
        }
    }

    private func runLoopHop() async {
        await withCheckedContinuation { c in
            DispatchQueue.main.async { c.resume() }
        }
    }
}
