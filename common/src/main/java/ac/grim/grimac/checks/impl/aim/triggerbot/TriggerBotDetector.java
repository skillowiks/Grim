package ac.grim.grimac.checks.impl.aim.triggerbot;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result;

/**
 * Bounded, single-thread-confined experimental reacquisition detector. The caller
 * supplies existing geometry and must restrict contextValid to continuously
 * grounded, otherwise supported combat. Airborne/critical-hit timing is not
 * scored. Readiness must already hold during the preceding OUTSIDE samples;
 * for attacks it must be the captured pre-reset state, never tick-end cooldown.
 * These deliberately conservative constants are uncalibrated research policy,
 * not measured human limits or a cheating probability. This class has no player,
 * alert, punishment, geometry-query, or packet side effects.
 */
public final class TriggerBotDetector {
    private static final int OUTSIDE_SAMPLES = 3;
    private static final int MIN_ENTRY_GAP = 10;
    private static final int HORIZON_TICKS = 12;
    private static final int WINDOW_EPISODES = 32;
    private static final int MIN_HITS = 30;
    private static final int MIN_FAST_HITS = 30;
    private static final int MIN_ZERO_HITS = 24;
    private static final long MIN_WINDOW_TICKS = 400;
    private static final long WINDOW_LIFETIME_NANOS = 120_000_000_000L;
    private static final long SUPPORT_IDLE_NANOS = 120_000_000_000L;
    private static final long SUPPORT_LIFETIME_NANOS = 300_000_000_000L;

    public record Frame(long tick, long nowNanos, Object targetIdentity, String weapon,
                        double attackSpeed, Result geometry, boolean contextValid,
                        boolean ready, boolean attacked, boolean attackValid) {
    }

    /** Raw totals across exactly two fresh, disjoint windows; no probability. */
    public record Evidence(int episodes, int hits, int fastHits, int zeroDelayHits, long elapsedTicks) {
    }

    private Object identity;
    private String weapon;
    private double attackSpeed;
    private boolean clockInitialized;
    private long latestTick;
    private long latestNanos;
    private int outsideStreak;
    private long lastEntryTick = -1;
    private long pendingEntryTick = -1;
    private long pendingEntryNanos;

    private int completed, hits, fastHits, zeroHits, censored, gapMask;
    private long windowFirstTick, windowFirstNanos, windowLastEntryTick;
    private boolean hasCompletedTime;
    private long lastCompletedNanos;
    private Window support;

    public Evidence accept(Frame frame) {
        if (frame == null || frame.targetIdentity() == null || frame.weapon() == null
                || frame.weapon().isEmpty() || frame.weapon().length() > 64
                || !Double.isFinite(frame.attackSpeed()) || frame.attackSpeed() <= 0) {
            reset();
            return null;
        }
        if (identity != null && (identity != frame.targetIdentity()
                || !weapon.equals(frame.weapon()) || Double.compare(attackSpeed, frame.attackSpeed()) != 0)) {
            reset();
        }
        identity = frame.targetIdentity();
        weapon = frame.weapon();
        attackSpeed = frame.attackSpeed();
        if (!advance(frame.tick(), frame.nowNanos())) return null;

        if (!frame.contextValid() || frame.geometry() == null || frame.geometry() == Result.UNKNOWN
                || frame.attacked() && (!frame.attackValid() || frame.geometry() != Result.INSIDE)) {
            interrupt(frame.tick(), frame.nowNanos());
            return null;
        }

        if (frame.geometry() == Result.OUTSIDE) {
            Evidence evidence = pendingEntryTick < 0 ? null : finish(frame.tick(), frame.nowNanos(), -1, !frame.ready());
            outsideStreak = frame.ready() ? Math.min(OUTSIDE_SAMPLES, outsideStreak + 1) : 0;
            return evidence;
        }

        if (pendingEntryTick < 0 && outsideStreak >= OUTSIDE_SAMPLES) {
            boolean separated = lastEntryTick < 0 || frame.tick() - lastEntryTick >= MIN_ENTRY_GAP;
            // Even an excluded rapid crossing restarts the separation interval.
            lastEntryTick = frame.tick();
            if (separated) {
                pendingEntryTick = frame.tick();
                pendingEntryNanos = frame.nowNanos();
            }
        }
        outsideStreak = 0;
        if (pendingEntryTick < 0) return null;

        if (!frame.ready()) {
            finish(frame.tick(), frame.nowNanos(), -1, true);
            return null;
        }
        long delay = frame.tick() - pendingEntryTick;
        if (delay >= HORIZON_TICKS) return finish(frame.tick(), frame.nowNanos(), -1, false);
        return frame.attacked() ? finish(frame.tick(), frame.nowNanos(), (int) delay, false) : null;
    }

    /**
     * A skipped/unsupported interval censors an open episode and disarms the next
     * crossing. Completed episodes remain in their window, so uncertainty is not
     * silently selected away and ordinary post-attack cooldown need not reset it.
     */
    public void invalidate(long tick, long nowNanos) {
        if (advance(tick, nowNanos)) interrupt(tick, nowNanos);
    }

    /** Hard reset for disable, reload, corrected movement, or identity changes. */
    public void reset() {
        identity = null;
        weapon = null;
        attackSpeed = 0;
        clockInitialized = false;
        outsideStreak = 0;
        lastEntryTick = -1;
        pendingEntryTick = -1;
        hasCompletedTime = false;
        support = null;
        clearWindow();
    }

    private boolean advance(long tick, long nowNanos) {
        if (tick < 0 || clockInitialized && (tick <= latestTick || nowNanos - latestNanos < 0)) {
            reset();
            return false;
        }
        if (clockInitialized && tick - latestTick != 1) interrupt(tick, nowNanos);
        clockInitialized = true;
        latestTick = tick;
        latestNanos = nowNanos;
        if (hasCompletedTime && nowNanos - lastCompletedNanos > SUPPORT_IDLE_NANOS) support = null;
        if (support != null && nowNanos - support.completedNanos() > SUPPORT_LIFETIME_NANOS) support = null;
        if (completed > 0 && nowNanos - windowFirstNanos > WINDOW_LIFETIME_NANOS) clearWindow();
        return true;
    }

    private void interrupt(long tick, long nowNanos) {
        if (pendingEntryTick >= 0) finish(tick, nowNanos, -1, true);
        outsideStreak = 0;
    }

    private Evidence finish(long tick, long nowNanos, int delay, boolean unknown) {
        long entryTick = pendingEntryTick;
        long entryNanos = pendingEntryNanos;
        pendingEntryTick = -1;
        if (completed == 0) {
            windowFirstTick = entryTick;
            windowFirstNanos = entryNanos;
        } else {
            long gap = entryTick - windowLastEntryTick;
            gapMask |= 1 << (gap <= 20 ? 0 : gap <= 40 ? 1 : gap <= 80 ? 2 : 3);
        }
        windowLastEntryTick = entryTick;
        completed++;
        if (unknown) censored++;
        if (delay >= 0) {
            hits++;
            if (delay <= 1) fastHits++;
            if (delay == 0) zeroHits++;
        }
        hasCompletedTime = true;
        lastCompletedNanos = nowNanos;
        if (completed < WINDOW_EPISODES) return null;

        boolean passing = censored == 0 && hits >= MIN_HITS && fastHits >= MIN_FAST_HITS
                && zeroHits >= MIN_ZERO_HITS && Integer.bitCount(gapMask) >= 3
                && tick - windowFirstTick >= MIN_WINDOW_TICKS
                && nowNanos - windowFirstNanos <= WINDOW_LIFETIME_NANOS;
        Window current = new Window(hits, fastHits, zeroHits, windowFirstTick, nowNanos);
        clearWindow();
        if (!passing) {
            support = null;
            return null;
        }
        if (support == null) {
            support = current;
            return null;
        }
        Evidence result = new Evidence(WINDOW_EPISODES * 2, support.hits() + current.hits(),
                support.fastHits() + current.fastHits(), support.zeroHits() + current.zeroHits(),
                tick - support.firstTick());
        support = null; // The next evidence requires another two completely fresh windows.
        return result;
    }

    private void clearWindow() {
        completed = hits = fastHits = zeroHits = censored = gapMask = 0;
        windowFirstTick = windowFirstNanos = windowLastEntryTick = 0;
    }

    private record Window(int hits, int fastHits, int zeroHits, long firstTick, long completedNanos) {
    }
}
