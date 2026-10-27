package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.sharedutils.mylogger.Log;

public class TranslationAudioPlayer implements Player.EventListener {
    private static final String TAG = TranslationAudioPlayer.class.getSimpleName();

    public interface OnReadyListener {
        void onReady();
    }

    private final Context mContext;
    private SimpleExoPlayer mPlayer;
    private final TranslationGainAudioProcessor mGainProcessor = new TranslationGainAudioProcessor();
    @Nullable
    private OnReadyListener mOnReadyListener;
    private boolean mReadyFired;
    @Nullable
    private Runnable mOnErrorListener;

    public TranslationAudioPlayer(Context context) {
        mContext = context.getApplicationContext();
    }

    public void play(String url, long startPositionMs, float volume, float speed) {
        release();
        mReadyFired = false;
        String userAgent = Util.getUserAgent(mContext, "SmartTubeVOT");
        DefaultDataSourceFactory dataSourceFactory = new DefaultDataSourceFactory(mContext, userAgent);
        ExtractorMediaSource mediaSource = new ExtractorMediaSource.Factory(dataSourceFactory)
                .createMediaSource(Uri.parse(url));

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(mContext) {
            @Override
            protected AudioProcessor[] buildAudioProcessors() {
                return new AudioProcessor[] {mGainProcessor};
            }
        };
        mPlayer = ExoPlayerFactory.newSimpleInstance(mContext, renderersFactory, new DefaultTrackSelector());
        mPlayer.addListener(this);
        setVolume(volume);
        if (speed > 0f && speed != 1f) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(speed, 1f));
        }
        mPlayer.prepare(mediaSource);
        mPlayer.setPlayWhenReady(false);
        if (startPositionMs > 0) {
            mPlayer.seekTo(startPositionMs);
        }
    }

    public void setOnReadyListener(@Nullable OnReadyListener listener) {
        mOnReadyListener = listener;
    }

    public void setVolume(float volume) {
        float safeVolume = Math.max(0f, Math.min(1.5f, volume));
        mGainProcessor.setGain(safeVolume > 1f ? safeVolume : 1f);
        if (mPlayer != null) {
            float level = Math.min(1f, safeVolume);
            if (mPlayer.getVolume() != level) {
                Log.d(TAG, "VOT translation volume=%s", level);
                mPlayer.setVolume(level);
            }
        }
    }

    public void setOnErrorListener(@Nullable Runnable listener) {
        mOnErrorListener = listener;
    }

    public void setPlaybackSpeed(float speed) {
        if (mPlayer == null) {
            return;
        }
        float s = speed > 0f ? speed : 1f;
        mPlayer.setPlaybackParameters(new PlaybackParameters(s, 1f));
    }

    public boolean isReady() {
        return mPlayer != null && mPlayer.getPlaybackState() == Player.STATE_READY;
    }

    public void seekTo(long positionMs) {
        if (mPlayer != null) {
            mPlayer.seekTo(positionMs);
        }
    }

    public long getPositionMs() {
        return mPlayer != null ? mPlayer.getCurrentPosition() : 0;
    }

    public void pause() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
        }
    }

    public void resume() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(true);
        }
    }

    public void release() {
        if (mPlayer != null) {
            mPlayer.removeListener(this);
            mPlayer.release();
            mPlayer = null;
        }
        mReadyFired = false;
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (playbackState == Player.STATE_READY && !mReadyFired) {
            mReadyFired = true;
            if (mOnReadyListener != null) {
                mOnReadyListener.onReady();
            }
        }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        Log.e(TAG, "Translation player error: %s", error.getMessage());
        if (mOnErrorListener != null) {
            mOnErrorListener.run();
        }
    }
}
