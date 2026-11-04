#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0501
#endif
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <objidl.h>
#include <commdlg.h>
#include <shellapi.h>
#include <gdiplus.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include "viewer_blob.h"
using namespace Gdiplus;

enum { MAX_IMAGES = 400, IMAGE_WIDTH = 240, IMAGE_HEIGHT = 160, PIXELS = 38400 };
struct Settings {
    int mode, filter, quality, rotate;
};
struct Photo {
    WCHAR path[MAX_PATH];
    Settings settings;
    UINT width, height;
    uint16_t *frame;
    BYTE *jpeg;
    DWORD jpegSize;
};
static Photo photos[MAX_IMAGES];
static int photoCount = 0, selected = -1;
static HWND windowHandle, listHandle, modeHandle, filterHandle, qualityHandle, rotateHandle,
    titleHandle, intervalHandle, statusHandle;
static HWND controls[16];
static int controlCount = 0;
static Bitmap *sourcePreview = NULL;
static DWORD previewPixels[PIXELS];
static CLSID jpegEncoder, pngEncoder, bmpEncoder;
static bool busy = false;
static HINSTANCE appInstance;
static WCHAR lastError[256];
static Settings defaults() {
    Settings s = {2, 1, 85, 0};
    return s;
}
static void fail(const WCHAR *message) { lstrcpynW(lastError, message, 256); }
static void *allocate(SIZE_T count) { return HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, count); }
static void release(void *memory) {
    if (memory)
        HeapFree(GetProcessHeap(), 0, memory);
}
static bool writeBytes(HANDLE file, const void *bytes, DWORD count) {
    DWORD done = 0;
    return WriteFile(file, bytes, count, &done, NULL) && done == count;
}
static const WCHAR *filename(const WCHAR *path) {
    const WCHAR *name = path;
    for (const WCHAR *p = path; *p; p++)
        if (*p == L'\\' || *p == L'/')
            name = p + 1;
    return name;
}
static bool joinPath(WCHAR *output, const WCHAR *directory, const WCHAR *name) {
    if (lstrlenW(directory) + lstrlenW(name) + 2 >= MAX_PATH)
        return false;
    lstrcpyW(output, directory);
    lstrcatW(output, L"\\");
    lstrcatW(output, name);
    return true;
}
static bool findEncoder(const WCHAR *mime, CLSID *clsid) {
    UINT count = 0, size = 0;
    if (GetImageEncodersSize(&count, &size) != Ok || !size)
        return false;
    ImageCodecInfo *items = (ImageCodecInfo *)allocate(size);
    if (!items)
        return false;
    bool found = false;
    if (GetImageEncoders(count, size, items) == Ok)
        for (UINT n = 0; n < count; n++)
            if (!lstrcmpW(items[n].MimeType, mime)) {
                *clsid = items[n].Clsid;
                found = true;
                break;
            }
    release(items);
    return found;
}
static void freePhoto(Photo *photo) {
    release(photo->frame);
    release(photo->jpeg);
    photo->frame = NULL;
    photo->jpeg = NULL;
    photo->jpegSize = 0;
}
static bool convert(Photo *photo) {
    Bitmap original(photo->path, FALSE);
    if (original.GetLastStatus() != Ok) {
        fail(L"Cannot decode this image. Choose a JPEG, PNG or BMP file.");
        return false;
    }
    UINT width = original.GetWidth(), height = original.GetHeight();
    if (!width || !height || width > 65535 || height > 65535 ||
        (uint64_t)width * height > 64000000) {
        fail(L"The source image is too large (maximum 64 million pixels).");
        return false;
    }
    photo->width = width;
    photo->height = height;
    if (photo->settings.rotate) {
        if (original.RotateFlip(Rotate90FlipNone) != Ok) {
            fail(L"Cannot rotate this image.");
            return false;
        }
        width = original.GetWidth();
        height = original.GetHeight();
    }
    Bitmap resized(240, 160, PixelFormat24bppRGB);
    if (resized.GetLastStatus() != Ok) {
        fail(L"Not enough memory for the converted image.");
        return false;
    }
    {
        Graphics graphics(&resized);
        graphics.Clear(Color(255, 0, 0, 0));
        InterpolationMode modes[] = {InterpolationModeNearestNeighbor, InterpolationModeBilinear,
                                     InterpolationModeHighQualityBicubic};
        graphics.SetInterpolationMode(modes[photo->settings.filter]);
        graphics.SetPixelOffsetMode(PixelOffsetModeHalf);
        REAL x = 0, y = 0, w = 240, h = 160;
        if (photo->settings.mode == 0) {
            REAL scale = 240.0f / width;
            if (160.0f / height < scale)
                scale = 160.0f / height;
            w = width * scale;
            h = height * scale;
            x = (240 - w) / 2;
            y = (160 - h) / 2;
        } else if (photo->settings.mode == 2) {
            REAL scale = 240.0f / width;
            if (160.0f / height > scale)
                scale = 160.0f / height;
            w = width * scale;
            h = height * scale;
            x = (240 - w) / 2;
            y = (160 - h) / 2;
        }
        if (graphics.DrawImage(&original, RectF(x, y, w, h), 0, 0, (REAL)width, (REAL)height,
                               UnitPixel) != Ok) {
            fail(L"Cannot resize this image.");
            return false;
        }
    }
    IStream *stream = NULL;
    if (FAILED(CreateStreamOnHGlobal(NULL, TRUE, &stream))) {
        fail(L"Not enough memory for JPEG conversion.");
        return false;
    }
    EncoderParameters parameters;
    parameters.Count = 1;
    ULONG quality = (ULONG)photo->settings.quality;
    parameters.Parameter[0].Guid = EncoderQuality;
    parameters.Parameter[0].Type = EncoderParameterValueTypeLong;
    parameters.Parameter[0].NumberOfValues = 1;
    parameters.Parameter[0].Value = &quality;
    bool success = false;
    BYTE *jpeg = NULL;
    uint16_t *frame = NULL;
    do {
        if (resized.Save(stream, &jpegEncoder, &parameters) != Ok) {
            fail(L"The Windows JPEG encoder failed.");
            break;
        }
        STATSTG statistics;
        if (FAILED(stream->Stat(&statistics, STATFLAG_NONAME)) || statistics.cbSize.HighPart ||
            !statistics.cbSize.LowPart) {
            fail(L"The JPEG stream is invalid.");
            break;
        }
        DWORD jpegSize = statistics.cbSize.LowPart;
        jpeg = (BYTE *)allocate(jpegSize);
        frame = (uint16_t *)allocate(PIXELS * sizeof(uint16_t));
        if (!jpeg || !frame) {
            fail(L"Not enough memory for this album.");
            break;
        }
        HGLOBAL memory = NULL;
        if (FAILED(GetHGlobalFromStream(stream, &memory))) {
            fail(L"Cannot read JPEG data.");
            break;
        }
        void *data = GlobalLock(memory);
        if (!data) {
            fail(L"Cannot read JPEG data.");
            break;
        }
        CopyMemory(jpeg, data, jpegSize);
        GlobalUnlock(memory);
        LARGE_INTEGER zero;
        zero.QuadPart = 0;
        stream->Seek(zero, STREAM_SEEK_SET, NULL);
        {
            Bitmap decoded(stream, FALSE);
            Rect area(0, 0, 240, 160);
            BitmapData pixels;
            if (decoded.GetLastStatus() != Ok ||
                decoded.LockBits(&area, ImageLockModeRead, PixelFormat24bppRGB, &pixels) != Ok) {
                fail(L"Cannot decode the converted JPEG.");
                break;
            }
            for (int y = 0; y < 160; y++) {
                const BYTE *row = (const BYTE *)pixels.Scan0 + y * pixels.Stride;
                for (int x = 0; x < 240; x++) {
                    const BYTE *p = row + x * 3;
                    frame[y * 240 + x] =
                        (uint16_t)((p[2] >> 3) | ((p[1] >> 3) << 5) | ((p[0] >> 3) << 10));
                }
            }
            decoded.UnlockBits(&pixels);
        }
        freePhoto(photo);
        photo->frame = frame;
        photo->jpeg = jpeg;
        photo->jpegSize = jpegSize;
        frame = NULL;
        jpeg = NULL;
        success = true;
    } while (false);
    release(frame);
    release(jpeg);
    stream->Release();
    return success;
}
static bool addPhoto(const WCHAR *path) {
    if (photoCount >= MAX_IMAGES) {
        fail(L"An album supports at most 400 images.");
        return false;
    }
    if (lstrlenW(path) >= MAX_PATH) {
        fail(L"Windows XP paths must be shorter than 260 characters.");
        return false;
    }
    Photo *photo = &photos[photoCount];
    ZeroMemory(photo, sizeof(*photo));
    lstrcpyW(photo->path, path);
    photo->settings = defaults();
    if (!convert(photo)) {
        freePhoto(photo);
        return false;
    }
    photoCount++;
    return true;
}
static bool exportRom(const WCHAR *path, const WCHAR *title, int seconds) {
    if (!photoCount) {
        fail(L"Add at least one image first.");
        return false;
    }
    DWORD firmwareSize = (sizeof(viewerBlob) + 3) & ~3u;
    DWORD albumSize = 16 + photoCount * 36;
    for (int i = 0; i < photoCount; i++)
        albumSize += PIXELS * 2 + 4 + photos[i].jpegSize;
    DWORD rawSize = firmwareSize + albumSize;
    if (rawSize > 32u * 1024 * 1024) {
        fail(L"This album exceeds the 32 MiB cartridge limit.");
        return false;
    }
    DWORD paddedSize = 256 * 1024;
    while (paddedSize < rawSize)
        paddedSize *= 2;
    BYTE *firmware = (BYTE *)allocate(firmwareSize);
    BYTE *entries = (BYTE *)allocate(16 + photoCount * 36);
    if (!firmware || !entries) {
        release(firmware);
        release(entries);
        fail(L"Not enough memory to build the ROM.");
        return false;
    }
    CopyMemory(firmware, viewerBlob, sizeof(viewerBlob));
    CopyMemory(firmware + 4, gbaLogo, 156);
    FillMemory(firmware + 160, 12, ' ');
    int t = 0;
    for (const WCHAR *c = title; *c && t < 12; c++)
        if (*c >= 32 && *c <= 126) {
            WCHAR value = *c;
            if (value >= L'a' && value <= L'z')
                value -= 32;
            firmware[160 + t++] = (BYTE)value;
        }
    CopyMemory(firmware + 172, "PI01", 4);
    CopyMemory(firmware + 176, "00", 2);
    firmware[178] = 0x96;
    ZeroMemory(firmware + 179, 13);
    BYTE checksum = 0;
    for (int n = 160; n < 189; n++)
        checksum = (BYTE)(checksum - firmware[n]);
    firmware[189] = (BYTE)(checksum - 0x19);
    DWORD *header = (DWORD *)entries;
    header[0] = 0x50494353;
    header[1] = photoCount;
    header[2] = (DWORD)seconds;
    header[3] = 0;
    DWORD offset = 16 + photoCount * 36;
    for (int i = 0; i < photoCount; i++) {
        BYTE *entry = entries + 16 + i * 36;
        *(DWORD *)entry = offset;
        offset += PIXELS * 2;
        const WCHAR *name = filename(photos[i].path);
        for (int n = 0; n < 30 && name[n]; n++)
            entry[4 + n] = (BYTE)(name[n] >= 32 && name[n] < 127 ? name[n] : '?');
    }
    WCHAR temporary[MAX_PATH];
    if (lstrlenW(path) + 5 >= MAX_PATH) {
        release(firmware);
        release(entries);
        fail(L"The output path is too long.");
        return false;
    }
    lstrcpyW(temporary, path);
    lstrcatW(temporary, L".tmp");
    HANDLE file =
        CreateFileW(temporary, GENERIC_WRITE, 0, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) {
        release(firmware);
        release(entries);
        fail(L"Cannot create the output ROM.");
        return false;
    }
    bool okay =
        writeBytes(file, firmware, firmwareSize) && writeBytes(file, entries, 16 + photoCount * 36);
    for (int i = 0; i < photoCount && okay; i++)
        okay = writeBytes(file, photos[i].frame, PIXELS * 2);
    for (int i = 0; i < photoCount && okay; i++)
        okay = writeBytes(file, &photos[i].jpegSize, 4) &&
               writeBytes(file, photos[i].jpeg, photos[i].jpegSize);
    BYTE padding[4096];
    FillMemory(padding, sizeof(padding), 0xff);
    DWORD remaining = paddedSize - rawSize;
    while (okay && remaining) {
        DWORD n = remaining > sizeof(padding) ? sizeof(padding) : remaining;
        okay = writeBytes(file, padding, n);
        remaining -= n;
    }
    if (okay)
        okay = FlushFileBuffers(file) != FALSE;
    CloseHandle(file);
    release(firmware);
    release(entries);
    if (okay)
        okay = MoveFileExW(temporary, path, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) !=
               FALSE;
    if (!okay) {
        DeleteFileW(temporary);
        fail(L"The ROM could not be saved. Check free space and the destination folder.");
    }
    return okay;
}

static void status(const WCHAR *message) {
    if (statusHandle)
        SetWindowTextW(statusHandle, message);
}
static HWND control(const WCHAR *className, const WCHAR *text, DWORD style, int id) {
    HWND h = CreateWindowExW(0, className, text, WS_CHILD | WS_VISIBLE | style, 0, 0, 10, 10,
                             windowHandle, (HMENU)(INT_PTR)id, appInstance, NULL);
    SendMessageW(h, WM_SETFONT, (WPARAM)GetStockObject(DEFAULT_GUI_FONT), TRUE);
    if (controlCount < 16)
        controls[controlCount++] = h;
    return h;
}
static void setBusy(bool value) {
    busy = value;
    for (int i = 0; i < controlCount; i++)
        EnableWindow(controls[i], !value);
    SetCursor(LoadCursor(NULL, value ? IDC_WAIT : IDC_ARROW));
}
static bool readSettings(Settings *settings) {
    settings->mode = (int)SendMessage(modeHandle, CB_GETCURSEL, 0, 0);
    settings->filter = (int)SendMessage(filterHandle, CB_GETCURSEL, 0, 0);
    WCHAR q[16];
    GetWindowTextW(qualityHandle, q, 16);
    settings->quality = _wtoi(q);
    settings->rotate = SendMessage(rotateHandle, BM_GETCHECK, 0, 0) == BST_CHECKED;
    if (settings->quality < 5 || settings->quality > 100) {
        fail(L"JPEG quality must be between 5 and 100.");
        return false;
    }
    return true;
}
static void loadSettings() {
    Settings s = selected >= 0 ? photos[selected].settings : defaults();
    SendMessage(modeHandle, CB_SETCURSEL, s.mode, 0);
    SendMessage(filterHandle, CB_SETCURSEL, s.filter, 0);
    WCHAR q[16];
    wsprintfW(q, L"%d", s.quality);
    SetWindowTextW(qualityHandle, q);
    SendMessage(rotateHandle, BM_SETCHECK, s.rotate ? BST_CHECKED : BST_UNCHECKED, 0);
}
static void updatePreview() {
    delete sourcePreview;
    sourcePreview = NULL;
    if (selected >= 0) {
        sourcePreview = new Bitmap(photos[selected].path, FALSE);
        for (int i = 0; i < PIXELS; i++) {
            unsigned c = photos[selected].frame[i];
            previewPixels[i] = ((c & 31) * 255 / 31) << 16 | (((c >> 5) & 31) * 255 / 31) << 8 |
                               (((c >> 10) & 31) * 255 / 31);
        }
    }
    InvalidateRect(windowHandle, NULL, TRUE);
}
static void refreshList() {
    SendMessage(listHandle, LB_RESETCONTENT, 0, 0);
    for (int i = 0; i < photoCount; i++)
        SendMessageW(listHandle, LB_ADDSTRING, 0, (LPARAM)filename(photos[i].path));
    if (!photoCount)
        selected = -1;
    else if (selected < 0)
        selected = 0;
    else if (selected >= photoCount)
        selected = photoCount - 1;
    if (selected >= 0)
        SendMessage(listHandle, LB_SETCURSEL, selected, 0);
    loadSettings();
    updatePreview();
}
static bool applySelected() {
    if (selected < 0)
        return true;
    Settings s;
    if (!readSettings(&s)) {
        status(lastError);
        return false;
    }
    Photo *p = &photos[selected];
    if (!memcmp(&s, &p->settings, sizeof(s)) && p->frame)
        return true;
    Settings previous = p->settings;
    p->settings = s;
    setBusy(true);
    bool okay = convert(p);
    setBusy(false);
    if (!okay) {
        p->settings = previous;
        status(lastError);
        return false;
    }
    updatePreview();
    status(L"Preview updated.");
    return true;
}
static void addFiles() {
    if (!applySelected())
        return;
    WCHAR *names = (WCHAR *)allocate(65536 * sizeof(WCHAR));
    if (!names) {
        status(L"Not enough memory for file selection.");
        return;
    }
    OPENFILENAMEW dialog;
    ZeroMemory(&dialog, sizeof(dialog));
    dialog.lStructSize = sizeof(dialog);
    dialog.hwndOwner = windowHandle;
    dialog.lpstrFilter =
        L"Images (*.jpg;*.jpeg;*.png;*.bmp)\0*.jpg;*.jpeg;*.png;*.bmp\0All files\0*.*\0\0";
    dialog.lpstrFile = names;
    dialog.nMaxFile = 65536;
    dialog.Flags = OFN_EXPLORER | OFN_ALLOWMULTISELECT | OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST;
    dialog.lpstrTitle = L"Add images";
    if (GetOpenFileNameW(&dialog)) {
        setBusy(true);
        int before = photoCount, failed = 0;
        WCHAR *next = names + lstrlenW(names) + 1;
        if (!*next) {
            if (!addPhoto(names))
                failed++;
        } else {
            for (WCHAR *name = next; *name; name += lstrlenW(name) + 1) {
                WCHAR path[MAX_PATH];
                if (!joinPath(path, names, name) || !addPhoto(path))
                    failed++;
            }
        }
        setBusy(false);
        refreshList();
        WCHAR message[200];
        wsprintfW(message, L"Added %d image(s). Album: %d image(s). Skipped: %d.",
                  photoCount - before, photoCount, failed);
        status(message);
    }
    release(names);
}
static void buildRomDialog() {
    if (!applySelected())
        return;
    if (!photoCount) {
        status(L"Add at least one image first.");
        return;
    }
    WCHAR path[MAX_PATH] = L"album.gba", title[64], seconds[16];
    OPENFILENAMEW dialog;
    ZeroMemory(&dialog, sizeof(dialog));
    dialog.lStructSize = sizeof(dialog);
    dialog.hwndOwner = windowHandle;
    dialog.lpstrFilter = L"GBA ROM (*.gba)\0*.gba\0\0";
    dialog.lpstrFile = path;
    dialog.nMaxFile = MAX_PATH;
    dialog.lpstrDefExt = L"gba";
    dialog.Flags = OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST;
    dialog.lpstrTitle = L"Build GBA ROM";
    if (!GetSaveFileNameW(&dialog))
        return;
    GetWindowTextW(titleHandle, title, 64);
    GetWindowTextW(intervalHandle, seconds, 16);
    int interval = _wtoi(seconds);
    if (interval < 1 || interval > 60) {
        status(L"Slideshow interval must be between 1 and 60 seconds.");
        return;
    }
    setBusy(true);
    bool okay = exportRom(path, title, interval);
    setBusy(false);
    status(okay ? L"GBA ROM saved. Copy it to a compatible flashcart or open it in a GBA emulator."
                : lastError);
}
static void layout() {
    RECT r;
    GetClientRect(windowHandle, &r);
    int w = r.right, h = r.bottom;
    MoveWindow(listHandle, 12, 34, 244, h - 245, TRUE);
    MoveWindow(controls[1], 12, h - 200, 116, 28, TRUE);
    MoveWindow(controls[2], 140, h - 200, 116, 28, TRUE);
    MoveWindow(controls[3], 12, h - 166, 116, 28, TRUE);
    MoveWindow(controls[4], 140, h - 166, 116, 28, TRUE);
    MoveWindow(controls[5], 12, h - 132, 244, 28, TRUE);
    MoveWindow(modeHandle, 278, h - 200, 140, 140, TRUE);
    MoveWindow(filterHandle, 430, h - 200, 145, 140, TRUE);
    MoveWindow(qualityHandle, 590, h - 200, 60, 26, TRUE);
    MoveWindow(rotateHandle, 665, h - 200, 145, 26, TRUE);
    MoveWindow(controls[10], 278, h - 158, 180, 30, TRUE);
    MoveWindow(controls[11], 470, h - 158, 180, 30, TRUE);
    MoveWindow(titleHandle, 278, h - 91, 200, 26, TRUE);
    MoveWindow(intervalHandle, 495, h - 91, 80, 26, TRUE);
    MoveWindow(controls[14], 665, h - 96, w - 677, 38, TRUE);
    MoveWindow(statusHandle, 12, h - 42, w - 24, 32, TRUE);
    InvalidateRect(windowHandle, NULL, TRUE);
}
static void drawPreview(HDC dc) {
    RECT client;
    GetClientRect(windowHandle, &client);
    int rightWidth = client.right - 290, half = (rightWidth - 16) / 2;
    int y = 58, height = client.bottom - 300;
    if (height < 120)
        height = 120;
    RECT a = {278, y, 278 + half, y + height}, b = {294 + half, y, 294 + half * 2, y + height};
    FillRect(dc, &a, (HBRUSH)GetStockObject(BLACK_BRUSH));
    FillRect(dc, &b, (HBRUSH)GetStockObject(BLACK_BRUSH));
    SetBkMode(dc, TRANSPARENT);
    TextOutW(dc, 12, 12, L"Images", lstrlenW(L"Images"));
    TextOutW(dc, 278, 34, L"Source image", lstrlenW(L"Source image"));
    TextOutW(dc, 294 + half, 34, L"GBA result - 240 x 160", lstrlenW(L"GBA result - 240 x 160"));
    int bottom = client.bottom;
    TextOutW(dc, 278, bottom - 222, L"Resize", lstrlenW(L"Resize"));
    TextOutW(dc, 430, bottom - 222, L"Resampling", lstrlenW(L"Resampling"));
    TextOutW(dc, 590, bottom - 222, L"Quality", lstrlenW(L"Quality"));
    TextOutW(dc, 278, bottom - 113, L"Cartridge title (12 ASCII characters)",
             lstrlenW(L"Cartridge title (12 ASCII characters)"));
    TextOutW(dc, 495, bottom - 113, L"Seconds", lstrlenW(L"Seconds"));
    if (selected < 0)
        return;
    if (sourcePreview && sourcePreview->GetLastStatus() == Ok) {
        Graphics graphics(dc);
        UINT sw = sourcePreview->GetWidth(), sh = sourcePreview->GetHeight();
        REAL scale = (REAL)half / sw;
        if ((REAL)height / sh < scale)
            scale = (REAL)height / sh;
        REAL width = sw * scale, high = sh * scale;
        graphics.SetInterpolationMode(InterpolationModeHighQualityBilinear);
        graphics.DrawImage(sourcePreview, RectF(a.left + (half - width) / 2,
                                                a.top + (height - high) / 2, width, high));
    }
    int targetWidth = half, targetHeight = half * 160 / 240;
    if (targetHeight > height) {
        targetHeight = height;
        targetWidth = height * 240 / 160;
    }
    BITMAPINFO info;
    ZeroMemory(&info, sizeof(info));
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = 240;
    info.bmiHeader.biHeight = -160;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    SetStretchBltMode(dc, COLORONCOLOR);
    StretchDIBits(dc, b.left + (half - targetWidth) / 2, b.top + (height - targetHeight) / 2,
                  targetWidth, targetHeight, 0, 0, 240, 160, previewPixels, &info, DIB_RGB_COLORS,
                  SRCCOPY);
}
static LRESULT CALLBACK windowProcedure(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
    switch (message) {
    case WM_CREATE:
        windowHandle = hwnd;
        listHandle = control(L"LISTBOX", L"",
                             WS_BORDER | WS_VSCROLL | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT, 100);
        control(L"BUTTON", L"Add images...", BS_PUSHBUTTON, 101);
        control(L"BUTTON", L"Remove", BS_PUSHBUTTON, 102);
        control(L"BUTTON", L"Move up", BS_PUSHBUTTON, 103);
        control(L"BUTTON", L"Move down", BS_PUSHBUTTON, 104);
        control(L"BUTTON", L"Clear album", BS_PUSHBUTTON, 105);
        modeHandle = control(L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_VSCROLL, 106);
        filterHandle = control(L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_VSCROLL, 107);
        qualityHandle = control(L"EDIT", L"85", WS_BORDER | ES_NUMBER, 108);
        rotateHandle = control(L"BUTTON", L"Rotate clockwise", BS_AUTOCHECKBOX, 109);
        control(L"BUTTON", L"Update preview", BS_PUSHBUTTON, 110);
        control(L"BUTTON", L"Apply settings to all", BS_PUSHBUTTON, 111);
        titleHandle = control(L"EDIT", L"MY PICTURES", WS_BORDER | ES_AUTOHSCROLL, 112);
        intervalHandle = control(L"EDIT", L"5", WS_BORDER | ES_NUMBER, 113);
        control(L"BUTTON", L"Build GBA ROM...", BS_PUSHBUTTON, 114);
        statusHandle = control(L"STATIC", L"Ready. Add JPEG, PNG or BMP images.", 0, 115);
        SendMessage(titleHandle, EM_SETLIMITTEXT, 12, 0);
        SendMessage(qualityHandle, EM_SETLIMITTEXT, 3, 0);
        SendMessage(intervalHandle, EM_SETLIMITTEXT, 2, 0);
        SendMessageW(modeHandle, CB_ADDSTRING, 0, (LPARAM)L"Fit");
        SendMessageW(modeHandle, CB_ADDSTRING, 0, (LPARAM)L"Stretch");
        SendMessageW(modeHandle, CB_ADDSTRING, 0, (LPARAM)L"Fill");
        SendMessageW(filterHandle, CB_ADDSTRING, 0, (LPARAM)L"Nearest");
        SendMessageW(filterHandle, CB_ADDSTRING, 0, (LPARAM)L"Bilinear");
        SendMessageW(filterHandle, CB_ADDSTRING, 0, (LPARAM)L"Bicubic");
        loadSettings();
        return 0;
    case WM_SIZE:
        if (listHandle)
            layout();
        return 0;
    case WM_GETMINMAXINFO:
        ((MINMAXINFO *)lp)->ptMinTrackSize.x = 900;
        ((MINMAXINFO *)lp)->ptMinTrackSize.y = 620;
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT paint;
        HDC dc = BeginPaint(hwnd, &paint);
        SelectObject(dc, GetStockObject(DEFAULT_GUI_FONT));
        drawPreview(dc);
        EndPaint(hwnd, &paint);
        return 0;
    }
    case WM_COMMAND:
        if (busy)
            return 0;
        switch (LOWORD(wp)) {
        case 100:
            if (HIWORD(wp) == LBN_SELCHANGE) {
                int next = (int)SendMessage(listHandle, LB_GETCURSEL, 0, 0);
                if (next >= 0 && next != selected) {
                    if (applySelected()) {
                        selected = next;
                        loadSettings();
                        updatePreview();
                    } else
                        SendMessage(listHandle, LB_SETCURSEL, selected, 0);
                }
            }
            break;
        case 101:
            addFiles();
            break;
        case 102:
            if (selected >= 0) {
                freePhoto(&photos[selected]);
                for (int i = selected; i < photoCount - 1; i++)
                    photos[i] = photos[i + 1];
                photoCount--;
                ZeroMemory(&photos[photoCount], sizeof(Photo));
                refreshList();
            }
            break;
        case 103:
        case 104:
            if (selected >= 0 && applySelected()) {
                int next = selected + (LOWORD(wp) == 103 ? -1 : 1);
                if (next >= 0 && next < photoCount) {
                    Photo temp = photos[selected];
                    photos[selected] = photos[next];
                    photos[next] = temp;
                    selected = next;
                    refreshList();
                }
            }
            break;
        case 105:
            if (MessageBoxW(hwnd, L"Clear the album? Original image files remain unchanged.",
                            L"Clear album", MB_YESNO | MB_ICONQUESTION) == IDYES) {
                for (int i = 0; i < photoCount; i++)
                    freePhoto(&photos[i]);
                photoCount = 0;
                refreshList();
            }
            break;
        case 110:
            if (applySelected())
                status(L"Preview updated.");
            break;
        case 111:
            if (selected >= 0) {
                Settings settings;
                if (readSettings(&settings)) {
                    setBusy(true);
                    bool okay = true;
                    for (int i = 0; i < photoCount; i++) {
                        Settings before = photos[i].settings;
                        photos[i].settings = settings;
                        if (!convert(&photos[i])) {
                            photos[i].settings = before;
                            okay = false;
                            break;
                        }
                    }
                    setBusy(false);
                    updatePreview();
                    status(okay ? L"Settings applied to every image." : lastError);
                } else
                    status(lastError);
            }
            break;
        case 114:
            buildRomDialog();
            break;
        }
        return 0;
    case WM_DESTROY:
        delete sourcePreview;
        sourcePreview = NULL;
        for (int i = 0; i < photoCount; i++)
            freePhoto(&photos[i]);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, message, wp, lp);
}
static int selfTest(const WCHAR *directory) {
    CreateDirectoryW(directory, NULL);
    WCHAR source[MAX_PATH], rom[MAX_PATH], report[MAX_PATH];
    if (!joinPath(rom, directory, L"xp-self-test.gba") ||
        !joinPath(report, directory, L"report.json"))
        return 11;
    const WCHAR *names[] = {L"sample-1.png", L"sample-2.jpg", L"sample-3.bmp"};
    CLSID codecs[] = {pngEncoder, jpegEncoder, bmpEncoder};
    for (int n = 0; n < 3; n++) {
        if (!joinPath(source, directory, names[n]))
            return 12;
        Bitmap image(480, 300, PixelFormat24bppRGB);
        BitmapData data;
        Rect area(0, 0, 480, 300);
        if (image.LockBits(&area, ImageLockModeWrite, PixelFormat24bppRGB, &data) != Ok)
            return 13;
        for (int y = 0; y < 300; y++)
            for (int x = 0; x < 480; x++) {
                BYTE *p = (BYTE *)data.Scan0 + y * data.Stride + x * 3;
                p[0] = (BYTE)(x + y + n * 23);
                p[1] = (BYTE)(y + n * 81);
                p[2] = (BYTE)(x + n * 67);
            }
        image.UnlockBits(&data);
        if (image.Save(source, &codecs[n], NULL) != Ok || !addPhoto(source))
            return 14;
        photos[n].settings.mode = n;
        photos[n].settings.quality = 55 + n * 20;
        if (!convert(&photos[n]))
            return 15;
    }
    if (!exportRom(rom, L"XP SELF TEST", 5))
        return 16;
    HANDLE file =
        CreateFileW(report, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE)
        return 17;
    const char result[] = {
        "{\"passed\":true,\"images\":3,\"formats\":[\"PNG\",\"JPEG\",\"BMP\"],\"resize\":[\"Fit\","
        "\"Stretch\",\"Fill\"],\"jpeg_quality\":[55,75,95],\"rom\":\"xp-self-test.gba\"}\n"};
    bool okay = writeBytes(file, result, sizeof(result) - 1);
    CloseHandle(file);
    for (int i = 0; i < photoCount; i++)
        freePhoto(&photos[i]);
    photoCount = 0;
    return okay ? 0 : 18;
}
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, LPWSTR, int show) {
    appInstance = instance;
    GdiplusStartupInput input;
    ULONG_PTR token;
    if (GdiplusStartup(&token, &input, NULL) != Ok)
        return 2;
    if (!findEncoder(L"image/jpeg", &jpegEncoder) || !findEncoder(L"image/png", &pngEncoder) ||
        !findEncoder(L"image/bmp", &bmpEncoder)) {
        MessageBoxW(NULL, L"Windows GDI+ image codecs are unavailable.", L"GBA Image Packer XP",
                    MB_OK | MB_ICONERROR);
        GdiplusShutdown(token);
        return 3;
    }
    int argc = 0;
    WCHAR **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv && argc == 3 && !lstrcmpW(argv[1], L"--self-test")) {
        int code = selfTest(argv[2]);
        LocalFree(argv);
        GdiplusShutdown(token);
        return code;
    }
    if (argv) {
        for (int n = 1; n < argc; n++)
            if (argv[n][0] != L'-')
                addPhoto(argv[n]);
        LocalFree(argv);
    }
    WNDCLASSW type;
    ZeroMemory(&type, sizeof(type));
    type.lpfnWndProc = windowProcedure;
    type.hInstance = instance;
    type.hCursor = LoadCursor(NULL, IDC_ARROW);
    type.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    type.lpszClassName = L"GbaImagePackerXP";
    if (!RegisterClassW(&type)) {
        GdiplusShutdown(token);
        return 4;
    }
    RECT workArea = {0, 0, GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)};
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &workArea, 0);
    int availableWidth = workArea.right - workArea.left;
    int availableHeight = workArea.bottom - workArea.top;
    int width = availableWidth < 1000 ? availableWidth : 1000;
    int height = availableHeight < 700 ? availableHeight : 700;
    int x = workArea.left + (availableWidth - width) / 2;
    int y = workArea.top + (availableHeight - height) / 2;
    HWND mainWindow =
        CreateWindowW(type.lpszClassName, L"GBA Image Packer - Windows XP edition",
                      WS_OVERLAPPEDWINDOW, x, y, width, height, NULL, NULL, instance, NULL);
    if (!mainWindow) {
        GdiplusShutdown(token);
        return 5;
    }
    if (photoCount) {
        selected = 0;
        refreshList();
    }
    ShowWindow(mainWindow, show);
    UpdateWindow(mainWindow);
    MSG message;
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    GdiplusShutdown(token);
    return (int)message.wParam;
}
