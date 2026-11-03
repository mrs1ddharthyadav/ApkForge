package com.sangam.apkforge.engine;

import com.reandroid.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal client for the Claude Messages API (https://api.anthropic.com/v1/messages).
 * The API key is only ever sent to [baseUrl], which is Anthropic's API unless a test overrides it.
 */
public final class ClaudeClient {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    /** Current API version for direct HTTP calls; older version kept as a fallback. */
    private static final String API_VERSION = "2026-10-21";
    private static final String FALLBACK_API_VERSION = "2023-06-01";

    /** An API error with a message written for a non-programmer. */
    public static final class ApiException extends IOException {
        public final int status;
        public final boolean retryable;

        ApiException(int status, String message, boolean retryable) {
            super(message);
            this.status = status;
            this.retryable = retryable;
        }
    }

    private final String apiKey;
    private final String workspaceId;
    private final String baseUrl;
    private volatile String apiVersion = API_VERSION;
    private volatile HttpURLConnection current;
    private volatile boolean cancelled;

    public ClaudeClient(String apiKey, String workspaceId) {
        this(apiKey, workspaceId, DEFAULT_BASE_URL);
    }

    /**
     * @param workspaceId only needed for keys that are not linked to a workspace; may be null.
     */
    public ClaudeClient(String apiKey, String workspaceId, String baseUrl) {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalArgumentException("No API key");
        this.apiKey = apiKey.trim();
        this.workspaceId = workspaceId == null || workspaceId.trim().isEmpty() ? null : workspaceId.trim();
        this.baseUrl = baseUrl;
    }

    /** Stops the request that is running now (if any) and any retries. */
    public void cancel() {
        cancelled = true;
        HttpURLConnection c = current;
        if (c != null) c.disconnect();
    }

    public void resetCancel() {
        cancelled = false;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Sends one Messages API request, retrying a few times if Claude is busy. */
    public JSONObject send(JSONObject body) throws IOException {
        int attempt = 0;
        while (true) {
            try {
                return sendOnce(body);
            } catch (VersionRejected e) {
                // The server doesn't know the newer API version: switch to the older one and try again.
                apiVersion = FALLBACK_API_VERSION;
            } catch (ApiException e) {
                attempt++;
                if (!e.retryable || attempt >= 4 || cancelled) throw e;
                sleep(2000L * attempt * attempt);
            } catch (SocketTimeoutException e) {
                throw new IOException("Claude took too long to answer. Try again, or ask for a smaller change.");
            } catch (UnknownHostException e) {
                throw new IOException("No internet connection. Check your Wi-Fi or mobile data.");
            }
        }
    }

    private JSONObject sendOnce(JSONObject body) throws IOException {
        if (cancelled) throw new IOException("Stopped.");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/v1/messages").openConnection();
        current = c;
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(30_000);
            c.setReadTimeout(600_000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("authorization", "Bearer " + apiKey);
            c.setRequestProperty("anthropic-version", apiVersion);
            if (workspaceId != null) c.setRequestProperty("anthropic-workspace-id", workspaceId);
            try (OutputStream out = c.getOutputStream()) {
                out.write(payload);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (status >= 400) {
                if (status == 400 && !FALLBACK_API_VERSION.equals(apiVersion)
                        && text.toLowerCase().contains("anthropic-version")) {
                    throw new VersionRejected();
                }
                throw toApiException(status, text);
            }
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                throw new IOException("Claude sent an answer APK Forge couldn't read.");
            }
        } catch (IOException e) {
            if (cancelled) throw new IOException("Stopped.");
            throw e;
        } finally {
            current = null;
            c.disconnect();
        }
    }

    static ApiException toApiException(int status, String body) {
        String type = "";
        String detail = "";
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null) {
                type = err.optString("type", "");
                detail = err.optString("message", "");
            }
        } catch (Exception ignored) {
            // not JSON
        }
        String lower = detail.toLowerCase();
        if (status == 401 || "authentication_error".equals(type)) {
            return new ApiException(status, "Your API key was not accepted. Open Settings and check that you pasted the whole key.", false);
        }
        if (lower.contains("workspace")) {
            return new ApiException(status, "This API key isn't linked to a workspace. Either create a new key and choose "
                    + "a workspace for it in the Claude Console, or paste your workspace ID in Settings.", false);
        }
        if (lower.contains("credit balance") || lower.contains("billing")) {
            return new ApiException(status, "Your Claude API account has no credits left. Add credits in the Claude Console (Billing), then try again.", false);
        }
        if (status == 403 || "permission_error".equals(type)) {
            return new ApiException(status, "This API key isn't allowed to do that. " + detail, false);
        }
        if (status == 404 || "not_found_error".equals(type)) {
            return new ApiException(status, "Claude couldn't find that model. Open Settings and pick a different model. (" + detail + ")", false);
        }
        if (status == 413 || "request_too_large".equals(type)) {
            return new ApiException(status, "The request was too large. Start a new chat or ask about fewer files at once.", false);
        }
        if (status == 429 || "rate_limit_error".equals(type)) {
            return new ApiException(status, "Too many requests right now. Wait a minute and try again.", true);
        }
        if (status == 529 || "overloaded_error".equals(type)) {
            return new ApiException(status, "Claude is very busy right now. Try again in a few minutes.", true);
        }
        if (status >= 500) {
            return new ApiException(status, "Claude's servers had a problem (error " + status + "). Try again.", true);
        }
        if (lower.contains("prompt is too long") || lower.contains("too many tokens")) {
            return new ApiException(status, "This chat has become too long. Tap New chat and ask again.", false);
        }
        return new ApiException(status, "Claude returned an error (" + status + "): " + (detail.isEmpty() ? type : detail), false);
    }

    private static final class VersionRejected extends IOException {
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void sleep(long ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelled) throw new IOException("Stopped.");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Stopped.");
            }
        }
    }
}
