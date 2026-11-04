#[allow(dead_code)]
#[path = "../../../src/model.rs"]
mod model;

use jni::objects::{JClass, JString};
use jni::sys::jlong;
use jni::JNIEnv;
use model::{Photo, Settings};
use serde::Deserialize;
use std::{fs, panic::AssertUnwindSafe, path::PathBuf};

const FRAME_BYTES: usize = 240 * 160 * 3;

fn string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|e| e.to_string())
}

fn guarded<T: Default>(env: &mut JNIEnv, f: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> T {
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| f(env)))
        .unwrap_or_else(|_| Err("The image could not be processed.".into()));
    match result {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            T::default()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_newbarktownkris_gbaimagepacker_Native_process(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    settings: JString,
    destination: JString,
) {
    guarded(&mut env, |env| {
        let source = string(env, &source)?;
        let settings: Settings = serde_json::from_str(&string(env, &settings)?)
            .map_err(|e| format!("Invalid image settings: {e}"))?;
        let destination = string(env, &destination)?;
        let original = image::open(&source).map_err(|e| e.to_string())?;
        let mut photo = Photo {
            path: PathBuf::from(source),
            name: String::new(),
            original,
            settings,
            pixels: image::RgbImage::new(240, 160),
            jpeg: Vec::new(),
        };
        photo.process()?;
        let mut frame = photo.pixels.into_raw();
        frame.extend(photo.jpeg);
        let temporary = format!("{destination}.tmp");
        fs::write(&temporary, frame).map_err(|e| e.to_string())?;
        fs::rename(&temporary, destination).map_err(|e| e.to_string())
    });
}

#[derive(Deserialize)]
struct Frame {
    path: PathBuf,
    name: String,
}

#[no_mangle]
pub extern "system" fn Java_io_github_newbarktownkris_gbaimagepacker_Native_build(
    mut env: JNIEnv,
    _class: JClass,
    frames: JString,
    title: JString,
    interval: jni::sys::jint,
    destination: JString,
) -> jlong {
    guarded(&mut env, |env| {
        let frames: Vec<Frame> = serde_json::from_str(&string(env, &frames)?)
            .map_err(|e| format!("Invalid album: {e}"))?;
        if frames.is_empty() || frames.len() > 400 {
            return Err("Choose between 1 and 400 images.".into());
        }
        let title = string(env, &title)?;
        let destination = string(env, &destination)?;
        let mut photos = Vec::with_capacity(frames.len());
        let mut total = 0usize;
        for frame in frames {
            let bytes = fs::read(&frame.path).map_err(|e| e.to_string())?;
            if bytes.len() <= FRAME_BYTES || bytes.len() > FRAME_BYTES + 1024 * 1024 {
                return Err("An image needs to be converted again.".into());
            }
            total += 240 * 160 * 2 + bytes.len() - FRAME_BYTES + 40;
            if total + 4096 > 32 * 1024 * 1024 {
                return Err("This album exceeds the 32 MiB GBA cartridge limit.".into());
            }
            photos.push(Photo {
                path: frame.path,
                name: frame.name,
                original: image::DynamicImage::new_rgb8(1, 1),
                settings: Settings::default(),
                pixels: image::RgbImage::from_raw(240, 160, bytes[..FRAME_BYTES].to_vec())
                    .ok_or("Invalid image frame")?,
                jpeg: bytes[FRAME_BYTES..].to_vec(),
            });
        }
        let bytes = model::export(&photos, &title, interval.clamp(1, 60) as u32)?;
        let length = bytes.len() as jlong;
        let temporary = format!("{destination}.tmp");
        fs::write(&temporary, bytes).map_err(|e| e.to_string())?;
        fs::rename(&temporary, destination).map_err(|e| e.to_string())?;
        Ok(length)
    })
}
