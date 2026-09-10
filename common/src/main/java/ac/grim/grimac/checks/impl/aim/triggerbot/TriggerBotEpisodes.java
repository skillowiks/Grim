package ac.grim.grimac.checks.impl.aim.triggerbot;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotStatistics.Episode;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotStatistics.Outcome;

/**
 * Single-thread-confined acquisition bookkeeping, with no player/check side effects.
 * The caller samples geometry once per client tick interval, then submits that
 * interval's attacks using the same tick counter. A delay is a difference between
 * sampled server observations, not a measurement of the player's reaction time.
 */
public final class TriggerBotEpisodes {
    public static final int MAX_TARGETS = 4;
    public static final int EXPIRE_AFTER_TICKS = 100;
    /** Observation horizon, not a suspicious-delay threshold. HIT delays are 0..11. */
    public static final int TIMEOUT_TICKS = 12;

    private final Consumer<Episode> sink;
    private final LinkedHashMap<Integer, Track> tracks = new LinkedHashMap<>(MAX_TARGETS, 0.75f, true);
    private long latestTick = -1;
    private Integer previousAttackTarget;

    public TriggerBotEpisodes(Consumer<Episode> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * An interval-end view cannot reconstruct a prior attack after the player has
     * turned away, or the intermediate geometry of attacks on multiple targets.
     * Preserve that uncertainty instead of inventing a missed opportunity.
     */
    public static Result withAttackEvidence(Result endpoint, boolean attacked, boolean multipleTargetsAttacked) {
        return endpoint == null || multipleTargetsAttacked || attacked && endpoint != Result.INSIDE
                ? Result.UNKNOWN : endpoint;
    }

    /**
     * Only consecutive definite OUTSIDE -> INSIDE samples open an episode.
     * First-seen INSIDE, uncertain crossings and skipped samples cannot do so.
     * Identity is compared by reference to reject reuse of a network entity ID.
     */
    public void observe(int targetId, Object identity, long tick, Result result, String weapon, boolean airborne) {
        if (!advance(tick)) return;
        String boundedWeapon = boundedWeapon(weapon);
        Result observation = identity == null || result == null ? Result.UNKNOWN : result;
        Track track = tracks.get(targetId);
        if (track != null && track.identity != identity) {
            finish(targetId, track, Outcome.UNKNOWN, -1);
            tracks.remove(targetId);
            if (Objects.equals(previousAttackTarget, targetId)) previousAttackTarget = null;
            track = null;
        }
        if (track == null) {
            evictIfFull();
            // A first definite OUTSIDE can arm the following tick; a first INSIDE
            // is only warmup and never retrospectively becomes an acquisition.
            tracks.put(targetId, new Track(identity, tick, observation, boundedWeapon));
            return;
        }

        if (track.lastObservedTick == tick) {
            if (track.result != observation || !track.weapon.equals(boundedWeapon)) {
                finish(targetId, track, Outcome.UNKNOWN, -1);
                track.result = Result.UNKNOWN;
                track.weapon = boundedWeapon;
            }
            return;
        }

        boolean consecutive = tick - track.lastObservedTick == 1;
        boolean sameWeapon = track.weapon.equals(boundedWeapon);
        if (!consecutive || !sameWeapon) {
            finish(targetId, track, Outcome.UNKNOWN, -1);
            track.result = Result.UNKNOWN;
        }

        if (observation == Result.UNKNOWN) {
            finish(targetId, track, Outcome.UNKNOWN, -1);
        } else if (observation == Result.OUTSIDE) {
            finish(targetId, track, Outcome.MISSED, -1);
        } else if (track.pending != null) {
            if (tick - track.pending.entryTick >= TIMEOUT_TICKS) {
                finish(targetId, track, Outcome.MISSED, -1);
            }
        } else if (track.result == Result.OUTSIDE && consecutive && sameWeapon) {
            track.pending = new Pending(tick, previousAttackTarget != null && previousAttackTarget != targetId,
                    boundedWeapon, airborne);
        }

        track.lastObservedTick = tick;
        track.result = observation;
        track.weapon = boundedWeapon;
    }

    /**
     * ATTACK denotes an observed attack request, not proof that damage was dealt.
     * No episode is inferred from an attack without definite geometry at this tick.
     * Repeated attacks while continuously aiming consume no additional episodes.
     */
    public void attack(int targetId, long tick) {
        if (!advance(tick)) return;
        Track track = tracks.get(targetId);
        if (track != null && track.pending != null) {
            if (track.lastObservedTick != tick || track.result != Result.INSIDE) {
                finish(targetId, track, Outcome.UNKNOWN, -1);
                track.result = Result.UNKNOWN;
            } else {
                long delay = tick - track.pending.entryTick;
                finish(targetId, track, delay < TIMEOUT_TICKS ? Outcome.HIT : Outcome.MISSED,
                        delay < TIMEOUT_TICKS ? (int) delay : -1);
            }
        }
        previousAttackTarget = targetId;
    }

    /** End uncertain pending opportunities, then discard all target/switch context. */
    public void invalidate() {
        for (Map.Entry<Integer, Track> entry : tracks.entrySet()) {
            finish(entry.getKey(), entry.getValue(), Outcome.UNKNOWN, -1);
        }
        tracks.clear();
        previousAttackTarget = null;
    }

    /** Unfinished opportunities at a report boundary; these are not known misses. */
    public int pendingEpisodes() {
        int count = 0;
        for (Track track : tracks.values()) if (track.pending != null) count++;
        return count;
    }

    /** Start a new observation session without exporting unfinished old episodes. */
    public void reset() {
        tracks.clear();
        previousAttackTarget = null;
        latestTick = -1;
    }

    private boolean advance(long tick) {
        if (tick < 0 || tick < latestTick) {
            invalidate();
            return false;
        }
        latestTick = tick;
        Iterator<Map.Entry<Integer, Track>> iterator = tracks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Track> entry = iterator.next();
            if (tick - entry.getValue().lastObservedTick >= EXPIRE_AFTER_TICKS) {
                finish(entry.getKey(), entry.getValue(), Outcome.UNKNOWN, -1);
                if (Objects.equals(previousAttackTarget, entry.getKey())) previousAttackTarget = null;
                iterator.remove();
            }
        }
        return true;
    }

    private void evictIfFull() {
        if (tracks.size() < MAX_TARGETS) return;
        Iterator<Map.Entry<Integer, Track>> iterator = tracks.entrySet().iterator();
        Map.Entry<Integer, Track> oldest = iterator.next();
        finish(oldest.getKey(), oldest.getValue(), Outcome.UNKNOWN, -1);
        if (Objects.equals(previousAttackTarget, oldest.getKey())) previousAttackTarget = null;
        iterator.remove();
    }

    private void finish(int targetId, Track track, Outcome outcome, int delay) {
        Pending pending = track.pending;
        if (pending == null) return;
        track.pending = null;
        sink.accept(new Episode(outcome, targetId, pending.entryTick, delay,
                pending.switchedTarget, pending.weapon, pending.airborne));
    }

    private static String boundedWeapon(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        StringBuilder bounded = new StringBuilder(Math.min(TriggerBotStatistics.MAX_WEAPON_LENGTH, value.length()));
        for (int i = 0; i < value.length() && bounded.length() < TriggerBotStatistics.MAX_WEAPON_LENGTH; i++) {
            char character = value.charAt(i);
            bounded.append(character >= 32 && character <= 126 ? character : '?');
        }
        return bounded.toString();
    }

    private static final class Track {
        private final Object identity;
        private long lastObservedTick;
        private Result result;
        private String weapon;
        private Pending pending;

        private Track(Object identity, long lastObservedTick, Result result, String weapon) {
            this.identity = identity;
            this.lastObservedTick = lastObservedTick;
            this.result = result;
            this.weapon = weapon;
        }
    }

    private record Pending(long entryTick, boolean switchedTarget, String weapon, boolean airborne) {
    }
}
