package ac.grim.grimac.utils.data.webhook.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookQueueTest {
    @Test
    void sendsOneRequestAtATimeInFifoOrder() {
        Fixture fixture = new Fixture();
        HttpRequest first = request("first");
        HttpRequest second = request("second");
        CompletableFuture<Boolean> firstResult = fixture.queue.enqueue(first);
        CompletableFuture<Boolean> secondResult = fixture.queue.enqueue(second);

        fixture.queue.tick();
        fixture.queue.tick();
        assertEquals(List.of(first), fixture.requests);
        fixture.respond(0, 204, "", Map.of());
        assertTrue(firstResult.join());
        assertFalse(secondResult.isDone());

        fixture.queue.tick();
        assertEquals(List.of(first, second), fixture.requests);
        fixture.respond(1, 200, "{}", Map.of());
        assertTrue(secondResult.join());
    }

    @ParameterizedTest
    @MethodSource("rateLimitResponses")
    void rateLimitsKeepHeadAndHonorSafeDecimalDeadline(String body, Map<String, List<String>> headers,
                                                     long expectedDelay) {
        Fixture fixture = new Fixture();
        HttpRequest first = request("first");
        CompletableFuture<Boolean> result = fixture.queue.enqueue(first);
        fixture.queue.enqueue(request("second"));
        fixture.queue.tick();
        fixture.respond(0, 429, body, headers);
        assertFalse(result.isDone());

        fixture.now.addAndGet(expectedDelay - 1);
        fixture.queue.tick();
        assertEquals(1, fixture.requests.size());
        fixture.now.incrementAndGet();
        fixture.queue.tick();
        assertEquals(2, fixture.requests.size());
        assertSame(first, fixture.requests.get(1));
        fixture.respond(1, 204, "", Map.of());
        assertTrue(result.join());
    }

    static Stream<Arguments> rateLimitResponses() {
        return Stream.of(
                Arguments.of("{\"retry_after\":1.25,\"global\":true}", Map.of(), 1250L),
                Arguments.of("{\"retry_after\":0.0001}", Map.of(), 1L),
                Arguments.of("{\"retry_after\":0}", Map.of(), 1L),
                Arguments.of("{}", Map.of("Retry-After", List.of("2.125")), 2125L),
                Arguments.of("{\"retry_after\":{}}", Map.of("X-RateLimit-Reset-After", List.of("1.125")), 1125L),
                Arguments.of("{}", Map.of("X-RateLimit-Reset", List.of("10.25")), 250L),
                Arguments.of("not json", Map.of(), 1000L),
                Arguments.of("[]", Map.of("X-RateLimit-Reset", List.of("9.9")), 1000L),
                Arguments.of("{\"retry_after\":-2}", Map.of("Retry-After", List.of("NaN")), 1000L),
                Arguments.of("{}", Map.of("X-RateLimit-Reset", List.of("invalid")), 1000L),
                Arguments.of("{\"retry_after\":\"Infinity\"}", Map.of("X-RateLimit-Reset-After", List.of("0.75")), 750L)
        );
    }

    @Test
    void bodyRetryDelayTakesPrecedenceOverBucketReset() {
        Fixture fixture = new Fixture();
        fixture.queue.enqueue(request("first"));
        fixture.queue.tick();
        fixture.respond(0, 429, "{\"retry_after\":10.5}", Map.of("X-RateLimit-Reset-After", List.of("0.5")));
        fixture.now.addAndGet(1000);
        fixture.queue.tick();
        assertEquals(1, fixture.requests.size());
        fixture.now.addAndGet(9500);
        fixture.queue.tick();
        assertEquals(2, fixture.requests.size());
    }

    @Test
    void transientFailuresBackOffAndStopAfterThreeAttemptsWithoutBlockingNextAlert() {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> failed = fixture.queue.enqueue(request("first"));
        CompletableFuture<Boolean> next = fixture.queue.enqueue(request("second"));
        fixture.queue.tick();
        fixture.responses.get(0).completeExceptionally(new IllegalStateException("private-token"));
        fixture.now.addAndGet(999);
        fixture.queue.tick();
        assertEquals(1, fixture.requests.size());
        fixture.now.incrementAndGet();
        fixture.queue.tick();
        fixture.respond(1, 503, "private-token", Map.of());
        fixture.now.addAndGet(1999);
        fixture.queue.tick();
        assertEquals(2, fixture.requests.size());
        fixture.now.incrementAndGet();
        fixture.queue.tick();
        fixture.respond(2, 502, "private-token", Map.of());
        assertFalse(failed.join());
        assertEquals(1, fixture.logs.size());
        assertTrue(fixture.logs.get(0).contains("HTTP 502"));
        assertFalse(fixture.logs.get(0).contains("private-token"));

        fixture.queue.tick();
        fixture.respond(3, 204, "", Map.of());
        assertTrue(next.join());
    }

    @Test
    void synchronousTransportExceptionsAlsoCompleteAfterBoundedRetries() {
        AtomicLong now = new AtomicLong(10_000);
        AtomicInteger calls = new AtomicInteger();
        List<String> logs = new ArrayList<>();
        DiscordWebhookQueue queue = new DiscordWebhookQueue(request -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("https://example.invalid/private-token");
        }, now::get, logs::add);
        CompletableFuture<Boolean> result = queue.enqueue(request("first"));
        queue.tick();
        now.addAndGet(1000);
        queue.tick();
        now.addAndGet(2000);
        queue.tick();
        queue.tick();
        assertEquals(3, calls.get());
        assertFalse(result.join());
        assertEquals(1, logs.size());
        assertFalse(logs.get(0).contains("private-token"));
    }

    @Test
    void nullResponseIsNeverReportedAsSuccess() {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> result = fixture.queue.enqueue(request("first"));
        fixture.queue.tick();
        fixture.responses.get(0).complete(null);
        fixture.now.addAndGet(1000);
        fixture.queue.tick();
        fixture.responses.get(1).complete(null);
        fixture.now.addAndGet(2000);
        fixture.queue.tick();
        fixture.responses.get(2).complete(null);
        assertFalse(result.join());
    }

    @Test
    void nullTransportFutureCannotStrandQueue() {
        AtomicLong now = new AtomicLong(10_000);
        DiscordWebhookQueue queue = new DiscordWebhookQueue(request -> null, now::get, ignored -> {});
        CompletableFuture<Boolean> result = queue.enqueue(request("first"));
        queue.tick();
        now.addAndGet(1000);
        queue.tick();
        now.addAndGet(2000);
        queue.tick();
        assertFalse(result.join());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 299})
    void onlySuccessfulHttpStatusesCompleteTrue(int status) {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> result = fixture.queue.enqueue(request("first"));
        fixture.queue.tick();
        fixture.respond(0, status, "", Map.of());
        assertTrue(result.join());
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 400, 401, 403, 404, 418})
    void redirectsAndPermanentFailuresAreNotSuccessOrRetried(int status) {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> result = fixture.queue.enqueue(request("first"));
        fixture.queue.tick();
        fixture.respond(0, status, "{\"code\":10015,\"message\":\"private-token\"}",
                Map.of("Location", List.of("https://example.invalid/private-token")));
        assertFalse(result.join());
        fixture.queue.tick();
        assertEquals(1, fixture.requests.size());
        String action = status == 401 || status == 403 || status == 404 ? "disabled until reload" : "rejected";
        assertEquals(List.of("Discord webhook " + action + ": HTTP " + status + " (Discord code 10015)."), fixture.logs);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void invalidEndpointIsStoppedWithoutBlockingOtherEndpointsAndReloadAllowsRetry(int status) {
        Fixture fixture = new Fixture();
        HttpRequest invalid = request("invalid");
        HttpRequest valid = request("valid");
        CompletableFuture<Boolean> first = fixture.queue.enqueue(invalid);
        CompletableFuture<Boolean> second = fixture.queue.enqueue(invalid);
        CompletableFuture<Boolean> unrelated = fixture.queue.enqueue(valid);
        CompletableFuture<Boolean> third = fixture.queue.enqueue(invalid);
        fixture.queue.tick();
        fixture.respond(0, status, "{}", Map.of());
        assertFalse(first.join());
        assertFalse(second.join());
        assertFalse(third.join());
        assertFalse(fixture.queue.enqueue(invalid).join());
        assertEquals(1, fixture.logs.size());
        fixture.queue.tick();
        assertEquals(List.of(invalid, valid), fixture.requests);
        fixture.respond(1, 204, "", Map.of());
        assertTrue(unrelated.join());

        fixture.queue.clear();
        CompletableFuture<Boolean> afterReload = fixture.queue.enqueue(invalid);
        assertFalse(afterReload.isDone());
        fixture.queue.tick();
        fixture.respond(2, 204, "", Map.of());
        assertTrue(afterReload.join());
    }

    @Test
    void untrustedResponseFieldsCannotLeakIntoLogs() {
        Fixture fixture = new Fixture();
        fixture.queue.enqueue(request("private-token"));
        fixture.queue.tick();
        fixture.respond(0, 400, "{\"code\":\"private-token\",\"message\":\"private-token\"}", Map.of());
        assertEquals(List.of("Discord webhook rejected: HTTP 400."), fixture.logs);
    }

    @Test
    void clearCompletesOldFuturesAndOldRateLimitCallbackCannotDelayNewQueue() {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> first = fixture.queue.enqueue(request("old-first"));
        CompletableFuture<Boolean> second = fixture.queue.enqueue(request("old-second"));
        fixture.queue.tick();
        fixture.queue.clear();
        assertFalse(first.join());
        assertFalse(second.join());
        HttpRequest fresh = request("fresh");
        CompletableFuture<Boolean> freshResult = fixture.queue.enqueue(fresh);
        fixture.queue.tick();
        assertEquals(1, fixture.requests.size(), "The old HTTP call is still in flight");
        fixture.respond(0, 429, "{\"retry_after\":3600}", Map.of());
        fixture.queue.tick();
        assertSame(fresh, fixture.requests.get(1));
        fixture.respond(1, 204, "", Map.of());
        assertTrue(freshResult.join());
        assertFalse(first.join());
    }

    @Test
    void clearRemovesExistingRetryDeadline() {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> first = fixture.queue.enqueue(request("old"));
        fixture.queue.tick();
        fixture.respond(0, 429, "{\"retry_after\":3600}", Map.of());
        fixture.queue.clear();
        assertFalse(first.join());
        fixture.queue.enqueue(request("fresh"));
        fixture.queue.tick();
        assertEquals(2, fixture.requests.size());
    }

    @Test
    void capacityRejectsNewMessagesAndLogsOnlyOnceWhileFull() {
        Fixture fixture = new Fixture();
        List<CompletableFuture<Boolean>> accepted = new ArrayList<>();
        for (int index = 0; index < DiscordWebhookQueue.CAPACITY; index++) {
            accepted.add(fixture.queue.enqueue(request("queued")));
        }
        assertFalse(fixture.queue.enqueue(request("overflow")).join());
        assertFalse(fixture.queue.enqueue(request("overflow-again")).join());
        assertEquals(1, fixture.logs.size());
        fixture.queue.clear();
        assertTrue(accepted.stream().allMatch(result -> result.isDone() && !result.join()));
        assertFalse(fixture.queue.enqueue(request("fresh")).isDone());
    }

    @Test
    void simultaneousTicksReserveOnlyOneSend() throws Exception {
        Fixture fixture = new Fixture();
        fixture.queue.enqueue(request("first"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> ticks = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                ticks.add(executor.submit(() -> {
                    start.await();
                    fixture.queue.tick();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> tick : ticks) tick.get(5, TimeUnit.SECONDS);
            assertEquals(1, fixture.requests.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rateLimitsDoNotConsumeTransientFailureBudget() {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> result = fixture.queue.enqueue(request("first"));
        for (int index = 0; index < 3; index++) {
            fixture.queue.tick();
            fixture.respond(index, 429, "{\"retry_after\":1}", Map.of());
            fixture.now.addAndGet(1000);
        }
        fixture.queue.tick();
        fixture.respond(3, 503, "", Map.of());
        fixture.now.addAndGet(1000);
        fixture.queue.tick();
        fixture.respond(4, 204, "", Map.of());
        assertTrue(result.join());
    }

    @Test
    void throwingLoggerDoesNotPreventCompletionOrNextSend() {
        AtomicInteger calls = new AtomicInteger();
        DiscordWebhookQueue queue = new DiscordWebhookQueue(request -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response(400, "", Map.of()));
        }, () -> 10_000, message -> { throw new IllegalStateException("logger unavailable"); });
        CompletableFuture<Boolean> first = queue.enqueue(request("first"));
        CompletableFuture<Boolean> second = queue.enqueue(request("second"));
        queue.tick();
        queue.tick();
        assertFalse(first.join());
        assertFalse(second.join());
        assertEquals(2, calls.get());
    }

    private static HttpRequest request(String id) {
        return HttpRequest.newBuilder(URI.create("https://example.invalid/" + id))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }

    private static HttpResponse<String> response(int status, String body, Map<String, List<String>> headers) {
        return new StubResponse(status, body, HttpHeaders.of(headers, (name, value) -> true));
    }

    private record StubResponse(int statusCode, String body, HttpHeaders headers) implements HttpResponse<String> {
        @Override public HttpRequest request() { return DiscordWebhookQueueTest.request("response"); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://example.invalid/response"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private static final class Fixture {
        private final AtomicLong now = new AtomicLong(10_000);
        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<HttpResponse<String>>> responses = new CopyOnWriteArrayList<>();
        private final List<String> logs = new ArrayList<>();
        private final DiscordWebhookQueue queue = new DiscordWebhookQueue(request -> {
            requests.add(request);
            CompletableFuture<HttpResponse<String>> future = new CompletableFuture<>();
            responses.add(future);
            return future;
        }, now::get, logs::add);

        private void respond(int index, int status, String body, Map<String, List<String>> headers) {
            responses.get(index).complete(response(status, body, headers));
        }
    }
}
