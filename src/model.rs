use image::{
    imageops::{self, FilterType},
    DynamicImage, RgbImage,
};
use serde::{Deserialize, Serialize};
use std::{fs, path::PathBuf};
#[derive(Clone, Serialize, Deserialize)]
pub struct Settings {
    pub mode: usize,
    pub filter: usize,
    pub quality: u8,
    pub rotate: bool,
    pub brightness: i32,
    pub contrast: i32,
    pub gamma: i32,
    pub sharpen: bool,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            mode: 2,
            filter: 1,
            quality: 85,
            rotate: false,
            brightness: 0,
            contrast: 0,
            gamma: 0,
            sharpen: false,
        }
    }
}
#[derive(Clone)]
pub struct Photo {
    pub path: PathBuf,
    pub name: String,
    pub original: DynamicImage,
    pub settings: Settings,
    pub pixels: RgbImage,
    pub jpeg: Vec<u8>,
}
impl Photo {
    pub fn load(path: PathBuf) -> Result<Self, String> {
        let im = image::open(&path).map_err(|e| e.to_string())?;
        let name = path
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .to_string();
        let mut p = Self {
            path,
            name,
            original: im,
            settings: Settings::default(),
            pixels: RgbImage::new(240, 160),
            jpeg: vec![],
        };
        p.process()?;
        Ok(p)
    }
    pub fn process(&mut self) -> Result<(), String> {
        self.settings.mode = self.settings.mode.min(2);
        self.settings.filter = self.settings.filter.min(3);
        self.settings.quality = self.settings.quality.clamp(5, 100);
        self.settings.gamma = self.settings.gamma.clamp(-15, 100);
        self.settings.brightness = self.settings.brightness.clamp(-100, 100);
        self.settings.contrast = self.settings.contrast.clamp(-100, 100);
        let s = &self.settings;
        let im = if s.rotate {
            self.original.rotate90()
        } else {
            self.original.clone()
        };
        let filter = [
            FilterType::Nearest,
            FilterType::Triangle,
            FilterType::CatmullRom,
            FilterType::Lanczos3,
        ][s.filter.min(3)];
        let mut out = match s.mode {
            0 => {
                let r = im.resize(240, 160, filter).to_rgb8();
                let mut dst = RgbImage::new(240, 160);
                imageops::replace(
                    &mut dst,
                    &r,
                    (240 - r.width()) as i64 / 2,
                    (160 - r.height()) as i64 / 2,
                );
                dst
            }
            1 => im.resize_exact(240, 160, filter).to_rgb8(),
            _ => im.resize_to_fill(240, 160, filter).to_rgb8(),
        };
        if s.sharpen {
            out = imageops::unsharpen(&out, 1.0, 1);
        }
        for p in out.pixels_mut() {
            for c in &mut p.0 {
                let v = (*c as f32 / 255.).powf(1.0 / (1. + s.gamma as f32 / 20.));
                let v = ((v - 0.5) * (1. + s.contrast as f32 / 100.) + 0.5) * 255.
                    + s.brightness as f32 * 2.55;
                *c = v.clamp(0., 255.) as u8;
            }
        }
        let mut jpeg = vec![];
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, s.quality)
            .encode_image(&DynamicImage::ImageRgb8(out))
            .map_err(|e| e.to_string())?;
        let mut pixels = image::load_from_memory(&jpeg)
            .map_err(|e| e.to_string())?
            .to_rgb8();
        for p in pixels.pixels_mut() {
            for c in &mut p.0 {
                *c = ((*c as u16 >> 3) * 255 / 31) as u8;
            }
        }
        self.pixels = pixels;
        self.jpeg = jpeg;
        Ok(())
    }
}
#[derive(Serialize, Deserialize)]
pub struct Project {
    pub title: String,
    pub interval: u32,
    pub images: Vec<(PathBuf, Settings)>,
}
pub fn export(photos: &[Photo], title: &str, interval: u32) -> Result<Vec<u8>, String> {
    if photos.is_empty() {
        return Err("Add at least one image first.".into());
    }
    let mut rom = include_bytes!("../assets/viewer.bin").to_vec();
    while rom.len() % 4 != 0 {
        rom.push(0);
    }
    let base = rom.len();
    let mut album = Vec::new();
    for n in [0x50494353u32, photos.len() as u32, interval, 0] {
        album.extend(n.to_le_bytes());
    }
    album.resize(16 + photos.len() * 36, 0);
    for (i, p) in photos.iter().enumerate() {
        let entry = 16 + i * 36;
        let off = album.len() as u32;
        album[entry..entry + 4].copy_from_slice(&off.to_le_bytes());
        for (j, ch) in p.name.chars().take(30).enumerate() {
            album[entry + 4 + j] = if ch.is_ascii() { ch as u8 } else { b'?' };
        }
        for px in p.pixels.pixels() {
            let c = (px[0] as u16 >> 3) | ((px[1] as u16 >> 3) << 5) | ((px[2] as u16 >> 3) << 10);
            album.extend(c.to_le_bytes());
        }
    }
    // JPEG copies retain the packed source alongside the directly displayable RGB555 frames.
    for p in photos {
        album.extend((p.jpeg.len() as u32).to_le_bytes());
        album.extend(&p.jpeg);
    }
    rom.extend(album);
    if rom.len() > 32 * 1024 * 1024 {
        return Err("This album exceeds the 32 MiB GBA cartridge limit.".into());
    }
    let logo: [u8; 156] = [
        0x24, 0xff, 0xae, 0x51, 0x69, 0x9a, 0xa2, 0x21, 0x3d, 0x84, 0x82, 0x0a, 0x84, 0xe4, 0x09,
        0xad, 0x11, 0x24, 0x8b, 0x98, 0xc0, 0x81, 0x7f, 0x21, 0xa3, 0x52, 0xbe, 0x19, 0x93, 0x09,
        0xce, 0x20, 0x10, 0x46, 0x4a, 0x4a, 0xf8, 0x27, 0x31, 0xec, 0x58, 0xc7, 0xe8, 0x33, 0x82,
        0xe3, 0xce, 0xbf, 0x85, 0xf4, 0xdf, 0x94, 0xce, 0x4b, 0x09, 0xc1, 0x94, 0x56, 0x8a, 0xc0,
        0x13, 0x72, 0xa7, 0xfc, 0x9f, 0x84, 0x4d, 0x73, 0xa3, 0xca, 0x9a, 0x61, 0x58, 0x97, 0xa3,
        0x27, 0xfc, 0x03, 0x98, 0x76, 0x23, 0x1d, 0xc7, 0x61, 0x03, 0x04, 0xae, 0x56, 0xbf, 0x38,
        0x84, 0x00, 0x40, 0xa7, 0x0e, 0xfd, 0xff, 0x52, 0xfe, 0x03, 0x6f, 0x95, 0x30, 0xf1, 0x97,
        0xfb, 0xc0, 0x85, 0x60, 0xd6, 0x80, 0x25, 0xa9, 0x63, 0xbe, 0x03, 0x01, 0x4e, 0x38, 0xe2,
        0xf9, 0xa2, 0x34, 0xff, 0xbb, 0x3e, 0x03, 0x44, 0x78, 0x00, 0x90, 0xcb, 0x88, 0x11, 0x3a,
        0x94, 0x65, 0xc0, 0x7c, 0x63, 0x87, 0xf0, 0x3c, 0xaf, 0xd6, 0x25, 0xe4, 0x8b, 0x38, 0x0a,
        0xac, 0x72, 0x21, 0xd4, 0xf8, 0x07,
    ];
    rom[4..160].copy_from_slice(&logo);
    rom[160..172].fill(b' ');
    for (i, b) in title
        .to_ascii_uppercase()
        .bytes()
        .filter(|b| b.is_ascii_graphic() || *b == b' ')
        .take(12)
        .enumerate()
    {
        rom[160 + i] = b;
    }
    rom[172..176].copy_from_slice(b"PI01");
    rom[176..178].copy_from_slice(b"00");
    rom[178] = 0x96;
    rom[179..189].fill(0);
    rom[189] = rom[160..189]
        .iter()
        .fold(0u8, |a, b| a.wrapping_sub(*b))
        .wrapping_sub(0x19);
    rom[190..192].fill(0);
    let size = rom.len().max(256 * 1024).next_power_of_two();
    rom.resize(size, 0xff);
    assert!(base % 4 == 0);
    Ok(rom)
}
pub fn save(path: &str, photos: &[Photo], title: &str, interval: u32) -> Result<(), String> {
    let p = Project {
        title: title.into(),
        interval,
        images: photos
            .iter()
            .map(|p| (p.path.clone(), p.settings.clone()))
            .collect(),
    };
    fs::write(path, serde_json::to_vec_pretty(&p).unwrap()).map_err(|e| e.to_string())
}
