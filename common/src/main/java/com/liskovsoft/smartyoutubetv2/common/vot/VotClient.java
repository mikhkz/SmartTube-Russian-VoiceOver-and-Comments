package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import org.json.JSONObject;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.schedulers.Schedulers;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.util.UUID;
import android.net.Uri;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class VotClient {
    private static final String TAG = VotClient.class.getSimpleName();
    private static final int MAX_POLL_ATTEMPTS = 60;

    private final Context mContext;
    private volatile boolean mCancelled;
    private volatile NativeSabrAudioDownloader mDownloader;
    private String mSourceLanguage = VotConfig.REQUEST_LANG;
    private String mPreferredAudioTrackId;
    private final VotHttp mHttp = new VotHttp();
    private final VotData mVotData;
    @Nullable
    private VotSession mSession;

    public VotClient(Context context) {
        mContext = context.getApplicationContext();
        mVotData = VotData.instance(context);
    }

    public String translateToRussian(String youtubeUrl, long durationSec) throws IOException, VotException {
        VotProgress last = observeTranslation(youtubeUrl, durationSec)
                .blockingLast();
        if (last == null) {
            throw new VotException("Translation cancelled");
        }
        if (last.type == VotProgress.TYPE_READY && last.audioUrl != null) {
            return last.audioUrl;
        }
        if (last.type == VotProgress.TYPE_FAILED) {
            throw new VotException(last.message != null ? last.message : "Translation failed");
        }
        throw new VotException("Translation timeout");
    }

    public Observable<VotProgress> observeTranslation(String youtubeUrl, long durationSec) {
        return observeTranslation(youtubeUrl, durationSec, "");
    }

    public Observable<VotProgress> observeTranslation(String youtubeUrl, long durationSec, String videoTitle) {
        return observeTranslation(youtubeUrl, durationSec, videoTitle, VotConfig.REQUEST_LANG, null);
    }

    public Observable<VotProgress> observeTranslation(String youtubeUrl, long durationSec, String videoTitle,
                                                       String sourceLanguage, String preferredAudioTrackId) {
        return Observable.<VotProgress>create(emitter -> {
            // Each run owns its HTTP calls, session and downloader. Cancelling the previous
            // video cannot cancel a newer translation or replace its session credentials.
            VotClient run = new VotClient(mContext);
            String language = SabrAudioTrackSelector.language(sourceLanguage);
            run.mSourceLanguage = language.isEmpty() ? VotConfig.REQUEST_LANG : language;
            run.mPreferredAudioTrackId = preferredAudioTrackId;
            emitter.setCancellable(run::cancel);
            run.pollTranslation(emitter, youtubeUrl, durationSec, videoTitle, true,
                    mVotData.isLivelyVoiceEnabled(), true);
        }).subscribeOn(Schedulers.io());
    }

    private void cancel() {
        mCancelled = true;
        mHttp.cancel();
        NativeSabrAudioDownloader downloader = mDownloader;
        if (downloader != null) downloader.cancel();
    }

    private void pollTranslation(ObservableEmitter<VotProgress> emitter, String youtubeUrl, long durationSec,
                                 String videoTitle, boolean allowAudioFallback,
                                 boolean useLivelyVoice, boolean allowLivelyFallback) {
        try {
            VotAudioChunks.checkCancelled(() -> mCancelled);
            VotTranslationResponse response = requestTranslation(
                    youtubeUrl, durationSec, videoTitle, useLivelyVoice);
            if (useLivelyVoice && allowLivelyFallback && isLivelyVoiceUnavailable(response)) {
                Log.w(TAG, "lively voice unavailable; retrying with standard voice");
                useLivelyVoice = false;
                response = requestTranslation(youtubeUrl, durationSec, videoTitle, false);
            }
            if (!processResponse(emitter, youtubeUrl, durationSec, videoTitle,
                    allowAudioFallback, useLivelyVoice, allowLivelyFallback, response)) {
                return;
            }

            int waitSec = Math.max(3, response.remainingTimeSec > 0 ? response.remainingTimeSec : 5);
            for (int i = 0; i < MAX_POLL_ATTEMPTS && !emitter.isDisposed(); i++) {
                sleep(waitSec);
                if (emitter.isDisposed()) {
                    return;
                }
                response = requestTranslation(youtubeUrl, durationSec, videoTitle, useLivelyVoice);
                if (useLivelyVoice && allowLivelyFallback && isLivelyVoiceUnavailable(response)) {
                    Log.w(TAG, "lively voice became unavailable; retrying with standard voice");
                    useLivelyVoice = false;
                    response = requestTranslation(youtubeUrl, durationSec, videoTitle, false);
                }
                if (!processResponse(emitter, youtubeUrl, durationSec, videoTitle,
                        allowAudioFallback, useLivelyVoice, allowLivelyFallback, response)) {
                    return;
                }
                waitSec = Math.max(3, response.remainingTimeSec > 0 ? response.remainingTimeSec : waitSec);
            }
            if (!emitter.isDisposed()) {
                emitter.onNext(VotProgress.failed("Translation timeout"));
                emitter.onComplete();
            }
        } catch (IOException e) {
            if (!emitter.isDisposed()) {
                emitter.onError(e);
            }
        } catch (VotException e) {
            if (!emitter.isDisposed()) {
                emitter.onNext(VotProgress.failed(e.getMessage()));
                emitter.onComplete();
            }
        }
    }

    /** @return false if polling should stop (ready, failed, or disposed) */
    private boolean processResponse(ObservableEmitter<VotProgress> emitter, String youtubeUrl, long durationSec,
                                    String videoTitle, boolean allowAudioFallback,
                                    boolean useLivelyVoice, boolean allowLivelyFallback,
                                    VotTranslationResponse response)
            throws IOException, VotException {
        if (emitter.isDisposed()) {
            return false;
        }

        if (response.status == VotTranslationResponse.STATUS_SESSION_REQUIRED) {
            emitter.onNext(VotProgress.failed("auth required"));
            emitter.onComplete();
            return false;
        }

        if (response.status == VotTranslationResponse.STATUS_AUDIO_REQUESTED && allowAudioFallback) {
            emitter.onNext(VotProgress.waiting(0, VotTranslationResponse.STATUS_AUDIO_REQUESTED));
            handleAudioRequested(youtubeUrl, durationSec, videoTitle, useLivelyVoice, response.translationId);
            pollTranslation(emitter, youtubeUrl, durationSec, videoTitle,
                    false, useLivelyVoice, allowLivelyFallback);
            return false;
        }

        if (response.isReady() && response.url != null && !response.url.isEmpty()) {
            emitter.onNext(VotProgress.ready(response.url, response.isLivelyVoice));
            emitter.onComplete();
            return false;
        }

        if (response.status == VotTranslationResponse.STATUS_FAILED) {
            String msg = response.message != null ? response.message : "Translation failed";
            emitter.onNext(VotProgress.failed(msg));
            emitter.onComplete();
            return false;
        }

        if (response.isWaiting() || response.status == VotTranslationResponse.STATUS_AUDIO_REQUESTED) {
            emitter.onNext(VotProgress.waiting(response.remainingTimeSec, response.status));
            return true;
        }

        emitter.onNext(VotProgress.failed("Unexpected translation status: " + response.status));
        emitter.onComplete();
        return false;
    }

    private VotTranslationResponse requestTranslation(String youtubeUrl, double durationSec, String videoTitle,
                                                       boolean useLivelyVoice)
            throws IOException {
        byte[] body = VotProtobuf.encodeTranslationRequest(
                youtubeUrl,
                durationSec,
                mSourceLanguage,
                VotConfig.RESPONSE_LANG,
                true, // Match current vot.js: polling keeps the firstRequest API flag enabled.
                useLivelyVoice,
                videoTitle
        );

        Map<String, String> headers = buildTranslateHeaders(body, useLivelyVoice);
        byte[] raw;
        try {
            raw = mHttp.postProtobuf("/video-translation/translate", body, headers);
        } catch (VotHttp.HttpFailure e) {
            // Some API edges return a typed failed-voice protobuf with HTTP 400.
            // Retain its reason so the existing lively-to-standard fallback can act.
            if (e.statusCode == 400) {
                try {
                    VotTranslationResponse failure = VotProtobuf.decodeTranslationResponse(e.responseBody);
                    if (failure.status == VotTranslationResponse.STATUS_FAILED && failure.message != null)
                        return failure;
                } catch (IllegalArgumentException ignored) {}
            }
            throw e;
        }

        if (raw == null || raw.length == 0) {
            throw new IOException("Empty translation response");
        }
        return VotProtobuf.decodeTranslationResponse(raw);
    }

    private Map<String, String> buildTranslateHeaders(byte[] body, boolean useLivelyVoice) throws IOException {
        // Current vot.js always starts a signed session before translation, including
        // anonymous users; keep the upload and translation requests in the same session.
        ensureSession();
        Map<String, String> headers = VotHeaders.sessionTranslate(mSession, body, "/video-translation/translate");
        if (useLivelyVoice && mVotData.hasOAuthToken()) {
            headers = VotHeaders.merge(headers, VotHeaders.oauthHeader(mVotData.getOAuthToken()));
        }
        return headers;
    }

    private void handleAudioRequested(String youtubeUrl, long durationSec, String videoTitle,
                                      boolean useLivelyVoice, @Nullable String translationId)
            throws IOException, VotException {
        if (translationId == null || translationId.isEmpty()) {
            VotTranslationResponse r = requestTranslation(
                    youtubeUrl, durationSec, videoTitle, useLivelyVoice);
            translationId = r.translationId;
        }
        if (translationId == null || translationId.isEmpty()) {
            throw new VotException("Missing translationId for audio upload");
        }

        ensureSession();
        File audio = null;
        try {
            String videoId = Uri.parse(youtubeUrl).getQueryParameter("v");
            if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) throw new IOException("Missing YouTube video ID");
            mDownloader = new NativeSabrAudioDownloader(mContext, () -> mCancelled);
            audio = mDownloader.download(videoId, mSourceLanguage, mPreferredAudioTrackId);
        } catch (IOException sourceError) {
            VotAudioChunks.checkCancelled(() -> mCancelled);
            if (sourceError.getMessage().contains("YouTube auth required")) throw sourceError;
            Log.w(TAG, "SABR source unavailable; trying server fallback: %s", sourceError.getMessage());
            requestFailAudio(youtubeUrl);
            uploadEmptyAudio(youtubeUrl, translationId);
            return;
        } finally { mDownloader = null; }
        try {
            uploadSabrAudio(youtubeUrl, translationId, audio);
        } finally {
            if (audio != null && !audio.delete()) Log.w(TAG, "Unable to remove completed SABR audio cache");
        }
    }

    private void uploadSabrAudio(String youtubeUrl, String translationId, File audio) throws IOException {
        String fileId = "random-web_sabr-" + UUID.randomUUID();
        try (FileInputStream input = new FileInputStream(audio)) {
            VotAudioChunks.upload(input, VotAudioChunks.CHUNK_SIZE, () -> mCancelled, (bytes, index, total) -> {
                byte[] body = VotProtobuf.encodeAudioChunkRequest(youtubeUrl, translationId, fileId, bytes, index, total);
                IOException last = null;
                for (int attempt = 1; attempt <= 6; attempt++) {
                    VotAudioChunks.checkCancelled(() -> mCancelled);
                    try {
                        ensureSession();
                        byte[] response = mHttp.putProtobuf("/video-translation/audio", body,
                                VotHeaders.sessionTranslate(mSession, body, "/video-translation/audio"),
                                attempt == 1 ? 15 : attempt == 2 ? 20 : 30);
                        int status = response != null ? VotProtobuf.decodeAudioUploadStatus(response) : 0;
                        if (status != 1 && status != 2) throw new IOException("Yandex rejected audio chunk: " + status);
                        Log.d(TAG, "SABR uploaded chunk=%s bytes=%s total=%s ack=%s", index, bytes.length, total, status);
                        return;
                    } catch (IOException e) { last = e; }
                    if (attempt < 6) {
                        try { Thread.sleep(1500L); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Audio upload cancelled", e); }
                    }
                }
                throw last;
            });
        }
    }

    private static boolean isLivelyVoiceUnavailable(VotTranslationResponse response) {
        if (response == null || response.status != VotTranslationResponse.STATUS_FAILED || response.message == null) {
            return false;
        }
        String message = response.message.toLowerCase(Locale.ROOT);
        return message.contains("обычная озвучка") || message.contains("standard voice") ||
                message.contains("regular dubbing");
    }

    private void requestFailAudio(String youtubeUrl) throws IOException, VotException {
        String json = "{\"video_url\":\"" + youtubeUrl.replace("\"", "\\\"") + "\"}";
        byte[] raw = mHttp.putJson("/video-translation/fail-audio-js", json,
                VotHeaders.simpleTranslate(json.getBytes(StandardCharsets.UTF_8)));
        if (raw == null) {
            throw new VotException("fail-audio-js: empty response");
        }
        try {
            String text = new String(raw, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(text);
            if (obj.optInt("status", 0) != 1) {
                throw new VotException("fail-audio-js failed");
            }
        } catch (VotException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "fail-audio-js parse error: %s", e.getMessage());
        }
    }

    private void uploadEmptyAudio(String youtubeUrl, String translationId) throws IOException {
        byte[] body = VotProtobuf.encodeTranslationAudioRequest(
                youtubeUrl, translationId, "fallback-empty-audio:video-translation:"
                        + Uri.parse(youtubeUrl).getQueryParameter("v"));
        mHttp.putProtobuf("/video-translation/audio", body,
                VotHeaders.sessionTranslate(mSession, body, "/video-translation/audio"));
    }

    private void ensureSession() throws IOException {
        if (mSession != null && System.currentTimeMillis() - mSession.createdAtMs < mSession.expiresSec * 1000L) {
            return;
        }
        String uuid = VotSignature.randomToken();
        byte[] body = VotProtobuf.encodeSessionRequest(uuid, "video-translation");
        byte[] raw = mHttp.postProtobuf("/session/create", body, java.util.Collections.singletonMap("Vtrans-Signature", VotSignature.sign(body)));
        if (raw == null) {
            throw new IOException("Empty session response");
        }
        VotSession decoded = VotProtobuf.decodeSessionResponse(raw);
        if (decoded.secretKey == null || decoded.secretKey.isEmpty()) throw new IOException("Invalid Yandex upload session");
        decoded.uuid = uuid;
        decoded.createdAtMs = System.currentTimeMillis();
        mSession = decoded;
    }

    private void sleep(int sec) throws VotException {
        try {
            TimeUnit.SECONDS.sleep(sec);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VotException("Interrupted");
        }
    }
}
