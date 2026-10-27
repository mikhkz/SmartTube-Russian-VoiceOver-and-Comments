package com.liskovsoft.smartyoutubetv2.common.vot;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class VoiceOverVolumeTest {
    @Test public void nativeVolumeChangesKeepDucking() {
        VoiceOverVolume v = new VoiceOverVolume();
        v.setVolume(0.8f); v.setMultiplier(0.15f);
        assertEquals(0.12f, v.getOutputVolume(), 0.00001f);
        v.setVolume(0.6f);
        assertEquals(0.6f, v.getVolume(), 0.00001f);
        assertEquals(0.09f, v.getOutputVolume(), 0.00001f);
    }
    @Test public void allGraduationsAndMuteApplyExactly() {
        VoiceOverVolume v = new VoiceOverVolume(); v.setVolume(0.7f);
        for (int percent = 0; percent <= 100; percent += 5) {
            v.setMultiplier(percent / 100f);
            assertEquals(0.7f * percent / 100f, v.getOutputVolume(), 0.00001f);
        }
        v.setMultiplier(0f); v.setVolume(1f);
        assertEquals(0f, v.getOutputVolume(), 0f);
    }
    @Test public void repeatedSynchronizationDoesNotReduceBaseVolume() {
        VoiceOverVolume v = new VoiceOverVolume(); v.setVolume(0.6f);
        for (int i = 0; i < 1000; i++) v.setMultiplier(0.1f);
        assertEquals(0.06f, v.getOutputVolume(), 0.00001f);
        v.setMultiplier(1f);
        assertEquals(0.6f, v.getOutputVolume(), 0.00001f);
    }
}
