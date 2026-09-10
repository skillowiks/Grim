package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TriggerBotReportPublisherTest {
    @Test
    void generationIsStableForIdempotentStartAndAdvancesOnRestart() {
        TriggerBotReportPublisher publisher = new TriggerBotReportPublisher();
        assertEquals(0, publisher.generation());
        publisher.start();
        try {
            long first = publisher.generation();
            assertEquals(1, first);
            publisher.start();
            assertEquals(first, publisher.generation());
            publisher.stop();
            publisher.start();
            assertEquals(first + 1, publisher.generation());
        } finally {
            publisher.stop();
        }
    }

    @Test
    void rejectsBeforeStartAndExecutesOnOneDaemonWorkerAwayFromCaller() throws InterruptedException {
        TriggerBotReportPublisher publisher = new TriggerBotReportPublisher();
        AtomicBoolean rejectedRan = new AtomicBoolean();
        assertFalse(publisher.trySubmit(() -> rejectedRan.set(true)));
        publisher.start();
        publisher.start(); // lifecycle start is idempotent
        try {
            Thread caller = Thread.currentThread();
            AtomicReference<Thread> first = new AtomicReference<>();
            AtomicReference<Thread> second = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(2);
            assertTrue(publisher.trySubmit(() -> { first.set(Thread.currentThread()); completed.countDown(); }));
            assertTrue(publisher.trySubmit(() -> { second.set(Thread.currentThread()); completed.countDown(); }));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNotSame(caller, first.get());
            assertSame(first.get(), second.get());
            assertTrue(first.get().isDaemon());
            assertFalse(rejectedRan.get());
        } finally {
            publisher.stop();
        }
    }

    @Test
    void saturationRejectsWithoutWaitingOrRunningWorkInline() throws InterruptedException {
        TriggerBotReportPublisher publisher = new TriggerBotReportPublisher();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch queuedCompleted = new CountDownLatch(TriggerBotReportPublisher.QUEUE_CAPACITY);
        AtomicBoolean rejectedRan = new AtomicBoolean();
        publisher.start();
        try {
            assertTrue(publisher.trySubmit(() -> {
                workerStarted.countDown();
                awaitRelease(releaseWorker);
            }));
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < TriggerBotReportPublisher.QUEUE_CAPACITY; i++) {
                assertTrue(publisher.trySubmit(queuedCompleted::countDown));
            }
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> assertFalse(publisher.trySubmit(() -> rejectedRan.set(true))));
            assertFalse(rejectedRan.get());
            assertEquals(TriggerBotReportPublisher.QUEUE_CAPACITY, queuedCompleted.getCount());
            releaseWorker.countDown();
            assertTrue(queuedCompleted.await(5, TimeUnit.SECONDS));
            assertFalse(rejectedRan.get());
        } finally {
            releaseWorker.countDown();
            publisher.stop();
        }
    }

    @Test
    void stopDiscardsQueuedWorkRejectsNewWorkAndAllowsRestart() throws InterruptedException {
        TriggerBotReportPublisher publisher = new TriggerBotReportPublisher();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean discardedRan = new AtomicBoolean();
        AtomicReference<Thread> oldWorker = new AtomicReference<>();
        publisher.start();
        try {
            assertTrue(publisher.trySubmit(() -> {
                oldWorker.set(Thread.currentThread());
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    workerFinished.countDown();
                }
            }));
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            assertTrue(publisher.trySubmit(() -> discardedRan.set(true)));
            publisher.stop();
            publisher.stop(); // lifecycle stop is idempotent
            assertFalse(publisher.trySubmit(() -> discardedRan.set(true)));
            assertTrue(workerFinished.await(5, TimeUnit.SECONDS));
            publisher.start();
            AtomicReference<Thread> newWorker = new AtomicReference<>();
            CountDownLatch restarted = new CountDownLatch(1);
            assertTrue(publisher.trySubmit(() -> { newWorker.set(Thread.currentThread()); restarted.countDown(); }));
            assertTrue(restarted.await(5, TimeUnit.SECONDS));
            assertNotSame(oldWorker.get(), newWorker.get());
            assertFalse(discardedRan.get());
        } finally {
            releaseWorker.countDown();
            publisher.stop();
        }
    }

    @Test
    void stopDoesNotWaitForAnAlreadyRunningTaskToFinish() throws InterruptedException {
        TriggerBotReportPublisher publisher = new TriggerBotReportPublisher();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        publisher.start();
        try {
            assertTrue(publisher.trySubmit(() -> {
                workerStarted.countDown();
                awaitRelease(releaseWorker);
                workerFinished.countDown();
            }));
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), publisher::stop);
            assertEquals(1, workerFinished.getCount());
            releaseWorker.countDown();
            assertTrue(workerFinished.await(5, TimeUnit.SECONDS));
        } finally {
            releaseWorker.countDown();
            publisher.stop();
        }
    }

    @Test
    void reportFailuresDoNotPoisonFollowingWorkOrKillTheWorker() throws InterruptedException {
        TriggerBotReportPublisher publisher = new TriggerBotReportPublisher();
        AtomicReference<Thread> failedWorker = new AtomicReference<>();
        AtomicReference<Thread> healthyWorker = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        publisher.start();
        try {
            assertTrue(publisher.trySubmit(() -> {
                failedWorker.set(Thread.currentThread());
                throw new IllegalArgumentException("private report payload");
            }));
            assertTrue(publisher.trySubmit(() -> { throw new AssertionError("bad formatter"); }));
            assertTrue(publisher.trySubmit(() -> { throw new LinkageError("optional diagnostics"); }));
            assertTrue(publisher.trySubmit(() -> {
                healthyWorker.set(Thread.currentThread());
                completed.countDown();
            }));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertSame(failedWorker.get(), healthyWorker.get());
        } finally {
            publisher.stop();
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        boolean interrupted = false;
        for (;;) {
            try {
                release.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
