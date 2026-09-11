package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class TriggerBotDetectorTest {
    private static final int[] VARIED_GAPS = {12, 30, 60, 100};
    private final Fixture f = new Fixture();

    @Test
    void twoFreshVariedAlreadyReadyWindowsProduceRawExperimentalEvidence() {
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty(), "one window is insufficient");
        f.positiveEpisodes(32);
        assertEquals(1, f.evidence.size());
        var evidence = f.evidence.get(0);
        assertEquals(64, evidence.episodes());
        assertEquals(64, evidence.hits());
        assertEquals(64, evidence.fastHits());
        assertEquals(48, evidence.zeroDelayHits());
        assertTrue(evidence.elapsedTicks() >= 800);
        f.positiveEpisodes(32);
        assertEquals(1, f.evidence.size(), "a scored window cannot be reused");
        f.positiveEpisodes(32);
        assertEquals(2, f.evidence.size());
    }

    @Test
    void vanillaSwordCooldownStillAllowsRealisticGroundedReacquisitionEvidence() {
        int[] gaps = {18, 32, 62, 90};
        long previousAttack = 0; // The earlier attack established the tracked player.
        for (int i = 0; i < 64; i++) {
            long entry = Math.max(f.tick + 4, f.lastEntry + gaps[i % gaps.length]);
            while (f.tick + 1 < entry) {
                // 1.6 attack speed takes 12.5 ticks to recharge; wait a full 13
                // before arming. The adapter skips geometry while recharging.
                if (f.tick + 1 - previousAttack < 13) f.excludeNext();
                else f.send(OUTSIDE, true, true, false, true);
            }
            f.lastEntry = entry;
            int delay = i % 4 == 0 ? 1 : 0;
            for (int elapsed = 0; elapsed <= delay; elapsed++) {
                f.send(INSIDE, true, true, elapsed == delay, true);
            }
            previousAttack = f.tick;
            if (i == 31) assertTrue(f.evidence.isEmpty(), "the first realistic window only supplies support");
        }
        assertEquals(1, f.evidence.size());
        assertEquals(64, f.evidence.get(0).hits());
        assertEquals(48, f.evidence.get(0).zeroDelayHits());
    }

    @Test
    void normalVariedClicksAndFixedCooldownPacingDoNotPass() {
        for (int i = 0; i < 128; i++) f.episode(VARIED_GAPS[i % 4], i % 8);
        assertTrue(f.evidence.isEmpty());
        f.detector.reset();
        for (int i = 0; i < 128; i++) f.episode(30, 0);
        assertTrue(f.evidence.isEmpty(), "consistent cooldown spacing is not acquisition evidence");
    }

    @Test
    void allOneTickDelaysHaveInsufficientZeroTickSupport() {
        for (int i = 0; i < 96; i++) f.episode(VARIED_GAPS[i % 4], 1);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void evenDiverseFastWindowsNeedEnoughElapsedObservationTicks() {
        for (int window = 0; window < 4; window++) {
            for (int i = 0; i < 32; i++) f.episode(i == 1 ? 22 : i == 2 ? 42 : 10, 0);
        }
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void firstSeenInsideAndContinuousSpamNeverOpenEpisodes() {
        for (int i = 0; i < 5_000; i++) f.send(INSIDE, true, true, true, true);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void newlyReadyEntriesAndUnsupportedAirborneContextDoNotPass() {
        for (int i = 0; i < 96; i++) {
            f.outsideToEntry(VARIED_GAPS[i % 4], false, true);
            f.send(INSIDE, true, true, true, true);
        }
        assertTrue(f.evidence.isEmpty(), "readiness at entry cannot replace preceding ready OUTSIDE samples");
        for (int i = 0; i < 96; i++) {
            f.outsideToEntry(VARIED_GAPS[i % 4], true, false);
            // The caller represents airborne/critical-hit dependent play as invalid context.
            f.send(INSIDE, false, true, true, true);
        }
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void unknownEpisodesCannotBeSelectedAwayFromTheWindow() {
        for (int i = 0; i < 96; i++) {
            f.outsideToEntry(VARIED_GAPS[i % 4], true, true);
            f.send(INSIDE, true, true, false, true);
            f.send(UNKNOWN, true, true, false, true);
        }
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void oneCensoredOpportunityMakesAnOtherwisePassingWindowFail() {
        f.positiveEpisodes(32);
        f.positiveEpisodes(31);
        f.outsideToEntry(60, true, true);
        f.send(INSIDE, true, true, false, true);
        f.excludeNext();
        assertTrue(f.evidence.isEmpty());
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty(), "the failed window clears the older passing support");
        f.positiveEpisodes(32);
        assertEquals(1, f.evidence.size());
    }

    @Test
    void invalidPreAttackStateAndPendingReadinessLossAreCensored() {
        for (int i = 0; i < 96; i++) {
            f.outsideToEntry(VARIED_GAPS[i % 4], true, true);
            f.send(INSIDE, true, true, true, false);
        }
        assertTrue(f.evidence.isEmpty());
        for (int i = 0; i < 96; i++) {
            f.outsideToEntry(VARIED_GAPS[i % 4], true, true);
            f.send(INSIDE, true, false, true, true);
        }
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void contextMustRemainSupportedUntilTheAttackAndAcrossConsecutiveSamples() {
        for (int i = 0; i < 96; i++) {
            f.outsideToEntry(VARIED_GAPS[i % 4], true, true);
            f.send(INSIDE, true, true, false, true);
            f.send(INSIDE, false, true, true, true);
        }
        assertTrue(f.evidence.isEmpty(), "an airborne or unsupported transition censors the pending hit");
        for (int i = 0; i < 96; i++) {
            f.outsideToEntry(VARIED_GAPS[i % 4], true, true);
            f.tick += 2;
            f.now += 100_000_000L;
            f.send(INSIDE, true, true, true, true);
        }
        assertTrue(f.evidence.isEmpty(), "a gap in frame coverage cannot establish an acquisition");
    }

    @Test
    void missesAndTimedOutOpportunitiesCountAgainstFreshWindows() {
        f.positiveEpisodes(32);
        for (int i = 0; i < 32; i++) {
            f.outsideToEntry(VARIED_GAPS[i % 4], true, true);
            for (int delay = 0; delay <= 12; delay++) f.send(INSIDE, true, true, false, true);
            // A later request must not turn the completed timeout into a new hit.
            f.send(INSIDE, true, true, true, true);
        }
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void jitterCrossingsAndTooShortOutsideRunsCannotBuildEvidence() {
        for (int i = 0; i < 256; i++) {
            for (int outside = 0; outside < 3; outside++) f.send(OUTSIDE, true, true, false, true);
            f.send(INSIDE, true, true, true, true);
        }
        assertTrue(f.evidence.isEmpty());
        for (int i = 0; i < 256; i++) {
            for (int outside = 0; outside < 2; outside++) f.send(OUTSIDE, true, true, false, true);
            f.send(INSIDE, true, true, true, true);
        }
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void ordinaryCooldownExclusionsBetweenEpisodesPreserveFreshEvidence() {
        for (int i = 0; i < 64; i++) {
            f.episode(VARIED_GAPS[i % 4], i % 4 == 0 ? 1 : 0);
            f.excludeNext();
        }
        assertEquals(1, f.evidence.size());
    }

    @Test
    void idleSupportAndPartialWindowsExpire() {
        f.positiveEpisodes(32);
        f.now += 121_000_000_000L;
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty(), "idle first-window support expired");
        f.detector.reset();
        f.positiveEpisodes(31);
        f.now += 121_000_000_000L;
        f.positiveEpisodes(33);
        assertTrue(f.evidence.isEmpty(), "expired partial episodes cannot complete a stale window");
    }

    @Test
    void targetIdentityWeaponAndAttackSpeedChangesResetAccumulation() {
        f.positiveEpisodes(32);
        f.identity = new Object();
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
        f.weapon = "minecraft:diamond_axe";
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
        f.attackSpeed = 1;
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
        f.positiveEpisodes(32);
        assertEquals(1, f.evidence.size());
    }

    @Test
    void repeatedOrOutOfOrderFramesCannotReuseEvidence() {
        f.positiveEpisodes(32);
        var repeated = f.lastFrame;
        for (int i = 0; i < 100; i++) assertNull(f.detector.accept(repeated));
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
        assertNull(f.detector.accept(new TriggerBotDetector.Frame(f.tick - 1, f.now, f.identity,
                f.weapon, f.attackSpeed, INSIDE, true, true, true, true)));
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void explicitResetAndInvalidClockOrAttributesDiscardOldSupport() {
        f.positiveEpisodes(32);
        f.detector.reset();
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
        assertNull(f.detector.accept(new TriggerBotDetector.Frame(f.tick + 1, f.now - 1, f.identity,
                f.weapon, f.attackSpeed, INSIDE, true, true, true, true)));
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
        assertNull(f.detector.accept(new TriggerBotDetector.Frame(f.tick + 1, f.now, f.identity,
                f.weapon, Double.NaN, INSIDE, true, true, true, true)));
        f.positiveEpisodes(32);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void diagnosticSnapshotsAreImmutableReadsAndShowArmingPendingAndCensoring() {
        var initial = f.detector.snapshot();
        assertEquals(new TriggerBotDetector.Snapshot(0, 0, 0, 0, 0, 0, 0, false, false), initial);
        f.outsideToEntry(18, true, true);
        var armed = f.detector.snapshot();
        assertEquals(3, armed.outsideStreak());
        assertFalse(armed.pending());
        for (int i = 0; i < 100; i++) assertEquals(armed, f.detector.snapshot());
        f.send(INSIDE, true, true, false, true);
        var pending = f.detector.snapshot();
        assertTrue(pending.pending());
        assertEquals(0, pending.completedEpisodes());
        assertEquals(0, pending.outsideStreak());
        f.excludeNext();
        var censored = f.detector.snapshot();
        assertEquals(1, censored.completedEpisodes());
        assertEquals(1, censored.censored());
        assertEquals(0, censored.hits());
        assertFalse(censored.pending());
        assertEquals(0, initial.completedEpisodes());
        assertEquals(3, armed.outsideStreak());
        assertTrue(pending.pending(), "previous snapshots cannot change with live detector state");
    }

    @Test
    void diagnosticSnapshotsTrackFreshWindowsSupportConsumptionAndReset() {
        f.positiveEpisodes(31);
        var partial = f.detector.snapshot();
        assertEquals(31, partial.completedEpisodes());
        assertEquals(31, partial.hits());
        assertEquals(31, partial.fastHits());
        assertEquals(23, partial.zeroDelayHits());
        assertEquals(4, partial.gapBuckets());
        assertFalse(partial.supportWindow());
        f.episode(100, 0);
        var supported = f.detector.snapshot();
        assertEquals(0, supported.completedEpisodes());
        assertTrue(supported.supportWindow());
        f.positiveEpisodes(1);
        var next = f.detector.snapshot();
        assertEquals(1, next.completedEpisodes());
        assertTrue(next.supportWindow());
        // Repeated report reads cannot promote an incomplete second window.
        for (int i = 0; i < 100; i++) assertEquals(next, f.detector.snapshot());
        assertTrue(f.evidence.isEmpty());
        for (int i = 1; i < 32; i++) f.episode(VARIED_GAPS[i % 4], i % 4 == 0 ? 1 : 0);
        assertEquals(1, f.evidence.size());
        assertFalse(f.detector.snapshot().supportWindow());
        assertEquals(0, f.detector.snapshot().completedEpisodes());
        f.positiveEpisodes(1);
        f.detector.reset();
        assertEquals(new TriggerBotDetector.Snapshot(0, 0, 0, 0, 0, 0, 0, false, false), f.detector.snapshot());
        assertEquals(31, partial.completedEpisodes());
        assertTrue(supported.supportWindow());
    }

    private static final class Fixture {
        private final TriggerBotDetector detector = new TriggerBotDetector();
        private final List<TriggerBotDetector.Evidence> evidence = new ArrayList<>();
        private Object identity = new Object();
        private String weapon = "minecraft:diamond_sword";
        private double attackSpeed = 1.6;
        private long tick = -1, now, lastEntry = -1;
        private TriggerBotDetector.Frame lastFrame;

        private void positiveEpisodes(int count) {
            for (int i = 0; i < count; i++) episode(VARIED_GAPS[i % 4], i % 4 == 0 ? 1 : 0);
        }

        private void episode(int gap, int delay) {
            outsideToEntry(gap, true, true);
            for (int elapsed = 0; elapsed <= delay; elapsed++) {
                send(INSIDE, true, true, elapsed == delay, true);
            }
        }

        private void outsideToEntry(int gap, boolean ready, boolean context) {
            long entry = Math.max(tick + 4, lastEntry + gap);
            while (tick + 1 < entry) send(OUTSIDE, context, ready, false, true);
            lastEntry = entry;
        }

        private void send(Result geometry, boolean context, boolean ready, boolean attacked, boolean attackValid) {
            tick++;
            now += 50_000_000L;
            lastFrame = new TriggerBotDetector.Frame(tick, now, identity, weapon, attackSpeed,
                    geometry, context, ready, attacked, attackValid);
            var result = detector.accept(lastFrame);
            if (result != null) evidence.add(result);
        }

        private void excludeNext() {
            tick++;
            now += 50_000_000L;
            detector.invalidate(tick, now);
        }
    }
}
