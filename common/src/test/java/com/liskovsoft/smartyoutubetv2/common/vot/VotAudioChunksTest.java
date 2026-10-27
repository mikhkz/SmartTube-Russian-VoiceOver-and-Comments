package com.liskovsoft.smartyoutubetv2.common.vot;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
public class VotAudioChunksTest {
    @Test public void terminalChunkUsesTotalAndConservesBytes() throws Exception {
        List<Integer> indices = new ArrayList<>(), totals = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = {0,1,2,3,4,5,6};
        VotAudioChunks.upload(new ByteArrayInputStream(data), 3, () -> false, (bytes, index, total) -> {
            out.write(bytes); indices.add(index); totals.add(total);
        });
        assertArrayEquals(data, out.toByteArray());
        assertEquals(Arrays.asList(0,1,2), indices); assertEquals(Arrays.asList(0,0,3), totals);
    }
    @Test public void exactMultipleAndSingleChunkHaveNoEmptyTerminal() throws Exception {
        List<Integer> sizes = new ArrayList<>(), totals = new ArrayList<>();
        VotAudioChunks.upload(new ByteArrayInputStream(new byte[6]), 3, () -> false, (b,i,n) -> {sizes.add(b.length); totals.add(n);});
        assertEquals(Arrays.asList(3,3), sizes); assertEquals(Arrays.asList(0,2), totals);
        VotAudioChunks.upload(new ByteArrayInputStream(new byte[1]), 3, () -> false, (b,i,n) -> {assertEquals(1,n);assertEquals(1,b.length);});
    }
    @Test(expected=IOException.class) public void emptyAudioIsRejected() throws Exception {
        VotAudioChunks.upload(new ByteArrayInputStream(new byte[0]), 3, () -> false, (b,i,n) -> fail());
    }
    @Test public void brokenStreamNeverMarksLastChunk() throws Exception {
        List<Integer> totals = new ArrayList<>();
        InputStream bad = new InputStream() {
            int bytes;
            public int read() throws IOException { if(bytes++ < 8) return 42; throw new IOException("broken"); }
        };
        try { VotAudioChunks.upload(bad, 3, () -> false, (b,i,n) -> totals.add(n)); fail(); }
        catch(IOException expected) { assertFalse(totals.contains(1)); assertFalse(totals.contains(2)); assertFalse(totals.contains(3)); }
    }
    @Test public void cancellationStopsBeforeAnotherChunk() throws Exception {
        boolean[] cancelled = {false}; List<Integer> sent = new ArrayList<>();
        try {
            VotAudioChunks.upload(new ByteArrayInputStream(new byte[9]), 3, () -> cancelled[0], (b,i,n) -> {
                sent.add(i); cancelled[0] = true;
            }); fail();
        } catch(IOException expected) { assertEquals(Collections.singletonList(0), sent); }
    }
}
