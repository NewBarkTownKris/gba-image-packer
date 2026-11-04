import io.github.newbarktownkris.gbaimagepacker.Native;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;

public final class CoreSmokeTest {
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static int u32(byte[] bytes, int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16)|((bytes[offset+3]&255)<<24); }
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]).toAbsolutePath(); Files.createDirectories(output);
        String settings = "{\"mode\":0,\"filter\":1,\"quality\":85,\"rotate\":false,\"brightness\":0,\"contrast\":0,\"gamma\":0,\"sharpen\":false}";
        StringBuilder frames = new StringBuilder("[");
        for (int i=0; i<3; i++) {
            BufferedImage image = new BufferedImage(480,300,BufferedImage.TYPE_INT_RGB);
            for (int y=0; y<300; y++) for (int x=0; x<480; x++) image.setRGB(x,y,((x+i*67)%256)<<16|((y+i*81)%256)<<8|((x+y+i*23)%256));
            Path source = output.resolve("photo-é-"+i+".png"), frame = output.resolve("photo-"+i+".frame");
            ImageIO.write(image,"png",source.toFile());
            byte[] before = Files.readAllBytes(source);
            Native.process(source.toString(),settings,frame.toString());
            require(java.util.Arrays.equals(before,Files.readAllBytes(source)),"Source changed");
            require(Files.size(frame)>115200,"Missing JPEG or frame");
            if (i>0) frames.append(',');
            frames.append("{\"path\":\"").append(frame).append("\",\"name\":\"Photo ").append(i).append("\"}");
        }
        frames.append(']'); Path rom = output.resolve("android-jni.gba");
        long size = Native.build(frames.toString(),"ANDROID TEST",5,rom.toString());
        byte[] data = Files.readAllBytes(rom); require(data.length==size,"Returned size differs");
        require((size&(size-1))==0 && size>=262144,"ROM padding");
        int checksum=0; for (int i=160; i<190; i++) checksum+=data[i]&255;
        require((checksum&255)==0xe7 && (data[178]&255)==0x96,"Header checksum");
        int album=-1; for (int i=192; i<65536; i+=4) if (u32(data,i)==0x50494353) { album=i; break; }
        require(album>=0 && u32(data,album+4)==3 && u32(data,album+8)==5,"Album header");
        for (int n=0; n<3; n++) {
            byte[] frame = Files.readAllBytes(output.resolve("photo-"+n+".frame"));
            int offset=album+u32(data,album+16+n*36);
            for (int px=0; px<38400; px++) {
                int expected=((frame[px*3]&255)>>3)|(((frame[px*3+1]&255)>>3)<<5)|(((frame[px*3+2]&255)>>3)<<10);
                require(((data[offset+px*2]&255)|((data[offset+px*2+1]&255)<<8))==expected,"Pixel mismatch");
            }
        }
        boolean rejected=false; try { Native.build("[]","BAD",5,output.resolve("bad.gba").toString()); } catch (IllegalArgumentException e) { rejected=true; }
        require(rejected,"Empty album accepted");
        rejected=false; try { Native.process(output.resolve("photo-é-0.png").toString(),"{}",output.resolve("bad.frame").toString()); } catch (IllegalArgumentException e) { rejected=true; }
        require(rejected,"Malformed settings accepted");
        System.out.println("PASS: Java → JNI → Rust conversion/export; RGB555 pixels, header, Unicode paths, unchanged inputs, and errors verified");
    }
}
