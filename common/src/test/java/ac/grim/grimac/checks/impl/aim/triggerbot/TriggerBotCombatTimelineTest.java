package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure integration of the observer's pre-attack/end-tick ordering and combat
 * model. Geometry is an explicit scenario input, not reconstructed world data.
 * Motion is a normal flat-floor jump at 20 ticks/second without external forces.
 */
class TriggerBotCombatTimelineTest {
    @Test
    void realJumpAndCooldownTimelineSupportsVariedAcquisitionAndFallingWindows() {
        Timeline timeline = new Timeline(false);
        timeline.train();
        assertTrue(timeline.detector.snapshot().trained());
        assertEquals(12, timeline.attacks);
        assertTrue(timeline.detector.snapshot().profiles().stream().allMatch(p -> p.completedEpisodes() == 0));

        int[] gaps = {18, 32, 62, 90};
        for (int i = 0; i < 32; i++) timeline.episode(i % 2 == 0, gaps[i % gaps.length]);
        assertTrue(timeline.evidence.isEmpty(), "one completed window cannot signal");
        assertTrue(timeline.detector.snapshot().profiles().stream().anyMatch(TriggerBotOpportunityDetector.ProfileSnapshot::supportWindow));
        for (int i = 0; i < 32; i++) timeline.episode(i % 2 == 0, gaps[i % gaps.length]);

        assertEquals(1, timeline.evidence.size());
        var evidence = timeline.evidence.get(0);
        assertEquals("mixed-acquisition", evidence.family());
        assertEquals(64, evidence.hits());
        assertEquals(32, evidence.acquisitionOpportunities());
        assertEquals(32, evidence.fallingOpportunities());
        assertEquals(0, evidence.cooldownOpportunities());
        assertTrue(timeline.attackCooldowns.stream().allMatch(cooldown -> Math.abs(cooldown - 1) < 1.0E-12));
    }

    @Test
    void fixedFullJumpOnlyCritWithRealSprintReleaseAndRestoreNeverSignals() {
        Timeline timeline = new Timeline(true);
        // Jump period is twelve ticks on a flat floor. Attack at the first
        // pre-movement descending phase every jump; the tracker computes actual
        // sword recharge from accepted attacks instead of a fabricated ready bit.
        for (int i = 0; i < 1008; i++) {
            int jumpPhase = i % 12 + 1;
            timeline.step(INSIDE, jumpPhase == 7, true, true);
        }
        assertEquals(84, timeline.attacks);
        assertTrue(timeline.detector.snapshot().trained());
        assertTrue(timeline.evidence.isEmpty());
        assertTrue(timeline.releasedAttackSnapshots > 70, "real forward/sprint edges reach the model");
        assertTrue(timeline.restoredSnapshots > 70, "the release/restore control was exercised");
        assertTrue(timeline.detector.snapshot().profiles().stream().anyMatch(p -> p.cooldownOpportunities() > 0),
                "full cooldown crosses after falling starts on a repeating jump");
        assertTrue(timeline.detector.snapshot().profiles().stream().allMatch(p -> p.acquisitionOpportunities() == 0));
        assertTrue(timeline.attackCooldowns.stream().allMatch(cooldown -> Math.abs(cooldown - 1) < 1.0E-12));
    }

    @Test
    void preAttackSnapshotUsesPreviousCompletedPredictionAndPreResetCooldown() {
        Timeline timeline = new Timeline(false);
        for (int phase = 1; phase <= 6; phase++) timeline.step(INSIDE, false, true, true);
        assertTrue(timeline.lastSnapshot.descending(), "end of phase six predicts the first descending motion");
        timeline.step(INSIDE, true, true, true);
        assertTrue(timeline.lastSnapshot.validMotion(), "previous checked position is one interval old at the attack");
        assertTrue(timeline.lastSnapshot.descending());
        assertEquals(1, timeline.lastSnapshot.cooldownProgress(), 1.0E-12);
        assertEquals(0.04, timeline.tracker.snapshot(timeline.tick, false, true, false, 1.6, 1).cooldownProgress(), 1.0E-12,
                "the same interval's accepted attack reset must not replace its captured cooldown");
        timeline.step(INSIDE, false, true, true);
        assertEquals(0.12, timeline.lastSnapshot.cooldownProgress(), 1.0E-12);
    }

    @Test
    void missingPositionPredictionsCensorAnOpenOpportunityInsteadOfReusingFallingState() {
        Timeline timeline = new Timeline(false);
        timeline.train();
        timeline.idle(20);
        for (int phase = 1; phase <= 6; phase++) timeline.step(INSIDE, false, true, true);
        assertTrue(timeline.detector.snapshot().profiles().stream().anyMatch(TriggerBotOpportunityDetector.ProfileSnapshot::pending));
        timeline.step(INSIDE, false, true, false);
        assertTrue(timeline.lastSnapshot.validMotion(), "the allowed one-tick position age is explicit");
        timeline.step(INSIDE, false, true, false);
        assertFalse(timeline.lastSnapshot.validMotion());
        timeline.step(INSIDE, true, true, false);
        assertFalse(timeline.lastSnapshot.validMotion(), "an attack cannot refresh physics");
        assertTrue(timeline.detector.snapshot().profiles().stream().allMatch(p -> p.censored() == 1 && p.hits() == 0));
        assertTrue(timeline.evidence.isEmpty());
    }

    @Test
    void skippedObserverFramesInvalidateTheOldOpportunityDespiteFreshResumedPhysics() {
        Timeline timeline = new Timeline(false);
        timeline.train();
        timeline.idle(20);
        for (int phase = 1; phase <= 6; phase++) timeline.step(INSIDE, false, true, true);
        assertTrue(timeline.detector.snapshot().profiles().stream().anyMatch(TriggerBotOpportunityDetector.ProfileSnapshot::pending));
        timeline.skipUnobservedTicks(2, true);
        timeline.step(INSIDE, true, true, true);
        assertFalse(timeline.lastSnapshot.validMotion(), "pre-attack state is stale before the resumed prediction");
        assertTrue(timeline.detector.snapshot().profiles().stream().allMatch(p -> p.censored() == 1 && p.hits() == 0));
        assertTrue(timeline.evidence.isEmpty());
    }

    private static final class Timeline {
        final TriggerBotCombatTracker tracker = new TriggerBotCombatTracker();
        final TriggerBotOpportunityDetector detector = new TriggerBotOpportunityDetector();
        final Object target = new Object();
        final List<TriggerBotOpportunityDetector.Evidence> evidence = new ArrayList<>();
        final List<Double> attackCooldowns = new ArrayList<>();
        final boolean resprint;
        long tick, lastAttack = -1000;
        int attacks, releasedAttackSnapshots, restoredSnapshots;
        double physicalY, nextPhysicalVelocity = -0.0784;
        boolean physicalGrounded = true;
        double observedY, observedNextVelocity = -0.0784;
        boolean observedGrounded = true, sprinting;
        TriggerBotCombatTracker.Snapshot lastSnapshot;

        Timeline(boolean resprint) {
            this.resprint = resprint;
            sprinting = resprint;
            tracker.input(0, resprint);
            tracker.sprint(0, resprint);
        }

        void train() {
            for (int i = 0; i < 12; i++) episode(false, 24);
        }

        void episode(boolean acquisition, int attackGap) {
            int attackPhase = acquisition ? 9 : 7;
            long attackTick = Math.max(tick + attackPhase + 1, lastAttack + attackGap);
            long startTick = attackTick - attackPhase + 1;
            while (tick + 1 < startTick) step(OUTSIDE, false, false, true);
            assertTrue(physicalGrounded, "each scheduled opportunity starts from a completed flat-floor jump");
            for (int phase = 1; phase <= 12; phase++) {
                Result geometry = acquisition && phase < attackPhase ? OUTSIDE : INSIDE;
                step(geometry, phase == attackPhase, true, true);
            }
            assertTrue(physicalGrounded);
        }

        void idle(int ticks) {
            for (int i = 0; i < ticks; i++) step(OUTSIDE, false, false, true);
        }

        void skipUnobservedTicks(int ticks, boolean jump) {
            for (int i = 0; i < ticks; i++) {
                tick++;
                advancePhysics(jump);
            }
        }

        void step(Result geometry, boolean attack, boolean jump, boolean acceptedPosition) {
            tick++;
            if (resprint && tick == lastAttack + 1) {
                tracker.input(tick, true);
                tracker.sprint(tick, true);
                sprinting = true;
            }
            if (resprint && attack) {
                tracker.input(tick, false);
                tracker.sprint(tick, false);
                sprinting = false;
            }
            TriggerBotCombatTracker.Snapshot preAttack = attack ? snapshot(jump) : null;
            boolean preLanding = attack && landingSoon(preAttack);
            if (attack) {
                attacks++;
                lastAttack = tick;
                attackCooldowns.add(preAttack.cooldownProgress());
                if (preAttack.sprintReleased()) releasedAttackSnapshots++;
                tracker.swing(tick); // Accepted attack, after the immutable snapshot.
            }

            advancePhysics(jump);
            if (acceptedPosition) {
                observedY = physicalY;
                observedGrounded = physicalGrounded;
                observedNextVelocity = nextPhysicalVelocity;
                tracker.movement(tick, observedGrounded, observedNextVelocity, true);
            }
            tracker.endTick(tick, observedGrounded, observedY);
            lastSnapshot = attack ? preAttack : snapshot(jump);
            if (lastSnapshot.forwardRestored()) restoredSnapshots++;
            if (!lastSnapshot.validMotion()) {
                detector.invalidate(tick, tick * 50_000_000L);
                return;
            }
            var frame = new TriggerBotOpportunityDetector.Frame(tick, tick * 50_000_000L,
                    target, "minecraft:diamond_sword", 1.6, geometry, true, attack, true,
                    lastSnapshot.cooldownProgress(), lastSnapshot.grounded(), lastSnapshot.descending(), jump,
                    lastSnapshot.groundTicks(), lastSnapshot.sprinting(), lastSnapshot.sprintReleased(),
                    attack ? preLanding : landingSoon(lastSnapshot), lastSnapshot.forwardHeld(), lastSnapshot.forwardRestored());
            var signal = detector.accept(frame);
            if (signal != null) evidence.add(signal);
        }

        private TriggerBotCombatTracker.Snapshot snapshot(boolean jump) {
            return tracker.snapshot(tick, observedGrounded, jump, sprinting, 1.6, 1);
        }

        private void advancePhysics(boolean jump) {
            double displacement = physicalGrounded && jump ? 0.42 : nextPhysicalVelocity;
            physicalY += displacement;
            physicalGrounded = physicalY <= 0;
            if (physicalGrounded) physicalY = 0;
            nextPhysicalVelocity = physicalGrounded ? -0.0784 : (displacement - 0.08) * 0.98;
        }

        private boolean landingSoon(TriggerBotCombatTracker.Snapshot state) {
            return state.descending() && observedY + observedNextVelocity <= 0;
        }
    }
}
