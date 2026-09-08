package ac.grim.grimac.utils.data.webhook.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** A bounded FIFO for one webhook, driven by the platform's asynchronous timer. */
public final class DiscordWebhookQueue {
    static final int CAPACITY = 256;
    private static final int MAX_FAILURES = 3;
    private static final long FALLBACK_DELAY_MILLIS = 1000;
    private static final int MAX_RESPONSE_JSON_LENGTH = 16_384;

    private final Object lock = new Object();
    private final ArrayDeque<PendingRequest> requests = new ArrayDeque<>();
    private final Set<URI> blockedEndpoints = new HashSet<>();
    private final Function<HttpRequest, CompletableFuture<HttpResponse<String>>> transport;
    private final LongSupplier clock;
    private final Consumer<String> logger;
    private PendingRequest inFlight;
    private long retryAt;
    private boolean overflowLogged;

    public DiscordWebhookQueue(Function<HttpRequest, CompletableFuture<HttpResponse<String>>> transport,
                               LongSupplier clock, Consumer<String> logger) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<Boolean> enqueue(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        boolean logOverflow = false;
        synchronized (lock) {
            if (blockedEndpoints.contains(request.uri())) return CompletableFuture.completedFuture(false);
            if (requests.size() < CAPACITY) {
                PendingRequest pending = new PendingRequest(request);
                requests.addLast(pending);
                return pending.result;
            }
            if (!overflowLogged) {
                overflowLogged = true;
                logOverflow = true;
            }
        }
        if (logOverflow) log("Discord webhook queue is full; new alerts are being dropped.");
        return CompletableFuture.completedFuture(false);
    }

    public void tick() {
        PendingRequest pending;
        synchronized (lock) {
            if (inFlight != null || requests.isEmpty() || clock.getAsLong() < retryAt) return;
            pending = requests.getFirst();
            inFlight = pending;
        }

        try {
            CompletableFuture<HttpResponse<String>> response = transport.apply(pending.request);
            if (response == null) {
                finish(pending, null, new IllegalStateException("Missing transport future"));
            } else {
                response.whenComplete((result, failure) -> finish(pending, result, failure));
            }
        } catch (RuntimeException failure) {
            finish(pending, null, failure);
        }
    }

    /** Discards old configuration's requests, including the result of an outstanding HTTP call. */
    public void clear() {
        List<PendingRequest> discarded;
        synchronized (lock) {
            discarded = new ArrayList<>(requests);
            requests.clear();
            blockedEndpoints.clear();
            retryAt = 0;
            overflowLogged = false;
            // Keep the active call reserved until it finishes: reload must not start a second send.
        }
        discarded.forEach(pending -> pending.result.complete(false));
    }

    private void finish(PendingRequest pending, HttpResponse<String> response, Throwable failure) {
        Boolean result = null;
        String message = null;
        List<PendingRequest> rejected = List.of();
        synchronized (lock) {
            if (inFlight != pending) return;
            try {
                // clear() may have removed this call while it was on the wire.
                if (requests.peekFirst() != pending) return;
                int status = response == null ? -1 : response.statusCode();
                if (failure == null && status == 429) {
                    retryAt = retryTime(response, clock.getAsLong());
                } else if (failure == null && (status == 401 || status == 403 || status == 404)) {
                    URI endpoint = pending.request.uri();
                    blockedEndpoints.add(endpoint);
                    rejected = new ArrayList<>();
                    for (PendingRequest queued : requests) {
                        if (queued.request.uri().equals(endpoint)) rejected.add(queued);
                    }
                    requests.removeAll(rejected);
                    retryAt = 0;
                    overflowLogged = false;
                    message = "Discord webhook disabled until reload: HTTP " + status + discordErrorCode(response) + ".";
                } else if (failure != null || response == null || status >= 500 && status <= 599) {
                    pending.failures++;
                    String reason = failure != null || response == null ? "transport failure" : "HTTP " + status;
                    if (pending.failures < MAX_FAILURES) {
                        retryAt = addSaturated(clock.getAsLong(), FALLBACK_DELAY_MILLIS << (pending.failures - 1));
                    } else {
                        removeHead();
                        result = false;
                        message = "Discord webhook failed after " + MAX_FAILURES + " attempts (" + reason + ").";
                    }
                } else {
                    removeHead();
                    result = status >= 200 && status <= 299;
                    if (!result) message = "Discord webhook rejected: HTTP " + status + discordErrorCode(response) + ".";
                }
            } finally {
                // Publish the delay/removal before another timer invocation can send again.
                inFlight = null;
            }
        }
        if (result != null) pending.result.complete(result);
        rejected.forEach(queued -> queued.result.complete(false));
        if (message != null) log(message);
    }

    private void removeHead() {
        requests.removeFirst();
        retryAt = 0;
        overflowLogged = false;
    }

    private static long retryTime(HttpResponse<String> response, long now) {
        JsonObject body = responseJson(response);
        Long delay = body == null ? null : secondsMillis(body.get("retry_after"));
        if (delay == null) delay = secondsMillis(response.headers().firstValue("Retry-After").orElse(null));
        if (delay == null) delay = secondsMillis(response.headers().firstValue("X-RateLimit-Reset-After").orElse(null));
        if (delay != null) return addSaturated(now, Math.max(1, delay));

        Long reset = secondsMillis(response.headers().firstValue("X-RateLimit-Reset").orElse(null));
        if (reset != null && reset > now) return reset;
        return addSaturated(now, FALLBACK_DELAY_MILLIS);
    }

    private static Long secondsMillis(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        return secondsMillis(value.getAsString());
    }

    private static Long secondsMillis(String value) {
        if (value == null || value.length() > 64) return null;
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds < 0) return null;
            double millis = Math.ceil(seconds * 1000.0);
            return millis >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) millis;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long addSaturated(long start, long delay) {
        return start > Long.MAX_VALUE - delay ? Long.MAX_VALUE : start + delay;
    }

    private static JsonObject responseJson(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.length() > MAX_RESPONSE_JSON_LENGTH) return null;
        try {
            // Keep compatibility with the Gson shipped by older supported servers.
            JsonElement parsed = new JsonParser().parse(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String discordErrorCode(HttpResponse<String> response) {
        JsonObject body = responseJson(response);
        if (body == null) return "";
        JsonElement code = body.get("code");
        if (code == null || !code.isJsonPrimitive()) return "";
        String value = code.getAsString();
        return value.matches("[0-9]{1,10}") ? " (Discord code " + value + ")" : "";
    }

    private void log(String message) {
        try {
            // Neither the request URL nor raw response/exception text is safe to log.
            logger.accept(message);
        } catch (RuntimeException ignored) {
            // Diagnostic failures must never strand the queue or a caller's future.
        }
    }

    private static final class PendingRequest {
        private final HttpRequest request;
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private int failures;

        private PendingRequest(HttpRequest request) {
            this.request = request;
        }
    }
}
