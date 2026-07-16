// Flutter port of the Nucleus benchmark — see ../../BENCHMARK-SPEC.md.
// Auto-runs on launch: CPU suite (12) → particle ramp → list load → JSON to
// ~/nucleus-benchmarks/flutter.json. Numbers are only meaningful in release (Dart AOT).

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' show PointMode;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import 'kernels.dart';

// Render ramp spec constants — shared across ports (see BENCHMARK-SPEC.md).
const rampWindowS = 1.0;
const rampMinFps = 55.0;
const rampStart = 25000, rampStep = 25000, rampMax = 500000; // particles
const starStart = 200, starStep = 200, starMax = 200000; // rotating alpha stars
const textStart = 500, textStep = 500, textMax = 200000; // drifting labels
const listRows = 50000;

void main() => runApp(const BenchApp());

// Isolate entrypoints MUST be top-level: a closure declared inside a State method shares its
// context with the sibling setState closures and drags the whole widget tree into the isolate
// message ("object is unsendable").
void _selfCheckWorker(void _) => selfCheck();

Future<List<Object>> _benchWorker(List<Object> args) async {
  final br = await runBench(args[0] as String, args[1] as int);
  return [br.name, br.threads, br.unit, br.workUnits, br.bestSeconds];
}

/// Graphics score, Geekbench-style anchor: 1000 × geo-mean of sustained/START per ramp.
double _graphicsScore(int particles, int stars, int texts) =>
    1000.0 *
    math
        .pow(
            (math.max(1, particles) / rampStart) *
                (math.max(1, stars) / starStart) *
                (math.max(1, texts) / textStart),
            1 / 3)
        .toDouble();

class BenchApp extends StatelessWidget {
  const BenchApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Flutter Benchmark — Dart AOT',
        theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2563EB))),
        home: const BenchPage(),
        debugShowCheckedModeBanner: false,
      );
}

class Particles {
  final int capacity;
  int active = rampStart;
  final Float32List x, y, vx, vy;

  Particles(this.capacity)
      : x = Float32List(capacity),
        y = Float32List(capacity),
        vx = Float32List(capacity),
        vy = Float32List(capacity);

  void reset() {
    active = rampStart;
    final rng = Lcg(42);
    for (var i = 0; i < capacity; i++) {
      x[i] = rng.nextDouble();
      y[i] = rng.nextDouble();
      vx[i] = rng.range(-0.2, 0.2);
      vy[i] = rng.range(-0.2, 0.2);
    }
  }

  void step(double dt) {
    final n = active;
    for (var i = 0; i < n; i++) {
      var nx = x[i] + vx[i] * dt;
      var ny = y[i] + vy[i] * dt;
      if (nx < 0) {
        nx = 0;
        vx[i] = -vx[i];
      } else if (nx > 1) {
        nx = 1;
        vx[i] = -vx[i];
      }
      if (ny < 0) {
        ny = 0;
        vy[i] = -vy[i];
      } else if (ny > 1) {
        ny = 1;
        vy[i] = -vy[i];
      }
      x[i] = nx;
      y[i] = ny;
    }
  }
}

/// Rotating-star field for the vector ramp — LCG seed 7.
class Stars {
  int active = starStart;
  double t = 0;
  final x = Float32List(starMax), y = Float32List(starMax), omega = Float32List(starMax);
  void reset() {
    active = starStart;
    t = 0;
    final rng = Lcg(7);
    for (var i = 0; i < starMax; i++) {
      x[i] = rng.nextDouble();
      y[i] = rng.nextDouble();
      omega[i] = rng.range(-2, 2);
    }
  }
}

/// Drifting text field for the glyph ramp — LCG seed 11.
class Texts {
  int active = textStart;
  double t = 0;
  final x = Float32List(textMax), y0 = Float32List(textMax);
  void reset() {
    active = textStart;
    t = 0;
    final rng = Lcg(11);
    for (var i = 0; i < textMax; i++) {
      x[i] = rng.nextDouble();
      y0[i] = rng.nextDouble();
    }
  }
}

class StarPainter extends CustomPainter {
  final Stars stars;
  StarPainter(this.stars, {required Listenable repaint}) : super(repaint: repaint);

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFFEEF0F3));
    final paint = Paint()..color = const Color(0x802563EB);
    for (var i = 0; i < stars.active; i++) {
      final cx = stars.x[i] * size.width;
      final cy = stars.y[i] * size.height;
      final theta = stars.omega[i] * stars.t;
      final path = Path();
      for (var k = 0; k < 12; k++) {
        final r = k % 2 == 0 ? 14.0 : 6.0;
        final a = theta + k * (math.pi / 6);
        final vx = cx + r * math.cos(a);
        final vy = cy + r * math.sin(a);
        if (k == 0) {
          path.moveTo(vx, vy);
        } else {
          path.lineTo(vx, vy);
        }
      }
      path.close();
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant StarPainter oldDelegate) => false;
}

class TextRampPainter extends CustomPainter {
  final Texts texts;
  static final List<TextPainter> _labels = List.generate(100, (i) {
    final tp = TextPainter(
      text: TextSpan(
          text: 'Bench#$i', style: const TextStyle(fontSize: 12, color: Color(0xFF334155))),
      textDirection: TextDirection.ltr,
    )..layout();
    return tp;
  });

  TextRampPainter(this.texts, {required Listenable repaint}) : super(repaint: repaint);

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFFEEF0F3));
    for (var i = 0; i < texts.active; i++) {
      final ty = (texts.y0[i] + 0.03 * texts.t) % 1.0;
      _labels[i % 100].paint(canvas, Offset(texts.x[i] * size.width, ty * size.height));
    }
  }

  @override
  bool shouldRepaint(covariant TextRampPainter oldDelegate) => false;
}

class ParticlePainter extends CustomPainter {
  final Particles particles;
  ParticlePainter(this.particles, {required Listenable repaint}) : super(repaint: repaint);

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFFEEF0F3));
    final n = particles.active;
    final pts = Float32List(n * 2);
    for (var i = 0; i < n; i++) {
      pts[i * 2] = particles.x[i] * size.width;
      pts[i * 2 + 1] = particles.y[i] * size.height;
    }
    final paint = Paint()
      ..color = const Color(0xFF2563EB)
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.square;
    canvas.drawRawPoints(PointMode.points, pts, paint);
  }

  @override
  bool shouldRepaint(covariant ParticlePainter oldDelegate) => false; // repaint listenable drives it
}

class BenchPage extends StatefulWidget {
  const BenchPage({super.key});

  @override
  State<BenchPage> createState() => _BenchPageState();
}

class _BenchPageState extends State<BenchPage> with SingleTickerProviderStateMixin {
  final cores = Platform.numberOfProcessors;
  String phase = 'cpu';
  final cpuResults = <BenchResult>[];
  double? composite;
  int? maxParticles;
  int? maxStars;
  int? maxTexts;
  String rampStatus = '';
  double? listMs;
  String? savedPath;
  List<String> listItems = const [];

  final particles = Particles(rampMax);
  final stars = Stars();
  final texts = Texts();
  final frameTick = ValueNotifier<int>(0);
  Ticker? _ticker;

  @override
  void initState() {
    super.initState();
    _runAll();
  }

  @override
  void dispose() {
    _ticker?.dispose();
    super.dispose();
  }

  Future<void> _runAll() async {
    setState(() {
      cpuResults.clear();
      composite = null;
      maxParticles = null;
      maxStars = null;
      maxTexts = null;
      rampStatus = '';
      listMs = null;
      savedPath = null;
      phase = 'cpu';
    });

    // Self-checks + CPU suite — each bench runs in a worker isolate so the UI stays alive
    // (MT benches spawn their chunk isolates from there).
    await compute(_selfCheckWorker, null);
    final results = <BenchResult>[];
    for (final name in benchNames) {
      final r = await compute(_benchWorker, [name, cores]);
      final br =
          BenchResult(r[0] as String, r[1] as int, r[2] as String, r[3] as int, r[4] as double);
      results.add(br);
      setState(() => cpuResults.add(br));
    }
    setState(() => composite = compositeScore(results));

    // Render ramps on the UI thread — that's the point: they measure the render pipeline.
    setState(() => phase = 'particles');
    particles.reset();
    maxParticles = await _runRamp(
        rampStep, rampMax, 'particles',
        () => particles.active, (v) => particles.active = v, (dt) => particles.step(dt));

    setState(() => phase = 'stars');
    stars.reset();
    maxStars = await _runRamp(
        starStep, starMax, 'stars',
        () => stars.active, (v) => stars.active = v, (dt) => stars.t += dt);

    setState(() => phase = 'texts');
    texts.reset();
    maxTexts = await _runRamp(
        textStep, textMax, 'texts',
        () => texts.active, (v) => texts.active = v, (dt) => texts.t += dt);

    setState(() => phase = 'list');
    listMs = await _runListBench();

    setState(() => phase = 'save');
    savedPath = _writeJson(results);
    setState(() => phase = 'done');
  }

  Future<int> _runRamp(int step, int max, String unit, int Function() getActive,
      void Function(int) setActive, void Function(double) advance) async {
    final done = Completer<int>();
    var sustained = 0;
    var frames = 0;
    var windowStart = -1.0;
    var last = -1.0;
    var warmup = true; // first window absorbs startup jank — never judged
    var failedOnce = false; // failure must be confirmed by a second consecutive window

    _ticker?.dispose();
    _ticker = createTicker((elapsed) {
      final now = elapsed.inMicroseconds / 1e6;
      if (windowStart < 0) {
        windowStart = now;
      } else {
        advance(math.min(now - last, 1.0 / 30.0));
      }
      last = now;
      frames++;
      frameTick.value++;
      if (now - windowStart >= rampWindowS && frames > 1) {
        if (warmup) {
          warmup = false;
          frames = 0;
          windowStart = now;
          return;
        }
        final fps = (frames - 1) / (now - windowStart);
        setState(() => rampStatus = '${getActive()} $unit — ${fps.toStringAsFixed(1)} fps');
        if (fps >= rampMinFps) {
          failedOnce = false;
          sustained = getActive();
          if (getActive() >= max) {
            _ticker!.stop();
            done.complete(sustained);
            return;
          }
          // Geometric growth (+25%/window, min = step) — see BENCHMARK-SPEC.md.
          setActive(math.min(max, getActive() + math.max(step, getActive() ~/ 4)));
          frames = 0;
          windowStart = now;
        } else if (!failedOnce) {
          failedOnce = true; // transient hitch? re-run the same count once
          frames = 0;
          windowStart = now;
        } else {
          _ticker!.stop();
          done.complete(sustained);
        }
      }
    });
    _ticker!.start();
    return done.future;
  }

  Future<double> _runListBench() async {
    var best = double.maxFinite;
    final sw = Stopwatch();
    for (var run = 0; run < 3; run++) {
      setState(() => listItems = const []);
      await WidgetsBinding.instance.endOfFrame;
      await WidgetsBinding.instance.endOfFrame;
      sw
        ..reset()
        ..start();
      setState(() => listItems =
          List.generate(listRows, (i) => 'Item $i — payload ${(i * 2654435761) % 4294967296}'));
      await WidgetsBinding.instance.endOfFrame;
      sw.stop();
      best = math.min(best, sw.elapsedMicroseconds / 1000.0);
    }
    setState(() => listItems = const []);
    return best;
  }

  String _writeJson(List<BenchResult> cpu) {
    final home = Platform.environment['HOME'] ?? Platform.environment['USERPROFILE'] ?? '.';
    final dir = Directory('$home/nucleus-benchmarks')..createSync(recursive: true);
    final file = File('${dir.path}/flutter.json');
    final payload = {
      'schema': 1,
      'runtime': 'flutter',
      'runtimeLabel':
          'Dart ${kReleaseMode ? "AOT (Flutter release)" : "JIT (Flutter debug — invalid numbers!)"}',
      'os': '${Platform.operatingSystem} ${Platform.operatingSystemVersion}',
      'cpus': cores,
      'timestampMs': DateTime.now().millisecondsSinceEpoch,
      'cpu': cpu.map((r) => r.toJson()).toList(),
      'compositeCpuScore': compositeScore(cpu),
      if (maxParticles != null && maxStars != null && maxTexts != null)
        'compositeGraphicsScore': _graphicsScore(maxParticles!, maxStars!, maxTexts!),
      'ui': [
        {
          'name': 'max_particles_55fps',
          'unit': 'particles',
          'value': maxParticles,
          'lowerIsBetter': false
        },
        {'name': 'max_stars_55fps', 'unit': 'stars', 'value': maxStars, 'lowerIsBetter': false},
        {'name': 'max_texts_55fps', 'unit': 'texts', 'value': maxTexts, 'lowerIsBetter': false},
        {'name': 'list_load', 'unit': 'ms', 'value': listMs, 'lowerIsBetter': true},
      ],
    };
    file.writeAsStringSync(const JsonEncoder.withIndent('  ').convert(payload));
    return file.path;
  }

  String get _statusLine => switch (phase) {
        'cpu' => 'Running CPU benchmarks… (${cpuResults.length}/13)',
        'particles' || 'stars' || 'texts' =>
          'Render ramp ($phase) — pushing until fps drops below ${rampMinFps.toInt()}…  $rampStatus',
        'list' => 'List-load benchmark — $listRows rows',
        'save' => 'Saving results…',
        _ => 'Done — JSON: $savedPath',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Flutter Benchmark Suite', style: Theme.of(context).textTheme.headlineMedium),
            Text(
                'Dart ${kReleaseMode ? "AOT (release)" : "JIT (DEBUG — numbers invalid, use --release)"} — $cores cores',
                style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: 8),
            Text(_statusLine, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            if (phase == 'particles')
              SizedBox(
                width: 600,
                height: 400,
                child: CustomPaint(painter: ParticlePainter(particles, repaint: frameTick)),
              ),
            if (phase == 'stars')
              SizedBox(
                width: 600,
                height: 400,
                child: CustomPaint(painter: StarPainter(stars, repaint: frameTick)),
              ),
            if (phase == 'texts')
              SizedBox(
                width: 600,
                height: 400,
                child: CustomPaint(painter: TextRampPainter(texts, repaint: frameTick)),
              ),
            if (phase == 'list')
              SizedBox(
                height: 300,
                child: ListView.builder(
                  itemCount: listItems.length,
                  itemBuilder: (_, i) => Text(listItems[i]),
                ),
              ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (final r in cpuResults)
                      Row(children: [
                        SizedBox(width: 150, child: Text(r.name)),
                        SizedBox(width: 50, child: Text('×${r.threads}')),
                        SizedBox(
                            width: 180,
                            child: Text('${r.throughputM.toStringAsFixed(2)} ${r.unit}')),
                        Text('best ${r.bestSeconds.toStringAsFixed(3)}s'),
                      ]),
                    if (composite != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: Text('Composite CPU score: ${composite!.toStringAsFixed(1)}',
                            style: Theme.of(context).textTheme.titleLarge),
                      ),
                    if (maxParticles != null)
                      Text('Particles: $maxParticles sustained @ ≥${rampMinFps.toInt()} fps'),
                    if (maxStars != null)
                      Text('Stars: $maxStars sustained @ ≥${rampMinFps.toInt()} fps'),
                    if (maxTexts != null)
                      Text('Texts: $maxTexts sustained @ ≥${rampMinFps.toInt()} fps'),
                    if (maxParticles != null && maxStars != null && maxTexts != null)
                      Text(
                          'Graphics score: ${_graphicsScore(maxParticles!, maxStars!, maxTexts!).toStringAsFixed(0)}',
                          style: Theme.of(context).textTheme.titleLarge),
                    if (listMs != null)
                      Text('List load: ${listMs!.toStringAsFixed(1)} ms ($listRows rows, best of 3)'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: phase == 'done' ? _runAll : null,
              child: Text(phase == 'done' ? 'Run again' : 'Running…'),
            ),
          ],
        ),
      ),
    );
  }
}
