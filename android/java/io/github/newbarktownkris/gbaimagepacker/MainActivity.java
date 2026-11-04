package io.github.newbarktownkris.gbaimagepacker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int IMPORT = 1, EXPORT = 2;
    private final ArrayList<Entry> photos = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView status, count;
    private Spinner selection, mode, filter;
    private ImageView preview;
    private EditText title;
    private NumberPicker interval;
    private SeekBar quality, brightness, contrast, gamma;
    private CheckBox rotate, sharpen;
    private int selected = -1;
    private boolean busy, updating;
    private File albumDirectory, pendingRom;
    private Bitmap currentPreview;

    private static class Entry {
        String name;
        File source, frame;
        JSONObject settings;
        boolean ready;
        JSONObject json() throws JSONException {
            return new JSONObject().put("name", name).put("source", source.getName())
                .put("frame", frame.getName()).put("settings", settings).put("ready", ready);
        }
    }
    private interface Work { void run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        albumDirectory = new File(getFilesDir(), "album");
        albumDirectory.mkdirs();
        buildInterface();
        restoreAlbum();
        if (state != null && state.getString("pendingRom") != null)
            pendingRom = new File(state.getString("pendingRom"));
        refreshSelection();
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size);
        v.setPadding(0, dp(8), 0, dp(4)); content.addView(v); return v;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); content.addView(b); return b;
    }
    private Spinner spinner(String label, String[] items) {
        text(label, 14); Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a); content.addView(s); return s;
    }
    private SeekBar slider(String label, int maximum, int offset) {
        TextView caption = text(label, 14); SeekBar bar = new SeekBar(this); bar.setMax(maximum);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int n, boolean user) { caption.setText(label + ": " + (n + offset)); }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        content.addView(bar); return bar;
    }
    private void buildInterface() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32)); scroll.addView(content); setContentView(scroll);
        // Keep controls below display cutouts and Android's gesture/navigation bars.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        text("GBA Image Packer", 26);
        text("Turn your photos into a Game Boy Advance cartridge. Everything is processed on this device.", 15);
        button("Add images", this::chooseImages);
        count = text("No images yet", 14);
        selection = spinner("Selected image", new String[]{"Add images to begin"});
        selection.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (!updating && !busy && position < photos.size() && position != selected) {
                    commitSettings(); selected = position; showSettings(); saveAlbum(); showPreview();
                }
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        preview = new ImageView(this); preview.setBackgroundColor(0xff142331);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setAdjustViewBounds(true);
        preview.setContentDescription("GBA image preview, 240 by 160 pixels");
        content.addView(preview, new LinearLayout.LayoutParams(-1, dp(220)));
        button("Move image earlier", () -> move(-1));
        button("Move image later", () -> move(1));
        button("Remove selected image", this::removeImage);
        text("Image conversion", 21);
        mode = spinner("Resize", new String[]{"Fit — black borders", "Stretch — fill screen", "Fill — crop center"});
        filter = spinner("Resampling", new String[]{"Nearest", "Bilinear", "Bicubic", "Lanczos 3"});
        quality = slider("JPEG quality", 95, 5);
        brightness = slider("Brightness", 200, -100);
        contrast = slider("Contrast", 200, -100);
        gamma = slider("Gamma", 115, -15);
        rotate = new CheckBox(this); rotate.setText("Rotate 90° clockwise"); content.addView(rotate);
        sharpen = new CheckBox(this); sharpen.setText("Sharpen"); content.addView(sharpen);
        button("Apply settings and update preview", this::applySelected);
        button("Apply these settings to every image", this::applyAll);
        text("Cartridge options", 21);
        text("Title (up to 12 ASCII characters)", 14);
        title = new EditText(this); title.setSingleLine(true); title.setText("MY PICTURES");
        title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)}); content.addView(title);
        text("Slideshow interval in seconds", 14);
        interval = new NumberPicker(this); interval.setMinValue(1); interval.setMaxValue(60); interval.setValue(5);
        interval.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS); content.addView(interval);
        button("Build and save GBA ROM", this::buildRom);
        button("Save included example ROM", this::saveExample);
        button("Controls and licenses", this::showAbout);
        button("Clear album", () -> new AlertDialog.Builder(this).setTitle("Clear album?")
            .setMessage("Remove the imported copies from this app? Your original images stay in place.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d,w) -> clearAlbum()).show());
        status = text("Ready. Add JPEG, PNG, BMP or other images supported by Android.", 15);
    }

    private void setBusy(boolean value) {
        busy = value; enable(content, !value); status.setEnabled(true);
    }
    private void enable(View view, boolean value) {
        view.setEnabled(value);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0; i<group.getChildCount(); i++) enable(group.getChildAt(i), value);
        }
    }
    private void work(String message, Work task, Runnable complete) {
        if (busy) return;
        setBusy(true); status.setText(message);
        worker.execute(() -> {
            try {
                task.run();
                runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); complete.run(); } });
            } catch (Exception | OutOfMemoryError e) {
                runOnUiThread(() -> { if (!isDestroyed()) {
                    setBusy(false); status.setText("Could not complete: " +
                        (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }});
            }
        });
    }

    private JSONObject defaults() {
        try { return new JSONObject("{\"mode\":2,\"filter\":1,\"quality\":85,\"rotate\":false,\"brightness\":0,\"contrast\":0,\"gamma\":0,\"sharpen\":false}"); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private JSONObject readSettings() {
        try { return new JSONObject().put("mode", mode.getSelectedItemPosition()).put("filter", filter.getSelectedItemPosition())
            .put("quality", quality.getProgress()+5).put("rotate", rotate.isChecked())
            .put("brightness", brightness.getProgress()-100).put("contrast", contrast.getProgress()-100)
            .put("gamma", gamma.getProgress()-15).put("sharpen", sharpen.isChecked()); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private void commitSettings() {
        if (selected < 0 || selected >= photos.size()) return;
        Entry e = photos.get(selected); JSONObject settings = readSettings();
        if (!e.settings.toString().equals(settings.toString())) { e.settings = settings; e.ready = false; }
    }
    private void showSettings() {
        JSONObject s = selected >= 0 && selected < photos.size() ? photos.get(selected).settings : defaults();
        mode.setSelection(s.optInt("mode",2)); filter.setSelection(s.optInt("filter",1));
        quality.setProgress(s.optInt("quality",85)-5); brightness.setProgress(s.optInt("brightness",0)+100);
        contrast.setProgress(s.optInt("contrast",0)+100); gamma.setProgress(s.optInt("gamma",0)+15);
        rotate.setChecked(s.optBoolean("rotate")); sharpen.setChecked(s.optBoolean("sharpen"));
    }
    private void refreshSelection() {
        updating = true;
        ArrayList<String> names = new ArrayList<>();
        for (int i=0; i<photos.size(); i++) names.add((i+1) + ". " + photos.get(i).name);
        if (names.isEmpty()) { names.add("Add images to begin"); selected = -1; }
        else selected = Math.max(0, Math.min(selected, photos.size()-1));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); selection.setAdapter(adapter);
        selection.setSelection(Math.max(0, selected));
        count.setText(photos.size() + " images · up to 32 MiB per cartridge");
        showSettings(); updating = false; showPreview();
    }
    private static Bitmap framePreview(File frame) throws IOException {
        byte[] rgb = new byte[240*160*3];
        try (DataInputStream in = new DataInputStream(new FileInputStream(frame))) { in.readFully(rgb); }
        int[] pixels = new int[240*160];
        for (int i=0; i<pixels.length; i++) pixels[i] = 0xff000000 | ((rgb[i*3]&255)<<16) | ((rgb[i*3+1]&255)<<8) | (rgb[i*3+2]&255);
        return Bitmap.createBitmap(pixels, 240, 160, Bitmap.Config.ARGB_8888);
    }
    private void display(Bitmap bitmap) {
        Bitmap previous = currentPreview; currentPreview = bitmap;
        if (bitmap == null) preview.setImageDrawable(null);
        else { BitmapDrawable d = new BitmapDrawable(getResources(), bitmap); d.setFilterBitmap(false); preview.setImageDrawable(d); }
        if (previous != null && previous != bitmap) previous.recycle();
    }
    private void showPreview() {
        if (selected < 0 || selected >= photos.size()) { display(null); return; }
        Entry e = photos.get(selected);
        if (e.ready && e.frame.isFile()) {
            try { display(framePreview(e.frame)); }
            catch (IOException error) { e.ready = false; display(null); }
        } else display(null);
        if (!e.ready) status.setText("Settings changed. Apply settings to update the preview, or build the ROM.");
    }
    private void process(Entry entry) { Native.process(entry.source.getAbsolutePath(), entry.settings.toString(), entry.frame.getAbsolutePath()); entry.ready = true; }
    private void applySelected() {
        if (selected < 0) return;
        commitSettings(); Entry entry = photos.get(selected);
        work("Converting image…", () -> process(entry), () -> { showPreview(); saveAlbum(); status.setText("Preview updated."); });
    }
    private void applyAll() {
        if (photos.isEmpty()) return;
        JSONObject settings = readSettings();
        try { for (Entry e : photos) { e.settings = new JSONObject(settings.toString()); e.ready = false; } }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        work("Converting album…", () -> { for (Entry e : photos) process(e); }, () -> { showPreview(); saveAlbum(); status.setText("Settings applied to all images."); });
    }
    private void move(int direction) {
        int target = selected + direction;
        if (selected < 0 || target < 0 || target >= photos.size()) return;
        commitSettings(); Collections.swap(photos, selected, target); selected = target; refreshSelection(); saveAlbum();
    }
    private void removeImage() {
        if (selected < 0) return;
        Entry e = photos.remove(selected); e.source.delete(); e.frame.delete();
        selected = Math.min(selected, photos.size()-1); refreshSelection(); saveAlbum();
    }
    private void clearAlbum() {
        for (Entry e : photos) { e.source.delete(); e.frame.delete(); }
        photos.clear(); selected = -1; refreshSelection(); saveAlbum(); status.setText("Album cleared.");
    }

    private void chooseImages() {
        commitSettings();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, IMPORT);
    }
    private String imageName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "Image " + (photos.size()+1) : uri.getLastPathSegment();
    }
    private Entry importImage(Uri uri, String name) throws Exception {
        String id = UUID.randomUUID().toString();
        File raw = new File(getCacheDir(), id + ".import"), normalized = new File(albumDirectory, id + ".png");
        File frame = new File(albumDirectory, id + ".frame");
        boolean success = false;
        try {
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(raw)) {
                if (in == null) throw new IOException("Cannot open " + name);
                byte[] block = new byte[32768]; int n; long total = 0;
                while ((n=in.read(block)) != -1) { total += n; if (total > 64L*1024*1024) throw new IOException("Image is larger than 64 MiB"); out.write(block,0,n); }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raw.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Unsupported or damaged image: " + name);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight)/options.inSampleSize > 2048) options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(raw.getAbsolutePath(), options);
            if (bitmap == null) throw new IOException("Cannot decode " + name);
            try {
                Matrix transform = new Matrix();
                try {
                    int orientation = new ExifInterface(raw.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                    switch (orientation) {
                        case 2: transform.setScale(-1,1); break;
                        case 3: transform.setRotate(180); break;
                        case 4: transform.setScale(1,-1); break;
                        case 5: transform.setRotate(90); transform.postScale(-1,1); break;
                        case 6: transform.setRotate(90); break;
                        case 7: transform.setRotate(-90); transform.postScale(-1,1); break;
                        case 8: transform.setRotate(-90); break;
                        default: break;
                    }
                } catch (IOException ignored) { }
                if (!transform.isIdentity()) {
                    Bitmap corrected = Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),transform,true);
                    if (corrected != bitmap) { bitmap.recycle(); bitmap = corrected; }
                }
                // Composite transparency onto black, matching the cartridge display.
                Bitmap opaque = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(opaque); canvas.drawColor(android.graphics.Color.BLACK);
                canvas.drawBitmap(bitmap,0,0,null); bitmap.recycle(); bitmap = opaque;
                try (OutputStream out = new FileOutputStream(normalized)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("Cannot import " + name);
                }
            } finally { bitmap.recycle(); }
            Entry e = new Entry(); e.name = name; e.source = normalized; e.frame = frame; e.settings = defaults();
            process(e); success = true; return e;
        } finally { raw.delete(); if (!success) { normalized.delete(); frame.delete(); } }
    }
    private void importImages(ArrayList<Uri> uris) {
        int available = 400 - photos.size();
        if (uris.size() > available) { status.setText("An album supports up to 400 images. Remove some images first."); return; }
        ArrayList<Entry> imported = new ArrayList<>(); ArrayList<String> errors = new ArrayList<>();
        work("Importing " + uris.size() + " images…", () -> {
            for (Uri uri : uris) {
                String name = imageName(uri);
                try { imported.add(importImage(uri,name)); }
                catch (Exception e) { errors.add(name + ": " + e.getMessage()); }
            }
        }, () -> {
            photos.addAll(imported); if (selected < 0 && !photos.isEmpty()) selected = 0;
            refreshSelection(); saveAlbum();
            status.setText("Imported " + imported.size() + " images." + (errors.isEmpty() ? "" : "\nSkipped " + errors.size() + ": " + errors.get(0)));
        });
    }

    private void buildRom() {
        if (photos.isEmpty()) { status.setText("Add at least one image first."); return; }
        commitSettings(); String cartridgeTitle = title.getText().toString(); int seconds = interval.getValue();
        File output = new File(getCacheDir(), "album.gba"); long[] size = new long[1];
        work("Building cartridge…", () -> {
            JSONArray frames = new JSONArray();
            for (Entry e : photos) {
                if (!e.ready || !e.frame.isFile()) process(e);
                frames.put(new JSONObject().put("path",e.frame.getAbsolutePath()).put("name",e.name));
            }
            size[0] = Native.build(frames.toString(),cartridgeTitle,seconds,output.getAbsolutePath());
        }, () -> {
            saveAlbum(); showPreview(); pendingRom = output;
            status.setText("ROM built: " + (size[0]/1024) + " KiB. Choose where to save it.");
            chooseDestination("album.gba");
        });
    }
    private void saveExample() {
        File output = new File(getCacheDir(), "example.gba");
        work("Preparing example ROM…", () -> {
            try (InputStream in = getAssets().open("example.gba"); OutputStream out = new FileOutputStream(output)) { copy(in,out); }
        }, () -> { pendingRom = output; chooseDestination("example.gba"); });
    }
    private void chooseDestination(String name) {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,name), EXPORT);
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] block = new byte[32768]; int n; while ((n=in.read(block)) != -1) out.write(block,0,n);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (result != RESULT_OK || data == null) {
            if (request == EXPORT) { pendingRom = null; status.setText("Save canceled. Your album is still available."); }
            return;
        }
        if (request == IMPORT) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) for (int i=0; i<data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) importImages(uris);
        } else if (request == EXPORT && pendingRom != null && data.getData() != null) {
            File file = pendingRom; Uri destination = data.getData();
            work("Saving ROM…", () -> {
                try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(destination,"wt")) {
                    if (out == null) throw new IOException("Cannot open destination"); copy(in,out);
                }
            }, () -> { pendingRom = null; status.setText("ROM saved. Copy it to your flashcart or open it in a GBA emulator."); });
        }
    }

    private void saveAlbum() {
        try {
            JSONArray entries = new JSONArray(); for (Entry e : photos) entries.put(e.json());
            JSONObject state = new JSONObject().put("images",entries).put("selected",selected)
                .put("title",title.getText().toString()).put("interval",interval.getValue());
            File temporary = new File(albumDirectory,"album.json.tmp"), destination = new File(albumDirectory,"album.json");
            try (OutputStream out = new FileOutputStream(temporary)) { out.write(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            if (!temporary.renameTo(destination)) throw new IOException("Could not save album state");
        } catch (Exception e) { status.setText("Album could not be saved: " + e.getMessage()); }
    }
    private void restoreAlbum() {
        File file = new File(albumDirectory,"album.json"); if (!file.isFile()) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(file)) { copy(in,bytes); }
            JSONObject state = new JSONObject(bytes.toString("UTF-8")); JSONArray images = state.getJSONArray("images");
            for (int i=0; i<images.length() && i<400; i++) {
                JSONObject item = images.getJSONObject(i); Entry e = new Entry(); e.name = item.getString("name");
                e.source = new File(albumDirectory,new File(item.getString("source")).getName());
                e.frame = new File(albumDirectory,new File(item.getString("frame")).getName());
                e.settings = item.getJSONObject("settings"); e.ready = item.optBoolean("ready") && e.frame.isFile();
                if (e.source.isFile()) photos.add(e);
            }
            selected = state.optInt("selected",0); title.setText(state.optString("title","MY PICTURES"));
            interval.setValue(Math.max(1,Math.min(60,state.optInt("interval",5))));
        } catch (Exception e) { status.setText("Could not restore the album: " + e.getMessage()); }
    }
    @Override protected void onPause() {
        if (!busy) { commitSettings(); saveAlbum(); }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (pendingRom != null) state.putString("pendingRom",pendingRom.getAbsolutePath());
    }
    private void showAbout() {
        String help = "GBA controls\n\nA opens image selection. D-pad selects, A views, Start starts a slideshow.\n\nL / R: previous / next\nA + L / R: skip 10\nB + L / R: skip 100\nStart + R: slideshow\nA: pause / resume slideshow\nB or Start + L: menu\nSelect: help\n\nImages are imported as private copies, downsampled to at most 2048 pixels per side. Originals are untouched. The album is saved automatically. Uninstalling the app removes its private album; exported ROMs remain where you saved them.\n\nGBA Image Packer is MIT licensed. Dependency licenses are included below.\n\n";
        try (InputStream in = getAssets().open("licenses.txt")) { ByteArrayOutputStream out = new ByteArrayOutputStream(); copy(in,out); help += out.toString("UTF-8"); }
        catch (IOException e) { help += "License file unavailable."; }
        TextView view = new TextView(this); view.setText(help); view.setTextIsSelectable(true); view.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(view);
        new AlertDialog.Builder(this).setTitle("Controls and licenses").setView(scroll).setPositiveButton("Close",null).show();
    }
    @Override protected void onDestroy() {
        worker.shutdown(); super.onDestroy();
    }
}
