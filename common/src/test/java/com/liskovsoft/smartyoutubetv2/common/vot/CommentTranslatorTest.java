package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CommentTranslatorTest {

    private interface Handler {
        HttpResponse handle(String requestLine) throws IOException;
    }

    private static final class HttpResponse {
        final int status;
        final String body;

        HttpResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class LocalHttpServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        final List<String> requestLines = new CopyOnWriteArrayList<>();
        final AtomicInteger requestCount = new AtomicInteger();
        private volatile boolean closed;

        LocalHttpServer(Handler handler) throws IOException {
            Thread acceptThread = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket socket = server.accept();
                        new Thread(() -> handleSocket(socket, handler), "test-http-worker").start();
                    } catch (IOException e) {
                        if (!closed) {
                            break;
                        }
                    }
                }
            }, "test-http-acceptor");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private void handleSocket(Socket socket, Handler handler) {
            try (Socket s = socket) {
                s.setSoTimeout(5000);
                InputStream in = s.getInputStream();
                String reqLine = readLine(in);
                requestLines.add(reqLine);
                requestCount.incrementAndGet();
                while (!readLine(in).isEmpty()) {
                    // consume headers
                }
                HttpResponse resp = handler.handle(reqLine);
                byte[] bodyBytes = resp.body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = s.getOutputStream();
                String headers = "HTTP/1.1 " + resp.status + " OK\r\n"
                        + "Content-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + bodyBytes.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(headers.getBytes(StandardCharsets.US_ASCII));
                out.write(bodyBytes);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                if (b != '\r') {
                    out.write(b);
                }
            }
            if (b == -1 && out.size() == 0) {
                throw new EOFException();
            }
            return new String(out.toByteArray(), StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
        }
    }

    @Test
    public void buildTranslatePaUrlMatchesFirefoxExtensionRequestFormat() {
        HttpUrl url = CommentTranslator.buildTranslatePaUrl(
                CommentTranslator.DEFAULT_TRANSLATE_PA_URL,
                "Hello world!\nAt 02:15 great moment.",
                "ru"
        );
        assertNotNull(url);
        assertEquals("translate-pa.googleapis.com", url.host());
        assertEquals("/v1/translate", url.encodedPath());
        assertEquals("gtx", url.queryParameter("params.client"));
        assertEquals("auto", url.queryParameter("query.source_language"));
        assertEquals(CommentTranslator.TRANSLATE_PA_API_KEY, url.queryParameter("key"));
        assertEquals(Arrays.asList("TRANSLATION", "SENTENCE_SPLITS", "BILINGUAL_DICTIONARY_FULL"),
                url.queryParameterValues("data_types"));
        assertEquals("ru", url.queryParameter("query.target_language"));
        assertEquals("ru", url.queryParameter("query.display_language"));
        assertEquals("Hello world!\nAt 02:15 great moment.", url.queryParameter("query.text"));
    }

    @Test
    public void needsTranslationSkipsRussianAndNonLetterCommentsAndDetectsForeignOnes() {
        assertFalse(CommentTranslator.needsTranslation(null, "ru"));
        assertFalse(CommentTranslator.needsTranslation("", "ru"));
        assertFalse(CommentTranslator.needsTranslation("   \n\t  ", "ru"));
        assertFalse(CommentTranslator.needsTranslation("02:15 10/10 😂😂😂🔥🔥", "ru"));
        assertFalse(CommentTranslator.needsTranslation("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "ru"));
        assertFalse(CommentTranslator.needsTranslation("Отличное видео, большое спасибо автору!", "ru"));
        assertFalse(CommentTranslator.needsTranslation("@AlexanderTheGreat Да, полностью согласен!", "ru"));
        assertFalse(CommentTranslator.needsTranslation("На SmartTube всё работает идеально 👍", "ru"));
        assertFalse(CommentTranslator.needsTranslation("Благодаря автору видео получилось супер!", "ru"));

        assertTrue(CommentTranslator.needsTranslation("This video is amazing! Thanks for sharing.", "ru"));
        assertTrue(CommentTranslator.needsTranslation("@ИванИванов Thank you very much for this explanation!", "ru"));
        assertTrue(CommentTranslator.needsTranslation("¡Excelente explicación, muchas gracias!", "ru"));
        assertTrue(CommentTranslator.needsTranslation("Дякую за чудове відео, дуже корисно!", "ru"));
        assertTrue(CommentTranslator.needsTranslation("Дякую за чудовий ролик, дуже круто!", "ru"));
        assertTrue(CommentTranslator.needsTranslation("Благодаря много за това страхотно видео!", "ru"));
        assertTrue(CommentTranslator.needsTranslation("Хвала пуно на овом видеу, свака част!", "ru"));
        assertTrue(CommentTranslator.needsTranslation("Дзякуй за відэа, вельмі цікава!", "ru"));
        assertTrue(CommentTranslator.needsTranslation("素晴らしい動画ですね！ありがとうございます。", "ru"));
    }

    @Test
    public void parseTranslatePaResponseConcatenatesSentencesAndFallsBackToTranslationField() {
        String multiSentenceJson = "{\n"
                + "  \"translation\": \"Первое предложение. Второе предложение.\",\n"
                + "  \"sentences\": [\n"
                + "    {\"trans\": \"Первое предложение. \", \"orig\": \"First sentence. \"},\n"
                + "    {\"trans\": \"Второе предложение.\", \"orig\": \"Second sentence.\"}\n"
                + "  ],\n"
                + "  \"sourceLanguage\": \"en\"\n"
                + "}";
        CommentTranslator.ParsedTranslation parsed = CommentTranslator.parseTranslatePaResponse(multiSentenceJson);
        assertNotNull(parsed);
        assertEquals("Первое предложение. Второе предложение.", parsed.translatedText);
        assertEquals("en", parsed.sourceLanguage);

        String topLevelOnlyJson = "{\"translation\": \"Только верхний уровень\", \"detectedLanguages\": {\"srclangs\": [\"es\"]}}";
        CommentTranslator.ParsedTranslation fallbackParsed = CommentTranslator.parseTranslatePaResponse(topLevelOnlyJson);
        assertNotNull(fallbackParsed);
        assertEquals("Только верхний уровень", fallbackParsed.translatedText);
        assertEquals("es", fallbackParsed.sourceLanguage);

        assertNull(CommentTranslator.parseTranslatePaResponse(""));
        assertNull(CommentTranslator.parseTranslatePaResponse("not-json"));
        assertNull(CommentTranslator.parseTranslatePaResponse("{}"));
    }

    @Test
    public void translateGroupTranslatesForeignCommentsSkipsRussianUsesCacheAndPreservesMetadata() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(reqLine -> {
            if (reqLine.contains("Hello")) {
                return new HttpResponse(200,
                        "{\"sentences\":[{\"trans\":\"Привет, мир!\"}],\"sourceLanguage\":\"en\"}");
            }
            if (reqLine.contains("Gracias")) {
                return new HttpResponse(200,
                        "{\"sentences\":[{\"trans\":\"Спасибо большое!\"}],\"sourceLanguage\":\"es\"}");
            }
            return new HttpResponse(500, "{}");
        })) {
            CommentTranslator translator = new CommentTranslator(server.url("/v1/translate"), null, "ru");

            CommentItem english1 = makeComment("c1", "Hello world!", "Alice", "nested-1", true, "42", "3 replies");
            CommentItem russian = makeComment("c2", "Уже на русском языке, переводить не нужно", "Борис", null, false, "10", null);
            CommentItem emojiOnly = makeComment("c3", "03:45 🔥🔥🔥", "Charlie", null, false, null, null);
            CommentItem spanish = makeComment("c4", "¡Gracias amigo!", "Diego", "nested-4", false, "7", "1 reply");
            CommentItem englishDuplicate = makeComment("c5", "Hello world!", "Eve", null, false, "1", null);

            CommentGroup group = new CommentGroup() {
                @Override
                public List<CommentItem> getComments() {
                    return Arrays.asList(english1, russian, emojiOnly, spanish, englishDuplicate);
                }

                @Override
                public String getNextCommentsKey() {
                    return "next-page-token-123";
                }
            };

            CommentGroup translatedGroup = translator.translateGroup(group);
            assertNotNull(translatedGroup);
            assertEquals("next-page-token-123", translatedGroup.getNextCommentsKey());

            List<CommentItem> items = translatedGroup.getComments();
            assertEquals(5, items.size());

            assertEquals("Привет, мир!", items.get(0).getMessage());
            assertEquals("c1", items.get(0).getId());
            assertEquals("Alice", items.get(0).getAuthorName());
            assertEquals("nested-1", items.get(0).getNestedCommentsKey());
            assertTrue(items.get(0).isLiked());
            assertEquals("42", items.get(0).getLikeCount());
            assertEquals("3 replies", items.get(0).getReplyCount());
            assertFalse(items.get(0).isEmpty());

            assertEquals("Уже на русском языке, переводить не нужно", items.get(1).getMessage());
            assertEquals("03:45 🔥🔥🔥", items.get(2).getMessage());
            assertEquals("Спасибо большое!", items.get(3).getMessage());
            assertEquals("Привет, мир!", items.get(4).getMessage());

            // Duplicate comment within the same batch is deduplicated: exactly 2 network calls total
            int countAfterFirstBatch = server.requestCount.get();
            assertEquals(2, countAfterFirstBatch);

            // Second call with same group should hit cache and make 0 additional HTTP requests
            CommentGroup cachedGroup = translator.translateGroup(group);
            assertEquals("Привет, мир!", cachedGroup.getComments().get(0).getMessage());
            assertEquals("Спасибо большое!", cachedGroup.getComments().get(3).getMessage());
            assertEquals(countAfterFirstBatch, server.requestCount.get());
        }
    }

    @Test
    public void fallbackEndpointUsedWhenPrimaryFailsAndOriginalKeptWhenBothFail() throws Exception {
        try (LocalHttpServer primary = new LocalHttpServer(req -> new HttpResponse(503, "Service Unavailable"));
             LocalHttpServer fallback = new LocalHttpServer(req -> new HttpResponse(200,
                     "[[\"Переведено через clients5\",\"en\"]]"))) {

            CommentTranslator translatorClients5 = new CommentTranslator(
                    primary.url("/v1/translate"),
                    fallback.url("/translate_a/t"),
                    "ru"
            );
            assertEquals("Переведено через clients5",
                    translatorClients5.translateText("Translated via clients5"));
            assertEquals(1, primary.requestCount.get());
            assertEquals(1, fallback.requestCount.get());
        }

        try (LocalHttpServer primary = new LocalHttpServer(req -> new HttpResponse(503, "Service Unavailable"));
             LocalHttpServer fallback = new LocalHttpServer(req -> new HttpResponse(200,
                     "[[[\"Переведено через резервный канал\",\"Translated via fallback\",null,null,10]],null,\"en\"]"))) {

            CommentTranslator translatorWithFallback = new CommentTranslator(
                    primary.url("/v1/translate"),
                    fallback.url("/translate_a/single"),
                    "ru"
            );
            assertEquals("Переведено через резервный канал",
                    translatorWithFallback.translateText("Translated via fallback"));
            assertEquals(1, primary.requestCount.get());
            assertEquals(1, fallback.requestCount.get());
        }

        try (LocalHttpServer failingPrimary = new LocalHttpServer(req -> new HttpResponse(500, ""));
             LocalHttpServer failingFallback = new LocalHttpServer(req -> new HttpResponse(500, ""))) {

            CommentTranslator translatorAllFailing = new CommentTranslator(
                    failingPrimary.url("/v1/translate"),
                    failingFallback.url("/translate_a/single"),
                    "ru"
            );
            assertEquals("Original stays intact on failure",
                    translatorAllFailing.translateText("Original stays intact on failure"));
        }
    }

    @Test
    public void longCommentsAreSplitOnBoundariesAndReassembledWithSpacesAndNewlines() throws Exception {
        StringBuilder longParagraph = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longParagraph.append("This is sentence number ").append(i).append(" in a very long comment. ");
        }
        String input = longParagraph.toString();
        assertTrue(input.length() > CommentTranslator.MAX_CHUNK_CHARS);

        List<String> chunks = CommentTranslator.splitIntoChunks(input, CommentTranslator.MAX_CHUNK_CHARS);
        assertTrue(chunks.size() >= 2);
        StringBuilder rejoined = new StringBuilder();
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= CommentTranslator.MAX_CHUNK_CHARS);
            rejoined.append(chunk);
        }
        assertEquals(input, rejoined.toString());

        // Server simulates Google Translate trimming trailing spaces from each chunk response
        try (LocalHttpServer server = new LocalHttpServer(req ->
                new HttpResponse(200, "{\"sentences\":[{\"trans\":\"[ЧАСТЬ]\"}],\"sourceLanguage\":\"en\"}"))) {
            CommentTranslator translator = new CommentTranslator(server.url("/v1/translate"), null, "ru");
            String translated = translator.translateText(input);
            assertNotNull(translated);
            assertTrue(server.requestCount.get() >= 2);
            assertTrue("Trailing space between sentence-split chunks must be preserved",
                    translated.contains("[ЧАСТЬ] [ЧАСТЬ]"));
        }
    }

    @Test
    public void edgeCasesJsonEscapesNullGroupsAndNewlinePreservation() {
        assertNull(CommentTranslator.instance().translateGroup(null));

        CommentGroup emptyGroup = new CommentGroup() {
            @Override
            public List<CommentItem> getComments() {
                return Collections.emptyList();
            }

            @Override
            public String getNextCommentsKey() {
                return null;
            }
        };
        assertEquals(emptyGroup, CommentTranslator.instance().translateGroup(emptyGroup));

        String escapedJson = "{\"sentences\":[{\"trans\":\"Кавычки \\\"тест\\\" и строка\\nвторая и \\u041f\\u0440\\u0438\\u0432\\u0435\\u0442\"}],\"sourceLanguage\":\"en\"}";
        CommentTranslator.ParsedTranslation parsed = CommentTranslator.parseTranslatePaResponse(escapedJson);
        assertNotNull(parsed);
        assertEquals("Кавычки \"тест\" и строка\nвторая и Привет", parsed.translatedText);

        assertEquals("\nПривет, мир! ", CommentTranslator.preserveBoundaryWhitespace("\nHello world! ", "Привет, мир!"));
    }

    private static CommentItem makeComment(
            String id, String message, String author, String nestedKey,
            boolean liked, String likeCount, String replyCount) {
        return new CommentItem() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getMessage() {
                return message;
            }

            @Override
            public String getAuthorName() {
                return author;
            }

            @Override
            public String getAuthorPhoto() {
                return "https://example.com/photo.jpg";
            }

            @Override
            public String getPublishedDate() {
                return "1 day ago";
            }

            @Override
            public String getNestedCommentsKey() {
                return nestedKey;
            }

            @Override
            public boolean isLiked() {
                return liked;
            }

            @Override
            public String getLikeCount() {
                return likeCount;
            }

            @Override
            public String getReplyCount() {
                return replyCount;
            }

            @Override
            public boolean isEmpty() {
                return replyCount == null || nestedKey == null;
            }
        };
    }
}
