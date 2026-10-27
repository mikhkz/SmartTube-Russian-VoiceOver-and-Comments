package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Port of the Firefox "YouTube Comment Translate" extension (BlanCow/YouTube-Comment-Translate)
 * for SmartTube comments. Uses Google Translate endpoints to translate
 * foreign YouTube comments and replies into Russian automatically.
 */
public class CommentTranslator {
    public static final String DEFAULT_TRANSLATE_PA_URL = "https://translate-pa.googleapis.com/v1/translate";
    public static final String DEFAULT_FALLBACK_URL = "https://clients5.google.com/translate_a/t";
    public static final String GOOGLE_COM_FALLBACK_URL = "https://translate.google.com/translate_a/single";
    public static final String LEGACY_FALLBACK_URL = "https://translate.googleapis.com/translate_a/single";
    public static final String TRANSLATE_PA_API_KEY = "AIzaSyDLEeFI5OtFBwYBIoK_jj5m32rZK5CkCXA";
    public static final String DEFAULT_TARGET_LANGUAGE = "ru";

    static final int MAX_CHUNK_CHARS = 1400;
    static final int MAX_MULTI_BATCH_ITEMS = 8;
    static final int MAX_MULTI_BATCH_CHARS = 1600;
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final int PARALLEL_THREADS = 10;
    private static final long BATCH_TIMEOUT_SECONDS = 8;
    private static final long ENDPOINT_COOLDOWN_MS = 180_000L;

    private static final Pattern URL_AND_MENTION_PATTERN = Pattern.compile(
            "(?:https?://\\S+|www\\.\\S+|@[\\p{L}\\p{N}_.-]+)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches common Ukrainian, Belarusian, Bulgarian, Serbian, and Macedonian words/orthography
     * that use only Cyrillic letters within U+0410..U+044F (without і/ї/є/ґ/ў/ђ/ј/љ/њ/ћ/џ):
     * - Bulgarian hard sign 'ъ' followed by a non-iotated Cyrillic letter or word boundary
     * - Distinct non-Russian vocabulary in Ukrainian/Bulgarian/Serbian comments
     */
    private static final Pattern NON_RUSSIAN_CYRILLIC_HINT_PATTERN = Pattern.compile(
            "(?:(?<=[а-яёіїєґўђјљњћџ])ъ(?=[абвгдежзийклмнопрстуфхцчшщъыьэ]|\\b)"
                    + "|(?<![\\p{L}])(?:"
                    + "дякую|дуже|чудово|чудовий|чудова|гарно|гарний|гарна|корисно|корисний|корисна"
                    + "|треба|зараз|якщо|тобто|лише|також|хлопц\\p{L}*|шановн\\p{L}*|багато|завжди|нарешт\\p{L}*|взагал\\p{L}*|найкращ\\p{L}*"
                    + "|благодаря\\s+(?:за|ти|ви|много)|това|страхотно|страхотен|страхотна|всички|всичко|винаги|защо|защото|какво|когато|няма|харесва"
                    + "|хвала|пуно|овом|овог|свака\\s+част|веома|благодарам|многу"
                    + ")(?![\\p{L}]))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final CommentTranslator sInstance = new CommentTranslator();

    private final String mTranslatePaUrl;
    private final String mFallbackUrl;
    private final String mTargetLanguage;
    private final OkHttpClient mClient;
    private final ExecutorService mExecutor;
    private final Map<String, String> mCache;
    private final Map<String, Long> mEndpointCooldownUntil = new ConcurrentHashMap<>();
    private final ThreadLocal<List<Call>> mBatchCalls = new ThreadLocal<>();

    public static CommentTranslator instance() {
        return sInstance;
    }

    public CommentTranslator() {
        this(DEFAULT_TRANSLATE_PA_URL, DEFAULT_FALLBACK_URL, DEFAULT_TARGET_LANGUAGE);
    }

    CommentTranslator(String translatePaUrl, @Nullable String fallbackUrl, String targetLanguage) {
        mTranslatePaUrl = translatePaUrl;
        mFallbackUrl = fallbackUrl;
        mTargetLanguage = targetLanguage != null && !targetLanguage.trim().isEmpty()
                ? targetLanguage.trim().toLowerCase(Locale.ROOT)
                : DEFAULT_TARGET_LANGUAGE;
        mClient = new OkHttpClient.Builder()
                .dns(hostname -> {
                    List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
                    if (addresses == null || addresses.size() <= 1) {
                        return addresses;
                    }
                    List<InetAddress> ordered = new ArrayList<>(addresses.size());
                    for (InetAddress addr : addresses) {
                        if (addr instanceof Inet4Address) {
                            ordered.add(addr);
                        }
                    }
                    for (InetAddress addr : addresses) {
                        if (!(addr instanceof Inet4Address)) {
                            ordered.add(addr);
                        }
                    }
                    return ordered;
                })
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(3500, TimeUnit.MILLISECONDS)
                .writeTimeout(3500, TimeUnit.MILLISECONDS)
                .callTimeout(4500, TimeUnit.MILLISECONDS)
                .build();
        mExecutor = Executors.newFixedThreadPool(PARALLEL_THREADS, r -> {
            Thread t = new Thread(r, "st-comment-translate");
            t.setDaemon(true);
            return t;
        });
        mCache = Collections.synchronizedMap(new LinkedHashMap<String, String>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        });
    }

    private boolean isDefaultProductionEndpoints() {
        return DEFAULT_TRANSLATE_PA_URL.equals(mTranslatePaUrl) && DEFAULT_FALLBACK_URL.equals(mFallbackUrl);
    }

    public boolean hasUntranslatedComments(@Nullable CommentGroup group) {
        if (group == null) {
            return false;
        }
        List<CommentItem> comments = group.getComments();
        if (comments == null || comments.isEmpty()) {
            return false;
        }
        for (CommentItem item : comments) {
            if (item == null) {
                continue;
            }
            String msg = item.getMessage();
            if (needsTranslation(msg, mTargetLanguage) && getCached(msg) == null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public CommentGroup translateGroup(@Nullable CommentGroup group) {
        if (group == null) {
            return null;
        }
        List<CommentItem> comments = group.getComments();
        if (comments == null || comments.isEmpty()) {
            return group;
        }

        final int size = comments.size();
        final CommentItem[] translatedBuffer = new CommentItem[size];
        final Map<String, List<Integer>> pendingByMessage = new LinkedHashMap<>();

        for (int i = 0; i < size; i++) {
            final CommentItem item = comments.get(i);
            if (item == null) {
                continue;
            }
            translatedBuffer[i] = item;
            final String originalMessage = item.getMessage();
            if (!needsTranslation(originalMessage, mTargetLanguage)) {
                continue;
            }
            String cached = getCached(originalMessage);
            if (cached != null) {
                if (!cached.isEmpty() && !cached.equals(originalMessage)) {
                    translatedBuffer[i] = withTranslatedMessage(item, cached);
                }
                continue;
            }
            List<Integer> indices = pendingByMessage.get(originalMessage);
            if (indices == null) {
                indices = new ArrayList<>();
                pendingByMessage.put(originalMessage, indices);
            }
            indices.add(i);
        }

        if (!pendingByMessage.isEmpty()) {
            final List<Call> activeBatchCalls = new CopyOnWriteArrayList<>();
            final List<Callable<Void>> tasks = new ArrayList<>();

            if (isDefaultProductionEndpoints() && pendingByMessage.size() > 1) {
                List<Map.Entry<String, List<Integer>>> currentBatch = new ArrayList<>();
                int currentChars = 0;
                for (Map.Entry<String, List<Integer>> entry : pendingByMessage.entrySet()) {
                    String msg = entry.getKey();
                    if (msg.length() > MAX_CHUNK_CHARS) {
                        final String longMessage = msg;
                        final List<Integer> indices = entry.getValue();
                        tasks.add(createSingleTranslationTask(comments, translatedBuffer, activeBatchCalls, longMessage, indices));
                        continue;
                    }
                    if (!currentBatch.isEmpty()
                            && (currentBatch.size() >= MAX_MULTI_BATCH_ITEMS || currentChars + msg.length() > MAX_MULTI_BATCH_CHARS)) {
                        final List<Map.Entry<String, List<Integer>>> batchToRun = new ArrayList<>(currentBatch);
                        tasks.add(createMultiBatchTranslationTask(comments, translatedBuffer, activeBatchCalls, batchToRun));
                        currentBatch.clear();
                        currentChars = 0;
                    }
                    currentBatch.add(entry);
                    currentChars += msg.length();
                }
                if (!currentBatch.isEmpty()) {
                    final List<Map.Entry<String, List<Integer>>> batchToRun = new ArrayList<>(currentBatch);
                    tasks.add(createMultiBatchTranslationTask(comments, translatedBuffer, activeBatchCalls, batchToRun));
                }
            } else {
                for (Map.Entry<String, List<Integer>> entry : pendingByMessage.entrySet()) {
                    tasks.add(createSingleTranslationTask(comments, translatedBuffer, activeBatchCalls, entry.getKey(), entry.getValue()));
                }
            }

            try {
                List<Future<Void>> futures = mExecutor.invokeAll(tasks, BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (Future<Void> future : futures) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    } else if (!future.isCancelled()) {
                        try {
                            future.get();
                        } catch (ExecutionException | RuntimeException | Error ignored) {
                            // Individual comment fallback keeps original item
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
                // Keep original items on unexpected executor failure
            } finally {
                for (Call call : activeBatchCalls) {
                    call.cancel();
                }
            }
        }

        final List<CommentItem> translatedItems;
        synchronized (translatedBuffer) {
            translatedItems = new ArrayList<>(Arrays.asList(translatedBuffer));
        }

        final String nextKey = group.getNextCommentsKey();
        return new CommentGroup() {
            @Override
            public List<CommentItem> getComments() {
                return translatedItems;
            }

            @Override
            public String getNextCommentsKey() {
                return nextKey;
            }
        };
    }

    private Callable<Void> createSingleTranslationTask(
            List<CommentItem> comments,
            CommentItem[] translatedBuffer,
            List<Call> activeBatchCalls,
            String originalMessage,
            List<Integer> indices) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            mBatchCalls.set(activeBatchCalls);
            try {
                String translated = translateText(originalMessage);
                applyTranslatedToBuffer(comments, translatedBuffer, originalMessage, translated, indices);
                return null;
            } catch (Throwable ignored) {
                return null;
            } finally {
                mBatchCalls.remove();
            }
        };
    }

    private Callable<Void> createMultiBatchTranslationTask(
            List<CommentItem> comments,
            CommentItem[] translatedBuffer,
            List<Call> activeBatchCalls,
            List<Map.Entry<String, List<Integer>>> batchEntries) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            mBatchCalls.set(activeBatchCalls);
            try {
                if (batchEntries.size() == 1) {
                    Map.Entry<String, List<Integer>> single = batchEntries.get(0);
                    String translated = translateText(single.getKey());
                    applyTranslatedToBuffer(comments, translatedBuffer, single.getKey(), translated, single.getValue());
                    return null;
                }

                List<String> texts = new ArrayList<>(batchEntries.size());
                for (Map.Entry<String, List<Integer>> entry : batchEntries) {
                    texts.add(entry.getKey());
                }

                List<ParsedTranslation> batchResults = requestMultiBatchClients5(DEFAULT_FALLBACK_URL, texts);
                for (int i = 0; i < batchEntries.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    Map.Entry<String, List<Integer>> entry = batchEntries.get(i);
                    String originalMessage = entry.getKey();
                    String translated = null;
                    if (batchResults != null && i < batchResults.size() && batchResults.get(i) != null) {
                        ParsedTranslation pt = batchResults.get(i);
                        if (pt.translatedText != null && !pt.translatedText.trim().isEmpty()) {
                            if (shouldKeepOriginal(originalMessage, pt.sourceLanguage, mTargetLanguage)) {
                                translated = originalMessage;
                            } else {
                                translated = preserveBoundaryWhitespace(originalMessage, pt.translatedText);
                            }
                            putCached(originalMessage, translated);
                        }
                    }
                    if (translated == null) {
                        translated = translateText(originalMessage);
                    }
                    applyTranslatedToBuffer(comments, translatedBuffer, originalMessage, translated, entry.getValue());
                }
                return null;
            } catch (Throwable ignored) {
                return null;
            } finally {
                mBatchCalls.remove();
            }
        };
    }

    private static void applyTranslatedToBuffer(
            List<CommentItem> comments,
            CommentItem[] translatedBuffer,
            String originalMessage,
            @Nullable String translated,
            List<Integer> indices) {
        if (translated != null && !translated.isEmpty() && !translated.equals(originalMessage)) {
            synchronized (translatedBuffer) {
                for (int idx : indices) {
                    CommentItem origItem = comments.get(idx);
                    if (origItem != null) {
                        translatedBuffer[idx] = withTranslatedMessage(origItem, translated);
                    }
                }
            }
        }
    }

    @Nullable
    public String translateText(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        if (!needsTranslation(text, mTargetLanguage)) {
            return text;
        }
        String cached = getCached(text);
        if (cached != null) {
            return cached;
        }

        List<String> chunks = splitIntoChunks(text, MAX_CHUNK_CHARS);
        StringBuilder result = new StringBuilder(text.length());
        boolean anyTranslated = false;

        for (String chunk : chunks) {
            if (Thread.currentThread().isInterrupted()) {
                return text;
            }
            if (!needsTranslation(chunk, mTargetLanguage)) {
                result.append(chunk);
                continue;
            }
            String translatedChunk = translateSingleChunk(chunk);
            if (translatedChunk == null || translatedChunk.isEmpty()) {
                return text;
            }
            if (!translatedChunk.equals(chunk)) {
                anyTranslated = true;
            }
            result.append(translatedChunk);
        }

        String finalResult = anyTranslated ? result.toString() : text;
        putCached(text, finalResult);
        return finalResult;
    }

    @Nullable
    private String translateSingleChunk(String chunk) {
        if (isDefaultProductionEndpoints()) {
            String[] fallbackUrls = new String[] {
                    DEFAULT_FALLBACK_URL,
                    GOOGLE_COM_FALLBACK_URL,
                    LEGACY_FALLBACK_URL
            };
            for (String url : fallbackUrls) {
                if (isEndpointOnCooldown(url)) {
                    continue;
                }
                ParsedTranslation res = requestFallback(url, chunk);
                if (res != null && res.translatedText != null && !res.translatedText.trim().isEmpty()) {
                    clearEndpointCooldown(url);
                    if (shouldKeepOriginal(chunk, res.sourceLanguage, mTargetLanguage)) {
                        return chunk;
                    }
                    return preserveBoundaryWhitespace(chunk, res.translatedText);
                }
                markEndpointFailed(url);
            }
            if (!isEndpointOnCooldown(mTranslatePaUrl)) {
                ParsedTranslation pa = requestTranslatePa(chunk);
                if (pa != null && pa.translatedText != null && !pa.translatedText.trim().isEmpty()) {
                    clearEndpointCooldown(mTranslatePaUrl);
                    if (shouldKeepOriginal(chunk, pa.sourceLanguage, mTargetLanguage)) {
                        return chunk;
                    }
                    return preserveBoundaryWhitespace(chunk, pa.translatedText);
                }
                markEndpointFailed(mTranslatePaUrl);
            }
            // Retry primary fast endpoints even if previously marked on cooldown
            for (String url : new String[] { DEFAULT_FALLBACK_URL, GOOGLE_COM_FALLBACK_URL }) {
                ParsedTranslation res = requestFallback(url, chunk);
                if (res != null && res.translatedText != null && !res.translatedText.trim().isEmpty()) {
                    clearEndpointCooldown(url);
                    if (shouldKeepOriginal(chunk, res.sourceLanguage, mTargetLanguage)) {
                        return chunk;
                    }
                    return preserveBoundaryWhitespace(chunk, res.translatedText);
                }
            }
            return null;
        }

        ParsedTranslation primary = requestTranslatePa(chunk);
        if (primary != null && primary.translatedText != null && !primary.translatedText.trim().isEmpty()) {
            if (shouldKeepOriginal(chunk, primary.sourceLanguage, mTargetLanguage)) {
                return chunk;
            }
            return preserveBoundaryWhitespace(chunk, primary.translatedText);
        }

        if (mFallbackUrl != null && !mFallbackUrl.trim().isEmpty()) {
            ParsedTranslation fallback = requestFallback(mFallbackUrl, chunk);
            if (fallback != null && fallback.translatedText != null && !fallback.translatedText.trim().isEmpty()) {
                if (shouldKeepOriginal(chunk, fallback.sourceLanguage, mTargetLanguage)) {
                    return chunk;
                }
                return preserveBoundaryWhitespace(chunk, fallback.translatedText);
            }
            if (DEFAULT_FALLBACK_URL.equals(mFallbackUrl)) {
                ParsedTranslation googleComFallback = requestFallback(GOOGLE_COM_FALLBACK_URL, chunk);
                if (googleComFallback != null && googleComFallback.translatedText != null && !googleComFallback.translatedText.trim().isEmpty()) {
                    if (shouldKeepOriginal(chunk, googleComFallback.sourceLanguage, mTargetLanguage)) {
                        return chunk;
                    }
                    return preserveBoundaryWhitespace(chunk, googleComFallback.translatedText);
                }
                ParsedTranslation legacyFallback = requestFallback(LEGACY_FALLBACK_URL, chunk);
                if (legacyFallback != null && legacyFallback.translatedText != null && !legacyFallback.translatedText.trim().isEmpty()) {
                    if (shouldKeepOriginal(chunk, legacyFallback.sourceLanguage, mTargetLanguage)) {
                        return chunk;
                    }
                    return preserveBoundaryWhitespace(chunk, legacyFallback.translatedText);
                }
            }
        }

        return null;
    }

    private boolean isEndpointOnCooldown(String endpoint) {
        Long until = mEndpointCooldownUntil.get(endpoint);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            mEndpointCooldownUntil.remove(endpoint);
            return false;
        }
        return true;
    }

    private void markEndpointFailed(String endpoint) {
        mEndpointCooldownUntil.put(endpoint, System.currentTimeMillis() + ENDPOINT_COOLDOWN_MS);
    }

    private void clearEndpointCooldown(String endpoint) {
        mEndpointCooldownUntil.remove(endpoint);
    }

    @Nullable
    private List<ParsedTranslation> requestMultiBatchClients5(String baseUrl, List<String> texts) {
        if (texts == null || texts.isEmpty() || isEndpointOnCooldown(baseUrl)) {
            return null;
        }
        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base == null) {
            return null;
        }
        HttpUrl.Builder builder = base.newBuilder()
                .addQueryParameter("client", "dict-chrome-ex")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", mTargetLanguage)
                .addQueryParameter("dt", "t");
        for (String text : texts) {
            builder.addQueryParameter("q", text);
        }
        String body = executeGet(builder.build());
        List<ParsedTranslation> parsed = parseMultiBatchClients5Response(body, texts.size());
        if (parsed != null) {
            clearEndpointCooldown(baseUrl);
        } else {
            markEndpointFailed(baseUrl);
        }
        return parsed;
    }

    @Nullable
    static List<ParsedTranslation> parseMultiBatchClients5Response(@Nullable String jsonBody, int expectedSize) {
        if (jsonBody == null || jsonBody.trim().isEmpty() || expectedSize <= 0) {
            return null;
        }
        try {
            Object parsed = SimpleJsonParser.parse(jsonBody);
            if (!(parsed instanceof List)) {
                return null;
            }
            List<?> root = (List<?>) parsed;
            if (root.size() != expectedSize) {
                return null;
            }
            List<ParsedTranslation> results = new ArrayList<>(expectedSize);
            for (int i = 0; i < expectedSize; i++) {
                Object item = root.get(i);
                if (item instanceof List) {
                    List<?> pair = (List<?>) item;
                    if (pair.isEmpty() || !(pair.get(0) instanceof String)) {
                        return null;
                    }
                    String trans = (String) pair.get(0);
                    String srcLang = pair.size() > 1 ? asNonEmptyString(pair.get(1)) : null;
                    results.add(new ParsedTranslation(trans, srcLang));
                } else if (item instanceof String) {
                    results.add(new ParsedTranslation((String) item, null));
                } else {
                    return null;
                }
            }
            return results;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private ParsedTranslation requestTranslatePa(String text) {
        HttpUrl url = buildTranslatePaUrl(mTranslatePaUrl, text, mTargetLanguage);
        if (url == null) {
            return null;
        }
        String body = executeGet(url);
        return parseTranslatePaResponse(body);
    }

    @Nullable
    private ParsedTranslation requestFallback(String fallbackBaseUrl, String text) {
        HttpUrl url = buildFallbackUrl(fallbackBaseUrl, text, mTargetLanguage);
        if (url == null) {
            return null;
        }
        String body = executeGet(url);
        return parseFallbackResponse(body);
    }

    @Nullable
    private String executeGet(HttpUrl url) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", VotConfig.USER_AGENT)
                .build();
        Call call = mClient.newCall(request);
        List<Call> batchCalls = mBatchCalls.get();
        if (batchCalls != null) {
            batchCalls.add(call);
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            return response.body().string();
        } catch (IOException e) {
            return null;
        } finally {
            if (batchCalls != null) {
                batchCalls.remove(call);
            }
        }
    }

    @Nullable
    static HttpUrl buildTranslatePaUrl(String baseUrl, String text, String targetLanguage) {
        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base == null) {
            return null;
        }
        return base.newBuilder()
                .addQueryParameter("params.client", "gtx")
                .addQueryParameter("query.source_language", "auto")
                .addQueryParameter("key", TRANSLATE_PA_API_KEY)
                .addQueryParameter("data_types", "TRANSLATION")
                .addQueryParameter("data_types", "SENTENCE_SPLITS")
                .addQueryParameter("data_types", "BILINGUAL_DICTIONARY_FULL")
                .addQueryParameter("query.target_language", targetLanguage)
                .addQueryParameter("query.display_language", targetLanguage)
                .addQueryParameter("query.text", text)
                .build();
    }

    @Nullable
    static HttpUrl buildFallbackUrl(String baseUrl, String text, String targetLanguage) {
        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base == null) {
            return null;
        }
        String client = base.encodedPath().endsWith("/translate_a/t") ? "dict-chrome-ex" : "gtx";
        return base.newBuilder()
                .addQueryParameter("client", client)
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", targetLanguage)
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text)
                .build();
    }

    @Nullable
    static ParsedTranslation parseTranslatePaResponse(@Nullable String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = SimpleJsonParser.parse(jsonBody);
            if (!(parsed instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            String sourceLang = asNonEmptyString(root.get("sourceLanguage"));
            if (sourceLang == null) {
                Object detectedObj = root.get("detectedLanguages");
                if (detectedObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detected = (Map<String, Object>) detectedObj;
                    Object srclangsObj = detected.get("srclangs");
                    if (srclangsObj instanceof List) {
                        List<?> srclangs = (List<?>) srclangsObj;
                        if (!srclangs.isEmpty()) {
                            sourceLang = asNonEmptyString(srclangs.get(0));
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            Object sentencesObj = root.get("sentences");
            if (sentencesObj instanceof List) {
                List<?> sentences = (List<?>) sentencesObj;
                for (Object item : sentences) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> obj = (Map<String, Object>) item;
                        Object trans = obj.get("trans");
                        if (trans instanceof String) {
                            sb.append((String) trans);
                        }
                    }
                }
            }
            if (sb.length() == 0) {
                Object translationObj = root.get("translation");
                if (translationObj instanceof String) {
                    sb.append((String) translationObj);
                }
            }
            if (sb.length() == 0) {
                return null;
            }
            return new ParsedTranslation(sb.toString(), sourceLang);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    static ParsedTranslation parseFallbackResponse(@Nullable String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = SimpleJsonParser.parse(jsonBody);
            if (!(parsed instanceof List)) {
                return null;
            }
            List<?> root = (List<?>) parsed;
            if (root.isEmpty()) {
                return null;
            }
            if (root.get(0) instanceof String) {
                String trans = (String) root.get(0);
                if (trans.isEmpty()) {
                    return null;
                }
                String sourceLang = root.size() > 1 ? asNonEmptyString(root.get(1)) : null;
                return new ParsedTranslation(trans, sourceLang);
            }
            if (!(root.get(0) instanceof List)) {
                return null;
            }
            List<?> first = (List<?>) root.get(0);
            if (first.isEmpty()) {
                return null;
            }
            // clients5.google.com/translate_a/t format: [["translated text", "en"]]
            if (first.get(0) instanceof String) {
                String trans = (String) first.get(0);
                if (trans.isEmpty()) {
                    return null;
                }
                String sourceLang = first.size() > 1
                        ? asNonEmptyString(first.get(1))
                        : (root.size() > 2 ? asNonEmptyString(root.get(2)) : null);
                return new ParsedTranslation(trans, sourceLang);
            }
            // translate.googleapis.com/translate_a/single format: [[["trans", "orig", ...], ...], null, "en"]
            String sourceLang = root.size() > 2 ? asNonEmptyString(root.get(2)) : null;
            StringBuilder sb = new StringBuilder();
            for (Object sentence : first) {
                if (sentence instanceof List) {
                    List<?> parts = (List<?>) sentence;
                    if (!parts.isEmpty() && parts.get(0) instanceof String) {
                        sb.append((String) parts.get(0));
                    }
                }
            }
            if (sb.length() == 0) {
                return null;
            }
            return new ParsedTranslation(sb.toString(), sourceLang);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String asNonEmptyString(@Nullable Object value) {
        if (value instanceof String) {
            String s = ((String) value).trim();
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    public static boolean needsTranslation(@Nullable String text, String targetLanguage) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String stripped = URL_AND_MENTION_PATTERN.matcher(text).replaceAll(" ");
        int totalLetters = 0;
        int russianCyrillicLetters = 0;
        int otherCyrillicLetters = 0;

        for (int i = 0; i < stripped.length(); ) {
            int cp = stripped.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            totalLetters++;
            if (isRussianCyrillicLetter(cp)) {
                russianCyrillicLetters++;
            } else if (isCyrillicCodePoint(cp)) {
                otherCyrillicLetters++;
            }
        }

        if (totalLetters == 0) {
            return false;
        }

        if (targetLanguage == null || targetLanguage.toLowerCase(Locale.ROOT).startsWith("ru")) {
            if (otherCyrillicLetters > 0) {
                return true;
            }
            if (russianCyrillicLetters * 2 < totalLetters) {
                return true;
            }
            return NON_RUSSIAN_CYRILLIC_HINT_PATTERN.matcher(stripped).find();
        }

        return true;
    }

    private static boolean shouldKeepOriginal(String chunk, @Nullable String detectedSourceLang, String targetLanguage) {
        if (detectedSourceLang == null || targetLanguage == null) {
            return false;
        }
        if (!detectedSourceLang.equalsIgnoreCase(targetLanguage)) {
            return false;
        }
        String stripped = URL_AND_MENTION_PATTERN.matcher(chunk).replaceAll(" ");
        int totalLetters = 0;
        int russianLetters = 0;
        int otherCyrillic = 0;
        for (int i = 0; i < stripped.length(); ) {
            int cp = stripped.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                totalLetters++;
                if (isRussianCyrillicLetter(cp)) {
                    russianLetters++;
                } else if (isCyrillicCodePoint(cp)) {
                    otherCyrillic++;
                }
            }
        }
        return otherCyrillic == 0 && totalLetters > 0 && russianLetters * 2 >= totalLetters;
    }

    static List<String> splitIntoChunks(String text, int maxChunkChars) {
        if (text == null || text.isEmpty() || text.length() <= maxChunkChars) {
            return Collections.singletonList(text == null ? "" : text);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int remaining = length - start;
            if (remaining <= maxChunkChars) {
                chunks.add(text.substring(start));
                break;
            }
            int end = start + maxChunkChars;
            if (Character.isHighSurrogate(text.charAt(end - 1)) && end < length && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            int splitAt = findBestSplitIndex(text, start, end);
            chunks.add(text.substring(start, splitAt));
            start = splitAt;
        }
        return chunks;
    }

    private static int findBestSplitIndex(String text, int start, int maxEnd) {
        int minSearch = start + (maxEnd - start) / 2;
        int newlineIdx = text.lastIndexOf('\n', maxEnd - 1);
        if (newlineIdx >= minSearch) {
            return newlineIdx + 1;
        }
        for (int i = maxEnd - 1; i >= minSearch; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < maxEnd && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 2;
            }
        }
        int spaceIdx = text.lastIndexOf(' ', maxEnd - 1);
        if (spaceIdx >= minSearch) {
            return spaceIdx + 1;
        }
        return maxEnd;
    }

    static String preserveBoundaryWhitespace(String original, String translated) {
        int origLen = original.length();
        int leadingEnd = 0;
        while (leadingEnd < origLen && Character.isWhitespace(original.charAt(leadingEnd))) {
            leadingEnd++;
        }
        int trailingStart = origLen;
        while (trailingStart > leadingEnd && Character.isWhitespace(original.charAt(trailingStart - 1))) {
            trailingStart--;
        }
        if (leadingEnd == 0 && trailingStart == origLen) {
            return translated;
        }
        int transStart = 0;
        int transEnd = translated.length();
        while (transStart < transEnd && Character.isWhitespace(translated.charAt(transStart))) {
            transStart++;
        }
        while (transEnd > transStart && Character.isWhitespace(translated.charAt(transEnd - 1))) {
            transEnd--;
        }
        StringBuilder sb = new StringBuilder(leadingEnd + (transEnd - transStart) + (origLen - trailingStart));
        sb.append(original, 0, leadingEnd);
        sb.append(translated, transStart, transEnd);
        sb.append(original, trailingStart, origLen);
        return sb.toString();
    }

    private static boolean isRussianCyrillicLetter(int cp) {
        return (cp >= 0x0410 && cp <= 0x044F) || cp == 0x0401 || cp == 0x0451;
    }

    private static boolean isCyrillicCodePoint(int cp) {
        return (cp >= 0x0400 && cp <= 0x052F)
                || (cp >= 0x1C80 && cp <= 0x1C8F)
                || (cp >= 0x2DE0 && cp <= 0x2DFF)
                || (cp >= 0xA640 && cp <= 0xA69F)
                || (cp >= 0x1E030 && cp <= 0x1E08F);
    }

    @Nullable
    private String getCached(String text) {
        return mCache.get(mTargetLanguage + "\n" + text);
    }

    private void putCached(String text, String translated) {
        if (text != null && translated != null) {
            mCache.put(mTargetLanguage + "\n" + text, translated);
        }
    }

    public static CommentItem withTranslatedMessage(final CommentItem item, final String translatedMessage) {
        return new CommentItem() {
            @Nullable
            @Override
            public String getId() {
                return item.getId();
            }

            @Nullable
            @Override
            public String getMessage() {
                return translatedMessage;
            }

            @Nullable
            @Override
            public String getAuthorName() {
                return item.getAuthorName();
            }

            @Nullable
            @Override
            public String getAuthorPhoto() {
                return item.getAuthorPhoto();
            }

            @Nullable
            @Override
            public String getPublishedDate() {
                return item.getPublishedDate();
            }

            @Nullable
            @Override
            public String getNestedCommentsKey() {
                return item.getNestedCommentsKey();
            }

            @Override
            public boolean isLiked() {
                return item.isLiked();
            }

            @Nullable
            @Override
            public String getLikeCount() {
                return item.getLikeCount();
            }

            @Nullable
            @Override
            public String getReplyCount() {
                return item.getReplyCount();
            }

            @Override
            public boolean isEmpty() {
                return item.isEmpty();
            }
        };
    }

    static final class ParsedTranslation {
        final String translatedText;
        @Nullable
        final String sourceLanguage;

        ParsedTranslation(String translatedText, @Nullable String sourceLanguage) {
            this.translatedText = translatedText;
            this.sourceLanguage = sourceLanguage;
        }
    }

    private static final class SimpleJsonParser {
        private final String mInput;
        private int mPos;

        private SimpleJsonParser(String input) {
            mInput = input;
        }

        static Object parse(String input) {
            SimpleJsonParser parser = new SimpleJsonParser(input);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (parser.mPos != parser.mInput.length()) {
                throw new IllegalArgumentException("Unexpected trailing data at " + parser.mPos);
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (mPos >= mInput.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = mInput.charAt(mPos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at " + mPos);
            }
        }

        private Map<String, Object> parseObject() {
            mPos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (mPos < mInput.length() && mInput.charAt(mPos) == '}') {
                mPos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (mPos >= mInput.length() || mInput.charAt(mPos) != '"') {
                    throw new IllegalArgumentException("Expected string key at " + mPos);
                }
                String key = parseString();
                skipWhitespace();
                if (mPos >= mInput.length() || mInput.charAt(mPos) != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + mPos);
                }
                mPos++;
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (mPos >= mInput.length()) {
                    throw new IllegalArgumentException("Unterminated object");
                }
                char c = mInput.charAt(mPos);
                if (c == '}') {
                    mPos++;
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at " + mPos);
                }
                mPos++;
            }
        }

        private List<Object> parseArray() {
            mPos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (mPos < mInput.length() && mInput.charAt(mPos) == ']') {
                mPos++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                if (mPos >= mInput.length()) {
                    throw new IllegalArgumentException("Unterminated array");
                }
                char c = mInput.charAt(mPos);
                if (c == ']') {
                    mPos++;
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at " + mPos);
                }
                mPos++;
            }
        }

        private String parseString() {
            mPos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (mPos < mInput.length()) {
                char c = mInput.charAt(mPos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (mPos >= mInput.length()) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    char esc = mInput.charAt(mPos++);
                    switch (esc) {
                        case '"':
                        case '\\':
                        case '/':
                            sb.append(esc);
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (mPos + 4 > mInput.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            String hex = mInput.substring(mPos, mPos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            mPos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Number parseNumber() {
            int start = mPos;
            if (mInput.charAt(mPos) == '-') {
                mPos++;
            }
            while (mPos < mInput.length()) {
                char c = mInput.charAt(mPos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    mPos++;
                } else {
                    break;
                }
            }
            String token = mInput.substring(start, mPos);
            return Double.valueOf(token);
        }

        private void expectLiteral(String literal) {
            if (!mInput.startsWith(literal, mPos)) {
                throw new IllegalArgumentException("Expected " + literal + " at " + mPos);
            }
            mPos += literal.length();
        }

        private void skipWhitespace() {
            while (mPos < mInput.length()) {
                char c = mInput.charAt(mPos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    mPos++;
                } else {
                    break;
                }
            }
        }
    }
}
