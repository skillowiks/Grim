package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TriggerBotStateFenceTest {
    @Test
    void untouchedFenceAllowsObservationAndCancelledSendIsACallerNoOp() {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        assertEquals(0, fence.generation());
        assertFalse(fence.isPending(0));
        // The caller excludes cancelled/unrelated packets before opening a token.
        fence.sent(null, 10);
        assertEquals(0, fence.generation());
        assertFalse(fence.isPending(10));
    }

    @Test
    void unsealedSendCannotBeAcknowledgedByAnyTransaction() {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        TriggerBotStateFence.Token token = fence.pending();
        assertEquals(1, token.generation());
        assertEquals(token.generation(), fence.generation());
        assertTrue(fence.isPending(0));
        assertTrue(fence.isPending(100));
        assertTrue(fence.isPending(Integer.MAX_VALUE));
    }

    @Test
    void onlyAnAcknowledgementStrictlyAfterPhysicalSendCounterReleasesFence() {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        TriggerBotStateFence.Token token = fence.pending();
        // Transactions may be sent while the relevant packet is still queued.
        fence.sent(token, 15);
        assertTrue(fence.isPending(10));
        assertTrue(fence.isPending(14));
        assertTrue(fence.isPending(15));
        assertFalse(fence.isPending(16));
        assertFalse(fence.isPending(100));
        assertEquals(1, fence.generation(), "acknowledgement retains the generation for RX invalidation");
    }

    @Test
    void anOldQueuedCompletionCannotSealOrClearTheNewestUnsentPacket() {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        TriggerBotStateFence.Token old = fence.pending();
        TriggerBotStateFence.Token current = fence.pending();
        fence.sent(old, 10);
        assertTrue(fence.isPending(100));
        assertEquals(2, fence.generation());
        fence.sent(current, 100);
        assertTrue(fence.isPending(100));
        assertFalse(fence.isPending(101));
    }

    @Test
    void outOfOrderOldCompletionCannotReplaceTheLatestPhysicalSendBoundary() {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        TriggerBotStateFence.Token old = fence.pending();
        TriggerBotStateFence.Token current = fence.pending();
        fence.sent(current, 20);
        fence.sent(old, 10);
        assertTrue(fence.isPending(20));
        assertFalse(fence.isPending(21));
        // Even a later old callback cannot extend/reopen the acknowledged fence.
        fence.sent(old, 30);
        assertFalse(fence.isPending(21));
        assertEquals(current.generation(), fence.generation());
    }

    @Test
    void aNewSendReopensAnAcknowledgedFenceAndChangesItsGeneration() {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        TriggerBotStateFence.Token first = fence.pending();
        fence.sent(first, 10);
        assertFalse(fence.isPending(11));
        long savedGeneration = fence.generation();
        TriggerBotStateFence.Token next = fence.pending();
        assertTrue(fence.isPending(100));
        assertNotEquals(savedGeneration, fence.generation());
        fence.sent(next, 12);
        assertTrue(fence.isPending(12));
        assertFalse(fence.isPending(13));
        assertNotEquals(savedGeneration, fence.generation());
    }

    @Test
    void duplicateAndForeignSendCompletionsCannotAlterTheBoundary() {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        TriggerBotStateFence.Token token = fence.pending();
        TriggerBotStateFence other = new TriggerBotStateFence();
        other.sent(token, 1);
        assertFalse(other.isPending(0));
        assertTrue(fence.isPending(100));
        fence.sent(other.pending(), 1);
        assertTrue(fence.isPending(100));
        fence.sent(token, 20);
        fence.sent(token, 10);
        assertTrue(fence.isPending(20));
        assertFalse(fence.isPending(21));
        fence.sent(token, 30);
        assertFalse(fence.isPending(21));
    }

    @Test
    void consecutiveSendsRequireAFutureTransactionForTheLastPacket() {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        for (int transaction = 0; transaction < 100; transaction++) {
            TriggerBotStateFence.Token token = fence.pending();
            fence.sent(token, transaction);
            assertTrue(fence.isPending(transaction));
            assertFalse(fence.isPending(transaction + 1));
            assertEquals(transaction + 1, fence.generation());
        }
    }

    @Test
    void racingOpensPublishUniqueMonotonicGenerationsAndOldCallbacksCannotWin() throws Exception {
        TriggerBotStateFence fence = new TriggerBotStateFence();
        var executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<TriggerBotStateFence.Token>>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < 4; thread++) {
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    List<TriggerBotStateFence.Token> tokens = new ArrayList<>();
                    for (int i = 0; i < 100; i++) tokens.add(fence.pending());
                    return tokens;
                }));
            }
            start.countDown();
            HashSet<Long> generations = new HashSet<>();
            List<TriggerBotStateFence.Token> all = new ArrayList<>();
            for (var future : futures) all.addAll(future.get(10, TimeUnit.SECONDS));
            for (var token : all) assertTrue(generations.add(token.generation()));
            assertEquals(400, generations.size());
            assertEquals(400, fence.generation());
            TriggerBotStateFence.Token newest = all.stream().filter(t -> t.generation() == 400).findFirst().orElseThrow();
            for (var token : all) if (token != newest) fence.sent(token, 1);
            assertTrue(fence.isPending(1000));
            fence.sent(newest, 1000);
            assertTrue(fence.isPending(1000));
            assertFalse(fence.isPending(1001));
        } finally {
            executor.shutdownNow();
        }
    }
}
