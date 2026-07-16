# Agent playbook — Nucleus cross-runtime benchmark

Instructions pour tout agent (Claude, Codex, Cursor…) chargé d'exécuter, étendre ou analyser ce
benchmark : une suite Geekbench-style qui compare 5 runtimes desktop (JVM, GraalVM natif,
SwiftUI, Tauri/Rust, Flutter/Dart) sur 13 benchs CPU + 3 rampes de rendu + un bench de liste.

## Architecture

- `BENCHMARK-SPEC.md` = **LE CONTRAT**. Chaque port doit faire un travail bit-identique
  (constantes, seeds LCG, protocole). Toute modification de kernel se fait dans les
  5 implémentations + la spec, jamais dans une seule.
- Référence Kotlin : `src/main/kotlin/benchmarkdemo/{Kernels,BenchmarkRunner,Main}.kt`
- Ports : `ports/swiftui/Sources/BenchmarkDemo/`, `ports/tauri/src-tauri/src/kernels.rs`
  (+ `ports/tauri/src/index.html` pour l'UI), `ports/flutter/lib/{kernels,main}.dart`
- Résultats : chaque app auto-exécute sa suite au lancement et écrit
  `~/nucleus-benchmarks/<id>.json` (`jvm|graalvm|swiftui|tauri|flutter`) à la fin.
  La complétion d'un run se détecte par le **mtime de ce fichier**, jamais par le process.
- Orchestrateur : `./run-all.sh` (variables `COOLDOWN`, `ONLY`) — builds d'abord, runs
  séquentiels, RSS max échantillonné, thermique loggé, archivage dans `results/<ts>/`.

## Les 9 variantes

| variante | commande |
|---|---|
| jvm-c2 | `./gradlew :examples:benchmark-demo:run` |
| jvm-graal | `./gradlew :examples:benchmark-demo:runRelease` (GraalVM JIT + ProGuard) |
| aot-os / o2 / o3 / o3-pgo | `nativeImageCompile` + `-Popt=s|2|(rien)` et `-Ppgo=off|(auto)` |
| swiftui | `swift build -c release` puis `.build/release/BenchmarkDemo` |
| tauri | `cargo build --release` puis `target/release/benchmark-demo` |
| flutter | `flutter build macos --release` puis le binaire dans le .app |

## Règles de build non négociables

- **GraalVM natif : TOUJOURS `--no-configuration-cache --rerun`.** Le configuration cache
  Gradle sert des builds fantômes (BUILD SUCCESSFUL en 2 s sans recompiler) quand
  `-Ppgo`/`-Popt` changent. Vérifier la ligne `Graal compiler: optimization level: X,
  target machine: Y, PGO: Z` dans la sortie — seule preuve de la config réellement compilée.
- Chaque build natif écrase `build/compose/tmp/main/graalvm/nativeCompile/` — pour comparer
  des variantes, copier le **dossier entier** (binaire + dylibs AWT), jamais le binaire seul.
- Flow PGO : build `-Ppgo=instrument` → lancer le binaire avec cwd = `pgo/` → fermer la
  fenêtre à « Done » → `default.iprof` s'écrit à la sortie du process → rebuild (le profil
  est auto-détecté). Entraîner en **GUI** (pas headless) pour couvrir les chemins de rendu.
- `march = "native"` dans le DSL graalvm : parité ISA avec ce que Swift/Rust expédient sur
  macOS ARM. `compatibility` = ARMv8.0 nu, ~-15 % de composite.

## Protocole de mesure

- **Jamais deux apps en même temps** : les rampes de rendu se disputent le compositeur et
  produisent des zéros. Séquentiel strict, cooldown entre runs si la machine throttle.
- Kernels CPU : 3 warmups jetés + best-of-5. Rampes : 1ʳᵉ fenêtre = warmup, échec confirmé
  sur 2 fenêtres consécutives < 55 fps, croissance géométrique +25 %.
- Self-checks intégrés (l'app crashe si faux) : π(2×10⁷) = 1 270 607 primes,
  digestSum SHA-256 = 16225487432, décimales de π = 3.141592653 589793238.
- Vérif kernels sans GUI : Kotlin `--headless`, Dart `dart run bin/check.dart`,
  Rust standalone `rustc -O` sur `kernels.rs`.

## Les pièges déjà rencontrés (NE PAS les redécouvrir)

1. **DCE** : un compilateur AOT supprime un kernel dont la sortie n'est pas observée
   (Mandelbrot Rust mesuré à 0.000 s). Chaque kernel retourne un checksum consommé par le
   runner (`black_box`/volatile/`isNaN`).
2. **Hoisting** : une fonction pure d'entrée constante est calculée une fois pour les 8 runs
   (sha256 à `inf` MB/s). L'entrée est mutée d'un octet à chaque run.
3. **Canvas asynchrone** : WebKit rasterise derrière rAF → le port JS force la complétion
   avec `getImageData(0,0,1,1)` par frame. Sans ça, les scores WebView sont gonflés ×10.
4. **Allocations dans la boucle de rendu** : jamais de liste boxée par frame (Compose
   mesurait son GC). FloatArray plat + API native.
5. **Sandbox macOS** : les entitlements Flutter doivent garder `app-sandbox = false`, sinon
   le JSON part dans `~/Library/Containers/.../nucleus-benchmarks/`.
6. **Isolates Dart** : les entrypoints doivent être top-level (`compute`) — une closure de
   méthode State embarque le widget tree (« object is unsendable », app figée à 0/13).
7. **Régression PGO connue** : mandelbrot fait ~1.9 avec profil vs ~3.3 sans. Reproductible,
   documenté — ne pas le « corriger ».
8. **`pkill -f`** : ne jamais utiliser un pattern qui matche la ligne de commande du script
   appelant (auto-kill).

## Analyse des résultats

- Composite CPU = geo-mean des 13 throughputs ; graphics = 1000 × geo-mean des rampes
  normalisées à leur START. Plus haut = mieux ; `list_load` en ms = plus bas mieux.
- Les composites ne sont comparables qu'à suite identique (ajouter un bench change l'échelle).
- Repères mesurés (M-series 10 cœurs, pour détecter une mesure aberrante) :
  jvm-graal ≈ 145, tauri ≈ 142, jvm-c2 ≈ 138, swiftui ≈ 135, aot-o3-pgo ≈ 131,
  aot-o3 ≈ 100, aot-o2 ≈ 86, aot-os ≈ 80, flutter ≈ 63. Graphics : flutter ≈ 17k,
  jvm ≈ 15-16k, graalvm ≈ 14k, tauri ≈ 12k, swiftui ≈ 11k. Tailles d'image : O3 pur 135 MB,
  O3+PGO et Os 89 MB.
- Signature du compilateur Graal (JIT comme AOT+PGO) : blur/sieve forts, fft/sha ternes.
  Flutter : CPU faible (raytracer ÷4), rendu excellent.
- Toujours rapporter la config exacte avec un chiffre (niveau, PGO, march, JVM utilisée —
  le champ `runtimeLabel` du JSON fait foi) et signaler tout écart > 10 % vs les repères.
- Si un chiffre semble trop beau, chercher d'abord le piège de mesure avant de célébrer.
