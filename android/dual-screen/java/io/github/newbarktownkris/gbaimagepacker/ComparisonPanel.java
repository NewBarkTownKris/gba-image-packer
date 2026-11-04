package io.github.newbarktownkris.gbaimagepacker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** A complete source image and GBA frame, each occupying half the available width. */
final class ComparisonPanel extends LinearLayout {
    private final TextView filename, sourceInfo, resultInfo;
    private final ImageView sourceImage, resultImage;

    ComparisonPanel(Context context) {
        super(context); setOrientation(VERTICAL); setBackgroundColor(0xff101923);
        int padding = dp(12); setPadding(padding,padding,padding,padding);
        filename = caption("Select an image", 18); addView(filename);
        LinearLayout halves = new LinearLayout(context); halves.setOrientation(HORIZONTAL);
        addView(halves, new LayoutParams(-1,0,1));
        LinearLayout left = pane(halves), right = pane(halves);
        sourceInfo = caption("Imported image",14); left.addView(sourceInfo);
        sourceImage = image("dual-source-image", "Selected source image"); left.addView(sourceImage,new LayoutParams(-1,0,1));
        resultInfo = caption("GBA result\n240 × 160",14); right.addView(resultInfo);
        resultImage = image("dual-result-image", "Converted GBA result"); right.addView(resultImage,new LayoutParams(-1,0,1));
        update(null,null,"",false,false);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView caption(String text,int size) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(dp(4),dp(4),dp(4),dp(8)); return view;
    }
    private LinearLayout pane(LinearLayout row) {
        LinearLayout pane = new LinearLayout(getContext()); pane.setOrientation(VERTICAL);
        pane.setPadding(dp(4),0,dp(4),0); row.addView(pane,new LayoutParams(0,-1,1)); return pane;
    }
    private ImageView image(String tag,String description) {
        ImageView view = new ImageView(getContext()); view.setTag(tag); view.setContentDescription(description);
        view.setBackgroundColor(Color.BLACK); view.setScaleType(ImageView.ScaleType.FIT_CENTER); return view;
    }
    private void bitmap(ImageView view, Bitmap bitmap, boolean smooth) {
        if (bitmap == null) { view.setImageDrawable(null); return; }
        BitmapDrawable drawable = new BitmapDrawable(getResources(),bitmap); drawable.setFilterBitmap(smooth); view.setImageDrawable(drawable);
    }
    void update(Bitmap source, Bitmap result, String name, boolean ready, boolean loading) {
        filename.setText(name.isEmpty() ? "Select an image to compare" : name);
        sourceInfo.setText(source == null ? (loading ? "Imported image\nLoading…" : "Imported image\nNo image selected") :
            "Imported image\n" + source.getWidth() + " × " + source.getHeight());
        resultInfo.setText(name.isEmpty() ? "GBA result\n240 × 160" :
            ready && result != null ? "GBA result\n240 × 160" : "GBA result\nApply settings to preview");
        bitmap(sourceImage, source, true); bitmap(resultImage, ready ? result : null, false);
    }
}
