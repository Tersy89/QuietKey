package app.quietkey;

import java.nio.ByteBuffer;

/** Copy only the Y plane, honoring vendor row padding and pixel stride. */
public final class QrLuma {
    public static byte[] copy(ByteBuffer source,int width,int height,int rowStride,int pixelStride){
        if(width<1||height<1||(long)width*height>16000000||pixelStride<1||rowStride<(long)(width-1)*pixelStride+1)throw new IllegalArgumentException("Invalid camera plane");
        ByteBuffer buffer=source.duplicate();int base=buffer.position();long last=(long)base+(long)(height-1)*rowStride+(long)(width-1)*pixelStride;
        if(last>=buffer.limit())throw new IllegalArgumentException("Incomplete camera plane");
        byte[] pixels=new byte[width*height];for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels[y*width+x]=buffer.get(base+y*rowStride+x*pixelStride);
        return pixels;
    }
}
