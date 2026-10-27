package com.liskovsoft.smartyoutubetv2.common.vot;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
public class SabrAudioTrackSelectorTest {
    private SabrAudioTrackSelector.Candidate<String> f(String id, String track, String lang, int rate, boolean drc) {
        return new SabrAudioTrackSelector.Candidate<>(id, track, lang, rate, drc);
    }
    @Test public void selectsLanguageBeforeBitrateAndDuplicateItag() throws Exception {
        assertEquals("en249", SabrAudioTrackSelector.select(Arrays.asList(
                f("es249", "es.10", "es:acont=dubbed", 10000, false),
                f("en251", "en.4", "en:acont=original", 128000, false),
                f("en249", "en.4", "en:acont=original", 48000, false)), "en", null));
    }
    @Test public void preservesExactRegionalTrack() throws Exception {
        assertEquals("us", SabrAudioTrackSelector.select(Arrays.asList(
                f("gb", "en-GB.4", "en-GB", 10000, false), f("us", "en-US.4", "en-US", 48000, false)), "en", "en-US.4"));
    }
    @Test(expected=IOException.class) public void refusesWrongLanguageFallback() throws Exception {
        SabrAudioTrackSelector.select(Arrays.asList(f("es", "es.10", "es", 10000, false)), "en", null);
    }
    @Test(expected=IOException.class) public void refusesAmbiguousTrack() throws Exception {
        SabrAudioTrackSelector.select(Arrays.asList(f("a", "en.4", "en", 10000, false),
                f("b", "en.10", "en", 20000, false)), "en", null);
    }
    @Test public void originalAndNonDrcArePreferred() throws Exception {
        assertEquals("original", SabrAudioTrackSelector.select(Arrays.asList(
                f("dub", "en.10", "en:acont=dubbed", 10000, false),
                f("drc", "en.4", "en:acont=original", 20000, true),
                f("original", "en.4", "en:acont=original", 48000, false)), "en", null));
    }
    @Test public void supportsLegacyUnscopedAudio() throws Exception {
        assertEquals("small", SabrAudioTrackSelector.select(Arrays.asList(
                f("large", null, null, 128000, false), f("small", null, null, 48000, false)), "en", null));
    }
}
