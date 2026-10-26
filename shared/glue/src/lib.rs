//! Runs a Craft app's desktop `main()` on Android.
//!
//! The Android build turns each app's binary crate into a `cdylib` whose root is a copy of its
//! `main.rs` (see `shared/scripts/androidify.py`). Its `android_main` calls [`start`] and then the
//! desktop `main()`, unchanged except that `eframe::run_native` becomes [`run_native`] here, which
//! hands eframe the activity: without it, winit can't open a window on Android.

#![cfg(target_os = "android")]

use std::path::Path;
use std::sync::OnceLock;

pub use winit::platform::android::activity::AndroidApp;

static APP: OnceLock<AndroidApp> = OnceLock::new();

/// Logging, panics to logcat, and the folders desktop code expects. Call first in `android_main`.
pub fn start(app: AndroidApp, tag: &str) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag(tag.to_string()));
    std::panic::set_hook(Box::new(|info| log::error!("panic: {info}")));
    if let Some(dir) = app.internal_data_path() {
        point_home_at(&dir);
    }
    let _ = APP.set(app);
}

/// `eframe::run_native` with the activity filled in. Apps that retry with another renderer call
/// this more than once; the activity is shared, so each call gets it.
pub fn run_native(app_name: &str, mut native_options: eframe::NativeOptions, app_creator: eframe::AppCreator<'_>) -> eframe::Result {
    if native_options.android_app.is_none() {
        native_options.android_app = APP.get().cloned();
    }
    eframe::run_native(app_name, native_options, app_creator)
}

/// Desktop code finds settings, data, caches and temporary files through `HOME`, the XDG
/// variables and `TMPDIR`, none of which Android sets: point them into the app's own storage.
fn point_home_at(files_dir: &Path) {
    let cache = files_dir.parent().map(|p| p.join("cache")).unwrap_or_else(|| files_dir.join("cache"));
    let vars = [
        ("HOME", files_dir.to_path_buf()),
        ("XDG_CONFIG_HOME", files_dir.join("config")),
        ("XDG_DATA_HOME", files_dir.join("data")),
        ("XDG_STATE_HOME", files_dir.join("state")),
        ("XDG_CACHE_HOME", cache.clone()),
        ("XDG_RUNTIME_DIR", cache.join("run")),
        ("TMPDIR", cache.join("tmp")),
    ];
    for (name, dir) in vars {
        let _ = std::fs::create_dir_all(&dir);
        // SAFETY: called first thing in `android_main`, before the app starts any thread.
        unsafe { std::env::set_var(name, dir) };
    }
}
