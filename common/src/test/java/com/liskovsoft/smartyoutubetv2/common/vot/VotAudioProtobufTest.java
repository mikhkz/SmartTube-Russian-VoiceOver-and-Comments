package com.liskovsoft.smartyoutubetv2.common.vot;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;
public class VotAudioProtobufTest {
    private Map<Integer,Object> fields(byte[] bytes) {
        Map<Integer,Object> result=new HashMap<>(); int[] p={0};
        while(p[0]<bytes.length) {
            int tag=varint(bytes,p), wire=tag&7, field=tag>>>3;
            if(wire==0) result.put(field,varint(bytes,p));
            else if(wire==2) {int size=varint(bytes,p);result.put(field,Arrays.copyOfRange(bytes,p[0],p[0]+size));p[0]+=size;}
            else throw new AssertionError("wire="+wire);
        }
        return result;
    }
    private int varint(byte[] b,int[] p) {int result=0,shift=0,x;do{x=b[p[0]++]&255;result|=(x&127)<<shift;shift+=7;}while((x&128)!=0);return result;}
    @Test public void actualAudioUsesPartialAudioInfoFieldAndOpaqueBytes() {
        byte[] audio={0,1,(byte)255,42};
        Map<Integer,Object> request=fields(VotProtobuf.encodeAudioChunkRequest("url","translation","file",audio,0,0));
        assertFalse(request.containsKey(6)); assertEquals("translation",new String((byte[])request.get(1),StandardCharsets.UTF_8));
        Map<Integer,Object> partial=fields((byte[])request.get(4));
        assertEquals(1,partial.get(4)); assertFalse(partial.containsKey(2));
        Map<Integer,Object> buffer=fields((byte[])partial.get(1)); assertFalse(buffer.containsKey(1));
        assertArrayEquals(audio,(byte[])buffer.get(2));
    }
    @Test public void finalChunkIncludesLastIndexAndTotal() {
        Map<Integer,Object> partial=fields((byte[])fields(VotProtobuf.encodeAudioChunkRequest("url","id","file",new byte[]{1},2,3)).get(4));
        assertEquals(3,partial.get(2)); assertEquals(2,fields((byte[])partial.get(1)).get(1));
        assertEquals(1,VotProtobuf.decodeAudioUploadStatus(new byte[]{8,1}));
        assertEquals(2,VotProtobuf.decodeAudioUploadStatus(new byte[]{8,2}));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidTerminalTotal() {
        VotProtobuf.encodeAudioChunkRequest("url","id","file",new byte[]{1},2,2);
    }
    @Test public void preservesCyrillicErrorForStandardVoiceFallback() {
        VotWireWriter w = new VotWireWriter(); w.writeString(9,"Доступна обычная озвучка");
        assertEquals("Доступна обычная озвучка",VotProtobuf.decodeTranslationResponse(w.toByteArray()).message);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsTruncatedApiResponse() {
        VotProtobuf.decodeTranslationResponse(new byte[]{10,5,65});
    }
}
