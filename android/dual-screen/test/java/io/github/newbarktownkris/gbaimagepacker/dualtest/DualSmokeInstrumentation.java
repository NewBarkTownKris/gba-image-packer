package io.github.newbarktownkris.gbaimagepacker.dualtest;

import io.github.newbarktownkris.gbaimagepacker.test.SmokeInstrumentation;
import android.app.Presentation;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.Spinner;
import java.io.OutputStream;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import java.util.concurrent.*;

/** Renders real Presentation windows into secondary-display surfaces and checks their contents. */
public final class DualSmokeInstrumentation extends SmokeInstrumentation {
    private boolean physical;
    @Override public void onCreate(android.os.Bundle arguments) {
        physical=arguments!=null && "true".equals(arguments.getString("physical"));super.onCreate(arguments);
    }
    @Override protected String artifactPrefix() { return "android-dual-smoke"; }
    private Object controller() { return field("dualPreview"); }
    private Object member(Object object,String name) {
        for (Class<?> type=object.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object); }
            catch(NoSuchFieldException e) { }
            catch(Exception e) { throw new RuntimeException(e); }
        }
        throw new IllegalArgumentException("Missing field "+name);
    }
    private void choice(int id) {
        runOnMainSync(() -> {
            try { Method method=controller().getClass().getDeclaredMethod("setChoice",int.class);method.setAccessible(true);method.invoke(controller(),id); }
            catch(Exception e) { throw new RuntimeException(e); }
        });
    }
    private Presentation presentation() { return (Presentation)member(controller(),"presentation"); }
    private void waitUntil(String message,BooleanSupplier condition) throws Exception {
        for(int i=0;i<120;i++) { boolean[] done={false};runOnMainSync(() -> done[0]=condition.getAsBoolean());if(done[0])return;Thread.sleep(100); }
        throw new AssertionError(message);
    }
    private void save(Bitmap bitmap,String filename) throws Exception {
        ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,filename);values.put(MediaStore.Downloads.MIME_TYPE,"image/png");
        android.net.Uri uri=getTargetContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        require(uri!=null,"Could not create screenshot");
        try(OutputStream out=getTargetContext().getContentResolver().openOutputStream(uri)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    private Bitmap capture(ImageReader reader) throws Exception {
        for(int attempt=0;attempt<120;attempt++) {
            try(Image image=reader.acquireLatestImage()) {
                if(image!=null) {
                    Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();
                    int width=image.getWidth(),height=image.getHeight();int[] pixels=new int[width*height];
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                        int offset=y*plane.getRowStride()+x*plane.getPixelStride();
                        pixels[y*width+x]=((buffer.get(offset+3)&255)<<24)|((buffer.get(offset)&255)<<16)|((buffer.get(offset+1)&255)<<8)|(buffer.get(offset+2)&255);
                    }
                    Bitmap actual=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
                    int colored=0;
                    for(int y=0;y<actual.getHeight();y+=8)for(int x=0;x<actual.getWidth();x+=8) {
                        int c=actual.getPixel(x,y),r=(c>>16)&255,g=(c>>8)&255,b=c&255;
                        if(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>35)colored++;
                    }
                    if(colored>300)return actual;actual.recycle();
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Secondary surface did not render both image previews");
    }
    private VirtualDisplay create(ImageReader reader,int width,int height) {
        VirtualDisplay[] value={null};runOnMainSync(() -> {
            DisplayManager manager=(DisplayManager)getTargetContext().getSystemService(Context.DISPLAY_SERVICE);
            value[0]=manager.createVirtualDisplay("GBA dual-screen verification",width,height,240,reader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        });require(value[0]!=null,"Could not create a secondary display");return value[0];
    }
    private void verifyPhysicalDisplay() throws Exception {
        choice(-1);
        waitUntil("No physical presentation display became available",() -> presentation()!=null && member(controller(),"sourceBitmap")!=null && presentation().getWindow().getDecorView().getWidth()>0);
        int[] displayId={-1};
        runOnMainSync(() -> {
            Presentation shown=presentation();displayId[0]=shown.getDisplay().getDisplayId();
            View root=shown.getWindow().getDecorView();ImageView source=root.findViewWithTag("dual-source-image"),result=root.findViewWithTag("dual-result-image");
            require(source.getWidth()>0 && Math.abs(source.getWidth()-result.getWidth())<=1,"Physical preview panes are not equal width");
            BitmapDrawable a=(BitmapDrawable)source.getDrawable(),b=(BitmapDrawable)result.getDrawable();
            require(a!=null && b!=null && a.getBitmap()!=b.getBitmap(),"Physical source/result missing");
            require(a.getBitmap().getWidth()==480 && b.getBitmap().getWidth()==240 && b.getBitmap().getHeight()==160,"Physical preview dimensions");
            require(b.getBitmap().sameAs((Bitmap)field("currentPreview")),"Physical display result differs from converted GBA frame");
            require(!b.getPaint().isFilterBitmap(),"Physical GBA preview blurred");
            require(((View)member(controller(),"inline")).getVisibility()==View.GONE,"Physical display did not collapse inline preview");
        });
        runOnMainSync(() -> ((Spinner)field("selection")).setSelection(1));waitForIdleSync();
        waitUntil("Physical preview selection did not update",() -> {
            Bitmap source=(Bitmap)member(controller(),"sourceBitmap");return source!=null && source.getPixel(0,0)==0xff435117;
        });
        choice(-2);waitUntil("Physical inline-only fallback failed",() -> presentation()==null && ((View)member(controller(),"inline")).getVisibility()==View.VISIBLE);
        choice(displayId[0]);waitUntil("Physical manual display routing failed",() -> presentation()!=null && presentation().getDisplay().getDisplayId()==displayId[0]);
        choice(-1);
        android.os.Bundle progress=new android.os.Bundle();progress.putString("stream","PASS: physical second display, equal source/result panes, exact GBA frame, selection changes, inline fallback and manual routing\n");sendStatus(0,progress);
    }
    @Override protected void verifyAdditionalFeatures() throws Exception {
        if(physical){verifyPhysicalDisplay();return;}
        ImageReader landscape=ImageReader.newInstance(1240,1080,PixelFormat.RGBA_8888,3);
        ImageReader portrait=null;VirtualDisplay virtual=null;
        try {
            choice(-1);
            waitUntil("Single-display fallback missing",() -> presentation()==null && ((View)member(controller(),"inline")).getVisibility()==View.VISIBLE);
            virtual=create(landscape,1240,1080);int id=virtual.getDisplay().getDisplayId();
            waitUntil("Automatic display routing failed",() -> presentation()!=null && presentation().getDisplay().getDisplayId()==id && presentation().getWindow().getDecorView().getWidth()>0 && member(controller(),"sourceBitmap")!=null);
            runOnMainSync(() -> {
                View root=presentation().getWindow().getDecorView();
                ImageView source=root.findViewWithTag("dual-source-image"),result=root.findViewWithTag("dual-result-image");
                require(source!=null && result!=null,"Missing preview halves");
                require(Math.abs(source.getWidth()-result.getWidth())<=1,"Preview panes are not equal width");
                BitmapDrawable a=(BitmapDrawable)source.getDrawable(),b=(BitmapDrawable)result.getDrawable();
                require(a!=null && b!=null && a.getBitmap()!=b.getBitmap(),"Source and result share the same image");
                require(a.getBitmap().getWidth()==480 && a.getBitmap().getHeight()==300,"Wrong source preview");
                require(b.getBitmap().getWidth()==240 && b.getBitmap().getHeight()==160,"Wrong GBA preview dimensions");
                require(!b.getPaint().isFilterBitmap(),"GBA pixels were blurred");
                require(b.getBitmap().sameAs((Bitmap)field("currentPreview")),"Second display result differs from converted frame");
                require(((View)member(controller(),"inline")).getVisibility()==View.GONE,"Inline preview should collapse when second display is active");
            });
            Bitmap comparison=capture(landscape);save(comparison,"android-dual-secondary.png");comparison.recycle();
            Bitmap controls=getUiAutomation().takeScreenshot();if(controls!=null){save(controls,"android-dual-controls.png");controls.recycle();}
            // Rapid selection must discard old decode completions instead of showing a previous source.
            CountDownLatch decoderBlocked=new CountDownLatch(1),allowDecode=new CountDownLatch(1);
            ExecutorService decoder=(ExecutorService)member(controller(),"decoder");
            decoder.submit(() -> { decoderBlocked.countDown();try { allowDecode.await(); } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); } });
            require(decoderBlocked.await(5,TimeUnit.SECONDS),"Could not hold preview decoding");
            try {
                for(int position:new int[]{2,0,1}) { runOnMainSync(() -> ((Spinner)field("selection")).setSelection(position));waitForIdleSync(); }
            } finally { allowDecode.countDown(); }
            waitUntil("Selection did not reach the second display",() -> {
                Bitmap source=(Bitmap)member(controller(),"sourceBitmap");
                return source!=null && source.getPixel(0,0)==0xff435117 && ((String)member(controller(),"name")).contains("test-photo-1");
            });
            runOnMainSync(() -> {
                ImageView result=presentation().getWindow().getDecorView().findViewWithTag("dual-result-image");
                require(((BitmapDrawable)result.getDrawable()).getBitmap().sameAs((Bitmap)field("currentPreview")),"Selection left an old converted result on the second display");
            });
            choice(-2);waitUntil("Manual inline mode failed",() -> presentation()==null && ((View)member(controller(),"inline")).getVisibility()==View.VISIBLE);
            choice(id);waitUntil("Manual display selection failed",() -> presentation()!=null && presentation().getDisplay().getDisplayId()==id);
            portrait=ImageReader.newInstance(1080,1240,PixelFormat.RGBA_8888,3);
            VirtualDisplay resizing=virtual;ImageReader next=portrait;
            runOnMainSync(() -> { resizing.resize(1080,1240,240);resizing.setSurface(next.getSurface()); });
            waitUntil("Display resize did not refresh presentation",() -> presentation()!=null && (Integer)member(controller(),"displayWidth")==1080 && (Integer)member(controller(),"displayHeight")==1240);
            Bitmap resized=capture(portrait);save(resized,"android-dual-portrait.png");resized.recycle();
            VirtualDisplay removing=virtual;runOnMainSync(removing::release);virtual=null;
            waitUntil("Display removal did not restore fallback",() -> presentation()==null && ((View)member(controller(),"inline")).getVisibility()==View.VISIBLE);
            choice(-1);
            virtual=create(landscape,1240,1080);int replacement=virtual.getDisplay().getDisplayId();
            waitUntil("Reconnect did not recover preview",() -> presentation()!=null && presentation().getDisplay().getDisplayId()==replacement);
            VirtualDisplay finalDisplay=virtual;runOnMainSync(finalDisplay::release);virtual=null;
            waitUntil("Final fallback missing",() -> presentation()==null && ((View)member(controller(),"inline")).getVisibility()==View.VISIBLE);
            android.os.Bundle progress=new android.os.Bundle();progress.putString("stream","PASS: equal source/result panes on a real virtual-display surface; exact GBA frame, selection changes, manual routing, portrait resize, disconnect and reconnect\n");sendStatus(0,progress);
        } finally {
            if(virtual!=null){VirtualDisplay closing=virtual;runOnMainSync(closing::release);}
            if(portrait!=null)portrait.close();landscape.close();
        }
    }
}
