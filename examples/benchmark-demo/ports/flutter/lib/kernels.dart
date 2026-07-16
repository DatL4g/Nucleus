// 1:1 port of Kernels.kt — see ../../BENCHMARK-SPEC.md. Keep constants byte-identical.
// Pure Dart (no Flutter imports) so `dart run bin/check.dart` can verify it on the VM.
//
// Multithreading note: Dart has no shared-memory threads — the *_mt benches use Isolate.run
// with per-chunk buffer copies. That copy cost is part of what the benchmark measures: it is
// the real price of parallelism on this platform.

import 'dart:isolate';
import 'dart:math' as math;
import 'dart:typed_data';

const benchNames = [
  'mandelbrot', 'nbody', 'raytracer', 'matmul', 'blur', 'sha256', 'sieve', 'fft', 'pi',
  'mandelbrot_mt', 'raytracer_mt', 'matmul_mt', 'blur_mt',
];

/// Strided sum over a pixel buffer — anti-DCE sink, identical in every port.
double checksum(Uint32List buf) {
  var cs = 0;
  var k = 0;
  while (k < buf.length) {
    cs += buf[k];
    k += 101;
  }
  return cs.toDouble();
}

/// Same anti-DCE sink for f64 buffers.
double checksumD(Float64List buf) {
  var cs = 0.0;
  var k = 0;
  while (k < buf.length) {
    cs += buf[k];
    k += 101;
  }
  return cs;
}

// ---- Shared deterministic RNG (MMIX LCG). Dart ints are 64-bit wrapping on the VM. ----

class Lcg {
  int _state;
  Lcg(this._state);

  double nextDouble() {
    _state = _state * 6364136223846793005 + 1442695040888963407;
    return (_state >>> 11).toDouble() * (1.0 / 9007199254740992.0);
  }

  double range(double lo, double hi) => lo + (hi - lo) * nextDouble();
}

// ---- K1 Mandelbrot ----

class Mandelbrot {
  static const width = 800, height = 800, maxIter = 1000;
  static const centerX = -0.743643887037151, centerY = 0.13182590420533;
  static const spanY = 0.0070;

  static double render(Uint32List argb) {
    _rows(argb, 0);
    return checksum(argb);
  }

  static Future<double> renderMt(Uint32List argb, int threads) async {
    final per = (height + threads - 1) ~/ threads;
    final futures = <Future<(int, Uint32List)>>[];
    for (var t = 0; t < threads; t++) {
      final y0 = t * per;
      final rows = math.min(height, y0 + per) - y0;
      if (rows <= 0) break;
      futures.add(Isolate.run(() {
        final chunk = Uint32List(rows * width);
        _rows(chunk, y0);
        return (y0, chunk);
      }));
    }
    for (final (y0, chunk) in await Future.wait(futures)) {
      argb.setRange(y0 * width, y0 * width + chunk.length, chunk);
    }
    return checksum(argb);
  }

  static void _rows(Uint32List out, int yStart) {
    const halfSpan = spanY * 0.5;
    const minY = centerY - halfSpan;
    const minX = centerX - halfSpan;
    const step = spanY / height;
    final rows = out.length ~/ width;
    var idx = 0;
    for (var r = 0; r < rows; r++) {
      final cy = minY + (yStart + r) * step;
      for (var px = 0; px < width; px++) {
        final cx = minX + px * step;
        var zx = 0.0, zy = 0.0;
        var i = 0;
        while (i < maxIter) {
          final zx2 = zx * zx, zy2 = zy * zy;
          if (zx2 + zy2 > 4.0) break;
          zy = 2.0 * zx * zy + cy;
          zx = zx2 - zy2 + cx;
          i++;
        }
        out[idx++] = _color(i);
      }
    }
  }

  static int _color(int i) {
    if (i >= maxIter) return 0xFF000000;
    final t = i / maxIter;
    final r = (9 * (1 - t) * t * t * t * 255).toInt().clamp(0, 255);
    final g = (15 * (1 - t) * (1 - t) * t * t * 255).toInt().clamp(0, 255);
    final b = (8.5 * (1 - t) * (1 - t) * (1 - t) * t * 255).toInt().clamp(0, 255);
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }
}

// ---- K2 N-body ----

class NBody {
  static const n = 1500, steps = 120;
  static const dt = 0.001, g = 1.0, softening2 = 0.05;
  static const seed = 0x9E3779B97F4A7C15;

  final x = Float64List(n), y = Float64List(n), z = Float64List(n);
  final vx = Float64List(n), vy = Float64List(n), vz = Float64List(n);
  final mass = Float64List(n);

  void reset() {
    final rng = Lcg(seed);
    for (var i = 0; i < n; i++) {
      x[i] = rng.range(-1, 1);
      y[i] = rng.range(-1, 1);
      z[i] = rng.range(-1, 1);
      vx[i] = 0;
      vy[i] = 0;
      vz[i] = 0;
      mass[i] = rng.range(0.5, 1.5);
    }
  }

  double simulate() {
    final ax = Float64List(n), ay = Float64List(n), az = Float64List(n);
    for (var s = 0; s < steps; s++) {
      ax.fillRange(0, n, 0);
      ay.fillRange(0, n, 0);
      az.fillRange(0, n, 0);
      for (var i = 0; i < n; i++) {
        final xi = x[i], yi = y[i], zi = z[i];
        var axi = 0.0, ayi = 0.0, azi = 0.0;
        for (var j = 0; j < n; j++) {
          if (j == i) continue;
          final dx = x[j] - xi, dy = y[j] - yi, dz = z[j] - zi;
          final dist2 = dx * dx + dy * dy + dz * dz + softening2;
          final invDist = 1.0 / math.sqrt(dist2);
          final f = g * mass[j] * invDist * invDist * invDist;
          axi += f * dx;
          ayi += f * dy;
          azi += f * dz;
        }
        ax[i] = axi;
        ay[i] = ayi;
        az[i] = azi;
      }
      for (var i = 0; i < n; i++) {
        vx[i] += ax[i] * dt;
        vy[i] += ay[i] * dt;
        vz[i] += az[i] * dt;
        x[i] += vx[i] * dt;
        y[i] += vy[i] * dt;
        z[i] += vz[i] * dt;
      }
    }
    var cs = 0.0;
    for (var i = 0; i < n; i++) {
      cs += x[i] + y[i] + z[i];
    }
    return cs;
  }
}

// ---- K3 Ray tracer ----

class RayTracer {
  static const width = 600, height = 600, maxDepth = 4;
  static const lightX = 5.0, lightY = 5.0, lightZ = 0.0;

  static double render(Uint32List argb) {
    _rows(argb, 0);
    return checksum(argb);
  }

  static Future<double> renderMt(Uint32List argb, int threads) async {
    final per = (height + threads - 1) ~/ threads;
    final futures = <Future<(int, Uint32List)>>[];
    for (var t = 0; t < threads; t++) {
      final y0 = t * per;
      final rows = math.min(height, y0 + per) - y0;
      if (rows <= 0) break;
      futures.add(Isolate.run(() {
        final chunk = Uint32List(rows * width);
        _rows(chunk, y0);
        return (y0, chunk);
      }));
    }
    for (final (y0, chunk) in await Future.wait(futures)) {
      argb.setRange(y0 * width, y0 * width + chunk.length, chunk);
    }
    return checksum(argb);
  }

  // Scene: 9 spheres (cx, cy, cz, r, colorRGB) — built per call, cheap.
  static List<List<double>> _scene() {
    final s = <List<double>>[];
    for (var gx = -1; gx <= 1; gx++) {
      for (var gz = -1; gz <= 1; gz++) {
        s.add([gx * 1.2, 0.0, -3.0 + gz * 1.2, 0.5, _colorFor(gx, gz).toDouble()]);
      }
    }
    return s;
  }

  static int _colorFor(int gx, int gz) => switch ((gx + gz + 4) % 3) {
        0 => 0xE06C75,
        1 => 0x98C379,
        _ => 0x61AFEF,
      };

  static void _rows(Uint32List out, int yStart) {
    final spheres = _scene();
    const aspect = width / height;
    final rows = out.length ~/ width;
    var idx = 0;
    for (var r = 0; r < rows; r++) {
      final py = yStart + r;
      final sy = 1.0 - 2.0 * (py + 0.5) / height;
      for (var px = 0; px < width; px++) {
        final sx = (2.0 * (px + 0.5) / width - 1.0) * aspect;
        final c = _trace(spheres, 0, 0, 0, sx, sy, -1, 0);
        final cr = (c[0] * 255).toInt().clamp(0, 255);
        final cg = (c[1] * 255).toInt().clamp(0, 255);
        final cb = (c[2] * 255).toInt().clamp(0, 255);
        out[idx++] = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
      }
    }
  }

  static List<double> _trace(List<List<double>> spheres, double ox, double oy, double oz,
      double dxIn, double dyIn, double dzIn, int depth) {
    final inv = 1.0 / math.sqrt(dxIn * dxIn + dyIn * dyIn + dzIn * dzIn);
    final dx = dxIn * inv, dy = dyIn * inv, dz = dzIn * inv;

    var hitT = double.maxFinite;
    List<double>? hit;
    for (final s in spheres) {
      final t = _intersect(ox, oy, oz, dx, dy, dz, s);
      if (t > 1e-4 && t < hitT) {
        hitT = t;
        hit = s;
      }
    }
    var groundT = double.maxFinite;
    if (dy < -1e-6) {
      final t = (-0.5 - oy) / dy;
      if (t > 1e-4 && t < hitT) groundT = t;
    }
    if (hit == null && groundT == double.maxFinite) {
      final t = 0.5 * (dy + 1.0);
      return [0.5 + 0.3 * t, 0.7 * t + 0.2, 0.9 * t + 0.1];
    }

    double px, py, pz, nx, ny, nz, ar, ag, ab, refl;
    if (groundT < hitT) {
      px = ox + dx * groundT;
      py = oy + dy * groundT;
      pz = oz + dz * groundT;
      nx = 0;
      ny = 1;
      nz = 0;
      final checker = (px.floor() + pz.floor()) & 1 == 0;
      final c = checker ? 0.9 : 0.3;
      ar = c;
      ag = c;
      ab = c;
      refl = 0.2;
    } else {
      final s = hit!;
      px = ox + dx * hitT;
      py = oy + dy * hitT;
      pz = oz + dz * hitT;
      final lnx = px - s[0], lny = py - s[1], lnz = pz - s[2];
      final ninv = 1.0 / math.sqrt(lnx * lnx + lny * lny + lnz * lnz);
      nx = lnx * ninv;
      ny = lny * ninv;
      nz = lnz * ninv;
      final color = s[4].toInt();
      ar = ((color >> 16) & 0xFF) / 255.0;
      ag = ((color >> 8) & 0xFF) / 255.0;
      ab = (color & 0xFF) / 255.0;
      refl = 0.5;
    }

    var ldx = lightX - px, ldy = lightY - py, ldz = lightZ - pz;
    final linv = 1.0 / math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz);
    ldx *= linv;
    ldy *= linv;
    ldz *= linv;
    final diff = math.max(0.0, nx * ldx + ny * ldy + nz * ldz);
    const ambient = 0.15;
    final shade = ambient + (1 - ambient) * diff;

    var r = ar * shade, g = ag * shade, b = ab * shade;
    if (depth < maxDepth && refl > 0.0) {
      final dot = dx * nx + dy * ny + dz * nz;
      final rr = _trace(spheres, px, py, pz, dx - 2 * dot * nx, dy - 2 * dot * ny,
          dz - 2 * dot * nz, depth + 1);
      r = r * (1 - refl) + rr[0] * refl;
      g = g * (1 - refl) + rr[1] * refl;
      b = b * (1 - refl) + rr[2] * refl;
    }
    return [r, g, b];
  }

  static double _intersect(
      double ox, double oy, double oz, double dx, double dy, double dz, List<double> s) {
    final ocx = ox - s[0], ocy = oy - s[1], ocz = oz - s[2];
    final b = ocx * dx + ocy * dy + ocz * dz;
    final c = ocx * ocx + ocy * ocy + ocz * ocz - s[3] * s[3];
    final disc = b * b - c;
    if (disc < 0) return double.maxFinite;
    final sq = math.sqrt(disc);
    final t0 = -b - sq;
    if (t0 > 1e-4) return t0;
    final t1 = -b + sq;
    if (t1 > 1e-4) return t1;
    return double.maxFinite;
  }
}

// ---- K4 MatMul (SGEMM-style, f64, N=512, ikj order) ----

class MatMul {
  static const n = 512;

  static void fill(Float64List a, Float64List b) {
    for (var i = 0; i < n; i++) {
      for (var j = 0; j < n; j++) {
        a[i * n + j] = ((i * 31 + j) % 100) * 0.01;
        b[i * n + j] = ((i * 17 + j) % 100) * 0.01;
      }
    }
  }

  static double multiply(Float64List a, Float64List b, Float64List c) {
    c.fillRange(0, c.length, 0);
    _rows(a, b, c, 0);
    return checksumD(c);
  }

  static Future<double> multiplyMt(Float64List a, Float64List b, Float64List c, int threads) async {
    final per = (n + threads - 1) ~/ threads;
    final futures = <Future<(int, Float64List)>>[];
    for (var t = 0; t < threads; t++) {
      final i0 = t * per;
      final rows = math.min(n, i0 + per) - i0;
      if (rows <= 0) break;
      futures.add(Isolate.run(() {
        final chunk = Float64List(rows * n);
        _rows(a, b, chunk, i0);
        return (i0, chunk);
      }));
    }
    c.fillRange(0, c.length, 0);
    for (final (i0, chunk) in await Future.wait(futures)) {
      c.setRange(i0 * n, i0 * n + chunk.length, chunk);
    }
    return checksumD(c);
  }

  static void _rows(Float64List a, Float64List b, Float64List out, int iStart) {
    final rows = out.length ~/ n;
    for (var r = 0; r < rows; r++) {
      final i = iStart + r;
      final ai = i * n;
      final oi = r * n;
      for (var k = 0; k < n; k++) {
        final f = a[ai + k];
        final bk = k * n;
        for (var j = 0; j < n; j++) {
          out[oi + j] += f * b[bk + j];
        }
      }
    }
  }
}

// ---- K5 Gaussian blur (separable, radius 8, 1536×1536 f64) ----

class Blur {
  static const width = 1536, height = 1536, radius = 8;
  static const seed = 0xB1005EED;

  static final Float64List _weights = _buildWeights();

  static Float64List _buildWeights() {
    final w = Float64List(2 * radius + 1);
    const sigma = radius / 2.0;
    var sum = 0.0;
    for (var i = 0; i < w.length; i++) {
      final d = (i - radius).toDouble();
      w[i] = math.exp(-d * d / (2 * sigma * sigma));
      sum += w[i];
    }
    for (var i = 0; i < w.length; i++) {
      w[i] /= sum;
    }
    return w;
  }

  static void reset(Float64List img) {
    final rng = Lcg(seed);
    for (var i = 0; i < img.length; i++) {
      img[i] = rng.nextDouble();
    }
  }

  static double convolve(Float64List img, Float64List tmp) {
    _hRows(img, tmp, 0);
    _vRows(tmp, img, 0);
    return checksumD(img);
  }

  static Future<double> convolveMt(Float64List img, Float64List tmp, int threads) async {
    final per = (height + threads - 1) ~/ threads;

    Future<void> pass(Float64List src, Float64List dst, void Function(Float64List, Float64List, int) rowsFn) async {
      final futures = <Future<(int, Float64List)>>[];
      for (var t = 0; t < threads; t++) {
        final y0 = t * per;
        final rows = math.min(height, y0 + per) - y0;
        if (rows <= 0) break;
        futures.add(Isolate.run(() {
          final chunk = Float64List(rows * width);
          rowsFn(src, chunk, y0);
          return (y0, chunk);
        }));
      }
      for (final (y0, chunk) in await Future.wait(futures)) {
        dst.setRange(y0 * width, y0 * width + chunk.length, chunk);
      }
    }

    await pass(img, tmp, _hRows);
    await pass(tmp, img, _vRows);
    return checksumD(img);
  }

  static void _hRows(Float64List src, Float64List dst, int yStart) {
    final w = _weights;
    final rows = dst.length ~/ width;
    for (var r = 0; r < rows; r++) {
      final row = (yStart + r) * width;
      final orow = r * width;
      for (var x = 0; x < width; x++) {
        var acc = 0.0;
        for (var k = -radius; k <= radius; k++) {
          var xx = x + k;
          if (xx < 0) {
            xx = 0;
          } else if (xx >= width) {
            xx = width - 1;
          }
          acc += src[row + xx] * w[k + radius];
        }
        dst[orow + x] = acc;
      }
    }
  }

  static void _vRows(Float64List src, Float64List dst, int yStart) {
    final w = _weights;
    final rows = dst.length ~/ width;
    for (var r = 0; r < rows; r++) {
      final y = yStart + r;
      final orow = r * width;
      for (var x = 0; x < width; x++) {
        var acc = 0.0;
        for (var k = -radius; k <= radius; k++) {
          var yy = y + k;
          if (yy < 0) {
            yy = 0;
          } else if (yy >= height) {
            yy = height - 1;
          }
          acc += src[yy * width + x] * w[k + radius];
        }
        dst[orow + x] = acc;
      }
    }
  }
}

// ---- K6 SHA-256 (hand-rolled FIPS 180-4, 8 MiB input, u32 via masking) ----

class Sha256 {
  static const bytes = 8 * 1024 * 1024;
  static const seed = 0xFEEDFACECAFEBEEF;
  static const _mask = 0xFFFFFFFF;

  static const List<int> _k = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, //
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];

  static void fill(Uint8List data) {
    var s = seed;
    for (var i = 0; i < data.length; i++) {
      s = s * 6364136223846793005 + 1442695040888963407;
      data[i] = (s >>> 56) & 0xFF;
    }
  }

  static double digest(Uint8List data) {
    final h = Uint32List.fromList(
        [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
    final w = Uint32List(64);
    var off = 0;
    while (off + 64 <= data.length) {
      _block(data, off, w, h);
      off += 64;
    }
    final pad = Uint8List(64);
    pad[0] = 0x80;
    final bits = data.length * 8;
    for (var i = 0; i < 8; i++) {
      pad[56 + i] = (bits >>> (56 - 8 * i)) & 0xFF;
    }
    _block(pad, 0, w, h);
    var cs = 0.0;
    for (final v in h) {
      cs += v.toDouble();
    }
    return cs;
  }

  static int _rotr(int x, int n) => ((x >>> n) | (x << (32 - n))) & _mask;

  static void _block(Uint8List block, int off, Uint32List w, Uint32List h) {
    for (var t = 0; t < 16; t++) {
      final i = off + t * 4;
      w[t] = (block[i] << 24) | (block[i + 1] << 16) | (block[i + 2] << 8) | block[i + 3];
    }
    for (var t = 16; t < 64; t++) {
      final x = w[t - 15], y = w[t - 2];
      final s0 = _rotr(x, 7) ^ _rotr(x, 18) ^ (x >>> 3);
      final s1 = _rotr(y, 17) ^ _rotr(y, 19) ^ (y >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) & _mask;
    }
    var a = h[0], b = h[1], c = h[2], d = h[3];
    var e = h[4], f = h[5], g = h[6], hh = h[7];
    for (var t = 0; t < 64; t++) {
      final s1 = _rotr(e, 6) ^ _rotr(e, 11) ^ _rotr(e, 25);
      final ch = (e & f) ^ (~e & g & _mask);
      final t1 = (hh + s1 + ch + _k[t] + w[t]) & _mask;
      final s0 = _rotr(a, 2) ^ _rotr(a, 13) ^ _rotr(a, 22);
      final maj = (a & b) ^ (a & c) ^ (b & c);
      final t2 = (s0 + maj) & _mask;
      hh = g;
      g = f;
      f = e;
      e = (d + t1) & _mask;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) & _mask;
    }
    h[0] = (h[0] + a) & _mask;
    h[1] = (h[1] + b) & _mask;
    h[2] = (h[2] + c) & _mask;
    h[3] = (h[3] + d) & _mask;
    h[4] = (h[4] + e) & _mask;
    h[5] = (h[5] + f) & _mask;
    h[6] = (h[6] + g) & _mask;
    h[7] = (h[7] + hh) & _mask;
  }
}

// ---- K7 Sieve of Eratosthenes (limit 20,000,000) ----

class Sieve {
  static const limit = 20000000;

  static double count(Uint8List composite) {
    composite.fillRange(0, composite.length, 0);
    var i = 2;
    while (i * i <= limit) {
      if (composite[i] == 0) {
        var j = i * i;
        while (j <= limit) {
          composite[j] = 1;
          j += i;
        }
      }
      i++;
    }
    var count = 0;
    for (var m = 2; m <= limit; m++) {
      if (composite[m] == 0) count++;
    }
    return count.toDouble();
  }
}

// ---- K8 FFT (iterative radix-2 Cooley-Tukey, 2^20 points) ----

class Fft {
  static const logN = 20;
  static const n = 1 << logN;
  static const seed = 0x0123456789ABCDEF;

  static void reset(Float64List re, Float64List im) {
    final rng = Lcg(seed);
    for (var i = 0; i < n; i++) {
      re[i] = rng.range(-1, 1);
      im[i] = 0;
    }
  }

  static double transform(Float64List re, Float64List im) {
    var j = 0;
    for (var i = 1; i < n; i++) {
      var bit = n >> 1;
      while (j & bit != 0) {
        j ^= bit;
        bit >>= 1;
      }
      j |= bit;
      if (i < j) {
        var t = re[i];
        re[i] = re[j];
        re[j] = t;
        t = im[i];
        im[i] = im[j];
        im[j] = t;
      }
    }
    var len = 2;
    while (len <= n) {
      final ang = -2.0 * math.pi / len;
      final wr = math.cos(ang), wi = math.sin(ang);
      var i = 0;
      while (i < n) {
        var cwr = 1.0, cwi = 0.0;
        final half = len >> 1;
        for (var t = 0; t < half; t++) {
          final ur = re[i + t], ui = im[i + t];
          final vr = re[i + t + half] * cwr - im[i + t + half] * cwi;
          final vi = re[i + t + half] * cwi + im[i + t + half] * cwr;
          re[i + t] = ur + vr;
          im[i + t] = ui + vi;
          re[i + t + half] = ur - vr;
          im[i + t + half] = ui - vi;
          final nwr = cwr * wr - cwi * wi;
          cwi = cwr * wi + cwi * wr;
          cwr = nwr;
        }
        i += len;
      }
      len <<= 1;
    }
    var cs = 0.0;
    var s = 0;
    while (s < n) {
      cs += re[s] + im[s];
      s += 101;
    }
    return cs;
  }
}

// ---- Runner (same protocol: 3 warmup + best-of-5) ----

class BenchResult {
  final String name, unit;
  final int threads;
  final int workUnits;
  final double bestSeconds;
  BenchResult(this.name, this.threads, this.unit, this.workUnits, this.bestSeconds);
  double get throughputM => workUnits / bestSeconds / 1e6;

  Map<String, Object> toJson() => {
        'name': name,
        'threads': threads,
        'unit': unit,
        'workUnits': workUnits,
        'bestSeconds': bestSeconds,
        'throughputM': throughputM,
      };
}

Future<BenchResult> _measure(
    String name, int threads, String unit, int workUnits, Future<double> Function() kernel) async {
  var sink = 0.0;
  for (var i = 0; i < 3; i++) {
    sink += await kernel();
  }
  var best = double.maxFinite;
  final sw = Stopwatch();
  for (var i = 0; i < 5; i++) {
    sw
      ..reset()
      ..start();
    sink += await kernel();
    sw.stop();
    final secs = sw.elapsedMicroseconds / 1e6;
    if (secs < best) best = secs;
  }
  if (sink.isNaN) throw StateError('checksum NaN'); // consume the sink → no DCE
  return BenchResult(name, threads, unit, workUnits, best);
}

/// Verifies the port against the cross-language reference values. Throws on mismatch.
void selfCheck() {
  final sieveBuf = Uint8List(Sieve.limit + 1);
  final primes = Sieve.count(sieveBuf);
  if (primes != 1270607.0) throw StateError('sieve self-check failed: $primes');
  final sha = Uint8List(Sha256.bytes);
  Sha256.fill(sha);
  final digestSum = Sha256.digest(sha);
  if (digestSum != 16225487432.0) throw StateError('sha256 cross-check failed: $digestSum');
}

/// Runs one named CPU bench. ST kernels do their work inline (call this from a worker isolate);
/// MT kernels spawn their own chunk isolates.
Future<BenchResult> runBench(String name, int cores) {
  const mandelWork = Mandelbrot.width * Mandelbrot.height;
  const nbodyWork = NBody.n * (NBody.n - 1) * NBody.steps;
  const rayWork = RayTracer.width * RayTracer.height;
  const matmulWork = 2 * MatMul.n * MatMul.n * MatMul.n;
  const blurWork = Blur.width * Blur.height;
  const shaWork = Sha256.bytes;
  const sieveWork = Sieve.limit;
  const fftWork = (Fft.n ~/ 2) * Fft.logN;

  switch (name) {
    case 'mandelbrot':
      final buf = Uint32List(Mandelbrot.width * Mandelbrot.height);
      return _measure(name, 1, 'Mpix/s', mandelWork, () async => Mandelbrot.render(buf));
    case 'nbody':
      final s = NBody();
      return _measure(name, 1, 'M-inter/s', nbodyWork, () async {
        s.reset();
        return s.simulate();
      });
    case 'raytracer':
      final buf = Uint32List(RayTracer.width * RayTracer.height);
      return _measure(name, 1, 'Mrays/s', rayWork, () async => RayTracer.render(buf));
    case 'matmul':
      final a = Float64List(MatMul.n * MatMul.n);
      final b = Float64List(MatMul.n * MatMul.n);
      final c = Float64List(MatMul.n * MatMul.n);
      MatMul.fill(a, b);
      return _measure(name, 1, 'MFLOP/s', matmulWork, () async => MatMul.multiply(a, b, c));
    case 'blur':
      final img = Float64List(Blur.width * Blur.height);
      final tmp = Float64List(Blur.width * Blur.height);
      Blur.reset(img);
      return _measure(name, 1, 'Mpix/s', blurWork, () async => Blur.convolve(img, tmp));
    case 'sha256':
      final data = Uint8List(Sha256.bytes);
      Sha256.fill(data);
      // data[0] mutated per run so the pure digest can't be hoisted/memoized (see spec).
      return _measure(name, 1, 'MB/s', shaWork, () async {
        data[0] = (data[0] + 1) & 0xFF;
        return Sha256.digest(data);
      });
    case 'sieve':
      final buf = Uint8List(Sieve.limit + 1);
      return _measure(name, 1, 'Mn/s', sieveWork, () async => Sieve.count(buf));
    case 'fft':
      final re = Float64List(Fft.n);
      final im = Float64List(Fft.n);
      return _measure(name, 1, 'Mbf/s', fftWork, () async {
        Fft.reset(re, im);
        return Fft.transform(re, im);
      });
    case 'pi':
      return _measure(name, 1, 'Mdig/s', Pi.digits, () async => Pi.compute());
    case 'mandelbrot_mt':
      final buf = Uint32List(Mandelbrot.width * Mandelbrot.height);
      return _measure(name, cores, 'Mpix/s', mandelWork, () => Mandelbrot.renderMt(buf, cores));
    case 'raytracer_mt':
      final buf = Uint32List(RayTracer.width * RayTracer.height);
      return _measure(name, cores, 'Mrays/s', rayWork, () => RayTracer.renderMt(buf, cores));
    case 'matmul_mt':
      final a = Float64List(MatMul.n * MatMul.n);
      final b = Float64List(MatMul.n * MatMul.n);
      final c = Float64List(MatMul.n * MatMul.n);
      MatMul.fill(a, b);
      return _measure(name, cores, 'MFLOP/s', matmulWork, () => MatMul.multiplyMt(a, b, c, cores));
    case 'blur_mt':
      final img = Float64List(Blur.width * Blur.height);
      final tmp = Float64List(Blur.width * Blur.height);
      Blur.reset(img);
      return _measure(name, cores, 'Mpix/s', blurWork, () => Blur.convolveMt(img, tmp, cores));
    default:
      throw ArgumentError('unknown bench: $name');
  }
}

double compositeScore(List<BenchResult> cpu) =>
    math.exp(cpu.map((r) => math.log(r.throughputM)).reduce((a, b) => a + b) / cpu.length);

// ---- K9 π to 10,000 digits (Machin formula, fixed-point bignum base 10^9) ----

class Pi {
  static const digits = 10000;
  static const _base = 1000000000;
  static const _w = digits ~/ 9 + 3;

  static void _divSmall(Int64List a, int d) {
    var rem = 0;
    for (var i = 0; i < _w; i++) {
      final cur = rem * _base + a[i];
      a[i] = cur ~/ d;
      rem = cur % d;
    }
  }

  static void _mulSmall(Int64List a, int m) {
    var carry = 0;
    for (var i = _w - 1; i >= 0; i--) {
      final p = a[i] * m + carry;
      a[i] = p % _base;
      carry = p ~/ _base;
    }
  }

  static void _addInPlace(Int64List a, Int64List b) {
    var carry = 0;
    for (var i = _w - 1; i >= 0; i--) {
      final s = a[i] + b[i] + carry;
      a[i] = s % _base;
      carry = s ~/ _base;
    }
  }

  static void _subInPlace(Int64List a, Int64List b) {
    var borrow = 0;
    for (var i = _w - 1; i >= 0; i--) {
      var s = a[i] - b[i] - borrow;
      if (s < 0) {
        s += _base;
        borrow = 1;
      } else {
        borrow = 0;
      }
      a[i] = s;
    }
  }

  static bool _isZero(Int64List a) {
    for (var i = 0; i < _w; i++) {
      if (a[i] != 0) return false;
    }
    return true;
  }

  static void _arctanInv(Int64List out, int x) {
    final term = Int64List(_w);
    final tmp = Int64List(_w);
    out.fillRange(0, _w, 0);
    term[0] = 1;
    _divSmall(term, x);
    _addInPlace(out, term);
    var n = 1;
    var subtract = true;
    final x2 = x * x;
    while (!_isZero(term)) {
      _divSmall(term, x2);
      n += 2;
      tmp.setRange(0, _w, term);
      _divSmall(tmp, n);
      if (subtract) {
        _subInPlace(out, tmp);
      } else {
        _addInPlace(out, tmp);
      }
      subtract = !subtract;
    }
  }

  /// π = 16·arctan(1/5) − 4·arctan(1/239). Self-verifying; returns a strided word checksum.
  static double compute() {
    final a = Int64List(_w);
    final b = Int64List(_w);
    _arctanInv(a, 5);
    _mulSmall(a, 16);
    _arctanInv(b, 239);
    _mulSmall(b, 4);
    _subInPlace(a, b);
    if (a[0] != 3 || a[1] != 141592653 || a[2] != 589793238) {
      throw StateError('pi self-check failed: ${a[0]}.${a[1]} ${a[2]}');
    }
    var cs = 0.0;
    var k = 0;
    while (k < _w) {
      cs += a[k];
      k += 101;
    }
    return cs;
  }
}
