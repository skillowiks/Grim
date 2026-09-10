package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.init.stop.StoppableInitable;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort background formatting, with no execution fallback on the submitting
 * thread. Tasks must capture immutable report data, never live player/cache data.
 * The caller owns per-recording coalescing and stale-session publication guards.
 */
public final class TriggerBotReportPublisher implements StartableInitable, StoppableInitable {
    public static final int QUEUE_CAPACITY = 32;

    private volatile ThreadPoolExecutor executor;
    private volatile long generation;

    @Override
    public synchronized void start() {
        if (executor != null) return;
        ThreadPoolExecutor started = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), work -> {
                    Thread worker = new Thread(work, "Grim-TriggerBot-Reports");
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
        // Thread creation belongs to lifecycle start, not the packet callback.
        started.prestartCoreThread();
        generation++;
        executor = started;
    }

    /** Changes on each real start so callers can abandon pending state from a stopped worker. */
    public long generation() {
        return generation;
    }

    /**
     * Returns false if stopped, saturated, or racing shutdown. Acceptance is not
     * a completion guarantee: shutdown discards queued reports. Never waits for
     * queue space or for a task to complete, and never invokes the task inline.
     */
    public boolean trySubmit(Runnable immutableSnapshotFormattingWork) {
        Objects.requireNonNull(immutableSnapshotFormattingWork, "immutableSnapshotFormattingWork");
        ThreadPoolExecutor current = executor;
        if (current == null) return false;
        try {
            current.execute(() -> {
                try {
                    immutableSnapshotFormattingWork.run();
                } catch (RuntimeException | LinkageError | AssertionError ignored) {
                    // Optional diagnostics must not poison later tasks or expose
                    // arbitrary exception messages through the packet pipeline.
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        ThreadPoolExecutor stopped = executor;
        executor = null;
        if (stopped != null) stopped.shutdownNow();
        // No awaitTermination/join. A task already running may briefly finish;
        // callers must reject stale publication after stop/session replacement.
    }
}
