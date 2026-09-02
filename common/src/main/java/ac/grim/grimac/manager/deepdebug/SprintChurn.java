package ac.grim.grimac.manager.deepdebug;

import org.jetbrains.annotations.Nullable;

/**
 * Sprint input churn statistics for the auto-sprint / ToggleSprint heuristic.
 * Fed from the deep-debug packet listener with every observed sprint state
 * (ENTITY_ACTION toggles pre-1.21.2, the PLAYER_INPUT sprint bit on 1.21.2+);
 * only actual state transitions are counted, so a constant input stream does
 * not inflate the numbers. A vanilla player toggles sprint rarely; a
 * ToggleSprint mod keeps sprint re-enabled instantly after the game or the
 * server turns it off, which shows up as fast re-enables and a high toggle
 * rate.
 */
public final class SprintChurn {
    /** A re-enable within this window of a stop is suspiciously fast for a human. */
    public static final long FAST_RE_ENABLE_WINDOW_MS = 250;

    private long starts;
    private long stops;
    private long fastReEnables;
    private @Nullable Boolean lastSprint;
    private long lastStopMs;

    /** Netty thread only (the deep-debug packet listener). */
    public synchronized void onSprintInput(boolean sprinting, long now) {
        Boolean last = lastSprint;
        lastSprint = sprinting;
        if (last == null || last == sprinting) return;

        if (sprinting) {
            if (lastStopMs != 0 && now - lastStopMs <= FAST_RE_ENABLE_WINDOW_MS) {
                fastReEnables++;
            }
            starts++;
        } else {
            stops++;
            lastStopMs = now;
        }
    }

    public synchronized long starts() {
        return starts;
    }

    public synchronized long stops() {
        return stops;
    }

    public synchronized long fastReEnables() {
        return fastReEnables;
    }
}
