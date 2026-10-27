package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Select a concrete language/track before choosing its economical representation. */
public final class SabrAudioTrackSelector {
    public static final class Candidate<T> {
        public final T value;
        public final String trackId, language;
        public final int bitrate;
        public final boolean drc;
        public Candidate(T value, String trackId, String language, int bitrate, boolean drc) {
            this.value = value; this.trackId = trackId; this.language = language;
            this.bitrate = bitrate; this.drc = drc;
        }
    }
    private SabrAudioTrackSelector() {}
    public static <T> T select(List<Candidate<T>> candidates, String sourceLanguage,
                               String preferredTrackId) throws IOException {
        List<Candidate<T>> tracks = new ArrayList<>();
        String requested = language(sourceLanguage);
        for (Candidate<T> c : candidates) {
            if (preferredTrackId != null && !preferredTrackId.isEmpty()) {
                if (preferredTrackId.equals(c.trackId)) tracks.add(c);
            } else if (requested.equals(language(c.language != null ? c.language : c.trackId))) {
                tracks.add(c);
            }
        }
        if (tracks.isEmpty() && (preferredTrackId == null || preferredTrackId.isEmpty())) {
            boolean unscoped = !candidates.isEmpty();
            for (Candidate<T> c : candidates) {
                unscoped &= language(c.language).isEmpty() && language(c.trackId).isEmpty();
            }
            if (unscoped) tracks.addAll(candidates);
        }
        if (tracks.isEmpty()) throw new IOException("SABR: requested original audio track is unavailable");
        List<Candidate<T>> originals = new ArrayList<>();
        for (Candidate<T> c : tracks) {
            if (c.language != null && c.language.contains("acont=original")) originals.add(c);
        }
        if (!originals.isEmpty()) tracks = originals;
        Set<String> ids = new HashSet<>();
        for (Candidate<T> c : tracks) ids.add(c.trackId == null ? "" : c.trackId);
        if (ids.size() > 1) throw new IOException("SABR: original audio language is ambiguous");
        Candidate<T> selected = null;
        for (Candidate<T> c : tracks) {
            if (selected == null || (selected.drc && !c.drc)
                    || selected.drc == c.drc && rate(c.bitrate) < rate(selected.bitrate)) selected = c;
        }
        return selected.value;
    }
    private static int rate(int bitrate) { return bitrate > 0 ? bitrate : Integer.MAX_VALUE; }
    static String language(String tag) {
        if (tag == null) return "";
        String lang = tag.toLowerCase(Locale.ROOT).split("[. :\\-]", 2)[0];
        return lang.matches("[a-z]{2,3}") ? lang : "";
    }
}
