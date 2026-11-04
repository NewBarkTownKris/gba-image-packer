package io.github.newbarktownkris.gbaimagepacker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Owns display routing and bounded source-image decoding; all view changes run on the main thread. */
final class DualPreviewController implements DisplayManager.DisplayListener {
    static final int AUTOMATIC = -1, INLINE = -2;
    private final Activity activity;
    private final DisplayManager displays;
    private final ComparisonPanel inline;
    private final TextView state;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Runnable retry = this::refreshDisplays;
    private int retryAttempts;
    private Future<?> decodeTask;
    private PreviewPresentation presentation;
    private AlertDialog chooser;
    private Bitmap sourceBitmap, resultBitmap;
    private String sourcePath, name = "", sourceError;
    private boolean ready, loading, started, closed;
    private int choice, sourceGeneration, displayWidth, displayHeight, displayRotation;

    DualPreviewController(Activity activity, ComparisonPanel inline, TextView state) {
        this.activity = activity; this.inline = inline; this.state = state;
        displays = (DisplayManager)activity.getSystemService(Context.DISPLAY_SERVICE);
        preferences = activity.getSharedPreferences("dual-preview",Context.MODE_PRIVATE);
        choice = preferences.getInt("display",AUTOMATIC); render(); refreshDisplays();
    }
    void start() {
        if (closed || started) return;
        started = true; retryAttempts = 0; displays.registerDisplayListener(this,main); refreshDisplays();
    }
    void stop() {
        if (!started) return;
        started = false; main.removeCallbacks(retry); displays.unregisterDisplayListener(this);
        if (chooser != null) { chooser.dismiss(); chooser = null; }
        dismissPresentation(); updateRoutingLabel(null);
    }
    void close() {
        stop(); closed = true; sourceGeneration++;
        if (decodeTask != null) decodeTask.cancel(true);
        decoder.shutdownNow(); sourceBitmap = null; resultBitmap = null;
        inline.update(null,null,"",false,false);
    }
    void update(File source, Bitmap result, String name, boolean ready) {
        if (closed) return;
        this.name = name; this.ready = ready;
        // The ordinary preview recycles its old bitmap, so own a separate small RGB555 snapshot.
        resultBitmap = result == null || !ready ? null : result.copy(Bitmap.Config.ARGB_8888,false);
        String path = source == null ? null : source.getAbsolutePath();
        if (!Objects.equals(path,sourcePath)) {
            sourcePath = path; sourceBitmap = null; sourceError = null;
            loading = path != null; int generation = ++sourceGeneration;
            if (decodeTask != null) decodeTask.cancel(true);
            if (path != null) decodeTask = decoder.submit(() -> {
                Bitmap decoded = null; String error = null;
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path,bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("Source preview unavailable");
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                    while (Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize > 1024) options.inSampleSize *= 2;
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    decoded = BitmapFactory.decodeFile(path,options);
                    if (decoded == null) error = "Source preview unavailable";
                } catch (RuntimeException | OutOfMemoryError failure) { error = "Source preview unavailable"; }
                Bitmap value = decoded; String message = error;
                main.post(() -> {
                    if (closed || generation != sourceGeneration) return;
                    sourceBitmap = value; sourceError = message; loading = false; render(); updateRoutingLabel(null);
                });
            });
        }
        render(); updateRoutingLabel(null);
    }
    private void render() {
        inline.update(sourceBitmap,resultBitmap,name,ready,loading);
        if (presentation != null) presentation.update();
    }
    private int controlDisplayId() {
        Display attached = activity.getWindow().getDecorView().getDisplay();
        return attached == null ? activity.getWindowManager().getDefaultDisplay().getDisplayId() : attached.getDisplayId();
    }
    private List<Display> availableDisplays() {
        LinkedHashMap<Integer,Display> candidates = new LinkedHashMap<>();
        for (Display d : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
            if (eligible(d)) candidates.put(d.getDisplayId(),d);
        // Some dual-screen firmware exposes an internal panel outside the presentation category.
        // Android still decides whether a Presentation is allowed on that display.
        for (Display d : displays.getDisplays())
            if (eligible(d) && (d.getFlags() & Display.FLAG_PRIVATE) == 0) candidates.putIfAbsent(d.getDisplayId(),d);
        return new ArrayList<>(candidates.values());
    }
    private boolean eligible(Display display) {
        return display.isValid() && display.getDisplayId() != controlDisplayId() && display.getState() != Display.STATE_OFF;
    }
    void setChoice(int choice) {
        this.choice = choice; retryAttempts = 0; main.removeCallbacks(retry); preferences.edit().putInt("display",choice).apply(); refreshDisplays();
    }
    void chooseDisplay() {
        List<String> labels = new ArrayList<>(Arrays.asList("Automatic", "This screen only"));
        List<Integer> ids = new ArrayList<>(Arrays.asList(AUTOMATIC,INLINE));
        for (Display d : availableDisplays()) {
            DisplayMetrics metrics = new DisplayMetrics(); d.getRealMetrics(metrics);
            labels.add(d.getName() + " · " + metrics.widthPixels + " × " + metrics.heightPixels); ids.add(d.getDisplayId());
        }
        chooser = new AlertDialog.Builder(activity).setTitle("Preview display")
            .setSingleChoiceItems(labels.toArray(new String[0]),ids.indexOf(choice),(dialog,index) -> {
                setChoice(ids.get(index)); dialog.dismiss(); chooser = null;
            }).setNegativeButton("Close",null).create();
        chooser.show();
    }
    void refreshDisplays() {
        if (closed || !started || activity.isFinishing() || activity.isDestroyed()) { updateRoutingLabel(null); return; }
        List<Display> candidates = availableDisplays();
        Display target = null;
        for (Display candidate : candidates) {
            if (choice == AUTOMATIC || candidate.getDisplayId() == choice) { target = candidate; break; }
        }
        if (target == null || choice == INLINE) { dismissPresentation(); updateRoutingLabel(null); return; }
        DisplayMetrics metrics = new DisplayMetrics(); target.getRealMetrics(metrics);
        if (presentation != null && presentation.getDisplay().getDisplayId() == target.getDisplayId()
                && displayWidth == metrics.widthPixels && displayHeight == metrics.heightPixels && displayRotation == target.getRotation()) {
            updateRoutingLabel(null); return;
        }
        dismissPresentation();
        PreviewPresentation next = new PreviewPresentation(target);
        try {
            next.show(); presentation = next; displayWidth = metrics.widthPixels; displayHeight = metrics.heightPixels; displayRotation = target.getRotation();
            next.setOnDismissListener(dialog -> {
                if (presentation == next) { presentation = null; updateRoutingLabel("Preview display closed"); }
            });
            next.update(); updateRoutingLabel(null);
        } catch (WindowManager.InvalidDisplayException | WindowManager.BadTokenException | SecurityException failure) {
            next.dismiss(); updateRoutingLabel("Android could not open this preview display");
            if (retryAttempts < 3) main.postDelayed(retry,300L * ++retryAttempts);
        }
    }
    private void dismissPresentation() {
        PreviewPresentation previous = presentation; presentation = null;
        if (previous != null) { previous.setOnDismissListener(null); previous.dismiss(); }
    }
    private void updateRoutingLabel(String error) {
        inline.setVisibility(presentation == null ? View.VISIBLE : View.GONE);
        String label;
        if (error != null) label = error + ". Comparison is on this screen.";
        else if (presentation != null) label = "Comparison on " + presentation.getDisplay().getName() + ". Editing stays on this screen.";
        else if (choice == INLINE) label = "Comparison is on this screen.";
        else if (choice >= 0) label = "Selected display unavailable. Comparison is on this screen.";
        else label = "No second preview display. Showing both images here.";
        if (sourceError != null) label += " " + sourceError + ".";
        state.setText(label);
    }
    @Override public void onDisplayAdded(int id) { retryAttempts = 0; refreshDisplays(); }
    @Override public void onDisplayRemoved(int id) { refreshDisplays(); }
    @Override public void onDisplayChanged(int id) { retryAttempts = 0; refreshDisplays(); }

    private final class PreviewPresentation extends Presentation {
        private ComparisonPanel panel;
        PreviewPresentation(Display display) {
            super(activity,display,android.R.style.Theme_Material_NoActionBar_Fullscreen);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        @Override protected void onCreate(Bundle savedState) {
            super.onCreate(savedState); panel = new ComparisonPanel(getContext()); setContentView(panel);
            getWindow().setLayout(-1,-1); update();
        }
        void update() { if (panel != null) panel.update(sourceBitmap,resultBitmap,name,ready,loading); }
    }
}
