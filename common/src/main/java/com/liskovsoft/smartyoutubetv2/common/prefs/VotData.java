package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase;

public class VotData extends SharedPreferencesBase {
    private static final String PREFS_NAME = "vot_data";
    private static final String OAUTH_TOKEN = "yandex_oauth_token";
    private static final String LIVELY_VOICE = "use_lively_voice";
    private static final String ORIGINAL_VOLUME_PERCENT = "original_volume_percent";
    private static final String TRANSLATION_VOLUME_PERCENT = "translation_volume_percent";
    private static final String AUTO_TRANSLATE = "auto_translate_enabled";
    private static final String PREFER_YOUTUBE_AUTO_DUB = "prefer_youtube_auto_dub";
    private static final String COMMENT_TRANSLATE = "comment_translate_enabled";
    private static final int DEFAULT_ORIGINAL_VOLUME_PERCENT = 15;
    private static final int DEFAULT_TRANSLATION_VOLUME_PERCENT = 100;
    public static final int MAX_TRANSLATION_VOLUME_PERCENT = 150;

    @SuppressLint("StaticFieldLeak")
    private static VotData sInstance;

    private VotData(Context context) {
        super(context.getApplicationContext(), PREFS_NAME);
    }

    public static VotData instance(Context context) {
        if (sInstance == null) {
            sInstance = new VotData(context);
        }
        return sInstance;
    }

    public String getOAuthToken() {
        return getString(OAUTH_TOKEN, "");
    }

    public void setOAuthToken(String token) {
        putString(OAUTH_TOKEN, normalizeToken(token));
    }

    public void clearOAuthToken() {
        putString(OAUTH_TOKEN, "");
    }

    public boolean hasOAuthToken() {
        return !TextUtils.isEmpty(getOAuthToken());
    }

    public boolean isLivelyVoiceEnabled() {
        // Yandex live voices are available through the current browser API
        // without an OAuth token. Keep the explicit preference as an opt-out,
        // while making the default match vot-cli-live.
        return getBoolean(LIVELY_VOICE, true);
    }

    public void setLivelyVoiceEnabled(boolean enabled) {
        putBoolean(LIVELY_VOICE, enabled);
    }

    /** YouTube/original track level while translation plays (0–100%). */
    public int getOriginalVolumePercent() {
        return clampPercent(getInt(ORIGINAL_VOLUME_PERCENT, DEFAULT_ORIGINAL_VOLUME_PERCENT));
    }

    public void setOriginalVolumePercent(int percent) {
        putInt(ORIGINAL_VOLUME_PERCENT, clampPercent(percent));
    }

    /** Russian voice-over level (0–100%). */
    public int getTranslationVolumePercent() {
        return Math.max(0, Math.min(MAX_TRANSLATION_VOLUME_PERCENT,
                getInt(TRANSLATION_VOLUME_PERCENT, DEFAULT_TRANSLATION_VOLUME_PERCENT)));
    }

    public void setTranslationVolumePercent(int percent) {
        putInt(TRANSLATION_VOLUME_PERCENT, Math.max(0, Math.min(MAX_TRANSLATION_VOLUME_PERCENT, percent)));
    }

    public boolean isAutoTranslateEnabled() {
        return getBoolean(AUTO_TRANSLATE, false);
    }

    public void setAutoTranslateEnabled(boolean enabled) {
        putBoolean(AUTO_TRANSLATE, enabled);
    }

    public boolean isPreferYoutubeAutoDub() {
        return getBoolean(PREFER_YOUTUBE_AUTO_DUB, false);
    }

    public void setPreferYoutubeAutoDub(boolean enabled) {
        putBoolean(PREFER_YOUTUBE_AUTO_DUB, enabled);
    }

    public boolean isCommentTranslateEnabled() {
        return getBoolean(COMMENT_TRANSLATE, true);
    }

    public void setCommentTranslateEnabled(boolean enabled) {
        putBoolean(COMMENT_TRANSLATE, enabled);
    }

    public float getOriginalVolumeMultiplier() {
        return getOriginalVolumePercent() / 100f;
    }

    public float getTranslationVolumeMultiplier() {
        return getTranslationVolumePercent() / 100f;
    }

    public String getTokenPreview() {
        String token = getOAuthToken();
        if (TextUtils.isEmpty(token)) {
            return "";
        }
        if (token.length() <= 12) {
            return token.substring(0, 4) + "…";
        }
        return token.substring(0, 6) + "…" + token.substring(token.length() - 4);
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        return token.trim().replaceAll("\\s+", "");
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
