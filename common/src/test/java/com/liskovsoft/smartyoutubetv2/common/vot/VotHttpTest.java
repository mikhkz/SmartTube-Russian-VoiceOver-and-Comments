package com.liskovsoft.smartyoutubetv2.common.vot;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class VotHttpTest {
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final FutureTask<byte[]> received;
        Fixture(int status, byte[] response) throws IOException {
            received = new FutureTask<>(()->{
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);InputStream in=socket.getInputStream();
                    String line=readLine(in);assertTrue(line.startsWith("PUT /audio "));
                    int size=0; boolean protobuf=false;
                    while(!(line=readLine(in)).isEmpty()) {
                        if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.split(":",2)[1].trim());
                        if(line.equalsIgnoreCase("Content-Type: application/x-protobuf"))protobuf=true;
                    }
                    assertTrue(protobuf);byte[] bytes=new byte[size];new DataInputStream(in).readFully(bytes);
                    OutputStream out=socket.getOutputStream();
                    out.write(("HTTP/1.1 "+status+" Response\r\nContent-Type: application/x-protobuf\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(response);out.flush();return bytes;
                }
            });
            Thread worker=new Thread(received,"vot-http-test");worker.setDaemon(true);worker.start();
        }
        String url(){return "http://127.0.0.1:"+server.getLocalPort();}
        private static String readLine(InputStream in)throws IOException {
            ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
            while((b=in.read())!=-1&&b!='\n')if(b!='\r')out.write(b);
            if(b==-1)throw new EOFException();return new String(out.toByteArray(),StandardCharsets.US_ASCII);
        }
        public void close()throws IOException {server.close();}
    }
    @Test public void binaryUploadReachesWireUnmodified()throws Exception {
        byte[] audio={0,(byte)255,42,0};
        byte[] request=VotProtobuf.encodeAudioChunkRequest("url","id","file",audio,0,1);
        try(Fixture f=new Fixture(200,new byte[]{8,2})) {
            byte[] response=new VotHttp(f.url()).putProtobuf("/audio",request,Collections.emptyMap(),5);
            assertEquals(2,VotProtobuf.decodeAudioUploadStatus(response));
            assertArrayEquals(request,f.received.get(5,TimeUnit.SECONDS));
        }
    }
    @Test public void httpFailureKeepsTypedReasonAndDoesNotAcknowledgeUpload()throws Exception {
        VotWireWriter w=new VotWireWriter();w.writeString(9,"Доступна обычная озвучка");
        try(Fixture f=new Fixture(400,w.toByteArray())) {
            try {new VotHttp(f.url()).putProtobuf("/audio",new byte[]{1},Collections.emptyMap(),5);fail();}
            catch(VotHttp.HttpFailure error){
                assertEquals(400,error.statusCode);
                assertEquals("Доступна обычная озвучка",VotProtobuf.decodeTranslationResponse(error.responseBody).message);
            }
            assertArrayEquals(new byte[]{1},f.received.get(5,TimeUnit.SECONDS));
        }
    }
    @Test public void cancellationIsScopedToOneHttpClient()throws Exception {
        try(Fixture f=new Fixture(200,new byte[]{8,2})) {
            VotHttp cancelled=new VotHttp(f.url());cancelled.cancel();
            try {cancelled.putProtobuf("/audio",new byte[]{1},Collections.emptyMap(),5);fail();}catch(IOException expected){}
            assertEquals(2,VotProtobuf.decodeAudioUploadStatus(new VotHttp(f.url())
                    .putProtobuf("/audio",new byte[]{2},Collections.emptyMap(),5)));
            assertArrayEquals(new byte[]{2},f.received.get(5,TimeUnit.SECONDS));
        }
    }
}
