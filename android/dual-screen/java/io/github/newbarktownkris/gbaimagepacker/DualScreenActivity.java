package io.github.newbarktownkris.gbaimagepacker;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.*;
import java.io.File;

/** Independent dual-screen app sharing the ordinary app's image and cartridge workflow. */
public final class DualScreenActivity extends MainActivity {
    private DualPreviewController dualPreview;
    @Override protected String applicationTitle() { return "GBA Image Packer Dual"; }
    @Override protected void onInterfaceReady(LinearLayout container, ImageView imagePreview) {
        int index = container.indexOfChild(imagePreview);
        LinearLayout section = new LinearLayout(this); section.setOrientation(LinearLayout.VERTICAL);
        TextView state = new TextView(this); state.setTextSize(14); section.addView(state);
        Button choose = new Button(this); choose.setText("Choose preview display"); choose.setAllCaps(false); section.addView(choose);
        ComparisonPanel inline = new ComparisonPanel(this); inline.setTag("dual-inline-preview");
        section.addView(inline,new LinearLayout.LayoutParams(-1,Math.round(240*getResources().getDisplayMetrics().density)));
        container.addView(section,index); imagePreview.setVisibility(View.GONE);
        dualPreview = new DualPreviewController(this,inline,state);
        choose.setOnClickListener(v -> dualPreview.chooseDisplay());
    }
    @Override protected void onSelectedImagePreview(File source, Bitmap result, String name, boolean ready) {
        if (dualPreview != null) dualPreview.update(source,result,name,ready);
    }
    @Override protected void onResume() { super.onResume(); if (dualPreview != null) dualPreview.start(); refreshSelectedPreview(); }
    @Override protected void onStop() { if (dualPreview != null) dualPreview.stop(); super.onStop(); }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (dualPreview != null) { dualPreview.refreshDisplays(); refreshSelectedPreview(); }
    }
    @Override protected void onDestroy() { if (dualPreview != null) dualPreview.close(); super.onDestroy(); }
}
