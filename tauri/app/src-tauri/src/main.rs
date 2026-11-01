// 桌面端入口；Android / iOS 由 lib 的 mobile_entry_point 承接（M4）
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    frpc_app_lib::run()
}
