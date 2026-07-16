//! 1:1 port of Kernels.kt — see ../../../BENCHMARK-SPEC.md. Pure std (no serde/tauri) so it
//! compiles standalone with `rustc -O` for verification. rustc release == LLVM -O3, the fair
//! AOT comparison against GraalVM -O3.

use std::time::Instant;

/// Strided sum over a pixel buffer — a cheap sink so the render (compute + stores) can't be
/// optimised away as dead code. Without it, `-O3 + LTO` deletes the whole kernel (0.000s).
/// MUST exist in every port (see BENCHMARK-SPEC.md).
pub fn checksum(buf: &[u32]) -> f64 {
    let mut cs: i64 = 0;
    let mut k = 0;
    while k < buf.len() {
        cs = cs.wrapping_add(buf[k] as i64);
        k += 101;
    }
    cs as f64
}

/// Same anti-DCE sink for f64 buffers.
pub fn checksum_d(buf: &[f64]) -> f64 {
    let mut cs = 0.0;
    let mut k = 0;
    while k < buf.len() {
        cs += buf[k];
        k += 101;
    }
    cs
}

pub fn cores() -> usize {
    std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4)
}

pub const BENCH_NAMES: [&str; 13] = [
    "mandelbrot", "nbody", "raytracer", "matmul", "blur", "sha256", "sieve", "fft",
    "pi",
    "mandelbrot_mt", "raytracer_mt", "matmul_mt", "blur_mt",
];

// ---- Shared deterministic RNG (MMIX LCG) ----

pub struct Lcg {
    state: u64,
}
impl Lcg {
    pub fn new(seed: u64) -> Self {
        Lcg { state: seed }
    }
    pub fn next_double(&mut self) -> f64 {
        self.state = self
            .state
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        (self.state >> 11) as f64 * (1.0 / 9007199254740992.0)
    }
    pub fn range(&mut self, lo: f64, hi: f64) -> f64 {
        lo + (hi - lo) * self.next_double()
    }
}

// ---- K1 Mandelbrot ----

pub mod mandelbrot {
    pub const WIDTH: usize = 800;
    pub const HEIGHT: usize = 800;
    pub const MAX_ITER: i32 = 1000;
    const CENTER_X: f64 = -0.743643887037151;
    const CENTER_Y: f64 = 0.13182590420533;
    const SPAN_Y: f64 = 0.0070;

    pub fn render(argb: &mut [u32]) -> f64 {
        render_rows(argb, 0);
        super::checksum(argb)
    }

    pub fn render_mt(argb: &mut [u32], threads: usize) -> f64 {
        let per = (HEIGHT + threads - 1) / threads;
        std::thread::scope(|s| {
            for (t, chunk) in argb.chunks_mut(per * WIDTH).enumerate() {
                s.spawn(move || render_rows(chunk, t * per));
            }
        });
        super::checksum(argb)
    }

    /// Renders `out.len()/WIDTH` rows starting at absolute row `y_start` into `out`.
    fn render_rows(out: &mut [u32], y_start: usize) {
        let half = SPAN_Y * 0.5;
        let min_y = CENTER_Y - half;
        let min_x = CENTER_X - half;
        let step = SPAN_Y / HEIGHT as f64;
        let rows = out.len() / WIDTH;
        let mut idx = 0usize;
        for r in 0..rows {
            let cy = min_y + (y_start + r) as f64 * step;
            for px in 0..WIDTH {
                let cx = min_x + px as f64 * step;
                let (mut zx, mut zy) = (0.0f64, 0.0f64);
                let mut i = 0;
                while i < MAX_ITER {
                    let zx2 = zx * zx;
                    let zy2 = zy * zy;
                    if zx2 + zy2 > 4.0 {
                        break;
                    }
                    zy = 2.0 * zx * zy + cy;
                    zx = zx2 - zy2 + cx;
                    i += 1;
                }
                out[idx] = color(i);
                idx += 1;
            }
        }
    }

    fn color(i: i32) -> u32 {
        if i >= MAX_ITER {
            return 0xFF00_0000;
        }
        let t = i as f64 / MAX_ITER as f64;
        let r = (9.0 * (1.0 - t) * t * t * t * 255.0) as i32;
        let g = (15.0 * (1.0 - t) * (1.0 - t) * t * t * 255.0) as i32;
        let b = (8.5 * (1.0 - t) * (1.0 - t) * (1.0 - t) * t * 255.0) as i32;
        let cl = |v: i32| v.clamp(0, 255) as u32;
        (0xFF << 24) | (cl(r) << 16) | (cl(g) << 8) | cl(b)
    }
}

// ---- K2 N-body ----

pub mod nbody {
    use super::Lcg;
    pub const N: usize = 1500;
    pub const STEPS: usize = 120;
    const DT: f64 = 0.001;
    const G: f64 = 1.0;
    const SOFTENING2: f64 = 0.05;
    const SEED: u64 = 0x9E37_79B9_7F4A_7C15;

    pub struct State {
        pub x: Vec<f64>, pub y: Vec<f64>, pub z: Vec<f64>,
        vx: Vec<f64>, vy: Vec<f64>, vz: Vec<f64>,
        mass: Vec<f64>,
    }
    impl State {
        pub fn new() -> Self {
            State {
                x: vec![0.0; N], y: vec![0.0; N], z: vec![0.0; N],
                vx: vec![0.0; N], vy: vec![0.0; N], vz: vec![0.0; N],
                mass: vec![0.0; N],
            }
        }
        pub fn reset(&mut self) {
            let mut rng = Lcg::new(SEED);
            for i in 0..N {
                self.x[i] = rng.range(-1.0, 1.0);
                self.y[i] = rng.range(-1.0, 1.0);
                self.z[i] = rng.range(-1.0, 1.0);
                self.vx[i] = 0.0; self.vy[i] = 0.0; self.vz[i] = 0.0;
                self.mass[i] = rng.range(0.5, 1.5);
            }
        }
    }

    pub fn simulate(s: &mut State) -> f64 {
        let mut ax = vec![0.0f64; N];
        let mut ay = vec![0.0f64; N];
        let mut az = vec![0.0f64; N];
        for _ in 0..STEPS {
            for i in 0..N { ax[i] = 0.0; ay[i] = 0.0; az[i] = 0.0; }
            for i in 0..N {
                let (xi, yi, zi) = (s.x[i], s.y[i], s.z[i]);
                let (mut axi, mut ayi, mut azi) = (0.0, 0.0, 0.0);
                for j in 0..N {
                    if j == i { continue; }
                    let dx = s.x[j] - xi;
                    let dy = s.y[j] - yi;
                    let dz = s.z[j] - zi;
                    let dist2 = dx * dx + dy * dy + dz * dz + SOFTENING2;
                    let inv = 1.0 / dist2.sqrt();
                    let f = G * s.mass[j] * inv * inv * inv;
                    axi += f * dx; ayi += f * dy; azi += f * dz;
                }
                ax[i] = axi; ay[i] = ayi; az[i] = azi;
            }
            for i in 0..N {
                s.vx[i] += ax[i] * DT; s.vy[i] += ay[i] * DT; s.vz[i] += az[i] * DT;
                s.x[i] += s.vx[i] * DT; s.y[i] += s.vy[i] * DT; s.z[i] += s.vz[i] * DT;
            }
        }
        let mut cs = 0.0;
        for i in 0..N { cs += s.x[i] + s.y[i] + s.z[i]; }
        cs
    }
}

// ---- K3 Ray tracer ----

pub mod raytracer {
    pub const WIDTH: usize = 600;
    pub const HEIGHT: usize = 600;
    const MAX_DEPTH: i32 = 4;
    const LIGHT: (f64, f64, f64) = (5.0, 5.0, 0.0);

    #[derive(Clone, Copy)]
    struct Sphere { cx: f64, cy: f64, cz: f64, r: f64, color: u32 }

    fn scene() -> Vec<Sphere> {
        let mut v = Vec::new();
        for gx in -1..=1 {
            for gz in -1..=1 {
                v.push(Sphere {
                    cx: gx as f64 * 1.2, cy: 0.0, cz: -3.0 + gz as f64 * 1.2, r: 0.5,
                    color: color_for(gx, gz),
                });
            }
        }
        v
    }
    fn color_for(gx: i32, gz: i32) -> u32 {
        match (gx + gz + 4).rem_euclid(3) {
            0 => 0xFFE0_6C75,
            1 => 0xFF98_C379,
            _ => 0xFF61_AFEF,
        }
    }

    pub fn render(argb: &mut [u32]) -> f64 {
        let spheres = scene();
        render_rows(&spheres, argb, 0);
        super::checksum(argb)
    }

    pub fn render_mt(argb: &mut [u32], threads: usize) -> f64 {
        let spheres = scene();
        let per = (HEIGHT + threads - 1) / threads;
        std::thread::scope(|s| {
            for (t, chunk) in argb.chunks_mut(per * WIDTH).enumerate() {
                let sp = &spheres;
                s.spawn(move || render_rows(sp, chunk, t * per));
            }
        });
        super::checksum(argb)
    }

    fn render_rows(sp: &[Sphere], out: &mut [u32], y_start: usize) {
        let aspect = WIDTH as f64 / HEIGHT as f64;
        let rows = out.len() / WIDTH;
        let mut idx = 0usize;
        for r in 0..rows {
            let py = y_start + r;
            let sy = 1.0 - 2.0 * (py as f64 + 0.5) / HEIGHT as f64;
            for px in 0..WIDTH {
                let sx = (2.0 * (px as f64 + 0.5) / WIDTH as f64 - 1.0) * aspect;
                let (cr, cg, cb) = trace(sp, 0.0, 0.0, 0.0, sx, sy, -1.0, 0);
                let cl = |v: f64| ((v * 255.0) as i32).clamp(0, 255) as u32;
                out[idx] = (0xFF << 24) | (cl(cr) << 16) | (cl(cg) << 8) | cl(cb);
                idx += 1;
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn trace(sp: &[Sphere], ox: f64, oy: f64, oz: f64, dx_in: f64, dy_in: f64, dz_in: f64, depth: i32) -> (f64, f64, f64) {
        let inv = 1.0 / (dx_in * dx_in + dy_in * dy_in + dz_in * dz_in).sqrt();
        let (dx, dy, dz) = (dx_in * inv, dy_in * inv, dz_in * inv);

        let mut hit_t = f64::MAX;
        let mut hit: Option<Sphere> = None;
        for s in sp {
            let t = intersect(ox, oy, oz, dx, dy, dz, s);
            if t > 1e-4 && t < hit_t { hit_t = t; hit = Some(*s); }
        }
        let mut ground_t = f64::MAX;
        if dy < -1e-6 {
            let t = (-0.5 - oy) / dy;
            if t > 1e-4 && t < hit_t { ground_t = t; }
        }
        if hit.is_none() && ground_t == f64::MAX {
            let t = 0.5 * (dy + 1.0);
            return (0.5 + 0.3 * t, 0.7 * t + 0.2, 0.9 * t + 0.1);
        }

        let (px, py, pz, nx, ny, nz, ar, ag, ab, refl);
        if ground_t < hit_t {
            px = ox + dx * ground_t; py = oy + dy * ground_t; pz = oz + dz * ground_t;
            nx = 0.0; ny = 1.0; nz = 0.0;
            let checker = ((px.floor() as i64) + (pz.floor() as i64)) & 1 == 0;
            let c = if checker { 0.9 } else { 0.3 };
            ar = c; ag = c; ab = c; refl = 0.2;
        } else {
            let s = hit.unwrap();
            px = ox + dx * hit_t; py = oy + dy * hit_t; pz = oz + dz * hit_t;
            let (lnx, lny, lnz) = (px - s.cx, py - s.cy, pz - s.cz);
            let ninv = 1.0 / (lnx * lnx + lny * lny + lnz * lnz).sqrt();
            nx = lnx * ninv; ny = lny * ninv; nz = lnz * ninv;
            ar = ((s.color >> 16) & 0xFF) as f64 / 255.0;
            ag = ((s.color >> 8) & 0xFF) as f64 / 255.0;
            ab = (s.color & 0xFF) as f64 / 255.0;
            refl = 0.5;
        }

        let (mut ldx, mut ldy, mut ldz) = (LIGHT.0 - px, LIGHT.1 - py, LIGHT.2 - pz);
        let linv = 1.0 / (ldx * ldx + ldy * ldy + ldz * ldz).sqrt();
        ldx *= linv; ldy *= linv; ldz *= linv;
        let diff = (nx * ldx + ny * ldy + nz * ldz).max(0.0);
        let ambient = 0.15;
        let shade = ambient + (1.0 - ambient) * diff;

        let (mut r, mut g, mut b) = (ar * shade, ag * shade, ab * shade);
        if depth < MAX_DEPTH && refl > 0.0 {
            let dot = dx * nx + dy * ny + dz * nz;
            let rr = trace(sp, px, py, pz, dx - 2.0 * dot * nx, dy - 2.0 * dot * ny, dz - 2.0 * dot * nz, depth + 1);
            r = r * (1.0 - refl) + rr.0 * refl;
            g = g * (1.0 - refl) + rr.1 * refl;
            b = b * (1.0 - refl) + rr.2 * refl;
        }
        (r, g, b)
    }

    fn intersect(ox: f64, oy: f64, oz: f64, dx: f64, dy: f64, dz: f64, s: &Sphere) -> f64 {
        let (ocx, ocy, ocz) = (ox - s.cx, oy - s.cy, oz - s.cz);
        let b = ocx * dx + ocy * dy + ocz * dz;
        let c = ocx * ocx + ocy * ocy + ocz * ocz - s.r * s.r;
        let disc = b * b - c;
        if disc < 0.0 { return f64::MAX; }
        let sq = disc.sqrt();
        let t0 = -b - sq;
        if t0 > 1e-4 { return t0; }
        let t1 = -b + sq;
        if t1 > 1e-4 { return t1; }
        f64::MAX
    }
}

// ---- K4 MatMul (SGEMM-style, f64, N=512, ikj order) ----

pub mod matmul {
    pub const N: usize = 512;

    pub fn fill(a: &mut [f64], b: &mut [f64]) {
        for i in 0..N {
            for j in 0..N {
                a[i * N + j] = ((i * 31 + j) % 100) as f64 * 0.01;
                b[i * N + j] = ((i * 17 + j) % 100) as f64 * 0.01;
            }
        }
    }

    pub fn multiply(a: &[f64], b: &[f64], c: &mut [f64]) -> f64 {
        for v in c.iter_mut() { *v = 0.0; }
        mul_rows(a, b, c, 0);
        super::checksum_d(c)
    }

    pub fn multiply_mt(a: &[f64], b: &[f64], c: &mut [f64], threads: usize) -> f64 {
        for v in c.iter_mut() { *v = 0.0; }
        let per = (N + threads - 1) / threads;
        std::thread::scope(|s| {
            for (t, chunk) in c.chunks_mut(per * N).enumerate() {
                s.spawn(move || mul_rows(a, b, chunk, t * per));
            }
        });
        super::checksum_d(c)
    }

    /// Multiplies `out.len()/N` rows of C starting at absolute row `i_start`.
    fn mul_rows(a: &[f64], b: &[f64], out: &mut [f64], i_start: usize) {
        let rows = out.len() / N;
        for r in 0..rows {
            let i = i_start + r;
            let ai = i * N;
            let oi = r * N;
            for k in 0..N {
                let f = a[ai + k];
                let bk = k * N;
                for j in 0..N {
                    out[oi + j] += f * b[bk + j];
                }
            }
        }
    }
}

// ---- K5 Gaussian blur (separable, radius 8, 1536×1536 f64) ----

pub mod blur {
    use super::Lcg;
    pub const WIDTH: usize = 1536;
    pub const HEIGHT: usize = 1536;
    pub const RADIUS: i32 = 8;
    const SEED: u64 = 0xB100_5EED;

    fn weights() -> [f64; 17] {
        let mut w = [0.0f64; 17];
        let sigma = RADIUS as f64 / 2.0;
        let mut sum = 0.0;
        for (i, v) in w.iter_mut().enumerate() {
            let d = i as f64 - RADIUS as f64;
            *v = (-d * d / (2.0 * sigma * sigma)).exp();
            sum += *v;
        }
        for v in w.iter_mut() { *v /= sum; }
        w
    }

    pub fn reset(img: &mut [f64]) {
        let mut rng = Lcg::new(SEED);
        for v in img.iter_mut() { *v = rng.next_double(); }
    }

    pub fn convolve(img: &mut [f64], tmp: &mut [f64]) -> f64 {
        let w = weights();
        h_pass(&w, img, tmp, 0);
        v_pass(&w, tmp, img, 0, HEIGHT);
        super::checksum_d(img)
    }

    pub fn convolve_mt(img: &mut [f64], tmp: &mut [f64], threads: usize) -> f64 {
        let w = weights();
        let per = (HEIGHT + threads - 1) / threads;
        std::thread::scope(|s| {
            let src = &*img;
            for (t, chunk) in tmp.chunks_mut(per * WIDTH).enumerate() {
                let wref = &w;
                s.spawn(move || h_pass_into(wref, src, chunk, t * per));
            }
        });
        std::thread::scope(|s| {
            let src = &*tmp;
            for (t, chunk) in img.chunks_mut(per * WIDTH).enumerate() {
                let wref = &w;
                s.spawn(move || v_pass_into(wref, src, chunk, t * per));
            }
        });
        super::checksum_d(img)
    }

    fn h_pass(w: &[f64; 17], src: &[f64], dst: &mut [f64], y0: usize) {
        h_pass_into(w, src, dst, y0)
    }

    /// Horizontal pass writing `dst.len()/WIDTH` rows starting at absolute row `y_start`.
    fn h_pass_into(w: &[f64; 17], src: &[f64], dst: &mut [f64], y_start: usize) {
        let rows = dst.len() / WIDTH;
        for r in 0..rows {
            let row = (y_start + r) * WIDTH;
            let orow = r * WIDTH;
            for x in 0..WIDTH {
                let mut acc = 0.0;
                for k in -RADIUS..=RADIUS {
                    let mut xx = x as i32 + k;
                    if xx < 0 { xx = 0 } else if xx >= WIDTH as i32 { xx = WIDTH as i32 - 1 }
                    acc += src[row + xx as usize] * w[(k + RADIUS) as usize];
                }
                dst[orow + x] = acc;
            }
        }
    }

    fn v_pass(w: &[f64; 17], src: &[f64], dst: &mut [f64], y0: usize, y1: usize) {
        // ST path: dst is the full image.
        for y in y0..y1 {
            for x in 0..WIDTH {
                let mut acc = 0.0;
                for k in -RADIUS..=RADIUS {
                    let mut yy = y as i32 + k;
                    if yy < 0 { yy = 0 } else if yy >= HEIGHT as i32 { yy = HEIGHT as i32 - 1 }
                    acc += src[yy as usize * WIDTH + x] * w[(k + RADIUS) as usize];
                }
                dst[y * WIDTH + x] = acc;
            }
        }
    }

    /// Vertical pass writing `dst.len()/WIDTH` rows starting at absolute row `y_start`.
    fn v_pass_into(w: &[f64; 17], src: &[f64], dst: &mut [f64], y_start: usize) {
        let rows = dst.len() / WIDTH;
        for r in 0..rows {
            let y = y_start + r;
            let orow = r * WIDTH;
            for x in 0..WIDTH {
                let mut acc = 0.0;
                for k in -RADIUS..=RADIUS {
                    let mut yy = y as i32 + k;
                    if yy < 0 { yy = 0 } else if yy >= HEIGHT as i32 { yy = HEIGHT as i32 - 1 }
                    acc += src[yy as usize * WIDTH + x] * w[(k + RADIUS) as usize];
                }
                dst[orow + x] = acc;
            }
        }
    }
}

// ---- K6 SHA-256 (hand-rolled FIPS 180-4, 8 MiB input) ----

pub mod sha256 {
    pub const BYTES: usize = 8 * 1024 * 1024;
    const SEED: u64 = 0xFEED_FACE_CAFE_BEEF;

    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];

    pub fn fill(data: &mut [u8]) {
        let mut s = SEED;
        for b in data.iter_mut() {
            s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            *b = (s >> 56) as u8;
        }
    }

    pub fn digest(data: &[u8]) -> f64 {
        let mut h: [u32; 8] = [
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
        ];
        let mut w = [0u32; 64];
        let mut off = 0;
        while off + 64 <= data.len() {
            process_block(&data[off..off + 64], &mut w, &mut h);
            off += 64;
        }
        let mut pad = [0u8; 64];
        pad[0] = 0x80;
        let bits = (data.len() as u64) * 8;
        for i in 0..8 {
            pad[56 + i] = (bits >> (56 - 8 * i)) as u8;
        }
        process_block(&pad, &mut w, &mut h);
        h.iter().map(|&v| v as f64).sum()
    }

    fn process_block(block: &[u8], w: &mut [u32; 64], h: &mut [u32; 8]) {
        for t in 0..16 {
            let i = t * 4;
            w[t] = ((block[i] as u32) << 24)
                | ((block[i + 1] as u32) << 16)
                | ((block[i + 2] as u32) << 8)
                | (block[i + 3] as u32);
        }
        for t in 16..64 {
            let x = w[t - 15];
            let y = w[t - 2];
            let s0 = x.rotate_right(7) ^ x.rotate_right(18) ^ (x >> 3);
            let s1 = y.rotate_right(17) ^ y.rotate_right(19) ^ (y >> 10);
            w[t] = w[t - 16].wrapping_add(s0).wrapping_add(w[t - 7]).wrapping_add(s1);
        }
        let (mut a, mut b, mut c, mut d) = (h[0], h[1], h[2], h[3]);
        let (mut e, mut f, mut g, mut hh) = (h[4], h[5], h[6], h[7]);
        for t in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ (!e & g);
            let t1 = hh.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[t]).wrapping_add(w[t]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g; g = f; f = e; e = d.wrapping_add(t1);
            d = c; c = b; b = a; a = t1.wrapping_add(t2);
        }
        h[0] = h[0].wrapping_add(a); h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c); h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e); h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g); h[7] = h[7].wrapping_add(hh);
    }
}

// ---- K7 Sieve of Eratosthenes (limit 20,000,000) ----

pub mod sieve {
    pub const LIMIT: usize = 20_000_000;

    pub fn count(composite: &mut [u8]) -> f64 {
        for v in composite.iter_mut() { *v = 0; }
        let mut i = 2usize;
        while i * i <= LIMIT {
            if composite[i] == 0 {
                let mut j = i * i;
                while j <= LIMIT {
                    composite[j] = 1;
                    j += i;
                }
            }
            i += 1;
        }
        let mut count = 0u32;
        for n in 2..=LIMIT {
            if composite[n] == 0 { count += 1; }
        }
        count as f64
    }
}

// ---- K8 FFT (iterative radix-2 Cooley-Tukey, 2^20 points) ----

pub mod fft {
    use super::Lcg;
    pub const LOG_N: usize = 20;
    pub const N: usize = 1 << LOG_N;
    const SEED: u64 = 0x0123_4567_89AB_CDEF;

    pub fn reset(re: &mut [f64], im: &mut [f64]) {
        let mut rng = Lcg::new(SEED);
        for i in 0..N {
            re[i] = rng.range(-1.0, 1.0);
            im[i] = 0.0;
        }
    }

    pub fn transform(re: &mut [f64], im: &mut [f64]) -> f64 {
        let mut j = 0usize;
        for i in 1..N {
            let mut bit = N >> 1;
            while j & bit != 0 {
                j ^= bit;
                bit >>= 1;
            }
            j |= bit;
            if i < j {
                re.swap(i, j);
                im.swap(i, j);
            }
        }
        let mut len = 2usize;
        while len <= N {
            let ang = -2.0 * std::f64::consts::PI / len as f64;
            let (wi, wr) = ang.sin_cos();
            let mut i = 0usize;
            while i < N {
                let (mut cwr, mut cwi) = (1.0f64, 0.0f64);
                let half = len >> 1;
                for t in 0..half {
                    let ur = re[i + t];
                    let ui = im[i + t];
                    let vr = re[i + t + half] * cwr - im[i + t + half] * cwi;
                    let vi = re[i + t + half] * cwi + im[i + t + half] * cwr;
                    re[i + t] = ur + vr;
                    im[i + t] = ui + vi;
                    re[i + t + half] = ur - vr;
                    im[i + t + half] = ui - vi;
                    let nwr = cwr * wr - cwi * wi;
                    cwi = cwr * wi + cwi * wr;
                    cwr = nwr;
                }
                i += len;
            }
            len <<= 1;
        }
        let mut cs = 0.0;
        let mut s = 0usize;
        while s < N {
            cs += re[s] + im[s];
            s += 101;
        }
        cs
    }
}

// ---- Runner (same protocol: 3 warmup + best-of-5) ----

pub struct KernelResult {
    pub name: String,
    pub unit: String,
    pub threads: usize,
    pub work: i64,
    pub best: f64,
}
impl KernelResult {
    pub fn throughput_m(&self) -> f64 {
        self.work as f64 / self.best / 1_000_000.0
    }
}

fn measure(name: &str, threads: usize, unit: &str, work_units: i64, mut kernel: impl FnMut() -> f64) -> KernelResult {
    let mut sink = 0.0f64;
    for _ in 0..3 { sink += kernel(); }
    let mut best = f64::MAX;
    for _ in 0..5 {
        let t0 = Instant::now();
        sink += kernel();
        let secs = t0.elapsed().as_secs_f64();
        if secs < best { best = secs; }
    }
    std::hint::black_box(sink); // observe the checksum so the kernel isn't dead-code-eliminated
    KernelResult { name: name.into(), unit: unit.into(), threads, work: work_units, best }
}

/// Runs a single named bench (buffers allocated + deterministic fills done outside timing).
pub fn run_bench(name: &str) -> Option<KernelResult> {
    let t = cores();
    let mandel_w = (mandelbrot::WIDTH * mandelbrot::HEIGHT) as i64;
    let nbody_w = (nbody::N as i64) * (nbody::N as i64 - 1) * (nbody::STEPS as i64);
    let ray_w = (raytracer::WIDTH * raytracer::HEIGHT) as i64;
    let matmul_w = 2 * (matmul::N as i64).pow(3);
    let blur_w = (blur::WIDTH * blur::HEIGHT) as i64;
    let sha_w = sha256::BYTES as i64;
    let sieve_w = sieve::LIMIT as i64;
    let fft_w = (fft::N as i64 / 2) * fft::LOG_N as i64;

    Some(match name {
        "mandelbrot" => {
            let mut buf = vec![0u32; mandelbrot::WIDTH * mandelbrot::HEIGHT];
            measure(name, 1, "Mpix/s", mandel_w, || mandelbrot::render(&mut buf))
        }
        "nbody" => {
            let mut s = nbody::State::new();
            measure(name, 1, "M-inter/s", nbody_w, || { s.reset(); nbody::simulate(&mut s) })
        }
        "raytracer" => {
            let mut buf = vec![0u32; raytracer::WIDTH * raytracer::HEIGHT];
            measure(name, 1, "Mrays/s", ray_w, || raytracer::render(&mut buf))
        }
        "matmul" => {
            let mut a = vec![0.0; matmul::N * matmul::N];
            let mut b = vec![0.0; matmul::N * matmul::N];
            let mut c = vec![0.0; matmul::N * matmul::N];
            matmul::fill(&mut a, &mut b);
            measure(name, 1, "MFLOP/s", matmul_w, || matmul::multiply(&a, &b, &mut c))
        }
        "blur" => {
            let mut img = vec![0.0; blur::WIDTH * blur::HEIGHT];
            let mut tmp = vec![0.0; blur::WIDTH * blur::HEIGHT];
            blur::reset(&mut img);
            measure(name, 1, "Mpix/s", blur_w, || blur::convolve(&mut img, &mut tmp))
        }
        "sha256" => {
            let mut data = vec![0u8; sha256::BYTES];
            sha256::fill(&mut data);
            measure(name, 1, "MB/s", sha_w, || { data[0] = data[0].wrapping_add(1); sha256::digest(&data) })
        }
        "sieve" => {
            let mut buf = vec![0u8; sieve::LIMIT + 1];
            measure(name, 1, "Mn/s", sieve_w, || sieve::count(&mut buf))
        }
        "fft" => {
            let mut re = vec![0.0; fft::N];
            let mut im = vec![0.0; fft::N];
            measure(name, 1, "Mbf/s", fft_w, || { fft::reset(&mut re, &mut im); fft::transform(&mut re, &mut im) })
        }
        "pi" => measure(name, 1, "Mdig/s", pi::DIGITS as i64, pi::compute),
        "mandelbrot_mt" => {
            let mut buf = vec![0u32; mandelbrot::WIDTH * mandelbrot::HEIGHT];
            measure(name, t, "Mpix/s", mandel_w, || mandelbrot::render_mt(&mut buf, t))
        }
        "raytracer_mt" => {
            let mut buf = vec![0u32; raytracer::WIDTH * raytracer::HEIGHT];
            measure(name, t, "Mrays/s", ray_w, || raytracer::render_mt(&mut buf, t))
        }
        "matmul_mt" => {
            let mut a = vec![0.0; matmul::N * matmul::N];
            let mut b = vec![0.0; matmul::N * matmul::N];
            let mut c = vec![0.0; matmul::N * matmul::N];
            matmul::fill(&mut a, &mut b);
            measure(name, t, "MFLOP/s", matmul_w, || matmul::multiply_mt(&a, &b, &mut c, t))
        }
        "blur_mt" => {
            let mut img = vec![0.0; blur::WIDTH * blur::HEIGHT];
            let mut tmp = vec![0.0; blur::WIDTH * blur::HEIGHT];
            blur::reset(&mut img);
            measure(name, t, "Mpix/s", blur_w, || blur::convolve_mt(&mut img, &mut tmp, t))
        }
        _ => return None,
    })
}

// ---- K9 π to 10,000 digits (Machin formula, fixed-point bignum base 10^9) ----

pub mod pi {
    pub const DIGITS: usize = 10_000;
    const BASE: i64 = 1_000_000_000;
    const W: usize = DIGITS / 9 + 3;

    fn div_small(a: &mut [i64], d: i64) {
        let mut rem: i64 = 0;
        for v in a.iter_mut() {
            let cur = rem * BASE + *v;
            *v = cur / d;
            rem = cur % d;
        }
    }

    fn mul_small(a: &mut [i64], m: i64) {
        let mut carry: i64 = 0;
        for v in a.iter_mut().rev() {
            let p = *v * m + carry;
            *v = p % BASE;
            carry = p / BASE;
        }
    }

    fn add_in_place(a: &mut [i64], b: &[i64]) {
        let mut carry: i64 = 0;
        for i in (0..W).rev() {
            let s = a[i] + b[i] + carry;
            a[i] = s % BASE;
            carry = s / BASE;
        }
    }

    fn sub_in_place(a: &mut [i64], b: &[i64]) {
        let mut borrow: i64 = 0;
        for i in (0..W).rev() {
            let mut s = a[i] - b[i] - borrow;
            if s < 0 {
                s += BASE;
                borrow = 1;
            } else {
                borrow = 0;
            }
            a[i] = s;
        }
    }

    fn is_zero(a: &[i64]) -> bool {
        a.iter().all(|&v| v == 0)
    }

    fn arctan_inv(out: &mut [i64], x: i64) {
        let mut term = vec![0i64; W];
        let mut tmp = vec![0i64; W];
        for v in out.iter_mut() {
            *v = 0;
        }
        term[0] = 1;
        div_small(&mut term, x);
        add_in_place(out, &term);
        let mut n: i64 = 1;
        let mut subtract = true;
        let x2 = x * x;
        while !is_zero(&term) {
            div_small(&mut term, x2);
            n += 2;
            tmp.copy_from_slice(&term);
            div_small(&mut tmp, n);
            if subtract {
                sub_in_place(out, &tmp);
            } else {
                add_in_place(out, &tmp);
            }
            subtract = !subtract;
        }
    }

    /// π = 16·arctan(1/5) − 4·arctan(1/239). Self-verifying; returns a strided word checksum.
    pub fn compute() -> f64 {
        let mut a = vec![0i64; W];
        let mut b = vec![0i64; W];
        arctan_inv(&mut a, 5);
        mul_small(&mut a, 16);
        arctan_inv(&mut b, 239);
        mul_small(&mut b, 4);
        sub_in_place(&mut a, &b);
        assert!(
            a[0] == 3 && a[1] == 141_592_653 && a[2] == 589_793_238,
            "pi self-check failed: {}.{} {}",
            a[0], a[1], a[2]
        );
        let mut cs = 0.0;
        let mut k = 0;
        while k < W {
            cs += a[k] as f64;
            k += 101;
        }
        cs
    }
}
