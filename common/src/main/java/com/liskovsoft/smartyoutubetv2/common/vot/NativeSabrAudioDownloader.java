package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.DefaultExtractorInput;
import com.google.android.exoplayer2.source.sabr.manifest.*;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.models.FormatSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.SabrFormatMetadata;
import com.google.android.exoplayer2.source.sabr.parser.parts.*;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import java.io.*;
import java.util.*;

/** Independent audio-only SABR pump. Never consumes or seeks the playback stream. */
public final class NativeSabrAudioDownloader {
    private static final String TAG = "VotSabrDownload";
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final long SPACE_RESERVE = 512L * 1024 * 1024;
    private static final long DEADLINE_MS = 10 * 60 * 1000;
    private final Context mContext;
    private final VotAudioChunks.Cancellation mCancelled;
    private volatile DataSource mActiveSource;

    public NativeSabrAudioDownloader(Context context, VotAudioChunks.Cancellation cancelled) {
        mContext = context.getApplicationContext(); mCancelled = cancelled;
    }
    public void cancel() {
        DataSource active = mActiveSource;
        if (active != null) try { active.close(); } catch (IOException ignored) {}
    }
    public File download(String videoId, String sourceLanguage, String preferredTrackId) throws IOException {
        VotAudioChunks.checkCancelled(mCancelled);
        if (mContext.getCacheDir().getUsableSpace() < MAX_BYTES + SPACE_RESERVE)
            throw new IOException("Insufficient space for translation audio");
        IOException last = null;
        long deadline = SystemClock.elapsedRealtime() + DEADLINE_MS;
        for (int attempt = 1; attempt <= 2; attempt++) {
            VotAudioChunks.checkCancelled(mCancelled);
            File target = File.createTempFile("vot-sabr-", ".audio", mContext.getCacheDir());
            boolean complete = false;
            try {
                MediaItemFormatInfo info = YouTubeServiceManager.instance().getMediaItemService()
                        .getFormatInfoObserve(videoId).blockingFirst();
                VotAudioChunks.checkCancelled(mCancelled);
                if (info.isUnplayable()) {
                    String reason = String.valueOf(info.getPlayabilityReason()).toLowerCase(Locale.ROOT);
                    if (reason.contains("sign in") || reason.contains("login") || reason.contains("log in")
                            || reason.contains("войдите") || reason.contains("авториз"))
                        throw new IOException("YouTube auth required");
                    throw new IOException("YouTube audio unavailable: " + info.getPlayabilityReason());
                }
                if (info.isLive() || !info.containsSabrFormats()) throw new IOException("SABR audio metadata unavailable");
                SabrManifest manifest = new SabrManifestParser().parse(info);
                Format audio = chooseAudio(manifest, sourceLanguage, preferredTrackId);
                Log.d(TAG, "start video=%s attempt=%s itag=%s track=%s bitrate=%s", videoId, attempt,
                        audio.id, audioTrackId(audio), audio.bitrate);
                pump(manifest, audio, target, deadline);
                if (target.length() == 0) throw new IOException("Empty SABR audio");
                complete = true;
                Log.d(TAG, "complete video=%s bytes=%s", videoId, target.length());
                return target;
            } catch (RuntimeException e) {
                last = new IOException("SABR audio download failed", e);
            } catch (IOException e) { last = e; }
            finally { if (!complete && !target.delete()) Log.w(TAG, "Unable to remove incomplete audio cache"); }
            VotAudioChunks.checkCancelled(mCancelled);
            Log.w(TAG, "attempt=%s failed: %s", attempt, last.getMessage());
            if (last.getMessage().contains("YouTube auth required")) throw last;
        }
        throw last;
    }
    static Format chooseAudio(SabrManifest manifest, String language, String preferredTrackId) throws IOException {
        List<SabrAudioTrackSelector.Candidate<Format>> candidates = new ArrayList<>();
        for (Period period : manifest.periods) for (AdaptationSet set : period.adaptationSets) {
            if (set.type != C.TRACK_TYPE_AUDIO) continue;
            for (Representation r : set.representations) {
                Format f = r.format;
                if (!"audio/opus".equals(f.sampleMimeType) && !"audio/vorbis".equals(f.sampleMimeType)
                        && !"audio/mp4a-latm".equals(f.sampleMimeType)) continue;
                candidates.add(new SabrAudioTrackSelector.Candidate<>(f, audioTrackId(f), f.language, f.bitrate, f.isDrc));
            }
        }
        return SabrAudioTrackSelector.select(candidates, language, preferredTrackId);
    }
    public static String audioTrackId(Format format) {
        if (format != null && format.metadata != null) for (int i = 0; i < format.metadata.length(); i++) {
            if (format.metadata.get(i) instanceof SabrFormatMetadata)
                return ((SabrFormatMetadata) format.metadata.get(i)).audioTrackId;
        }
        return null;
    }
    private void pump(SabrManifest manifest, Format audio, File target, long deadline) throws IOException {
        SabrStream stream = manifest.getSabrStream(C.TRACK_TYPE_AUDIO);
        stream.setFormatSelector(new FormatSelector("VOT original audio", false, audio));
        DataSource.Factory factory = new ExoMediaSourceFactory(mContext).createAudioDownloadDataSourceFactory();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-protobuf"); headers.put("Accept", "application/vnd.yt-ump");
        if (manifest.visitorCookie != null) headers.put("Cookie", manifest.visitorCookie);
        long firstSequence = -1, lastSequence = -1, coveredMs = 0, exactDurationMs = manifest.durationMs;
        boolean initialized = false, finished = false;
        int idleRequests = 0;
        byte[] copyBuffer = new byte[64 * 1024];
        try (FileOutputStream out = new FileOutputStream(target)) {
            for (int request = 0; !finished; request++) {
                VotAudioChunks.checkCancelled(mCancelled);
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("SABR download timed out");
                if (idleRequests >= 5) throw new IOException("SABR audio stopped advancing");
                long previousSequence = lastSequence;
                long previousBytes = out.getChannel().position();
                DataSource source = factory.createDataSource();
                mActiveSource = source;
                VotAudioChunks.checkCancelled(mCancelled);
                String requestUrl = manifest.getRequestUrl(C.TRACK_TYPE_AUDIO);
                DataSpec spec = new DataSpec(Uri.parse(requestUrl), DataSpec.HTTP_METHOD_POST,
                        manifest.createAudioDownloadRequestBytes(!initialized, coveredMs, firstSequence, lastSequence),
                        0, 0, C.LENGTH_UNSET, null, 0, headers);
                boolean segmentOpen = false, skip = false;
                long openSequence = -1;
                try {
                    long length = source.open(spec);
                    DefaultExtractorInput input = new DefaultExtractorInput(source, 0, length);
                    SabrPart part;
                    while ((part = stream.parse(input)) != null) {
                        VotAudioChunks.checkCancelled(mCancelled);
                        if (part instanceof FormatInitializedSabrPart) {
                            long end = ((FormatInitializedSabrPart) part).endTimeMs;
                            if (end > 0) exactDurationMs = end;
                        } else if (part instanceof RefreshPlayerResponseSabrPart) {
                            throw new IOException("SABR metadata expired; refresh required");
                        } else if (part instanceof MediaSegmentInitSabrPart) {
                            MediaSegmentInitSabrPart init = (MediaSegmentInitSabrPart) part;
                            if (segmentOpen) throw new IOException("SABR audio segments interleaved");
                            segmentOpen = true; openSequence = init.sequenceNumber;
                            skip = init.isInitSegment ? initialized : init.sequenceNumber <= lastSequence;
                            if (!skip && !init.isInitSegment && (lastSequence < 0 ? init.sequenceNumber > 1
                                    : init.sequenceNumber != lastSequence + 1)) throw new IOException("SABR audio has a segment gap");
                        } else if (part instanceof MediaSegmentDataSabrPart) {
                            MediaSegmentDataSabrPart data = (MediaSegmentDataSabrPart) part;
                            if (!segmentOpen || data.sequenceNumber != openSequence)
                                throw new IOException("SABR audio data without a matching segment");
                            int remaining = data.contentLength;
                            if (remaining < 0) throw new IOException("Invalid SABR audio size");
                            while (remaining > 0) {
                                VotAudioChunks.checkCancelled(mCancelled);
                                int count = Math.min(copyBuffer.length, remaining);
                                data.data.readFully(copyBuffer, 0, count);
                                if (!skip) {
                                    if (out.getChannel().position() + count > MAX_BYTES) throw new IOException("Translation audio is too large");
                                    out.write(copyBuffer, 0, count);
                                }
                                remaining -= count;
                            }
                        } else if (part instanceof MediaSegmentEndSabrPart) {
                            MediaSegmentEndSabrPart end = (MediaSegmentEndSabrPart) part;
                            if (!segmentOpen || end.sequenceNumber != openSequence)
                                throw new IOException("SABR audio end without a matching segment");
                            if (!skip) {
                                if (end.isInitSegment) initialized = true;
                                else {
                                    if (!initialized) throw new IOException("SABR audio initialization missing");
                                    if (firstSequence < 0) firstSequence = end.sequenceNumber;
                                    lastSequence = end.sequenceNumber;
                                    coveredMs = end.startTimeMs + end.durationMs;
                                    finished = end.totalSegments > 0 ? lastSequence >= end.totalSegments
                                            : exactDurationMs > 0 && coveredMs >= exactDurationMs;
                                }
                            }
                            segmentOpen = false;
                        }
                    }
                    if (segmentOpen) throw new IOException("SABR response ended with an incomplete audio segment");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IOException("SABR audio download cancelled", e);
                } catch (IOException e) {
                    // CDN failover must not continue a response whose payload was partly written.
                    if (out.getChannel().position() != previousBytes || previousSequence != lastSequence
                            || !manifest.maybeUseNextCdn(requestUrl)) throw e;
                } finally { source.close(); mActiveSource = null; }
                idleRequests = previousSequence == lastSequence ? idleRequests + 1 : 0;
                if (request % 10 == 0) Log.d(TAG, "progress request=%s sequence=%s positionMs=%s bytes=%s",
                        request, lastSequence, coveredMs, out.getChannel().position());
                if (!finished && stream.getBackoffTimeMs() > 0) {
                    try { Thread.sleep(Math.min(1000, stream.getBackoffTimeMs())); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Cancelled", e); }
                }
            }
            Log.d(TAG, "EOF segments=%s durationMs=%s bytes=%s", lastSequence + 1, coveredMs, out.getChannel().position());
        }
    }
}
