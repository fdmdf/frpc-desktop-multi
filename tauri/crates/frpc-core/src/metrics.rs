//! 进程资源指标：CPU / 内存 / 线程 / 状态采样。
//!
//! Kotlin 来源：`desktopApp/.../runtime/ProcessMetrics.kt`（逐分支对译）。
//! 平台分支：Linux 读 `/proc`；Windows 走 PowerShell `Get-CimInstance`；
//! 其余平台（macOS）返回 `None` —— 与 Kotlin 行为一致（macOS 上原本就是 null）。
//! CPU 百分比按 `delta / (elapsed × cores) × 100` 归一，钳制 0..100。

use serde::{Deserialize, Serialize};

/// 一次进程采样（Kotlin `ProcMetrics`）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProcMetrics {
    pub pid: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cpu_percent: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rss_kb: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub threads: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<String>,
}

struct Raw {
    cpu100ns: Option<i64>,
    rss_kb: Option<i64>,
    threads: Option<i32>,
    state: Option<String>,
}

pub struct ProcessMetricsReader {
    cores: usize,
    cached: Option<ProcMetrics>,
    cached_at: i64,
    last_cpu100ns: Option<i64>,
    last_cpu_at: i64,
}

impl ProcessMetricsReader {
    pub fn new() -> Self {
        Self {
            cores: std::thread::available_parallelism()
                .map(|n| n.get())
                .unwrap_or(1)
                .max(1),
            cached: None,
            cached_at: 0,
            last_cpu100ns: None,
            last_cpu_at: 0,
        }
    }

    /// 采样；1.5s 内的重复读取返回缓存（Kotlin `MIN_INTERVAL_MS`）。
    pub fn read(&mut self, pid: i64) -> Option<ProcMetrics> {
        let now = crate::admin::now_epoch_ms();
        if now - self.cached_at < MIN_INTERVAL_MS {
            return self.cached.clone();
        }
        let raw = read_raw(pid);
        self.cached = raw.map(|sample| {
            let cpu = compute_cpu(
                self.last_cpu100ns,
                self.last_cpu_at,
                now,
                sample.cpu100ns,
                self.cores,
            );
            if sample.cpu100ns.is_some() {
                self.last_cpu100ns = sample.cpu100ns;
                self.last_cpu_at = now;
            }
            ProcMetrics {
                pid,
                cpu_percent: cpu,
                rss_kb: sample.rss_kb,
                threads: sample.threads,
                state: sample.state,
            }
        });
        self.cached_at = now;
        self.cached.clone()
    }

    pub fn reset(&mut self) {
        self.cached = None;
        self.cached_at = 0;
        self.last_cpu100ns = None;
        self.last_cpu_at = 0;
    }
}

const MIN_INTERVAL_MS: i64 = 1_500;

/// CPU 百分比计算（Kotlin read() 中的公式，独立成纯函数便于对拍）。
pub fn compute_cpu(
    prev_cpu100ns: Option<i64>,
    prev_at_ms: i64,
    now_ms: i64,
    cur_cpu100ns: Option<i64>,
    cores: usize,
) -> Option<f32> {
    let elapsed_ms = now_ms - prev_at_ms;
    match (prev_cpu100ns, cur_cpu100ns) {
        (Some(prev), Some(cur)) if elapsed_ms > 0 => {
            let delta_ms = (cur - prev) as f64 / 10_000.0;
            let pct = delta_ms / (elapsed_ms as f64 * cores as f64) * 100.0;
            Some(pct.clamp(0.0, 100.0) as f32)
        }
        _ => None,
    }
}

fn read_raw(pid: i64) -> Option<Raw> {
    #[cfg(target_os = "windows")]
    {
        read_windows(pid)
    }
    #[cfg(target_os = "linux")]
    {
        read_proc(pid)
    }
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    {
        let _ = pid;
        None
    }
}

#[cfg(target_os = "linux")]
fn read_proc(pid: i64) -> Option<Raw> {
    let stat_text = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let tail = stat_text.rsplit(')').next()?.trim_start().to_string();
    let cols: Vec<&str> = tail.split(' ').collect();
    if cols.len() < 18 {
        return None;
    }
    let state = cols
        .first()
        .and_then(|s| s.chars().next())
        .map(|c| c.to_string());
    let utime = cols
        .get(11)
        .and_then(|s| s.parse::<i64>().ok())
        .unwrap_or(0);
    let stime = cols
        .get(12)
        .and_then(|s| s.parse::<i64>().ok())
        .unwrap_or(0);
    let threads = cols.get(17).and_then(|s| s.parse::<i32>().ok());
    let pages = std::fs::read_to_string(format!("/proc/{pid}/statm"))
        .ok()
        .and_then(|s| s.split(' ').nth(1).and_then(|p| p.parse::<i64>().ok()))
        .unwrap_or(0);
    Some(Raw {
        cpu100ns: Some((utime + stime) * 100_000),
        rss_kb: Some(pages * 4),
        threads,
        state,
    })
}

#[cfg(target_os = "windows")]
fn read_windows(pid: i64) -> Option<Raw> {
    use std::os::windows::process::CommandExt;
    let script = format!(
        "(Get-CimInstance Win32_Process -Filter \"ProcessId={pid}\" | \
         Select-Object KernelModeTime,UserModeTime,WorkingSetSize,ThreadCount | ConvertTo-Json -Compress)"
    );
    // CREATE_NO_WINDOW：避免从 GUI 主进程拉起 PowerShell 时弹出控制台窗口
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let output = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&output.stdout).trim().to_string();
    parse_windows_json(&text)
}

#[cfg(target_os = "windows")]
fn parse_windows_json(text: &str) -> Option<Raw> {
    if text.is_empty() || !text.starts_with('{') {
        return None;
    }
    let v: serde_json::Value = serde_json::from_str(text).ok()?;
    let field = |name: &str| -> Option<i64> { v.get(name).and_then(|x| x.as_i64()) };
    let kernel = field("KernelModeTime").unwrap_or(0);
    let user = field("UserModeTime").unwrap_or(0);
    let working_set = field("WorkingSetSize");
    Some(Raw {
        cpu100ns: Some(kernel + user),
        rss_kb: working_set.map(|w| w / 1024),
        threads: field("ThreadCount").map(|t| t as i32),
        state: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cpu_first_sample_is_none() {
        assert_eq!(None, compute_cpu(None, 0, 1_000, Some(1_000_000), 4));
    }

    #[test]
    fn cpu_second_sample_computes_percent() {
        // 0.5s 内用满 1 核的 50%：CPU 时间 = 250ms = 2_500_000 × 100ns
        let prev = 1_000_000_000i64;
        let cur = 1_000_000_000 + 2_500_000;
        let pct = compute_cpu(Some(prev), 1_000, 1_500, Some(cur), 1).expect("pct");
        assert!((pct - 50.0).abs() < 0.01, "pct = {pct}");
    }

    #[test]
    fn cpu_multi_core_normalization() {
        let prev = 0i64;
        let cur = 100_000_000i64; // 1s 的 100ns → 10_000 ms CPU 时间
        let pct = compute_cpu(Some(prev), 0, 1_000, Some(cur), 4).expect("pct");
        assert!((pct - 250.0f32.min(100.0)).abs() < 0.01, "pct = {pct}");
        assert!((0.0..=100.0).contains(&pct), "应钳制到 0..100");
    }

    #[test]
    fn reader_caches_within_interval() {
        let mut reader = ProcessMetricsReader::new();
        reader.cached = Some(ProcMetrics {
            pid: 42,
            ..ProcMetrics::default()
        });
        reader.cached_at = crate::admin::now_epoch_ms();
        let got = reader.read(7).expect("cached");
        assert_eq!(42, got.pid, "1.5s 内应返回缓存");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn proc_reader_reads_self() {
        let mut reader = ProcessMetricsReader::new();
        let pid = std::process::id() as i64;
        let m = reader.read(pid).expect("self /proc 可读");
        assert!(m.rss_kb.unwrap_or(0) > 0);
        assert!(m.threads.unwrap_or(0) > 0);
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn windows_json_parse() {
        let raw = r#"{"KernelModeTime":1000000,"UserModeTime":2000000,"WorkingSetSize":40960,"ThreadCount":3}"#;
        let r = parse_windows_json(raw).expect("parsed");
        assert_eq!(Some(3_000_000), r.cpu100ns);
        assert_eq!(Some(40), r.rss_kb);
        assert_eq!(Some(3), r.threads);
    }
}
