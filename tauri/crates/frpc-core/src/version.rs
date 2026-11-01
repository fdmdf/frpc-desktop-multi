//! 引擎版本解析（`frpc -v` 输出 → 版本号字符串）。
//!
//! 为什么单独抽出来：Android 上引擎在 `nativeLibraryDir/libfrpc.so`，APK 里
//! 没有 `resources/engine/VERSION`，唯一能拿到版本的途径就是跑一次 `frpc -v`。
//! 而 `frpc -v` 的实测输出**只有一行版本号**（如 `0.71.1`），并不含 "frp"
//! 字样 —— 早期实现按「含 frp 的行」过滤，于是永远返回空串，界面一直显示
//! unknown。这里改成「找第一个长得像版本号的 token」，并兼容
//! `frpc version 0.61.0` / `v0.71.1` 等写法。

/// 把单个 token 规整成版本号（去掉前缀 v；不像版本号则返回 None）。
fn normalize_token(token: &str) -> Option<String> {
    // 去掉首尾标点（",;.)" 之类），保留数字、点、连字符与字母（rc1 后缀）
    let trimmed = token.trim_matches(|c: char| !c.is_ascii_alphanumeric() && c != '.' && c != '-');
    if trimmed.is_empty() {
        return None;
    }
    let core = trimmed
        .strip_prefix('v')
        .or_else(|| trimmed.strip_prefix('V'))
        .unwrap_or(trimmed);
    let mut chars = core.chars();
    let first_ok = chars.next().is_some_and(|c| c.is_ascii_digit());
    if !first_ok || !core.contains('.') {
        return None;
    }
    // 0.71.1 / 0.62.0-rc1 都接受；纯日期（2024.10.1）也走这条，但 frpc 不会这么打
    if !core
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
    {
        return None;
    }
    Some(core.to_string())
}

/// 从 `frpc -v` 的 stdout 里提取版本号，取不到时返回空串（调用方回退）。
pub fn parse_version_output(stdout: &str) -> String {
    for line in stdout.lines() {
        for token in line.split_whitespace() {
            if let Some(v) = normalize_token(token) {
                return v;
            }
        }
    }
    String::new()
}

/// 去掉版本号前缀的 `v`（VERSION 文件里写的是 `v0.71.1`，界面统一显示 `0.71.1`）。
pub fn strip_v_prefix(version: &str) -> String {
    version
        .trim()
        .trim_start_matches(['v', 'V'])
        .trim()
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_bare_version_line() {
        // 实测 `frpc -v` 的真实输出就是这一行，不含 "frp"
        assert_eq!(parse_version_output("0.71.1\n"), "0.71.1");
        assert_eq!(parse_version_output("0.71.1"), "0.71.1");
    }

    #[test]
    fn parses_verbose_forms() {
        assert_eq!(parse_version_output("frpc version 0.61.0\n"), "0.61.0");
        assert_eq!(parse_version_output("frpc 0.71.1\n"), "0.71.1");
        assert_eq!(parse_version_output("v0.71.1\n"), "0.71.1");
        assert_eq!(parse_version_output("0.62.0-rc1\n"), "0.62.0-rc1");
    }

    #[test]
    fn ignores_noise_lines() {
        assert_eq!(parse_version_output(""), "");
        assert_eq!(parse_version_output("frpc\n"), "");
        assert_eq!(parse_version_output("usage: frpc [-c config]\n"), "");
        // 第一行是说明文字时继续往下找
        assert_eq!(parse_version_output("build: dev\n0.71.1\n"), "0.71.1");
    }

    #[test]
    fn strips_v_prefix() {
        assert_eq!(strip_v_prefix("v0.71.1"), "0.71.1");
        assert_eq!(strip_v_prefix(" 0.71.1 "), "0.71.1");
        assert_eq!(strip_v_prefix("unknown"), "unknown");
    }
}
