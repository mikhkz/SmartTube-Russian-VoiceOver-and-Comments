package com.liskovsoft.smartyoutubetv2.common.vot;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.HashMap;
import java.util.Map;

public final class VotProtobuf {
    private VotProtobuf() {
    }

    public static byte[] encodeTranslationRequest(
            String url,
            double duration,
            String requestLang,
            String responseLang,
            boolean firstRequest,
            boolean useLivelyVoice,
            String videoTitle
    ) {
        VotWireWriter w = new VotWireWriter();
        w.writeString(3, url);
        w.writeBool(5, firstRequest);
        w.writeDouble(6, duration);
        w.writeInt32(7, 1);
        w.writeString(8, requestLang);
        w.writeBool(9, false);
        w.writeInt32(10, 0);
        w.writeString(14, responseLang);
        w.writeInt32(15, 1);
        w.writeInt32(16, 2);
        w.writeBool(18, useLivelyVoice);
        w.writeString(19, videoTitle);
        return w.toByteArray();
    }

    public static byte[] encodeSessionRequest(String uuid, String module) {
        VotWireWriter w = new VotWireWriter();
        w.writeString(1, uuid);
        w.writeString(2, module);
        return w.toByteArray();
    }

    public static byte[] encodeTranslationAudioRequest(String url, String translationId, String fileId) {
        VotWireWriter audioInfo = new VotWireWriter();
        audioInfo.writeString(1, fileId);
        audioInfo.writeBytes(2, new byte[0]);

        VotWireWriter w = new VotWireWriter();
        w.writeString(1, translationId);
        w.writeString(2, url);
        w.writeEmbedded(6, audioInfo.toByteArray());
        return w.toByteArray();
    }

    public static byte[] encodeAudioChunkRequest(String url, String translationId, String fileId,
                                                  byte[] audio, int index, int total) {
        if (audio == null || audio.length == 0 || index < 0 || total < 0
                || total != 0 && total != index + 1) throw new IllegalArgumentException("Invalid audio chunk");
        VotWireWriter buffer = new VotWireWriter();
        buffer.writeInt32(1, index); buffer.writeBytes(2, audio);
        VotWireWriter chunks = new VotWireWriter();
        chunks.writeEmbedded(1, buffer.toByteArray()); chunks.writeInt32(2, total);
        chunks.writeString(3, fileId); chunks.writeInt32(4, 1);
        VotWireWriter request = new VotWireWriter();
        request.writeString(1, translationId); request.writeString(2, url);
        request.writeEmbedded(4, chunks.toByteArray());
        return request.toByteArray();
    }

    public static int decodeAudioUploadStatus(byte[] data) {
        Object status = parseFields(data).get(1);
        return status instanceof Integer ? (Integer) status : 0;
    }

    public static VotTranslationResponse decodeTranslationResponse(byte[] data) {
        Map<Integer, Object> fields = parseFields(data);
        VotTranslationResponse r = new VotTranslationResponse();
        r.url = (String) fields.get(1);
        Object status = fields.get(4);
        r.status = status instanceof Integer ? (Integer) status : 0;
        Object remaining = fields.get(5);
        r.remainingTimeSec = remaining instanceof Integer ? (Integer) remaining : 0;
        r.translationId = (String) fields.get(7);
        r.message = (String) fields.get(9);
        Object lively = fields.get(10);
        r.isLivelyVoice = lively instanceof Integer && (Integer) lively != 0;
        return r;
    }

    public static VotSession decodeSessionResponse(byte[] data) {
        Map<Integer, Object> fields = parseFields(data);
        VotSession s = new VotSession();
        s.secretKey = (String) fields.get(1);
        Object expires = fields.get(2);
        s.expiresSec = expires instanceof Integer ? (Integer) expires : 3600;
        return s;
    }

    private static Map<Integer, Object> parseFields(byte[] data) {
        Map<Integer, Object> result = new HashMap<>();
        int pos = 0;
        while (pos < data.length) {
            int[] tag = readVarint(data, pos);
            if (tag[0] <= 0) throw new IllegalArgumentException("Invalid protobuf tag");
            pos = tag[1];
            int fieldNumber = tag[0] >>> 3;
            int wireType = tag[0] & 0x7;

            switch (wireType) {
                case 0: {
                    int[] val = readVarint(data, pos);
                    if (val[0] < 0) throw new IllegalArgumentException("Invalid protobuf varint");
                    pos = val[1];
                    result.put(fieldNumber, val[0]);
                    break;
                }
                case 1: {
                    if (data.length - pos < 8) throw new IllegalArgumentException("Truncated protobuf double");
                    pos += 8;
                    break;
                }
                case 2: {
                    int[] len = readVarint(data, pos);
                    pos = len[1];
                    int length = len[0];
                    if (length < 0 || length > data.length - pos) throw new IllegalArgumentException("Truncated protobuf bytes");
                    byte[] chunk = new byte[length];
                    System.arraycopy(data, pos, chunk, 0, length);
                    pos += length;
                    if (isUtf8String(chunk)) {
                        result.put(fieldNumber, new String(chunk, StandardCharsets.UTF_8));
                    }
                    break;
                }
                case 5: {
                    if (data.length - pos < 4) throw new IllegalArgumentException("Truncated protobuf fixed32");
                    pos += 4;
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unsupported protobuf wire type");
            }
        }
        return result;
    }

    private static boolean isUtf8String(byte[] chunk) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(chunk));
            return true;
        } catch (CharacterCodingException e) { return false; }
    }

    private static int[] readVarint(byte[] data, int pos) {
        int result = 0;
        int shift = 0;
        while (pos < data.length) {
            int b = data[pos++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{result, pos};
            }
            shift += 7;
            if (shift > 35) {
                return new int[]{-1, pos};
            }
        }
        return new int[]{-1, pos};
    }
}
