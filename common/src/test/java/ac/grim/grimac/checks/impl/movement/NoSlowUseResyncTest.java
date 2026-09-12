package ac.grim.grimac.checks.impl.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoSlowUseResyncTest {
    @Test
    void burstOfFlagsKeepsOnlyOneEntityTaskAndRateLimitsCompletedRequests() {
        NoSlowUseResync recovery = new NoSlowUseResync();
        Queue queue = new Queue();
        int[] resends = {0};
        recovery.request(0, queue, () -> resends[0]++);
        recovery.request(NoSlowUseResync.INTERVAL_NANOS * 10, queue, () -> resends[0]++);
        assertEquals(1, queue.scheduled);
        assertEquals(0, resends[0]); // Packet thread never runs the authoritative read.
        queue.run.run();
        assertEquals(1, resends[0]);
        recovery.request(NoSlowUseResync.INTERVAL_NANOS * 10 + 1, queue, () -> resends[0]++);
        assertEquals(2, queue.scheduled);
        queue.run.run();
        recovery.request(NoSlowUseResync.INTERVAL_NANOS * 11, queue, () -> resends[0]++);
        assertEquals(2, queue.scheduled);
        recovery.request(NoSlowUseResync.INTERVAL_NANOS * 12 + 1, queue, () -> resends[0]++);
        assertEquals(3, queue.scheduled);
    }

    @Test
    void retiredOrRejectedTaskDoesNotLeaveRecoveryPending() {
        NoSlowUseResync recovery = new NoSlowUseResync();
        Queue queue = new Queue();
        recovery.request(0, queue, () -> fail("Retired task must not read player state"));
        queue.retired.run();
        recovery.request(NoSlowUseResync.INTERVAL_NANOS, (run, retired) -> false, () -> fail("Rejected task"));
        recovery.request(NoSlowUseResync.INTERVAL_NANOS * 2, queue, () -> {});
        assertEquals(2, queue.scheduled);
    }

    @Test
    void dispatchAndCallbackFailuresReleasePendingSlot() {
        NoSlowUseResync recovery = new NoSlowUseResync();
        Queue queue = new Queue();
        assertThrows(IllegalStateException.class, () -> recovery.request(0, (run, retired) -> {
            throw new IllegalStateException("scheduler unavailable");
        }, () -> {}));
        recovery.request(NoSlowUseResync.INTERVAL_NANOS, queue, () -> {
            throw new IllegalStateException("resend failed");
        });
        assertThrows(IllegalStateException.class, queue.run::run);
        recovery.request(NoSlowUseResync.INTERVAL_NANOS * 2, queue, () -> {});
        assertEquals(2, queue.scheduled);
    }

    @Test
    void recoveryDoesNotResetNoSlowEvidenceOrPermitContinuedUnslowedUse() {
        NoSlowUseResync recovery = new NoSlowUseResync();
        NoSlowBuffer evidence = new NoSlowBuffer();
        Queue queue = new Queue();
        evidence.analyze(0.12544);
        assertFalse(evidence.complete(true, true, false, 0.03));
        evidence.analyze(0.12544);
        assertTrue(evidence.complete(true, true, false, 0.03));
        recovery.request(0, queue, () -> {}); // Server still says active: no exemption.
        queue.run.run();
        evidence.analyze(0.12544);
        assertTrue(evidence.complete(true, true, false, 0.03));
        // A subsequent authoritative inactive state ends that old use, as before.
        assertFalse(evidence.complete(true, false, false, 0.03));
        evidence.analyze(0.12544);
        assertFalse(evidence.complete(true, true, false, 0.03));
        evidence.analyze(0.12544);
        assertTrue(evidence.complete(true, true, false, 0.03));
    }

    private static final class Queue implements NoSlowUseResync.Dispatcher {
        int scheduled;
        Runnable run;
        Runnable retired;

        @Override
        public boolean schedule(Runnable run, Runnable retired) {
            scheduled++;
            this.run = run;
            this.retired = retired;
            return true;
        }
    }
}
