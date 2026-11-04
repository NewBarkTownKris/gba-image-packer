#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

mod model;
use macroquad::prelude::*;
use model::*;
use std::{fs, path::PathBuf, sync::mpsc};
fn conf() -> Conf {
    Conf {
        window_title: "Game Boy Advance Image Packer".into(),
        window_width: 1000,
        window_height: 630,
        high_dpi: true,
        ..Default::default()
    }
}
static INPUT_BLOCKED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
const BG: Color = Color::new(0.92, 0.92, 0.90, 1.);
const INK: Color = Color::new(0.10, 0.12, 0.16, 1.);
const BLUE: Color = Color::new(0.08, 0.27, 0.55, 1.);
fn label(f: &Font, t: &str, x: f32, y: f32, size: u16, c: Color) {
    draw_text_ex(
        t,
        x,
        y,
        TextParams {
            font: Some(f),
            font_size: size,
            color: c,
            ..Default::default()
        },
    );
}
fn boxx(x: f32, y: f32, w: f32, h: f32, c: Color) {
    draw_rectangle(x, y, w, h, c);
    draw_rectangle_lines(x, y, w, h, 1., GRAY);
}
fn hit(x: f32, y: f32, w: f32, h: f32) -> bool {
    if INPUT_BLOCKED.load(std::sync::atomic::Ordering::Relaxed) {
        return false;
    }
    let (mx, my) = mouse_position();
    let mx = mx * 1000. / screen_width();
    let my = my * 630. / screen_height();
    mx >= x && mx < x + w && my >= y && my < y + h
}
fn button(f: &Font, t: &str, x: f32, y: f32, w: f32, h: f32) -> bool {
    let hover = hit(x, y, w, h);
    boxx(
        x,
        y,
        w,
        h,
        if hover {
            Color::from_hex(0xdce8f6)
        } else {
            Color::from_hex(0xe7e7e4)
        },
    );
    draw_line(x + 1., y + 1., x + w - 1., y + 1., 1., WHITE);
    let tw = measure_text(t, Some(f), 13, 1.).width;
    label(f, t, x + (w - tw) / 2., y + h / 2. + 4., 13, INK);
    hover && is_mouse_button_pressed(MouseButton::Left)
}
fn group(f: &Font, t: &str, x: f32, y: f32, w: f32, h: f32) {
    draw_rectangle_lines(x, y + 6., w, h - 6., 1., GRAY);
    let width = measure_text(t, Some(f), 12, 1.).width;
    draw_rectangle(x + 7., y, width + 6., 15., BG);
    label(f, t, x + 10., y + 11., 12, INK);
}
fn texture(im: &image::RgbImage) -> Texture2D {
    let rgba = image::DynamicImage::ImageRgb8(im.clone()).to_rgba8();
    Texture2D::from_rgba8(im.width() as u16, im.height() as u16, rgba.as_raw())
}
fn preview(tex: &Texture2D, x: f32, y: f32, w: f32, h: f32) {
    boxx(x, y, w, h, Color::from_hex(0x292c30));
    let r = (w / tex.width()).min(h / tex.height());
    let sz = vec2(tex.width() * r, tex.height() * r);
    draw_texture_ex(
        tex,
        x + (w - sz.x) / 2.,
        y + (h - sz.y) / 2.,
        WHITE,
        DrawTextureParams {
            dest_size: Some(sz),
            ..Default::default()
        },
    );
}
fn dialog(kind: &str) -> Option<Vec<String>> {
    match kind {
        "add" => tinyfiledialogs::open_file_dialog_multi(
            "Add images",
            "",
            Some((
                &[
                    "*.jpg", "*.jpeg", "*.JPG", "*.JPEG", "*.png", "*.PNG", "*.bmp", "*.BMP",
                ],
                "Images",
            )),
        ),
        "rom" => {
            tinyfiledialogs::save_file_dialog("Build GBA ROM", "album.GBA").map(|path| vec![path])
        }
        "save" => {
            tinyfiledialogs::save_file_dialog("Save project", "album.json").map(|path| vec![path])
        }
        _ => tinyfiledialogs::open_file_dialog("Open project", "", Some((&["*.json"], "Project")))
            .map(|path| vec![path]),
    }
}
fn refresh(p: &Photo) -> (Texture2D, Texture2D) {
    let original = p.original.thumbnail(720, 480).to_rgb8();
    let a = texture(&original);
    let b = texture(&p.pixels);
    b.set_filter(FilterMode::Nearest);
    (a, b)
}
fn demo() -> Vec<Photo> {
    let mut list = vec![];
    for k in 0..3 {
        let mut im = image::RgbImage::new(480, 300);
        for (x, y, p) in im.enumerate_pixels_mut() {
            let ridge = 160. + 30. * (x as f32 / 80. + k as f32).sin();
            *p = if (y as f32) > ridge {
                image::Rgb([35 + (y / 6) as u8, 70 + (y / 9) as u8, 70])
            } else {
                image::Rgb([100 + (y / 5) as u8, 160 + (y / 8) as u8, 200])
            };
            if (x as i32 - 350).pow(2) + (y as i32 - 65).pow(2) < 900 {
                *p = image::Rgb([255, 221, 153]);
            }
        }
        let path = PathBuf::from(format!("demo-landscape-{}.png", k + 1));
        let mut p = Photo {
            path,
            name: format!("Sample landscape {}", k + 1),
            original: image::DynamicImage::ImageRgb8(im),
            settings: Settings::default(),
            pixels: image::RgbImage::new(240, 160),
            jpeg: vec![],
        };
        p.process().unwrap();
        list.push(p);
    }
    list
}
#[macroquad::main(conf)]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let shot = args
        .iter()
        .position(|a| a == "--screenshot")
        .and_then(|i| args.get(i + 1))
        .cloned();
    let test = args
        .iter()
        .position(|a| a == "--self-test")
        .and_then(|i| args.get(i + 1))
        .cloned();
    let font = load_ttf_font_from_bytes(include_bytes!("../assets/ui.ttf")).unwrap();
    let mut photos: Vec<Photo> = vec![];
    let mut i = 1;
    while i < args.len() {
        if args[i].starts_with("--") {
            i += 2;
            continue;
        }
        if let Ok(p) = Photo::load(PathBuf::from(&args[i])) {
            photos.push(p);
        }
        i += 1;
    }
    if shot.is_some() || test.is_some() {
        if photos.is_empty() {
            photos = demo();
        }
    }
    let mut selected = 0usize;
    let mut scroll = 0usize;
    let mut textures = photos.first().map(refresh);
    let mut status = "Ready. Add JPEG, PNG or BMP images to begin.".to_string();
    let mut title = "MY PICTURES".to_string();
    let mut interval = 5u32;
    let mut options = false;
    let mut title_edit = false;
    let mut playing = false;
    let mut last = get_time();
    let mut frame = 0;
    let (tx, rx) = mpsc::channel::<(String, Option<Vec<String>>)>();
    let mut busy = false;
    let mut self_steps = vec![];
    loop {
        INPUT_BLOCKED.store(options, std::sync::atomic::Ordering::Relaxed);
        if options && is_key_pressed(KeyCode::Escape) {
            options = false;
        }
        clear_background(BG);
        let w = screen_width();
        let h = screen_height();
        let sx = w / 1000.;
        let sy = h / 630.; // UI uses a fixed logical canvas, resized through the camera.
        set_camera(&Camera2D {
            zoom: vec2(2. / 1000., 2. / 630.),
            target: vec2(500., 315.),
            ..Default::default()
        });
        // Hit testing maps resized window coordinates to the logical canvas.
        let _ = (sx, sy);
        for file in get_dropped_files() {
            if let Some(path) = file.path {
                match Photo::load(path) {
                    Ok(p) => {
                        photos.push(p);
                        if textures.is_none() {
                            selected = 0;
                            textures = Some(refresh(&photos[0]));
                        }
                        status = format!("Ready. {} pictures processed.", photos.len());
                    }
                    Err(e) => status = e,
                }
            }
        }
        if let Ok((kind, result)) = rx.try_recv() {
            busy = false;
            if let Some(paths) = result {
                let path = paths.first().cloned().unwrap_or_default();
                match kind.as_str() {
                    "add" => {
                        let mut errors = vec![];
                        for file in &paths {
                            match Photo::load(PathBuf::from(file)) {
                                Ok(p) => photos.push(p),
                                Err(e) => errors.push(e),
                            }
                        }
                        status = if errors.is_empty() {
                            format!("Ready. {} pictures processed.", photos.len())
                        } else {
                            format!("Some files could not be read: {}", errors[0])
                        };
                    }
                    "rom" => {
                        let path = if path.to_lowercase().ends_with(".gba") {
                            path
                        } else {
                            format!("{path}.GBA")
                        };
                        status = match export(&photos, &title, interval).and_then(|bytes| {
                            let size = bytes.len();
                            fs::write(&path, bytes).map_err(|e| e.to_string())?;
                            Ok(size)
                        }) {
                            Ok(n) => {
                                format!("GBA ROM created: {} ({:.1} KiB)", path, n as f32 / 1024.)
                            }
                            Err(e) => e,
                        };
                    }
                    "save" => {
                        status = match save(&path, &photos, &title, interval) {
                            Ok(_) => "Project saved.".into(),
                            Err(e) => e,
                        };
                    }
                    _ => {
                        match fs::read(&path).map_err(|e| e.to_string()).and_then(|b| {
                            serde_json::from_slice::<Project>(&b).map_err(|e| e.to_string())
                        }) {
                            Ok(p) => {
                                let mut loaded = vec![];
                                let mut missing = 0;
                                for (path, s) in p.images {
                                    match Photo::load(path) {
                                        Ok(mut im) => {
                                            im.settings = s;
                                            if im.process().is_ok() {
                                                loaded.push(im)
                                            } else {
                                                missing += 1
                                            }
                                        }
                                        Err(_) => missing += 1,
                                    }
                                }
                                photos = loaded;
                                title = p.title;
                                interval = p.interval.clamp(1, 60);
                                selected = 0;
                                scroll = 0;
                                status = format!(
                                    "Project opened. {} images; {} missing files.",
                                    photos.len(),
                                    missing
                                );
                            }
                            Err(e) => status = e,
                        }
                    }
                }
                if !photos.is_empty() {
                    selected = selected.min(photos.len() - 1);
                    textures = Some(refresh(&photos[selected]));
                } else {
                    textures = None;
                }
            }
        }
        draw_rectangle(0., 0., 1000., 29., BLUE);
        label(&font, "Game Boy Advance Image Packer", 12., 20., 15, WHITE);
        label(
            &font,
            "RUST EDITION",
            884.,
            19.,
            11,
            Color::from_hex(0xc9ddf5),
        );
        let listw = 866.;
        boxx(8., 36., listw, 256., WHITE);
        draw_rectangle(9., 37., listw - 2., 25., Color::from_hex(0xf1f1ed));
        let cols = [16., 345., 420., 518., 610., 666., 760.];
        for (k, t) in [
            "File name",
            "Size",
            "Source",
            "Target",
            "Format",
            "Status",
            "JPEG size",
        ]
        .iter()
        .enumerate()
        {
            label(&font, t, cols[k], 54., 12, INK);
            if k > 0 {
                draw_line(cols[k] - 8., 37., cols[k] - 8., 61., 1., LIGHTGRAY);
            }
        }
        if photos.is_empty() {
            label(
                &font,
                "Your image collection is empty",
                279.,
                155.,
                19,
                GRAY,
            );
            label(
                &font,
                "Add one image or a whole series to create a cartridge.",
                225.,
                180.,
                13,
                GRAY,
            );
        } else {
            if hit(8., 62., 866., 230.) {
                let (_, dy) = mouse_wheel();
                if dy < 0. {
                    scroll = (scroll + 1).min(photos.len().saturating_sub(12));
                } else if dy > 0. {
                    scroll = scroll.saturating_sub(1);
                }
            }
            for (j, p) in photos.iter().enumerate().skip(scroll).take(12) {
                let y = 63. + (j - scroll) as f32 * 18.5;
                if j == selected {
                    draw_rectangle(10., y, 860., 18.5, Color::from_hex(0xcbdcf3));
                }
                if hit(10., y, 860., 18.5) && is_mouse_button_pressed(MouseButton::Left) {
                    selected = j;
                    textures = Some(refresh(p));
                }
                let name = if p.name.chars().count() > 43 {
                    format!("{}…", p.name.chars().take(42).collect::<String>())
                } else {
                    p.name.clone()
                };
                let vals = [
                    name,
                    format!(
                        "{}",
                        fs::metadata(&p.path)
                            .map(|m| m.len() as usize)
                            .unwrap_or(p.original.as_bytes().len())
                    ),
                    format!("{}x{}", p.original.width(), p.original.height()),
                    "240x160".into(),
                    p.path
                        .extension()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .into(),
                    "Ready".into(),
                    format!("{}", p.jpeg.len()),
                ];
                for (k, v) in vals.iter().enumerate() {
                    label(&font, v, cols[k], y + 13., 12, INK);
                }
            }
        }
        let mut action = None;
        for (j, (txt, kind)) in [
            ("Add files…", "add"),
            ("Remove entry", "remove"),
            ("Clear list", "clear"),
            ("Apply to all", "jpeg"),
            ("ROM options…", "options"),
            ("Up", "up"),
            ("Down", "down"),
            ("Open project…", "open"),
            ("Save project…", "save"),
            ("Build GBA ROM…", "rom"),
        ]
        .iter()
        .enumerate()
        {
            let y = 36. + j as f32 * 25.5 + if j >= 5 { 10. } else { 0. };
            if button(&font, txt, 884., y, 108., 22.) && !busy {
                action = Some(*kind);
            }
        }
        if !options && is_key_pressed(KeyCode::Delete) {
            action = Some("remove");
        }
        if !options && is_key_down(KeyCode::LeftControl) && is_key_pressed(KeyCode::O) {
            action = Some("add");
        }
        if let Some(a) = action {
            match a {
                "add" | "open" | "save" | "rom" => {
                    if a == "rom" && photos.is_empty() {
                        status = "Add at least one image before building a ROM.".into();
                    } else {
                        busy = true;
                        let tx = tx.clone();
                        let a = a.to_string();
                        std::thread::spawn(move || {
                            let result = dialog(&a);
                            let _ = tx.send((a, result));
                        });
                    }
                }
                "remove" => {
                    if !photos.is_empty() {
                        photos.remove(selected);
                        selected = selected.min(photos.len().saturating_sub(1));
                        textures = photos.get(selected).map(refresh);
                    }
                }
                "clear" => {
                    photos.clear();
                    selected = 0;
                    scroll = 0;
                    textures = None;
                }
                "up" => {
                    if selected > 0 {
                        photos.swap(selected, selected - 1);
                        selected -= 1;
                    }
                }
                "down" => {
                    if selected + 1 < photos.len() {
                        photos.swap(selected, selected + 1);
                        selected += 1;
                    }
                }
                "options" => options = !options,
                "jpeg" => {
                    if let Some(p) = photos.get(selected) {
                        let settings = p.settings.clone();
                        let mut failed = 0;
                        for p in &mut photos {
                            p.settings = settings.clone();
                            if p.process().is_err() {
                                failed += 1;
                            }
                        }
                        textures = photos.get(selected).map(refresh);
                        status = format!(
                            "Conversion settings applied to {} images. {} failures.",
                            photos.len(),
                            failed
                        );
                    }
                }
                _ => (),
            }
        }
        group(&font, "Cartridge", 8., 302., 984., 43.);
        label(
            &font,
            "240 × 160 pixels  •  RGB555 color  •  .GBA cartridge",
            20.,
            330.,
            13,
            INK,
        );
        label(
            &font,
            &format!(
                "{} images   |   Approx. {:.2} MiB",
                photos.len(),
                (photos.len() as f32 * 76800.
                    + 5000.
                    + photos.iter().map(|p| p.jpeg.len()).sum::<usize>() as f32)
                    / 1048576.
            ),
            570.,
            330.,
            12,
            INK,
        );
        group(&font, "Source", 8., 353., 323., 243.);
        group(&font, "Destination · GBA preview", 339., 353., 323., 243.);
        group(&font, "Summary", 670., 353., 322., 243.);
        if let Some((ref a, ref b)) = textures {
            preview(a, 15., 374., 309., 213.);
            preview(b, 346., 374., 309., 213.);
        } else {
            boxx(15., 374., 309., 213., WHITE);
            boxx(346., 374., 309., 213., WHITE);
            label(&font, "Source image", 125., 487., 14, GRAY);
            label(&font, "240 × 160 preview", 441., 487., 14, GRAY);
        }
        if let Some(p) = photos.get_mut(selected) {
            let old = serde_json::to_string(&p.settings).unwrap();
            label(&font, "Source", 736., 379., 11, INK);
            label(&font, "Dest.", 815., 379., 11, INK);
            for (j, (name, value, dest)) in [
                ("Width", p.original.width(), 240),
                ("Height", p.original.height(), 160),
                ("Bpp", 24, 15),
            ]
            .iter()
            .enumerate()
            {
                let y = 388. + j as f32 * 23.;
                label(&font, name, 682., y + 14., 12, INK);
                for (x, v) in [(736., *value), (810., *dest)] {
                    boxx(x, y, 64., 20., WHITE);
                    label(&font, &v.to_string(), x + 5., y + 14., 12, INK);
                }
            }
            if button(
                &font,
                if p.settings.rotate {
                    "✓ Rotate"
                } else {
                    "Rotate"
                },
                884.,
                389.,
                95.,
                21.,
            ) {
                p.settings.rotate = !p.settings.rotate;
            }
            label(&font, "JPEG quality", 880., 427., 11, INK);
            if button(&font, "−", 882., 437., 22., 20.) {
                p.settings.quality = p.settings.quality.saturating_sub(5).max(5);
            }
            label(
                &font,
                &format!("{}", p.settings.quality),
                913.,
                452.,
                12,
                INK,
            );
            if button(&font, "+", 953., 437., 25., 20.) {
                p.settings.quality = (p.settings.quality + 5).min(100);
            }
            label(&font, "Resize:", 682., 482., 12, INK);
            if button(
                &font,
                [
                    "Fit · keep proportions",
                    "Stretch to 240 × 160",
                    "Fill · center crop",
                ][p.settings.mode],
                736.,
                466.,
                243.,
                23.,
            ) {
                p.settings.mode = (p.settings.mode + 1) % 3;
            }
            label(&font, "Mode:", 682., 510., 12, INK);
            if button(
                &font,
                ["Nearest neighbor", "Bilinear", "Bicubic", "Lanczos 3"][p.settings.filter],
                736.,
                494.,
                243.,
                23.,
            ) {
                p.settings.filter = (p.settings.filter + 1) % 4;
            }
            for (j, n) in ["Gamma", "Brightness", "Contrast"].iter().enumerate() {
                let y = 526. + j as f32 * 21.;
                label(&font, n, 682., y + 14., 12, INK);
                let val = match j {
                    0 => &mut p.settings.gamma,
                    1 => &mut p.settings.brightness,
                    _ => &mut p.settings.contrast,
                };
                if button(&font, "−", 759., y, 23., 19.) {
                    *val = (*val - 5).max(if j == 0 { -15 } else { -100 });
                }
                boxx(787., y, 52., 19., WHITE);
                label(&font, &val.to_string(), 798., y + 14., 12, INK);
                if button(&font, "+", 844., y, 23., 19.) {
                    *val = (*val + 5).min(100);
                }
            }
            if button(
                &font,
                if p.settings.sharpen {
                    "✓ Sharpen"
                } else {
                    "Sharpen"
                },
                880.,
                527.,
                99.,
                21.,
            ) {
                p.settings.sharpen = !p.settings.sharpen;
            }
            if button(&font, "Reset", 880., 555., 99., 21.) {
                p.settings = Settings::default();
            }
            if serde_json::to_string(&p.settings).unwrap() != old {
                match p.process() {
                    Ok(_) => {
                        textures = Some(refresh(p));
                        status =
                            "Preview updated. Settings are saved separately for each image.".into();
                    }
                    Err(e) => status = e,
                }
            }
        }
        draw_line(0., 603., 1000., 603., 1., GRAY);
        let stat = if busy {
            "Waiting for file selection…"
        } else {
            &status
        };
        label(
            &font,
            &stat.chars().take(112).collect::<String>(),
            10.,
            621.,
            12,
            INK,
        );
        if options {
            INPUT_BLOCKED.store(false, std::sync::atomic::Ordering::Relaxed);
            boxx(220., 150., 560., 290., BG);
            draw_rectangle(221., 151., 558., 28., BLUE);
            label(&font, "ROM options & viewer preview", 235., 171., 15, WHITE);
            label(
                &font,
                "Cartridge title (click to edit)",
                242.,
                205.,
                13,
                INK,
            );
            boxx(242., 216., 515., 30., WHITE);
            label(&font, &title, 251., 237., 15, INK);
            if hit(242., 216., 515., 30.) && is_mouse_button_pressed(MouseButton::Left) {
                title_edit = true;
            }
            if title_edit {
                while let Some(c) = get_char_pressed() {
                    if c.is_ascii_alphanumeric() || c == ' ' {
                        if title.len() < 12 {
                            title.push(c.to_ascii_uppercase());
                        }
                    }
                }
                if is_key_pressed(KeyCode::Backspace) {
                    title.pop();
                }
                if is_key_pressed(KeyCode::Enter) {
                    title_edit = false;
                }
            }
            label(
                &font,
                &format!("Slideshow interval: {} seconds", interval),
                242.,
                277.,
                14,
                INK,
            );
            if button(&font, "−", 580., 258., 40., 26.) {
                interval = interval.saturating_sub(1).max(1);
            }
            if button(&font, "+", 630., 258., 40., 26.) {
                interval = (interval + 1).min(60);
            }
            label(
                &font,
                "On GBA: L / R browse • Select help • Start + R slideshow",
                242.,
                310.,
                13,
                INK,
            );
            label(
                &font,
                "Start + L image menu • A pause • B return to menu",
                242.,
                333.,
                13,
                INK,
            );
            if button(
                &font,
                if playing {
                    "Stop preview"
                } else {
                    "Preview slideshow"
                },
                242.,
                367.,
                175.,
                29.,
            ) {
                playing = !playing;
                last = get_time();
            }
            if button(&font, "Done", 643., 392., 112., 28.) {
                options = false;
                title_edit = false;
            }
        }
        if playing && !photos.is_empty() && get_time() - last >= interval as f64 {
            selected = (selected + 1) % photos.len();
            textures = Some(refresh(&photos[selected]));
            last = get_time();
        }
        frame += 1;
        if test.is_some() {
            if frame == 20 {
                selected = 1;
                textures = Some(refresh(&photos[selected]));
                self_steps.push(serde_json::json!({"step":"move","passed":selected==1,"detail":"Selected the next image in the collection."}));
            }
            if frame == 40 {
                photos[selected].settings.mode = 0;
                photos[selected].process().unwrap();
                textures = Some(refresh(&photos[selected]));
                let rom = export(&photos, &title, interval).unwrap();
                let path = PathBuf::from(test.as_ref().unwrap())
                    .parent()
                    .unwrap_or(std::path::Path::new("."))
                    .join("self-test.GBA");
                fs::write(path, &rom).unwrap();
                let checksum = rom[160..190].iter().fold(0u8, |a, b| a.wrapping_add(*b));
                self_steps.push(serde_json::json!({"step":"act","passed":rom[178]==0x96&&checksum==0xe7&&photos[selected].pixels.dimensions()==(240,160),"detail":format!("Converted image and exported {} byte ROM with valid GBA header checksum.",rom.len())}));
            }
            if frame == 100 {
                self_steps.push(serde_json::json!({"step":"hud","passed":textures.is_some()&&!photos.is_empty(),"detail":"Rendered image list, source and destination previews, dimensions, conversion controls and status."}));
                fs::write(
                    test.as_ref().unwrap(),
                    serde_json::to_vec_pretty(&serde_json::json!({"version":1,"steps":self_steps}))
                        .unwrap(),
                )
                .unwrap();
            }
        }
        set_default_camera();
        if frame >= 120 {
            if let Some(path) = &shot {
                get_screen_data().export_png(path);
            }
            if shot.is_some() || test.is_some() {
                break;
            }
        }
        next_frame().await;
    }
}
