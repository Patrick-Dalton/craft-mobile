//! PdfCraft on Android.
//!
//! The whole desktop UI (`pdfcraft-ui-egui`) runs unchanged inside a `NativeActivity`. What the
//! desktop gets from the operating system comes from the Java side instead
//! (`CraftNativeActivity`, shared by every Craft app):
//! - file pickers: the `rfd` shim asks the activity for the Storage Access Framework pickers;
//! - files opened from other apps ("Open with", "Share"): a thread here waits for them and hands
//!   them to the app as `OsEvent::Open`, like macOS Apple events on the desktop.

#![cfg(target_os = "android")]

use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};

use eframe::egui_winit::winit::platform::android::activity::AndroidApp;
use pdfcraft_ui_egui::{OsEvent, PdfCraftApp};

/// Where the app keeps its settings in eframe's storage (the desktop app's key).
const STORAGE_KEY: &str = "pdfcraft";

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("PdfCraft"));
    std::panic::set_hook(Box::new(|info| log::error!("panic: {info}")));

    let files_dir = app.internal_data_path();
    if let Some(dir) = &files_dir {
        point_home_at(dir);
    }

    let incoming: Arc<Mutex<Vec<String>>> = Arc::default();
    let repaint: Arc<OnceLock<egui::Context>> = Arc::default();
    listen_for_incoming(incoming.clone(), repaint.clone());

    let options = eframe::NativeOptions {
        android_app: Some(app),
        // OpenGL ES runs on every Android GPU; wgpu's Vulkan path does not.
        renderer: eframe::Renderer::Glow,
        persistence_path: files_dir.as_ref().map(|d| d.join("app.ron")),
        ..Default::default()
    };
    let result = eframe::run_native(
        "PdfCraft",
        options,
        Box::new(move |cc| {
            let _ = repaint.set(cc.egui_ctx.clone());
            let mut app = PdfCraftApp::new();
            if let Some(json) = cc.storage.and_then(|s| s.get_string(STORAGE_KEY)) {
                app.restore(&json);
            }
            if let Some(dir) = pdfcraft_ui_egui::RecoveryStore::default_dir() {
                app.enable_recovery(pdfcraft_ui_egui::RecoveryStore::new(dir));
            }
            app.os_events = Some(Box::new(move || {
                let paths: Vec<String> = incoming.lock().map(|mut q| std::mem::take(&mut *q)).unwrap_or_default();
                if paths.is_empty() { Vec::new() } else { vec![OsEvent::Open(paths)] }
            }));
            Ok(Box::new(app))
        }),
    );
    if let Err(e) = result {
        log::error!("PdfCraft stopped: {e}");
    }
}

/// The desktop code finds its settings, recovery and temporary folders through `HOME`, the XDG
/// variables and `TMPDIR`, none of which Android sets: point them all into the app's own storage.
fn point_home_at(files_dir: &Path) {
    let cache = files_dir.parent().map(|p| p.join("cache")).unwrap_or_else(|| files_dir.join("cache"));
    let vars = [
        ("HOME", files_dir.to_path_buf()),
        ("XDG_CONFIG_HOME", files_dir.join("config")),
        ("XDG_DATA_HOME", files_dir.join("data")),
        ("XDG_STATE_HOME", files_dir.join("state")),
        ("XDG_CACHE_HOME", cache.clone()),
        ("TMPDIR", cache.join("tmp")),
    ];
    for (name, dir) in vars {
        let _ = std::fs::create_dir_all(&dir);
        // SAFETY: this runs first thing in `android_main`, before any other thread exists.
        unsafe { std::env::set_var(name, dir) };
    }
}

/// Waits (on its own thread) for files that other apps send to PdfCraft and queues them for the
/// next frame.
fn listen_for_incoming(queue: Arc<Mutex<Vec<String>>>, repaint: Arc<OnceLock<egui::Context>>) {
    let spawned = std::thread::Builder::new().name("incoming files".into()).spawn(move || {
        let ctx = ndk_context::android_context();
        // SAFETY: android-activity stores the process's JavaVM and the activity in ndk-context
        // before calling `android_main`; both outlive this thread.
        let Ok(vm) = (unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }) else { return };
        let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
        let activity = unsafe { jni::objects::JObject::from_raw(ctx.context().cast()) };
        loop {
            let next = env.call_method(&activity, "craftNextIncoming", "()Ljava/lang/String;", &[]).and_then(|v| v.l());
            let path = match next {
                Ok(obj) if !obj.is_null() => env.get_string(&jni::objects::JString::from(obj)).map(String::from).ok(),
                Ok(_) => None,
                Err(e) => {
                    let _ = env.exception_clear();
                    log::warn!("incoming files: {e}");
                    std::thread::sleep(std::time::Duration::from_secs(1));
                    None
                }
            };
            if let Some(path) = path {
                if let Ok(mut q) = queue.lock() {
                    q.push(path);
                }
                if let Some(ctx) = repaint.get() {
                    ctx.request_repaint();
                }
            }
        }
    });
    if let Err(e) = spawned {
        log::warn!("incoming files: {e}");
    }
}
