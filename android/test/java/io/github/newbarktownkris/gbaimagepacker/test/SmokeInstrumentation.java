package io.github.newbarktownkris.gbaimagepacker.test;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Exercises the actual Activity and Rust library; document picker results are supplied by a monitor. */
public class SmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private Uri destination;
    private final ArrayList<Uri> sources = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void require(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
    private Object field(String name) {
        try { Field f = activity.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private Button find(View v, String label) {
        if (v instanceof Button && label.contentEquals(((Button)v).getText())) return (Button)v;
        if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for (int i=0;i<group.getChildCount();i++) { Button result=find(group.getChildAt(i),label); if (result!=null) return result; }
        }
        return null;
    }
    private void click(String label) {
        runOnMainSync(() -> { Button b=find(activity.getWindow().getDecorView(),label); require(b!=null && b.isEnabled(),"Missing/disabled button: "+label); b.performClick(); });
    }
    private void await(String prefix) throws Exception {
        for (int i=0;i<180;i++) {
            String[] text={""}; runOnMainSync(() -> text[0]=((TextView)field("status")).getText().toString());
            if (text[0].startsWith(prefix)) return;
            if (text[0].startsWith("Could not")) throw new AssertionError(text[0]);
            Thread.sleep(500);
        }
        throw new AssertionError("Timed out waiting for "+prefix);
    }
    private byte[] read(Uri uri) throws Exception {
        try (InputStream in=getTargetContext().getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[16384]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n); return out.toByteArray();
        }
    }
    private int u32(byte[] b,int i) { return (b[i]&255)|((b[i+1]&255)<<8)|((b[i+2]&255)<<16)|((b[i+3]&255)<<24); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            for (int n=0;n<3;n++) {
                File file=new File(context.getFilesDir(),"test-photo-"+n+".png");
                Bitmap bitmap=Bitmap.createBitmap(480,300,Bitmap.Config.ARGB_8888);
                int[] pixels=new int[480*300];
                for (int y=0;y<300;y++) for (int x=0;x<480;x++) pixels[y*480+x]=0xff000000|(((x+n*67)%256)<<16)|(((y+n*81)%256)<<8)|((x+y+n*23)%256);
                bitmap.setPixels(pixels,0,480,0,0,480,300);
                try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);} bitmap.recycle(); sources.add(Uri.fromFile(file));
            }
            ContentValues values=new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,"android-smoke.gba"); values.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");
            destination=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values); require(destination!=null,"No save destination");
            addMonitor(new ActivityMonitor() {
                @Override public ActivityResult onStartActivity(Intent intent) {
                    if (Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) {
                        ClipData data=ClipData.newRawUri("Photos",sources.get(0));
                        for (int i=1;i<sources.size();i++) data.addItem(new ClipData.Item(sources.get(i)));
                        Intent selected=new Intent(); selected.setClipData(data);
                        return new ActivityResult(Activity.RESULT_OK,selected);
                    }
                    if (Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) return new ActivityResult(Activity.RESULT_OK,new Intent().setData(destination));
                    return null;
                }
            });
            Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity=startActivitySync(launch); waitForIdleSync();
            click("Add images"); await("Imported 3 images.");
            runOnMainSync(() -> { ((Spinner)field("mode")).setSelection(0); ((SeekBar)field("quality")).setProgress(62); ((SeekBar)field("brightness")).setProgress(110); });
            click("Apply settings and update preview"); await("Preview updated.");
            click("Move image later"); click("Move image earlier");
            runOnMainSync(() -> ((EditText)field("title")).setText("ANDROID TEST"));
            click("Build and save GBA ROM"); await("ROM saved.");
            byte[] rom=read(destination); int sum=0; for(int i=160;i<190;i++)sum+=rom[i]&255;
            require((sum&255)==0xe7 && (rom[178]&255)==0x96,"Invalid cartridge header");
            int album=-1;for(int i=192;i<65536;i+=4)if(u32(rom,i)==0x50494353){album=i;break;}
            require(album>0 && u32(rom,album+4)==3 && u32(rom,album+8)==5,"Invalid album metadata");
            require(new String(rom,160,12,"US-ASCII").equals("ANDROID TEST"),"Cartridge title");
            // Close and reopen the Activity to verify the private album survives recreation.
            runOnMainSync(() -> activity.finish()); waitForIdleSync(); activity=startActivitySync(launch); waitForIdleSync();
            runOnMainSync(() -> require(((TextView)field("count")).getText().toString().startsWith("3 images"),"Album did not persist"));
            Bitmap screenshot=getUiAutomation().takeScreenshot();
            if (screenshot!=null) {
                ContentValues imageValues=new ContentValues(); imageValues.put(MediaStore.Downloads.DISPLAY_NAME,"android-smoke.png"); imageValues.put(MediaStore.Downloads.MIME_TYPE,"image/png");
                Uri imageUri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,imageValues);
                try(OutputStream out=context.getContentResolver().openOutputStream(imageUri)){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);} screenshot.recycle();
            }
            result.putString("stream","PASS: image URI import, settings, preview, order, Rust ROM build, document URI export and album restoration\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            StringWriter details=new StringWriter();error.printStackTrace(new PrintWriter(details)); result.putString("stream",details.toString());finish(Activity.RESULT_CANCELED,result);
        }
    }
}
