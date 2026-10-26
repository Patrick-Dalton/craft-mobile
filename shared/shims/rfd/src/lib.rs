//! The part of rfd's API the Craft apps use, for Android.
//!
//! Each picker calls `craftPick` on the running `CraftNativeActivity`, which shows the system
//! picker and blocks the calling (worker) thread until the user answers. Files come back as
//! local copies; the activity writes changes to them back to the documents they came from, and
//! a "save" path is a fresh local file that it copies to the chosen document once written.

use std::future::Future;
use std::path::{Path, PathBuf};

/// What the activity should show (its `craftPick` `kind`).
#[derive(Clone, Copy, Debug)]
#[repr(i32)]
enum Kind {
    File = 0,
    Files = 1,
    Folder = 2,
    Save = 3,
}

#[derive(Clone, Debug, Default)]
pub struct AsyncFileDialog {
    filters: Vec<(String, Vec<String>)>,
    title: Option<String>,
    file_name: Option<String>,
}

impl AsyncFileDialog {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn add_filter(mut self, name: impl Into<String>, extensions: &[impl ToString]) -> Self {
        self.filters.push((name.into(), extensions.iter().map(ToString::to_string).collect()));
        self
    }

    pub fn set_title(mut self, title: impl Into<String>) -> Self {
        self.title = Some(title.into());
        self
    }

    pub fn set_file_name(mut self, file_name: impl Into<String>) -> Self {
        self.file_name = Some(file_name.into());
        self
    }

    /// Android's pickers start where the user last was; a starting folder can't be given.
    pub fn set_directory<P: AsRef<Path>>(self, _path: P) -> Self {
        self
    }

    pub fn set_can_create_directories(self, _can: bool) -> Self {
        self
    }

    pub fn set_parent<W: ?Sized>(self, _parent: &W) -> Self {
        self
    }

    pub fn pick_file(self) -> impl Future<Output = Option<FileHandle>> + Send {
        async move { pick(Kind::File, &self).into_iter().next().map(FileHandle) }
    }

    pub fn pick_files(self) -> impl Future<Output = Option<Vec<FileHandle>>> + Send {
        async move {
            let paths = pick(Kind::Files, &self);
            (!paths.is_empty()).then(|| paths.into_iter().map(FileHandle).collect())
        }
    }

    pub fn pick_folder(self) -> impl Future<Output = Option<FileHandle>> + Send {
        async move { pick(Kind::Folder, &self).into_iter().next().map(FileHandle) }
    }

    pub fn pick_folders(self) -> impl Future<Output = Option<Vec<FileHandle>>> + Send {
        async move {
            let paths = pick(Kind::Folder, &self);
            (!paths.is_empty()).then(|| paths.into_iter().map(FileHandle).collect())
        }
    }

    pub fn save_file(self) -> impl Future<Output = Option<FileHandle>> + Send {
        async move { pick(Kind::Save, &self).into_iter().next().map(FileHandle) }
    }
}

/// The blocking dialog, for code that still uses it. It must not run on the UI thread.
#[derive(Clone, Debug, Default)]
pub struct FileDialog(AsyncFileDialog);

impl FileDialog {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn add_filter(self, name: impl Into<String>, extensions: &[impl ToString]) -> Self {
        Self(self.0.add_filter(name, extensions))
    }

    pub fn set_title(self, title: impl Into<String>) -> Self {
        Self(self.0.set_title(title))
    }

    pub fn set_file_name(self, file_name: impl Into<String>) -> Self {
        Self(self.0.set_file_name(file_name))
    }

    pub fn set_directory<P: AsRef<Path>>(self, _path: P) -> Self {
        self
    }

    pub fn set_can_create_directories(self, _can: bool) -> Self {
        self
    }

    pub fn set_parent<W: ?Sized>(self, _parent: &W) -> Self {
        self
    }

    pub fn pick_file(self) -> Option<PathBuf> {
        pick(Kind::File, &self.0).into_iter().next()
    }

    pub fn pick_files(self) -> Option<Vec<PathBuf>> {
        let paths = pick(Kind::Files, &self.0);
        (!paths.is_empty()).then_some(paths)
    }

    pub fn pick_folder(self) -> Option<PathBuf> {
        pick(Kind::Folder, &self.0).into_iter().next()
    }

    pub fn save_file(self) -> Option<PathBuf> {
        pick(Kind::Save, &self.0).into_iter().next()
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileHandle(PathBuf);

impl FileHandle {
    pub fn path(&self) -> &Path {
        &self.0
    }

    pub fn file_name(&self) -> String {
        self.0.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
    }

    pub async fn read(&self) -> Vec<u8> {
        std::fs::read(&self.0).unwrap_or_default()
    }

    pub async fn write(&self, data: &[u8]) -> std::io::Result<()> {
        std::fs::write(&self.0, data)
    }
}

impl From<PathBuf> for FileHandle {
    fn from(path: PathBuf) -> Self {
        Self(path)
    }
}

impl From<&Path> for FileHandle {
    fn from(path: &Path) -> Self {
        Self(path.to_path_buf())
    }
}

// ---- Message boxes ------------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum MessageLevel {
    #[default]
    Info,
    Warning,
    Error,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum MessageButtons {
    #[default]
    Ok,
    OkCancel,
    YesNo,
    YesNoCancel,
    OkCustom(String),
    OkCancelCustom(String, String),
    YesNoCancelCustom(String, String, String),
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum MessageDialogResult {
    Yes,
    No,
    #[default]
    Ok,
    Cancel,
    Custom(String),
}

impl std::fmt::Display for MessageDialogResult {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Yes => f.write_str("Yes"),
            Self::No => f.write_str("No"),
            Self::Ok => f.write_str("Ok"),
            Self::Cancel => f.write_str("Cancel"),
            Self::Custom(s) => f.write_str(s),
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct MessageDialog {
    level: MessageLevel,
    title: String,
    description: String,
    buttons: MessageButtons,
}

impl MessageDialog {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set_level(mut self, level: MessageLevel) -> Self {
        self.level = level;
        self
    }

    pub fn set_title(mut self, title: impl Into<String>) -> Self {
        self.title = title.into();
        self
    }

    pub fn set_description(mut self, text: impl Into<String>) -> Self {
        self.description = text.into();
        self
    }

    pub fn set_buttons(mut self, buttons: MessageButtons) -> Self {
        self.buttons = buttons;
        self
    }

    pub fn set_parent<W: ?Sized>(self, _parent: &W) -> Self {
        self
    }

    /// Shows the box and waits for the answer. The Rust app runs on its own thread, so this
    /// blocks only that thread; Android's UI thread shows the dialog.
    pub fn show(self) -> MessageDialogResult {
        show_message(&self)
    }
}

#[derive(Clone, Debug, Default)]
pub struct AsyncMessageDialog(MessageDialog);

impl AsyncMessageDialog {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set_level(self, level: MessageLevel) -> Self {
        Self(self.0.set_level(level))
    }

    pub fn set_title(self, title: impl Into<String>) -> Self {
        Self(self.0.set_title(title))
    }

    pub fn set_description(self, text: impl Into<String>) -> Self {
        Self(self.0.set_description(text))
    }

    pub fn set_buttons(self, buttons: MessageButtons) -> Self {
        Self(self.0.set_buttons(buttons))
    }

    pub fn set_parent<W: ?Sized>(self, _parent: &W) -> Self {
        self
    }

    pub fn show(self) -> impl Future<Output = MessageDialogResult> + Send {
        async move { show_message(&self.0) }
    }
}

/// `craftMessage`'s button kinds: the activity labels the standard ones in the system's language.
fn button_kind(buttons: &MessageButtons) -> (i32, Vec<String>) {
    match buttons {
        MessageButtons::Ok => (0, vec![]),
        MessageButtons::OkCancel => (1, vec![]),
        MessageButtons::YesNo => (2, vec![]),
        MessageButtons::YesNoCancel => (3, vec![]),
        MessageButtons::OkCustom(a) => (4, vec![a.clone()]),
        MessageButtons::OkCancelCustom(a, b) => (4, vec![a.clone(), b.clone()]),
        MessageButtons::YesNoCancelCustom(a, b, c) => (4, vec![a.clone(), b.clone(), c.clone()]),
    }
}

/// The answer for the button the user pressed (0 = first), or for dismissing the box (-1).
fn message_result(buttons: &MessageButtons, pressed: i32) -> MessageDialogResult {
    use MessageDialogResult as R;
    match (buttons, pressed) {
        (MessageButtons::Ok, _) => R::Ok,
        (MessageButtons::OkCancel, 0) => R::Ok,
        (MessageButtons::OkCancel, _) => R::Cancel,
        (MessageButtons::YesNo, 0) => R::Yes,
        (MessageButtons::YesNo, _) => R::No,
        (MessageButtons::YesNoCancel, 0) => R::Yes,
        (MessageButtons::YesNoCancel, 1) => R::No,
        (MessageButtons::YesNoCancel, _) => R::Cancel,
        (MessageButtons::OkCustom(a), _) => R::Custom(a.clone()),
        (MessageButtons::OkCancelCustom(a, _), 0) => R::Custom(a.clone()),
        (MessageButtons::OkCancelCustom(_, b), _) => R::Custom(b.clone()),
        (MessageButtons::YesNoCancelCustom(a, _, _), 0) => R::Custom(a.clone()),
        (MessageButtons::YesNoCancelCustom(_, b, _), 1) => R::Custom(b.clone()),
        (MessageButtons::YesNoCancelCustom(_, _, c), _) => R::Custom(c.clone()),
    }
}

#[cfg(target_os = "android")]
fn show_message(dialog: &MessageDialog) -> MessageDialogResult {
    let (kind, labels) = button_kind(&dialog.buttons);
    let level = match dialog.level {
        MessageLevel::Info => 0,
        MessageLevel::Warning => 1,
        MessageLevel::Error => 2,
    };
    let pressed = android::message(level, &dialog.title, &dialog.description, kind, &labels).unwrap_or_else(|e| {
        eprintln!("rfd (Android): message box failed: {e}");
        -1
    });
    message_result(&dialog.buttons, pressed)
}

#[cfg(not(target_os = "android"))]
fn show_message(dialog: &MessageDialog) -> MessageDialogResult {
    let _ = button_kind(&dialog.buttons);
    message_result(&dialog.buttons, -1)
}

/// The MIME types the picker should offer for the dialog's filters. An extension we don't
/// know widens the choice to every file rather than hiding the one the user wants.
fn mime_types(filters: &[(String, Vec<String>)]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for ext in filters.iter().flat_map(|(_, exts)| exts) {
        let mime = match ext.trim_start_matches('.').to_ascii_lowercase().as_str() {
            "pdf" => "application/pdf",
            "png" => "image/png",
            "jpg" | "jpeg" => "image/jpeg",
            "gif" => "image/gif",
            "webp" => "image/webp",
            "bmp" => "image/bmp",
            "tif" | "tiff" => "image/tiff",
            "svg" => "image/svg+xml",
            "heic" | "heif" => "image/heif",
            "txt" => "text/plain",
            "csv" => "text/csv",
            "html" | "htm" => "text/html",
            "xml" => "text/xml",
            "json" => "application/json",
            "rtf" => "application/rtf",
            "fdf" => "application/vnd.fdf",
            "xfdf" => "application/vnd.adobe.xfdf",
            "doc" => "application/msword",
            "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" => "application/vnd.ms-excel",
            "xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" => "application/vnd.ms-powerpoint",
            "pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "p12" | "pfx" => "application/x-pkcs12",
            "cer" | "crt" | "pem" => "application/x-x509-ca-cert",
            "ttf" => "font/ttf",
            "otf" => "font/otf",
            "zip" => "application/zip",
            _ => "*/*",
        };
        if !out.iter().any(|m| m == mime) {
            out.push(mime.to_string());
        }
    }
    if out.iter().any(|m| m == "*/*") {
        out = vec!["*/*".to_string()];
    }
    out
}

#[cfg(target_os = "android")]
fn pick(kind: Kind, dialog: &AsyncFileDialog) -> Vec<PathBuf> {
    match android::pick(kind as i32, &mime_types(&dialog.filters), dialog.file_name.as_deref().unwrap_or(""), dialog.title.as_deref().unwrap_or("")) {
        Ok(paths) => paths,
        Err(e) => {
            eprintln!("rfd (Android): picker failed: {e}");
            Vec::new()
        }
    }
}

#[cfg(not(target_os = "android"))]
fn pick(_kind: Kind, dialog: &AsyncFileDialog) -> Vec<PathBuf> {
    let _ = mime_types(&dialog.filters);
    Vec::new()
}

#[cfg(target_os = "android")]
mod android {
    use std::path::PathBuf;

    use jni::objects::{JObject, JObjectArray, JString, JValue};

    pub(crate) fn pick(kind: i32, mimes: &[String], name: &str, title: &str) -> jni::errors::Result<Vec<PathBuf>> {
        let ctx = ndk_context::android_context();
        // SAFETY: android-activity puts the process's JavaVM and the activity in ndk-context
        // before the app starts, and both live as long as the process.
        let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }?;
        let mut env = vm.attach_current_thread()?;
        let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
        let result = (|| {
            let array = env.new_object_array(mimes.len() as i32, "java/lang/String", JObject::null())?;
            for (i, mime) in mimes.iter().enumerate() {
                let s = env.new_string(mime)?;
                env.set_object_array_element(&array, i as i32, s)?;
            }
            let name = env.new_string(name)?;
            let title = env.new_string(title)?;
            let out = env
                .call_method(
                    &activity,
                    "craftPick",
                    "(I[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",
                    &[JValue::Int(kind), JValue::Object(&array), JValue::Object(&name), JValue::Object(&title)],
                )?
                .l()?;
            if out.is_null() {
                return Ok(Vec::new());
            }
            let out = JObjectArray::from(out);
            let len = env.get_array_length(&out)?;
            let mut paths = Vec::with_capacity(len as usize);
            for i in 0..len {
                let item = JString::from(env.get_object_array_element(&out, i)?);
                let path: String = env.get_string(&item)?.into();
                paths.push(PathBuf::from(path));
            }
            Ok(paths)
        })();
        if result.is_err() {
            let _ = env.exception_clear();
        }
        result
    }

    pub(crate) fn message(level: i32, title: &str, text: &str, kind: i32, labels: &[String]) -> jni::errors::Result<i32> {
        let ctx = ndk_context::android_context();
        // SAFETY: as in `pick`.
        let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }?;
        let mut env = vm.attach_current_thread()?;
        let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
        let result = (|| {
            let array = env.new_object_array(labels.len() as i32, "java/lang/String", JObject::null())?;
            for (i, label) in labels.iter().enumerate() {
                let s = env.new_string(label)?;
                env.set_object_array_element(&array, i as i32, s)?;
            }
            let title = env.new_string(title)?;
            let text = env.new_string(text)?;
            env.call_method(
                &activity,
                "craftMessage",
                "(ILjava/lang/String;Ljava/lang/String;I[Ljava/lang/String;)I",
                &[JValue::Int(level), JValue::Object(&title), JValue::Object(&text), JValue::Int(kind), JValue::Object(&array)],
            )?
            .i()
        })();
        if result.is_err() {
            let _ = env.exception_clear();
        }
        result
    }
}
