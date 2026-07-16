#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod kernels;

use serde::Serialize;

#[derive(Serialize)]
struct BenchDto {
    name: String,
    unit: String,
    threads: usize,
    #[serde(rename = "workUnits")]
    work_units: i64,
    #[serde(rename = "bestSeconds")]
    best_seconds: f64,
    #[serde(rename = "throughputM")]
    throughput_m: f64,
}

/// Heavy CPU work runs on the blocking pool so the webview stays responsive.
#[tauri::command]
async fn run_cpu_bench(name: String) -> Result<BenchDto, String> {
    tauri::async_runtime::spawn_blocking(move || {
        kernels::run_bench(&name)
            .map(|r| BenchDto {
                throughput_m: r.throughput_m(),
                name: r.name,
                unit: r.unit,
                threads: r.threads,
                work_units: r.work,
                best_seconds: r.best,
            })
            .ok_or_else(|| format!("unknown bench: {name}"))
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
fn sys_info() -> serde_json::Value {
    serde_json::json!({
        "os": format!("{} {}", std::env::consts::OS, std::env::consts::ARCH),
        "cpus": kernels::cores(),
        "benchNames": kernels::BENCH_NAMES,
    })
}

/// Writes the final results JSON to ~/nucleus-benchmarks/tauri.json and returns the path.
#[tauri::command]
fn save_results(json: String) -> Result<String, String> {
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .map_err(|e| e.to_string())?;
    let dir = std::path::Path::new(&home).join("nucleus-benchmarks");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join("tauri.json");
    std::fs::write(&path, json).map_err(|e| e.to_string())?;
    Ok(path.display().to_string())
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![run_cpu_bench, sys_info, save_results])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
