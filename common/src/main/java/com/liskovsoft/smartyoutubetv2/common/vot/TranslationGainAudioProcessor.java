package com.liskovsoft.smartyoutubetv2.common.vot;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * Zero-latency gain stage for the secondary VOT player.
 *
 * <p>Only gain above unity is applied here. Values up to 100% continue to use ExoPlayer's
 * regular volume control. Peaks above 85% are softly limited to avoid hard PCM clipping.</p>
 */
public final class TranslationGainAudioProcessor extends BaseAudioProcessor {
    private static final float MAX_GAIN = 1.5f;
    private static final float LIMITER_THRESHOLD = 0.85f;
    private volatile float mGain = 1f;

    public void setGain(float gain) {
        mGain = Math.max(1f, Math.min(MAX_GAIN, gain));
    }

    @Override
    public boolean configure(int sampleRateHz, int channelCount, int encoding) {
        return setInputFormat(sampleRateHz, channelCount, encoding);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int inputSize = inputBuffer.remaining();
        ByteBuffer outputBuffer = replaceOutputBuffer(inputSize);
        float gain = mGain;

        if (encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2) {
                outputBuffer.putShort(applyGain(inputBuffer.getShort(), gain));
            }
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) {
                outputBuffer.putFloat(applyGain(inputBuffer.getFloat(), gain));
            }
        } else {
            // Stay compatible with an unexpected PCM encoding. ExoPlayer may still play it;
            // it simply won't receive the optional boost.
            outputBuffer.put(inputBuffer);
        }

        while (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer.get());
        }
        outputBuffer.flip();
    }

    static short applyGain(short sample, float gain) {
        if (gain == 1f) {
            return sample;
        }
        float normalized = sample / 32768f;
        float processed = applyGain(normalized, gain);
        int pcm = Math.round(processed * 32768f);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm));
    }

    static float applyGain(float sample, float gain) {
        float amplified = sample * Math.max(1f, Math.min(MAX_GAIN, gain));
        float magnitude = Math.abs(amplified);
        if (magnitude <= LIMITER_THRESHOLD) {
            return amplified;
        }

        float over = (magnitude - LIMITER_THRESHOLD) / (1f - LIMITER_THRESHOLD);
        float limited = LIMITER_THRESHOLD + (1f - LIMITER_THRESHOLD)
                * (1f - (float) Math.exp(-over));
        limited = Math.min(0.9999695f, limited);
        return Math.copySign(limited, amplified);
    }
}
