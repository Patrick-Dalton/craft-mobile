//! The part of arboard's API the Craft apps use, for Android. Text uses the system clipboard
//! (`craftClipboardGet` / `craftClipboardSet` on the activity); images and file lists report
//! `ClipboardNotSupported`, which the apps already handle as "nothing to paste".

use std::borrow::Cow;
use std::path::Path;

#[derive(Debug, Clone)]
pub struct ImageData<'a> {
    pub width: usize,
    pub height: usize,
    pub bytes: Cow<'a, [u8]>,
}

impl ImageData<'_> {
    pub fn into_owned_bytes(self) -> Cow<'static, [u8]> {
        Cow::Owned(self.bytes.into_owned())
    }

    pub fn to_owned_img(&self) -> ImageData<'static> {
        ImageData { width: self.width, height: self.height, bytes: Cow::Owned(self.bytes.to_vec()) }
    }
}

#[derive(Debug)]
#[non_exhaustive]
pub enum Error {
    ContentNotAvailable,
    ClipboardNotSupported,
    ClipboardOccupied,
    ConversionFailure,
    Unknown { description: String },
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::ContentNotAvailable => f.write_str("The clipboard contents were not available in the requested format"),
            Error::ClipboardNotSupported => f.write_str("This kind of clipboard content isn't supported on Android"),
            Error::ClipboardOccupied => f.write_str("The clipboard is in use"),
            Error::ConversionFailure => f.write_str("The clipboard contents couldn't be converted"),
            Error::Unknown { description } => write!(f, "Clipboard error: {description}"),
        }
    }
}

impl std::error::Error for Error {}

pub struct Clipboard(());

impl Clipboard {
    pub fn new() -> Result<Self, Error> {
        Ok(Self(()))
    }

    pub fn get_text(&mut self) -> Result<String, Error> {
        platform::get_text()
    }

    pub fn set_text<'a, T: Into<Cow<'a, str>>>(&mut self, text: T) -> Result<(), Error> {
        platform::set_text(&text.into())
    }

    pub fn get_image(&mut self) -> Result<ImageData<'static>, Error> {
        Err(Error::ClipboardNotSupported)
    }

    pub fn set_image(&mut self, _image: ImageData) -> Result<(), Error> {
        Err(Error::ClipboardNotSupported)
    }

    pub fn clear(&mut self) -> Result<(), Error> {
        platform::set_text("")
    }

    pub fn get(&mut self) -> Get<'_> {
        Get { clipboard: self }
    }

    pub fn set(&mut self) -> Set<'_> {
        Set { clipboard: self }
    }
}

pub struct Get<'clipboard> {
    clipboard: &'clipboard mut Clipboard,
}

impl Get<'_> {
    pub fn text(self) -> Result<String, Error> {
        self.clipboard.get_text()
    }

    pub fn image(self) -> Result<ImageData<'static>, Error> {
        Err(Error::ClipboardNotSupported)
    }

    pub fn html(self) -> Result<String, Error> {
        Err(Error::ClipboardNotSupported)
    }

    pub fn file_list(self) -> Result<Vec<std::path::PathBuf>, Error> {
        Err(Error::ClipboardNotSupported)
    }
}

pub struct Set<'clipboard> {
    clipboard: &'clipboard mut Clipboard,
}

impl Set<'_> {
    pub fn text<'a, T: Into<Cow<'a, str>>>(self, text: T) -> Result<(), Error> {
        self.clipboard.set_text(text)
    }

    pub fn html<'a, T: Into<Cow<'a, str>>>(self, _html: T, alt_text: Option<T>) -> Result<(), Error> {
        match alt_text {
            Some(text) => self.clipboard.set_text(text),
            None => Err(Error::ClipboardNotSupported),
        }
    }

    pub fn image(self, _image: ImageData) -> Result<(), Error> {
        Err(Error::ClipboardNotSupported)
    }

    pub fn file_list(self, _paths: &[impl AsRef<Path>]) -> Result<(), Error> {
        Err(Error::ClipboardNotSupported)
    }
}

#[cfg(target_os = "android")]
mod platform {
    use jni::objects::{JObject, JString, JValue};

    use super::Error;

    fn with_activity<T>(f: impl FnOnce(&mut jni::JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T, Error> {
        let ctx = ndk_context::android_context();
        // SAFETY: android-activity puts the JavaVM and the activity in ndk-context before the
        // app starts; both live as long as the process.
        let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }.map_err(unknown)?;
        let mut env = vm.attach_current_thread().map_err(unknown)?;
        let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
        let result = f(&mut env, &activity);
        if result.is_err() {
            let _ = env.exception_clear();
        }
        result.map_err(unknown)
    }

    fn unknown(e: jni::errors::Error) -> Error {
        Error::Unknown { description: e.to_string() }
    }

    pub(super) fn get_text() -> Result<String, Error> {
        let text = with_activity(|env, activity| {
            let obj = env.call_method(activity, "craftClipboardGet", "()Ljava/lang/String;", &[])?.l()?;
            if obj.is_null() {
                return Ok(None);
            }
            Ok(Some(env.get_string(&JString::from(obj))?.into()))
        })?;
        text.ok_or(Error::ContentNotAvailable)
    }

    pub(super) fn set_text(text: &str) -> Result<(), Error> {
        with_activity(|env, activity| {
            let s = env.new_string(text)?;
            env.call_method(activity, "craftClipboardSet", "(Ljava/lang/String;)V", &[JValue::Object(&s)])?;
            Ok(())
        })
    }
}

#[cfg(not(target_os = "android"))]
mod platform {
    use super::Error;

    pub(super) fn get_text() -> Result<String, Error> {
        Err(Error::ClipboardNotSupported)
    }

    pub(super) fn set_text(_text: &str) -> Result<(), Error> {
        Err(Error::ClipboardNotSupported)
    }
}
