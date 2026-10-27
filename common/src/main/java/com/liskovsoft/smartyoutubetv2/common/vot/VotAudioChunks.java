package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** One-chunk lookahead: only clean EOF can mark the last nonempty upload as terminal. */
public final class VotAudioChunks {
    public static final int CHUNK_SIZE = 5_295_308;
    public interface Cancellation { boolean isCancelled(); }
    public interface Sink { void send(byte[] bytes, int index, int total) throws IOException; }
    private VotAudioChunks() {}
    public static void upload(InputStream source, int chunkSize, Cancellation cancelled, Sink sink)
            throws IOException {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize");
        byte[] pending = read(source, chunkSize, cancelled);
        if (pending.length == 0) throw new IOException("Empty SABR audio");
        int index = 0;
        while (true) {
            byte[] next = read(source, chunkSize, cancelled);
            checkCancelled(cancelled);
            boolean last = next.length == 0;
            sink.send(pending, index, last ? index + 1 : 0);
            checkCancelled(cancelled);
            if (last) return;
            index++;
            pending = next;
        }
    }
    private static byte[] read(InputStream source, int size, Cancellation cancelled) throws IOException {
        byte[] buffer = new byte[size];
        int count = 0;
        while (count < size) {
            checkCancelled(cancelled);
            int read = source.read(buffer, count, Math.min(64 * 1024, size - count));
            if (read == -1) break;
            if (read == 0) throw new IOException("Audio stream made no progress");
            count += read;
        }
        return count == size ? buffer : Arrays.copyOf(buffer, count);
    }
    static void checkCancelled(Cancellation cancelled) throws IOException {
        if (cancelled.isCancelled() || Thread.currentThread().isInterrupted())
            throw new IOException("Audio transfer cancelled");
    }
}
