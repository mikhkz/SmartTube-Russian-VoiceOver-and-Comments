package com.liskovsoft.smartyoutubetv2.common.vot;
import org.junit.Test;
import static org.junit.Assert.*;
import com.google.android.exoplayer2.C;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
public class TranslationGainAudioProcessorTest {
    @Test public void unityPreservesPcmAndBoostIncreasesQuietSamples() {
        for (short sample : new short[] {Short.MIN_VALUE, -10000, 0, 10000, Short.MAX_VALUE})
            assertEquals(sample, TranslationGainAudioProcessor.applyGain(sample, 1f));
        assertEquals(15000, TranslationGainAudioProcessor.applyGain((short) 10000, 1.5f));
        assertEquals(-15000, TranslationGainAudioProcessor.applyGain((short) -10000, 1.5f));
    }
    @Test public void boostLimitsPeaksWithoutWrappingOrChangingPolarity() {
        short positive = TranslationGainAudioProcessor.applyGain(Short.MAX_VALUE, 1.5f);
        short negative = TranslationGainAudioProcessor.applyGain(Short.MIN_VALUE, 1.5f);
        assertTrue(positive > 30000 && positive < Short.MAX_VALUE);
        assertTrue(negative < -30000 && negative > Short.MIN_VALUE);
    }
    @Test public void changingGainAppliesToTheNextPcmBuffer() {
        TranslationGainAudioProcessor p = new TranslationGainAudioProcessor();
        p.configure(48000, 1, C.ENCODING_PCM_16BIT); p.flush();
        ByteBuffer input = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder());
        input.putShort((short) 10000).flip(); p.queueInput(input);
        assertEquals(10000, p.getOutput().getShort());
        p.setGain(1.5f); input.clear(); input.putShort((short) 10000).flip(); p.queueInput(input);
        assertEquals(15000, p.getOutput().getShort());
    }
}
