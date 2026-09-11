package ac.grim.grimac.checks.impl.aim.triggerbot;

import java.util.ArrayList;
import java.util.List;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result;

/**
 * Experimental source-informed opportunity observations, independent of player,
 * packet and punishment state. Cooldown and motion are sampled estimates: the
 * two-tick response allowance is not a measurement of human reaction time.
 * Environmental exceptions and uncertain motion must be excluded by the caller.
 * Fixed bounds and thresholds are uncalibrated policy, not a cheating probability.
 * In particular, periodic critical hits and sprint resets alone cannot qualify.
 */
public final class TriggerBotOpportunityDetector {
    private static final int TRAINING_ATTACKS = 12;
    private static final int WINDOW_SIZE = 32;
    private static final int RESPONSE_TICKS = 2;
    private static final long WINDOW_NANOS = 120_000_000_000L;
    private static final long SUPPORT_NANOS = 300_000_000_000L;

    public enum Mode { SMART, ONLY_CRIT }
    private enum Blocker { ACQUISITION, FALLING, COOLDOWN, UNKNOWN }

    /** sprintReleased means an observed STOP plus actual forward true-to-false transition. */
    public record Frame(long tick, long nowNanos, Object targetIdentity, String weapon, double attackSpeed,
                        Result geometry, boolean contextValid, boolean attacked, boolean attackValid,
                        double cooldownProgress, boolean grounded, boolean descending, boolean jumpInput,
                        int groundTicks, boolean sprinting, boolean sprintReleased, boolean landingSoon,
                        boolean forwardHeld, boolean forwardRestored) {
    }

    public record Profile(String name, Mode mode, double airborneThreshold, double groundedThreshold) {
    }

    public record Evidence(Profile profile, int episodes, int hits, int promptHits,
                           int acquisitionOpportunities, int fallingOpportunities, int cooldownOpportunities,
                           int sprintCorroboratedHits, int queuedOpportunities, int controlOpportunities,
                           int promptControlHits, long elapsedTicks, String family) {
    }

    public record ProfileSnapshot(Profile profile, int completedEpisodes, int hits, int promptHits, int censored,
                                  int acquisitionOpportunities, int fallingOpportunities, int cooldownOpportunities,
                                  int sprintCorroboratedHits, int queuedOpportunities, int controlOpportunities,
                                  int promptControlHits, int gapBuckets, boolean pending, boolean supportWindow) {
    }

    public record Snapshot(int trainingAttacks, boolean trained, List<ProfileSnapshot> profiles) {
        public Snapshot { profiles = List.copyOf(profiles); }
    }

    private Object identity;
    private String weapon;
    private double attackSpeed;
    private int trainingAttacks;
    private double trainingFloor = 1;
    private final List<Model> models = new ArrayList<>(6);
    private boolean clockInitialized;
    private long latestTick, latestNanos;

    /** Copy on the owning packet thread; immutable snapshots can be formatted elsewhere. */
    public Snapshot snapshot() {
        List<ProfileSnapshot> copy = new ArrayList<>(models.size());
        for (Model model : models) copy.add(model.snapshot());
        return new Snapshot(trainingAttacks, trainingAttacks == TRAINING_ATTACKS, copy);
    }

    public Evidence accept(Frame frame) {
        if (frame == null || frame.targetIdentity() == null || frame.weapon() == null
                || frame.weapon().isEmpty() || frame.weapon().length() > 64
                || !Double.isFinite(frame.attackSpeed()) || frame.attackSpeed() <= 0) {
            reset();
            return null;
        }
        if (identity != null && (identity != frame.targetIdentity() || !weapon.equals(frame.weapon())
                || Double.compare(attackSpeed, frame.attackSpeed()) != 0)) reset();
        identity = frame.targetIdentity();
        weapon = frame.weapon();
        attackSpeed = frame.attackSpeed();
        if (!advance(frame.tick(), frame.nowNanos())) return null;
        if (!frame.contextValid() || frame.geometry() == null
                || !Double.isFinite(frame.cooldownProgress()) || frame.cooldownProgress() < 0
                || frame.attacked() && (!frame.attackValid() || frame.geometry() == Result.OUTSIDE)) {
            interrupt(frame.tick(), frame.nowNanos());
            return null;
        }

        if (trainingAttacks < TRAINING_ATTACKS) {
            if (frame.attacked()) {
                // UNKNOWN geometry may estimate a cooldown hypothesis, but it
                // is never scored as an opportunity or successful response.
                trainingFloor = Math.min(trainingFloor, Math.min(1, frame.cooldownProgress()));
                if (++trainingAttacks == TRAINING_ATTACKS) freezeProfiles();
            }
            return null; // Training attacks never contribute to evaluated windows.
        }
        if (frame.geometry() == Result.UNKNOWN) {
            interrupt(frame.tick(), frame.nowNanos());
            return null;
        }
        Evidence evidence = null;
        for (Model model : models) {
            Evidence result = model.accept(frame);
            if (evidence == null && result != null) evidence = result;
        }
        if (evidence != null) {
            // Correlated profiles cannot reuse these same opportunities for another alert.
            for (Model model : models) model.clearEvidence();
        }
        return evidence;
    }

    public void invalidate(long tick, long nowNanos) {
        if (advance(tick, nowNanos)) interrupt(tick, nowNanos);
    }

    public void reset() {
        identity = null;
        weapon = null;
        attackSpeed = 0;
        trainingAttacks = 0;
        trainingFloor = 1;
        models.clear();
        clockInitialized = false;
    }

    private boolean advance(long tick, long nowNanos) {
        if (tick < 0 || clockInitialized && (tick <= latestTick || nowNanos - latestNanos < 0)) {
            reset();
            return false;
        }
        for (Model model : models) model.expire(nowNanos);
        if (clockInitialized && tick - latestTick != 1) interrupt(tick, nowNanos);
        clockInitialized = true;
        latestTick = tick;
        latestNanos = nowNanos;
        return true;
    }

    private void interrupt(long tick, long nowNanos) {
        for (Model model : models) model.interrupt(tick, nowNanos);
    }

    private void freezeProfiles() {
        if (weapon.endsWith("mace")) {
            addProfile(new Profile("mace-source", Mode.ONLY_CRIT, 0.4, 0.4));
            addProfile(new Profile("mace-full", Mode.ONLY_CRIT, 1, 1));
            double maceFloor = Math.max(0.4, trainingFloor);
            addProfile(new Profile("mace-trained-tps-floor", Mode.ONLY_CRIT, maceFloor, maceFloor));
            return; // TPS can raise readiness; the source's custom slider cannot change mace's floor.
        }
        boolean slowWeapon = weapon.endsWith("_axe") || weapon.endsWith(":trident");
        for (Mode mode : Mode.values()) {
            addProfile(new Profile("source", mode, slowWeapon ? 0.85 : 0.75, slowWeapon ? 0.95 : 0.8));
            addProfile(new Profile("full", mode, 1, 1));
            // Frozen empirical attack floor, not a claim to know the client's setting.
            // The next windows validate it prospectively; no fitting to scored data.
            addProfile(new Profile("trained-floor", mode, trainingFloor, trainingFloor));
        }
    }

    private void addProfile(Profile profile) {
        for (Model model : models) {
            if (model.profile.mode() == profile.mode()
                    && model.profile.airborneThreshold() == profile.airborneThreshold()
                    && model.profile.groundedThreshold() == profile.groundedThreshold()) return;
        }
        models.add(new Model(profile));
    }

    private static final class Pending {
        final long tick, nanos;
        final Blocker blocker;
        final boolean queued, control;
        boolean released, restored;
        long hitTick = -1;
        int delay = -1;

        Pending(Frame frame, Blocker blocker, boolean queued, boolean control) {
            tick = frame.tick();
            nanos = frame.nowNanos();
            this.blocker = blocker;
            this.queued = queued;
            this.control = control;
            released = queued && frame.sprintReleased();
            restored = released && frame.forwardRestored();
        }
    }

    private static final class Counts {
        int completed, hits, prompt, censored, acquisition, falling, cooldown, corroborated, queued, controls, promptControls, gapMask;
        long firstTick, firstNanos, lastEntryTick;
    }

    private record Support(Counts counts, long endNanos, String family) {
    }

    private static final class Model {
        final Profile profile;
        Counts window = new Counts();
        Support support;
        Pending pending;
        boolean hasPrevious, previousInside, previousPhase, previousCooled, previousSprinting, previousForward;
        int outsideReadyStreak;
        long lastCompletedNanos;

        Model(Profile profile) { this.profile = profile; }

        ProfileSnapshot snapshot() {
            Counts c = window;
            return new ProfileSnapshot(profile, c.completed, c.hits, c.prompt, c.censored, c.acquisition,
                    c.falling, c.cooldown, c.corroborated, c.queued, c.controls, c.promptControls,
                    Integer.bitCount(c.gapMask), pending != null, support != null);
        }

        Evidence accept(Frame frame) {
            boolean inside = frame.geometry() == Result.INSIDE;
            boolean phase = !frame.landingSoon() && (!frame.grounded() && frame.descending()
                    || profile.mode() == Mode.SMART && frame.grounded() && !frame.jumpInput() && frame.groundTicks() >= 5);
            double threshold = frame.grounded() ? profile.groundedThreshold() : profile.airborneThreshold();
            boolean cooled = frame.cooldownProgress() + 1.0E-7 >= threshold;
            boolean available = inside && phase && cooled;
            if (frame.attacked() && !available) {
                // A valid observed request outside this hypothesis must not be
                // selected away while older matching evidence is retained. This
                // only rejects a model; it does not claim exact client readiness.
                clearEvidence();
                return null;
            }
            Evidence result = null;

            if (pending != null && pending.hitTick >= 0) {
                if (pending.released && frame.forwardRestored() && frame.tick() - pending.hitTick <= RESPONSE_TICKS) pending.restored = true;
                if (pending.restored || frame.tick() - pending.hitTick >= RESPONSE_TICKS) result = finish(frame, false);
            }
            if (pending == null && hasPrevious && available && !(previousInside && previousPhase && previousCooled)) {
                Blocker blocker = !previousInside && previousPhase && previousCooled && outsideReadyStreak >= 3
                        ? Blocker.ACQUISITION
                        : previousInside && !previousPhase && previousCooled && !frame.grounded() && frame.descending()
                        ? Blocker.FALLING
                        : previousInside && previousPhase && !previousCooled ? Blocker.COOLDOWN : Blocker.UNKNOWN;
                boolean queued = !frame.grounded() && frame.descending()
                        && (frame.sprinting() && frame.forwardHeld()
                        || previousSprinting && previousForward && frame.sprintReleased());
                boolean control = !frame.sprinting() && !previousSprinting && !frame.sprintReleased();
                pending = new Pending(frame, blocker, queued, control);
                if (blocker == Blocker.UNKNOWN) result = first(result, finish(frame, true));
            }
            if (pending != null && pending.hitTick < 0) {
                long elapsed = frame.tick() - pending.tick;
                if (pending.queued && frame.sprintReleased() && elapsed <= RESPONSE_TICKS) pending.released = true;
                if (pending.released && frame.forwardRestored()) pending.restored = true;
                if (frame.attacked() && available && elapsed <= RESPONSE_TICKS) {
                    pending.hitTick = frame.tick();
                    pending.delay = (int) elapsed;
                    if (!pending.queued || pending.restored) result = first(result, finish(frame, false));
                } else if (!available || elapsed > RESPONSE_TICKS) {
                    result = first(result, finish(frame, false));
                }
            }
            outsideReadyStreak = !inside && phase && cooled ? Math.min(3, outsideReadyStreak + 1) : 0;
            previousInside = inside;
            previousPhase = phase;
            // Observe an actual below-threshold sample before the next cooldown
            // crossing. An attack reset alone must not manufacture a gate edge
            // for a zero/custom-low threshold that remains ready throughout.
            previousCooled = cooled;
            previousSprinting = frame.sprinting();
            previousForward = frame.forwardHeld();
            hasPrevious = true;
            return result;
        }

        void interrupt(long tick, long now) {
            if (pending != null) finish(tick, now, true);
            hasPrevious = false;
            outsideReadyStreak = 0;
        }

        void expire(long now) {
            if (support != null && (now - lastCompletedNanos > WINDOW_NANOS || now - support.endNanos() > SUPPORT_NANOS)) support = null;
            if (window.completed > 0 && now - window.firstNanos > WINDOW_NANOS) {
                window = new Counts();
                pending = null;
                hasPrevious = false;
            }
        }

        void clearEvidence() {
            window = new Counts();
            support = null;
            pending = null;
            hasPrevious = false;
            outsideReadyStreak = 0;
        }

        private static Evidence first(Evidence previous, Evidence next) {
            return previous == null ? next : previous;
        }

        private Evidence finish(Frame frame, boolean censored) { return finish(frame.tick(), frame.nowNanos(), censored); }

        private Evidence finish(long tick, long now, boolean censored) {
            Pending p = pending;
            pending = null;
            Counts c = window;
            if (c.completed == 0) {
                c.firstTick = p.tick;
                c.firstNanos = p.nanos;
            } else {
                long gap = p.tick - c.lastEntryTick;
                c.gapMask |= 1 << (gap <= 20 ? 0 : gap <= 40 ? 1 : gap <= 80 ? 2 : 3);
            }
            c.lastEntryTick = p.tick;
            c.completed++;
            if (censored) c.censored++;
            if (p.blocker == Blocker.ACQUISITION) c.acquisition++;
            else if (p.blocker == Blocker.FALLING) c.falling++;
            else if (p.blocker == Blocker.COOLDOWN) c.cooldown++;
            if (p.queued) c.queued++;
            if (p.control) c.controls++;
            if (!censored && p.hitTick >= 0) {
                c.hits++;
                if (p.delay <= RESPONSE_TICKS) {
                    c.prompt++;
                    if (p.control) c.promptControls++;
                    if (p.queued && p.released && p.restored) c.corroborated++;
                }
            }
            lastCompletedNanos = now;
            if (c.completed < WINDOW_SIZE) return null;
            boolean shared = c.censored == 0 && c.hits >= 30 && c.prompt >= 30
                    && Integer.bitCount(c.gapMask) >= 3 && tick - c.firstTick >= 400
                    && now - c.firstNanos <= WINDOW_NANOS;
            String family = c.acquisition >= 8 && (c.falling >= 8 || c.cooldown >= 8) ? "mixed-acquisition"
                    : c.falling >= 8 && c.cooldown >= 8 && c.queued >= 24 && c.corroborated >= 24
                    && c.controls >= 4 && c.promptControls >= 4 ? "readiness-control-coupling" : null;
            window = new Counts();
            if (!shared || family == null) {
                support = null;
                return null;
            }
            if (support == null || !support.family().equals(family)) {
                support = new Support(c, now, family);
                return null;
            }
            Counts old = support.counts();
            Evidence evidence = new Evidence(profile, WINDOW_SIZE * 2, old.hits + c.hits, old.prompt + c.prompt,
                    old.acquisition + c.acquisition, old.falling + c.falling, old.cooldown + c.cooldown,
                    old.corroborated + c.corroborated, old.queued + c.queued, old.controls + c.controls,
                    old.promptControls + c.promptControls, tick - old.firstTick, family);
            support = null;
            return evidence;
        }
    }
}
