package com.liskovsoft.smartyoutubetv2.common.vot;

/** Logical player volume and temporary original-track gain are independent. */
public final class VoiceOverVolume {
    private float mVolume = 1f;
    private float mMultiplier = 1f;

    public void setVolume(float volume) { mVolume = clamp(volume); }
    public float getVolume() { return mVolume; }
    public void setMultiplier(float multiplier) { mMultiplier = clamp(multiplier); }
    public float getOutputVolume() { return mVolume * mMultiplier; }

    private static float clamp(float value) {
        return Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value));
    }
}
