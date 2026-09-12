package ac.grim.grimac.checks.impl.movement;

import java.util.concurrent.TimeUnit;

/** Bounds recovery work; it does not exempt movements or change violation evidence. */
final class NoSlowUseResync {
    static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private boolean pending;
    private boolean requested;
    private long lastRequest;

    interface Dispatcher {
        boolean schedule(Runnable run, Runnable retired);
    }

    void request(long now, Dispatcher dispatcher, Runnable resync) {
        synchronized (this) {
            if (pending || requested && now - lastRequest < INTERVAL_NANOS) return;
            pending = true;
            requested = true;
            lastRequest = now;
        }
        try {
            if (!dispatcher.schedule(() -> {
                try {
                    resync.run();
                } finally {
                    complete();
                }
            }, this::complete)) complete();
        } catch (RuntimeException e) {
            complete();
            throw e;
        }
    }

    private synchronized void complete() {
        pending = false;
    }
}
