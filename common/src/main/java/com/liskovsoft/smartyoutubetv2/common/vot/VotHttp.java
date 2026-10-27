package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VotHttp {
    private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");
    private static final MediaType JSON = MediaType.parse("application/json");
    private final String mBaseUrl;

    public VotHttp() { this("https://" + VotConfig.HOST); }
    VotHttp(String baseUrl) { mBaseUrl = baseUrl; }

    private final OkHttpClient mClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    public static final class HttpFailure extends IOException {
        public final int statusCode;
        final byte[] responseBody;
        HttpFailure(int statusCode, byte[] responseBody) {
            super("Yandex HTTP " + statusCode);
            this.statusCode = statusCode; this.responseBody = responseBody;
        }
    }

    private volatile Call mActiveCall;
    private volatile boolean mCancelled;

    public void cancel() {
        mCancelled = true;
        Call active = mActiveCall;
        if (active != null) active.cancel();
    }

    public byte[] postProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
        return execute(path, "POST", RequestBody.create(PROTOBUF, body), headers, 0);
    }

    public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
        return putProtobuf(path, body, headers, 0);
    }

    public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers, int timeoutSeconds) throws IOException {
        return execute(path, "PUT", RequestBody.create(PROTOBUF, body), headers, timeoutSeconds);
    }

    public byte[] putJson(String path, String json, Map<String, String> headers) throws IOException {
        return execute(path, "PUT", RequestBody.create(JSON, json.getBytes(StandardCharsets.UTF_8)), headers, 0);
    }

    @Nullable
    private byte[] execute(String path, String method, RequestBody requestBody, Map<String, String> headers, int timeoutSeconds) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(mBaseUrl + path)
                .method(method, requestBody)
                .header("Accept", "application/x-protobuf")
                .header("Accept-Language", "en")
                .header("Content-Type", method.equals("PUT") && requestBody.contentType() == JSON
                        ? "application/json" : "application/x-protobuf")
                .header("User-Agent", VotConfig.USER_AGENT)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache");

        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
        }

        Call call = mClient.newCall(builder.build());
        if (timeoutSeconds > 0) call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS);
        mActiveCall = call;
        if (mCancelled) call.cancel();
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                byte[] errorBody = response.body() != null ? response.peekBody(64 * 1024).bytes() : new byte[0];
                throw new HttpFailure(response.code(), errorBody);
            }
            if (response.body() == null) {
                return null;
            }
            return response.body().bytes();
        } finally { mActiveCall = null; }
    }
}
