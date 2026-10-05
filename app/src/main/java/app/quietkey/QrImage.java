package app.quietkey;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.*;

/** Avoid QR masks that confuse ordinary finder detection even with perfect pixels. */
public final class QrImage {
    public static BitMatrix encode(String text,int side)throws WriterException{
        Map<EncodeHintType,Object> options=new EnumMap<>(EncodeHintType.class);
        options.put(EncodeHintType.MARGIN,6);options.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);
        for(int mask=-1;mask<8;mask++){
            if(mask>=0)options.put(EncodeHintType.QR_MASK_PATTERN,mask);
            BitMatrix matrix=new QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,side,side,options);
            try{if(text.equals(decode(matrix)))return matrix;}catch(ReaderException ignored){}
        }
        throw new WriterException("QR could not be displayed at this size. Use the copied transfer instead.");
    }
    static String decode(BitMatrix matrix)throws ReaderException{
        int width=matrix.getWidth(),height=matrix.getHeight();byte[] pixels=new byte[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels[y*width+x]=matrix.get(x,y)?0:(byte)255;
        LuminanceSource source=new PlanarYUVLuminanceSource(pixels,width,height,0,0,width,height,false);
        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)),hints).getText();
    }
}
