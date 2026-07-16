# Nucleus cross-runtime benchmark — results

**Date**: 2026-07-16 · **Machine**: Apple M4, 10 cores, AC power, thermally stable
**Toolchains**: Oracle GraalVM 25.1.3+9.1 (Java 25.0.3) · OpenJDK 26.0.1 · Gradle daemon JBR 21
**Protocol**: each variant self-runs its suite (3 discarded warmups + best-of-5 over 13 CPU
kernels, geometric render ramps, a list-load bench). Runs are **strictly sequential**, one app at
a time. Peak RSS sampled during each run. Built-in self-checks pass
everywhere (π(2×10⁷)=1,270,607, SHA-256 digestSum=16225487432, digits of π=3.141592653 589793238).

> Composites are only comparable within an **identical suite**. Every value below comes from the
> same suite and the same machine session.

## Summary (sorted by CPU score)

| # | variant | runtime | CPU | GFX | list (ms) | peak RAM | app size |
|---|---------|---------|----:|----:|----------:|---------:|---------:|
| 1 | **jvm-graal** | GraalVM 25.0.3 JIT + ProGuard | **146.7** | 13041 | 21.0 | 695 MB | 160 MB |
| 2 | **tauri** | Rust + WebView (WKWebView) | **142.2** | 15013 | 233.0 | **794 MB** † | 5.6 MB ‡ |
| 3 | **jvm-c2-pg** | OpenJDK 26 C2 + ProGuard | **140.4** | 13041 | 20.9 | 616 MB | 119 MB |
| 4 | **swiftui** | Swift/LLVM + SwiftUI | **138.7** | 15254 | 14.4 | 268 MB | 0.34 MB ‡ |
| 5 | **jvm-c2** | OpenJDK 26 C2 (no ProGuard) | **138.5** | 13041 | 21.6 | 620 MB | 136 MB |
| 6 | **aot-o3-pgo** | GraalVM Native O3 + PGO | **132.7** | 13859 | 17.1 | 269 MB | 129 MB |
| 7 | **aot-o3** | GraalVM Native O3 (ML-inferred) | **100.8** | 13859 | 17.1 | 297 MB | 174 MB |
| 8 | **aot-o2** | GraalVM Native level 2 | **86.9** | 11239 | 17.2 | 278 MB | 150 MB |
| 9 | **aot-os** | GraalVM Native size-opt (-Os) | **80.1** | 10433 | 17.3 | 326 MB | 129 MB |
| 10 | **flutter** | Dart AOT + Impeller | **57.3** | 15133 | 17.2 | **196 MB** | **37 MB** |

CPU/GFX: **higher is better**. list & peak RAM & app size: **lower is better**.

### † Tauri RAM — the WebView pitfall (multi-process)

Tauri/WKWebView is **multi-process** on macOS. The canvas and JS run in separate WebKit
processes (`com.apple.WebKit.WebContent`, `.GPU`, `.Networking`), not in the `benchmark-demo`
binary. The main-process RSS alone is **177 MB** — but the real footprint, summing the WebKit
process tree, is **794 MB**, which makes Tauri **the heaviest variant in RAM**, not the lightest.
(Summing RSS slightly overcounts shared `WebKit.framework` pages, so 794 MB is an upper bound, but
the 177→794 gap is large and real.)

### ‡ App size — self-contained vs system runtime (apples to oranges)

- **Self-contained** (ship their own runtime): JVM (jlink), GraalVM native, Flutter. The listed
  weight is the full `.app` (runtime + Skiko/dylibs + jars/binary).
- **System runtime**: swiftui relies on the OS's SwiftUI/AppKit; tauri on the OS's WebKit. Their
  binaries (0.34 / 5.6 MB) **exclude** the engine macOS provides and shares across all apps. Unlike
  Electron (bundled Chromium ~150 MB), Tauri ships nothing — but pays for it in RAM at runtime (†).

## Per-kernel CPU detail (throughput, higher is better)

| kernel | jvm-graal | tauri | jvm-c2-pg | swiftui | jvm-c2 | aot-o3-pgo | aot-o3 | aot-o2 | aot-os | flutter |
|--------|----:|----:|----:|----:|----:|----:|----:|----:|----:|----:|
| mandelbrot     | 4.09 | 4.13 | 4.33 | 3.92 | 3.99 | 1.98 | 3.32 | 1.97 | 3.27 | 3.61 |
| nbody          | 700.6 | 693.2 | 639.6 | 659.4 | 691.3 | 694.7 | 683.0 | 654.8 | 678.3 | 608.7 |
| raytracer      | 17.2 | 19.8 | 18.9 | 19.8 | 19.0 | 17.5 | 15.8 | 14.0 | 7.0 | 4.2 |
| matmul         | 14217 | 16224 | 16690 | 16215 | 16724 | 12225 | 3877 | 3244 | 3105 | 3054 |
| blur           | 93.4 | 44.5 | 59.7 | 80.7 | 49.2 | 91.5 | 49.5 | 46.6 | 36.5 | 30.0 |
| sha256         | 332.9 | 442.1 | 361.2 | 360.3 | 361.7 | 326.9 | 296.8 | 294.9 | 268.2 | 192.4 |
| sieve          | 708.5 | 642.3 | 565.9 | 603.6 | 599.9 | 725.9 | 467.2 | 463.2 | 484.4 | 268.6 |
| fft            | 407.8 | 452.7 | 441.5 | 436.9 | 441.1 | 363.9 | 369.6 | 376.3 | 356.8 | 366.9 |
| pi             | 0.09 | 0.11 | 0.11 | 0.11 | 0.10 | 0.10 | 0.09 | 0.09 | 0.09 | 0.08 |
| mandelbrot_mt  | 15.5 | 16.5 | 15.6 | 16.1 | 16.1 | 14.5 | 14.1 | 7.5 | 12.2 | 10.6 |
| raytracer_mt   | 74.5 | 81.2 | 68.6 | 75.0 | 67.2 | 71.4 | 60.2 | 49.9 | 25.5 | 13.7 |
| matmul_mt      | 62088 | 69442 | 64038 | 33013 | 63925 | 60113 | 19988 | 17255 | 15956 | 9731 |
| blur_mt        | 345.0 | 188.4 | 244.1 | 261.7 | 238.3 | 285.9 | 203.5 | 194.4 | 155.1 | 40.0 |

## Render ramps (max sustained ≥ 55 fps, higher is better)

| variant | particles | stars | texts |
|---------|----:|----:|----:|
| swiftui    | 125000 | 18175 | 3906 |
| aot-o3 / o3-pgo | 75000 | 14540 | 6102 |
| jvm (×3)   | 50000 | 18175 | 6102 |
| flutter    | 50000 | 18175 | 9533 |
| aot-o2     | 50000 | 11632 | 6102 |
| aot-os     | 50000 | 9306 | 6102 |
| tauri      | 25000 | 18175 | 18618 |

## Key takeaways

- **Raw CPU**: Graal JIT leads (146.7). The LLVM baselines (tauri/rust 142, swiftui 138) and C2
  (138–140) are bunched together. GraalVM native only catches the JVM/LLVM pack **with PGO**
  (100.8 → 132.7); without a profile `matmul` collapses (3877 vs 12225). Os/O2 trail far behind.
- **Graal compiler signature** (JIT and AOT+PGO alike): strong `blur`/`sieve`, dull `fft`/`sha`.
  The known PGO regression on `mandelbrot` reproduces (1.98 with profile vs 3.32 without) — expected.
- **Lowest CPU = Flutter** (57.3, Dart AOT): `raytracer` ÷4, weak `matmul`/`sha`/`sieve` — but it
  compensates with top-tier rendering (GFX 15133).
- **RAM**: **Flutter is the leanest (196 MB)**, ahead of swiftui (268) and GraalVM native (269–326).
  The JVMs sit at 616–695 MB. **Tauri is the worst (794 MB)** once the WebKit tree is counted.
- **Size**: **Flutter is the lightest self-contained bundle (37 MB)** — Impeller is compact vs the
  JVM jlink runtime (~78 MB) and the GraalVM native binary. PGO shrinks the native image (aot-o3
  174 → aot-o3-pgo 129 MB). swiftui/tauri look "tiny" (0.3 / 5.6 MB) but rely on the system
  runtime, so they are not comparable (‡).
- **List / startup**: native ~17 ms, JVM ~21 ms, swiftui **14 ms** (best). **Tauri 233 ms**
  (WebView DOM/canvas list rendering — an order of magnitude slower).
- **Sweet spot**: **aot-o3-pgo** — near-JVM CPU (132.7), **2.5× lower RAM** (269 MB), compact `.app`
  (129 MB), fast startup. The cost: the PGO flow (instrumented build → GUI run → rebuild).

## Flutter — measured (Dart AOT + Impeller)

CPU **57.3** (the lowest — the Flutter signature: `raytracer` ÷4 at 4.2, weak `matmul`/`sha`/`sieve`),
but excellent rendering (GFX **15133**, on par with the top pack) and a lean footprint: **RAM 196 MB**,
**`.app` 37 MB** — the lightest of the self-contained runtimes (Impeller is compact vs the JVM jlink
runtime ~78 MB / the GraalVM native binary). Fast startup/list (17 ms).

> Build unblocked after fixing the Xcode/CLT mismatch (`xcodebuild -runFirstLaunch`). The port is
> compliant: `app-sandbox=false` entitlements (pitfall #5), top-level isolates (pitfall #6).

## Reproduce

```bash
cd examples/benchmark-demo
# JVM + native matrix (homogeneous RAM):
JAVA_HOME=<jbr-21> GRAALVM_HOME=<graalvm-25> JDK_C2_HOME=<jdk-26> \
  ONLY="jvm-c2 jvm-c2-pg jvm-graal aot-os aot-o2 aot-o3 aot-o3-pgo" ./run-all.sh
# ports:
ONLY="swiftui tauri flutter" ./run-all.sh
```

Toolchain note: native builds **must** run with `JAVA_HOME=<graalvm>` (current JVM = GraalVM).
Otherwise a stale Gradle-auto-provisioned Oracle JDK 25 (no `native-image`) also matches the
`vendor=ORACLE/version=25` spec and gets selected, failing the compile.

## Appendix — full precise results per benchmark

Exact figures from each variant's result JSON. `throughputM` = work units / best-of-5 seconds;
`best (s)` = fastest of 5 timed runs after 3 discarded warmups. Higher throughput / lower
`best (s)` is better. Render ramps = max sustained ≥ 55 fps; `list_load` in ms (lower better).

### jvm-graal — JVM JIT · Java HotSpot(TM) 64-Bit Server VM 25.0.3

- **compositeCpuScore**: 146.655379
- **compositeGraphicsScore**: 13041.439755
- **peak RAM**: 694.6 MB
- **app size**: 160.0 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.156315 | 4.0943 |
| nbody | 1 | M-inter/s | 269820000 | 0.385103 | 700.6440 |
| raytracer | 1 | Mrays/s | 360000 | 0.020985 | 17.1549 |
| matmul | 1 | MFLOP/s | 268435456 | 0.018881 | 14216.9126 |
| blur | 1 | Mpix/s | 2359296 | 0.025275 | 93.3454 |
| sha256 | 1 | MB/s | 8388608 | 0.025196 | 332.9325 |
| sieve | 1 | Mn/s | 20000000 | 0.028229 | 708.4934 |
| fft | 1 | Mbf/s | 10485760 | 0.025716 | 407.7530 |
| pi | 1 | Mdig/s | 10000 | 0.106763 | 0.0937 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.041376 | 15.4679 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.004833 | 74.4828 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.004324 | 62087.5346 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.006840 | 344.9473 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 50000 | particles |
| max_stars_55fps | 18175 | stars |
| max_texts_55fps | 6102 | texts |
| list_load | 20.9833 | ms |

### tauri — Rust · rustc release (LLVM -O3) + WebView

- **compositeCpuScore**: 142.163865
- **compositeGraphicsScore**: 15013.057519
- **peak RAM**: 177.4 MB (main process only; main + WebKit tree = 794.2 MB)
- **app size**: None MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.155117 | 4.1259 |
| nbody | 1 | M-inter/s | 269820000 | 0.389261 | 693.1603 |
| raytracer | 1 | Mrays/s | 360000 | 0.018209 | 19.7706 |
| matmul | 1 | MFLOP/s | 268435456 | 0.016546 | 16223.7901 |
| blur | 1 | Mpix/s | 2359296 | 0.053047 | 44.4755 |
| sha256 | 1 | MB/s | 8388608 | 0.018975 | 442.0757 |
| sieve | 1 | Mn/s | 20000000 | 0.031138 | 642.3003 |
| fft | 1 | Mbf/s | 10485760 | 0.023162 | 452.7107 |
| pi | 1 | Mdig/s | 10000 | 0.094982 | 0.1053 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.038742 | 16.5195 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.004433 | 81.2038 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.003866 | 69441.6701 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.012525 | 188.3688 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 25000 | particles |
| max_stars_55fps | 18175 | stars |
| max_texts_55fps | 18618 | texts |
| list_load | 233.0000 | ms |

### jvm-c2-pg — JVM JIT · OpenJDK 64-Bit Server VM 26.0.1

- **compositeCpuScore**: 140.430827
- **compositeGraphicsScore**: 13041.439755
- **peak RAM**: 616.0 MB
- **app size**: 118.7 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.147816 | 4.3297 |
| nbody | 1 | M-inter/s | 269820000 | 0.421856 | 639.6022 |
| raytracer | 1 | Mrays/s | 360000 | 0.019007 | 18.9404 |
| matmul | 1 | MFLOP/s | 268435456 | 0.016083 | 16690.3739 |
| blur | 1 | Mpix/s | 2359296 | 0.039527 | 59.6887 |
| sha256 | 1 | MB/s | 8388608 | 0.023226 | 361.1667 |
| sieve | 1 | Mn/s | 20000000 | 0.035340 | 565.9323 |
| fft | 1 | Mbf/s | 10485760 | 0.023753 | 441.4491 |
| pi | 1 | Mdig/s | 10000 | 0.095154 | 0.1051 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.041129 | 15.5609 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.005252 | 68.5518 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.004192 | 64038.3530 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.009667 | 244.0504 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 50000 | particles |
| max_stars_55fps | 18175 | stars |
| max_texts_55fps | 6102 | texts |
| list_load | 20.9453 | ms |

### swiftui — Swift · LLVM (release) + SwiftUI

- **compositeCpuScore**: 138.702862
- **compositeGraphicsScore**: 15254.297812
- **peak RAM**: 268.0 MB
- **app size**: 0.34 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.163394 | 3.9169 |
| nbody | 1 | M-inter/s | 269820000 | 0.409196 | 659.3904 |
| raytracer | 1 | Mrays/s | 360000 | 0.018188 | 19.7936 |
| matmul | 1 | MFLOP/s | 268435456 | 0.016555 | 16214.6027 |
| blur | 1 | Mpix/s | 2359296 | 0.029236 | 80.6996 |
| sha256 | 1 | MB/s | 8388608 | 0.023286 | 360.2464 |
| sieve | 1 | Mn/s | 20000000 | 0.033135 | 603.5990 |
| fft | 1 | Mbf/s | 10485760 | 0.024002 | 436.8695 |
| pi | 1 | Mdig/s | 10000 | 0.094312 | 0.1060 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.039704 | 16.1191 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.004803 | 74.9532 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.008131 | 33012.6490 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.009016 | 261.6824 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 125000 | particles |
| max_stars_55fps | 18175 | stars |
| max_texts_55fps | 3906 | texts |
| list_load | 14.3990 | ms |

### jvm-c2 — JVM JIT · OpenJDK 64-Bit Server VM 26.0.1

- **compositeCpuScore**: 138.478073
- **compositeGraphicsScore**: 13041.439755
- **peak RAM**: 620.5 MB
- **app size**: 136.0 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.160456 | 3.9886 |
| nbody | 1 | M-inter/s | 269820000 | 0.390313 | 691.2916 |
| raytracer | 1 | Mrays/s | 360000 | 0.018904 | 19.0432 |
| matmul | 1 | MFLOP/s | 268435456 | 0.016050 | 16724.4733 |
| blur | 1 | Mpix/s | 2359296 | 0.048007 | 49.1453 |
| sha256 | 1 | MB/s | 8388608 | 0.023191 | 361.7202 |
| sieve | 1 | Mn/s | 20000000 | 0.033337 | 599.9415 |
| fft | 1 | Mbf/s | 10485760 | 0.023770 | 441.1280 |
| pi | 1 | Mdig/s | 10000 | 0.098784 | 0.1012 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.039792 | 16.0837 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.005357 | 67.2028 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.004199 | 63924.6189 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.009901 | 238.2817 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 50000 | particles |
| max_stars_55fps | 18175 | stars |
| max_texts_55fps | 6102 | texts |
| list_load | 21.5753 | ms |

### aot-o3-pgo — GraalVM Native Image (AOT · -O3)

- **compositeCpuScore**: 132.689085
- **compositeGraphicsScore**: 13858.597710
- **peak RAM**: 269.1 MB
- **app size**: 128.8 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.322584 | 1.9840 |
| nbody | 1 | M-inter/s | 269820000 | 0.388390 | 694.7147 |
| raytracer | 1 | Mrays/s | 360000 | 0.020630 | 17.4499 |
| matmul | 1 | MFLOP/s | 268435456 | 0.021958 | 12224.7648 |
| blur | 1 | Mpix/s | 2359296 | 0.025793 | 91.4702 |
| sha256 | 1 | MB/s | 8388608 | 0.025664 | 326.8687 |
| sieve | 1 | Mn/s | 20000000 | 0.027551 | 725.9308 |
| fft | 1 | Mbf/s | 10485760 | 0.028814 | 363.9125 |
| pi | 1 | Mdig/s | 10000 | 0.104356 | 0.0958 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.044230 | 14.4699 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.005040 | 71.4286 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.004465 | 60113.1914 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.008253 | 285.8555 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 75000 | particles |
| max_stars_55fps | 14540 | stars |
| max_texts_55fps | 6102 | texts |
| list_load | 17.0770 | ms |

### aot-o3 — GraalVM Native Image (AOT · -O3)

- **compositeCpuScore**: 100.834766
- **compositeGraphicsScore**: 13858.597710
- **peak RAM**: 297.4 MB
- **app size**: 174.1 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.192831 | 3.3190 |
| nbody | 1 | M-inter/s | 269820000 | 0.395042 | 683.0152 |
| raytracer | 1 | Mrays/s | 360000 | 0.022760 | 15.8175 |
| matmul | 1 | MFLOP/s | 268435456 | 0.069240 | 3876.8585 |
| blur | 1 | Mpix/s | 2359296 | 0.047641 | 49.5220 |
| sha256 | 1 | MB/s | 8388608 | 0.028266 | 296.7694 |
| sieve | 1 | Mn/s | 20000000 | 0.042812 | 467.1560 |
| fft | 1 | Mbf/s | 10485760 | 0.028371 | 369.5889 |
| pi | 1 | Mdig/s | 10000 | 0.108895 | 0.0918 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.045528 | 14.0574 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.005984 | 60.1587 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.013430 | 19988.3672 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.011595 | 203.4760 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 75000 | particles |
| max_stars_55fps | 14540 | stars |
| max_texts_55fps | 6102 | texts |
| list_load | 17.1333 | ms |

### aot-o2 — GraalVM Native Image (AOT · -O3)

- **compositeCpuScore**: 86.900577
- **compositeGraphicsScore**: 11238.772087
- **peak RAM**: 277.8 MB
- **app size**: 150.4 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.324075 | 1.9748 |
| nbody | 1 | M-inter/s | 269820000 | 0.412086 | 654.7660 |
| raytracer | 1 | Mrays/s | 360000 | 0.025674 | 14.0217 |
| matmul | 1 | MFLOP/s | 268435456 | 0.082750 | 3243.9346 |
| blur | 1 | Mpix/s | 2359296 | 0.050603 | 46.6239 |
| sha256 | 1 | MB/s | 8388608 | 0.028442 | 294.9395 |
| sieve | 1 | Mn/s | 20000000 | 0.043174 | 463.2409 |
| fft | 1 | Mbf/s | 10485760 | 0.027863 | 376.3390 |
| pi | 1 | Mdig/s | 10000 | 0.109629 | 0.0912 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.085440 | 7.4907 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.007218 | 49.8762 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.015557 | 17255.0094 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.012137 | 194.3887 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 50000 | particles |
| max_stars_55fps | 11632 | stars |
| max_texts_55fps | 6102 | texts |
| list_load | 17.1505 | ms |

### aot-os — GraalVM Native Image (AOT · -O3)

- **compositeCpuScore**: 80.142345
- **compositeGraphicsScore**: 10433.301291
- **peak RAM**: 326.3 MB
- **app size**: 128.6 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.195958 | 3.2660 |
| nbody | 1 | M-inter/s | 269820000 | 0.397765 | 678.3397 |
| raytracer | 1 | Mrays/s | 360000 | 0.051151 | 7.0380 |
| matmul | 1 | MFLOP/s | 268435456 | 0.086459 | 3104.7687 |
| blur | 1 | Mpix/s | 2359296 | 0.064703 | 36.4635 |
| sha256 | 1 | MB/s | 8388608 | 0.031281 | 268.1676 |
| sieve | 1 | Mn/s | 20000000 | 0.041287 | 484.4150 |
| fft | 1 | Mbf/s | 10485760 | 0.029392 | 356.7515 |
| pi | 1 | Mdig/s | 10000 | 0.112193 | 0.0891 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.052372 | 12.2204 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.014111 | 25.5113 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.016823 | 15956.4169 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.015211 | 155.1076 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 50000 | particles |
| max_stars_55fps | 9306 | stars |
| max_texts_55fps | 6102 | texts |
| list_load | 17.2805 | ms |

### flutter — Dart AOT (Flutter release)

- **compositeCpuScore**: 57.272817
- **compositeGraphicsScore**: 15132.522770
- **peak RAM**: 196.4 MB
- **app size**: 37.2 MB

| kernel | threads | unit | work units | best (s) | throughput |
|--------|--------:|------|-----------:|---------:|-----------:|
| mandelbrot | 1 | Mpix/s | 640000 | 0.177531 | 3.6050 |
| nbody | 1 | M-inter/s | 269820000 | 0.443292 | 608.6733 |
| raytracer | 1 | Mrays/s | 360000 | 0.085356 | 4.2176 |
| matmul | 1 | MFLOP/s | 268435456 | 0.087902 | 3053.8037 |
| blur | 1 | Mpix/s | 2359296 | 0.078605 | 30.0146 |
| sha256 | 1 | MB/s | 8388608 | 0.043607 | 192.3684 |
| sieve | 1 | Mn/s | 20000000 | 0.074473 | 268.5537 |
| fft | 1 | Mbf/s | 10485760 | 0.028581 | 366.8787 |
| pi | 1 | Mdig/s | 10000 | 0.126447 | 0.0791 |
| mandelbrot_mt | 10 | Mpix/s | 640000 | 0.060647 | 10.5529 |
| raytracer_mt | 10 | Mrays/s | 360000 | 0.026344 | 13.6654 |
| matmul_mt | 10 | MFLOP/s | 268435456 | 0.027587 | 9730.5055 |
| blur_mt | 10 | Mpix/s | 2359296 | 0.059005 | 39.9847 |

| render / list metric | value | unit |
|----------------------|------:|------|
| max_particles_55fps | 50000 | particles |
| max_stars_55fps | 18175 | stars |
| max_texts_55fps | 9533 | texts |
| list_load | 17.2290 | ms |

