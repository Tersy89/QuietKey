package app.quietkey;
import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;
public final class QrImageTest {
    @Test public void variedFramesHaveDetectableFinderPatternsAtPhoneSizes()throws Exception{
        Random random=new Random(41042);String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for(int sample=0;sample<24;sample++){StringBuilder text=new StringBuilder("QKF1:");for(int i=0;i<369;i++)text.append(alphabet.charAt(random.nextInt(alphabet.length())));for(int size:new int[]{320,480,720,960})assertEquals(text.toString(),QrImage.decode(QrImage.encode(text.toString(),size)));}
    }
}
