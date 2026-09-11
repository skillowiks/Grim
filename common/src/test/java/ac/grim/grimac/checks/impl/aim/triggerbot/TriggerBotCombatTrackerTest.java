package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TriggerBotCombatTrackerTest {
    private final TriggerBotCombatTracker tracker = new TriggerBotCombatTracker();

    @Test
    void groundStabilityUsesTickEndYAfterLandingAndNeedsTwoConsecutiveTicks() {
        tracker.movement(10, true, -0.0784, true);
        tracker.endTick(10, true, 72);
        assertFalse(snapshot(10, true).validMotion());
        tracker.endTick(11, true, 72);
        var stable = snapshot(11, true);
        assertTrue(stable.validMotion());
        assertEquals(2, stable.groundTicks());
        assertFalse(stable.descending());
        tracker.endTick(11, true, 72);
        assertEquals(2, snapshot(11, true).groundTicks(), "duplicate end tick cannot accumulate stability");
        tracker.endTick(12, true, 72.1);
        assertFalse(snapshot(12, true).validMotion(), "a step breaks the stable run");
        tracker.endTick(14, true, 72.1);
        assertFalse(snapshot(14, true).validMotion(), "a missing tick breaks continuity");
    }

    @Test
    void airborneMotionRequiresFreshCheckedPositionAndDoesNotReuseGroundGravity() {
        tracker.movement(1, false, -0.075, true);
        assertTrue(snapshot(1, false).descending());
        assertTrue(snapshot(2, false).descending());
        assertFalse(snapshot(3, false).validMotion());
        tracker.movement(4, false, 0.03, true);
        assertTrue(snapshot(4, false).validMotion());
        assertFalse(snapshot(4, false).descending());
        tracker.movement(5, true, -0.0784, true);
        assertFalse(snapshot(5, false).validMotion(), "ground gravity is not airborne evidence");
        tracker.movement(6, false, -0.075, false);
        assertFalse(snapshot(6, false).validMotion(), "unchecked position invalidates earlier prediction");
        tracker.movement(7, false, Double.NaN, true);
        assertFalse(snapshot(7, false).validMotion());
        tracker.movement(8, false, -0.075, true);
        tracker.endTick(8, false, Double.NaN);
        assertFalse(snapshot(8, false).validMotion(), "invalid endpoint cannot certify an airborne prediction");
    }

    @Test
    void takingOffClearsStableGroundHistoryAndLandingStartsOver() {
        tracker.endTick(1, true, 72);
        tracker.endTick(2, true, 72);
        assertTrue(snapshot(2, true).validMotion());
        tracker.movement(3, false, 0.3332, true);
        assertEquals(0, snapshot(3, false).groundTicks());
        tracker.endTick(4, true, 72);
        assertFalse(snapshot(4, true).validMotion());
        assertFalse(snapshot(4, false).validMotion(), "landing invalidates the earlier flight prediction");
        tracker.endTick(5, true, 72);
        assertTrue(snapshot(5, true).validMotion());
        assertFalse(snapshot(7, true).validMotion(), "stale ground history also expires");
    }

    @Test
    void descendingRequiresTheStrictClientFloatBoundary() {
        double threshold = (double) -0.01F;
        tracker.movement(1, false, threshold, true);
        assertFalse(snapshot(1, false).descending());
        tracker.movement(2, false, Math.nextDown(threshold), true);
        assertTrue(snapshot(2, false).descending());
        tracker.movement(3, false, Math.nextUp(threshold), true);
        assertFalse(snapshot(3, false).descending());
        tracker.movement(4, false, -0.001, true);
        assertFalse(snapshot(4, false).descending());
    }

    @Test
    void corroboratedSprintReleaseAndForwardRestoreAreFreshEdges() {
        tracker.input(1, true);
        tracker.sprint(1, true);
        tracker.sprint(2, false);
        tracker.input(3, false);
        var released = snapshot(3, false);
        assertTrue(released.sprintReleased());
        assertFalse(released.forwardHeld());
        assertFalse(released.forwardRestored());
        tracker.input(4, true);
        assertTrue(snapshot(4, false).forwardRestored());
        assertFalse(snapshot(4, false).sprintReleased());
        assertTrue(snapshot(6, false).forwardRestored());
        assertFalse(snapshot(7, false).forwardRestored());
    }

    @Test
    void releaseBeforeStopAlsoWorksAndHeldSprintDoesNotCountAsReleased() {
        tracker.input(1, true);
        tracker.sprint(1, true);
        tracker.input(2, false);
        assertFalse(snapshot(2, false).sprintReleased());
        tracker.sprint(3, false);
        assertTrue(snapshot(3, false).sprintReleased());
        assertFalse(tracker.snapshot(3, false, false, true, 1.6, 1).sprintReleased());
        tracker.sprint(4, true);
        assertFalse(snapshot(4, false).sprintReleased());
    }

    @Test
    void missingInitialInputOrSprintStateNeverManufacturesReleaseOrRestore() {
        tracker.sprint(1, false);
        tracker.input(1, false);
        assertFalse(snapshot(1, false).sprintReleased());
        tracker.input(2, true);
        assertFalse(snapshot(2, false).forwardRestored());
        tracker.input(3, false);
        assertFalse(snapshot(3, false).sprintReleased(), "no observed prior sprint");
        tracker.sprint(4, true);
        tracker.sprint(5, false);
        assertFalse(snapshot(5, false).sprintReleased(), "stop and forward release are more than a tick apart");
    }

    @Test
    void repeatedStatePacketsDoNotRefreshEdgesAndResetDropsAllEvidence() {
        tracker.input(1, true);
        tracker.sprint(1, true);
        tracker.input(2, false);
        tracker.sprint(2, false);
        tracker.input(5, false);
        tracker.sprint(5, false);
        assertFalse(snapshot(5, false).sprintReleased());
        tracker.input(6, true);
        assertFalse(snapshot(6, false).forwardRestored());
        tracker.movement(6, false, -0.075, true);
        tracker.swing(6);
        tracker.reset();
        var cleared = tracker.snapshot(6, false, false, false, 1.6, 0.7);
        assertFalse(cleared.validMotion());
        assertFalse(cleared.forwardHeld());
        assertFalse(cleared.sprintReleased());
        assertFalse(cleared.forwardRestored());
        assertEquals(0.7, cleared.cooldownProgress(), 1.0E-12);
    }

    @Test
    void cooldownUsesFallbackOnlyUntilFirstAcceptedSwingAndPreservesPreAttackState() {
        assertEquals(0.8, tracker.snapshot(10, false, false, false, 1.6, 0.8).cooldownProgress(), 1.0E-12);
        tracker.swing(10);
        assertEquals(0.04, tracker.snapshot(10, false, false, false, 1.6, 1).cooldownProgress(), 1.0E-12);
        assertEquals(0.92, tracker.snapshot(21, false, false, false, 1.6, 1).cooldownProgress(), 1.0E-12);
        var beforeAttack = tracker.snapshot(22, false, false, false, 1.6, 0);
        assertEquals(1, beforeAttack.cooldownProgress(), 1.0E-12);
        tracker.swing(22);
        assertEquals(1, beforeAttack.cooldownProgress(), 1.0E-12, "snapshot is immutable across reset");
        assertEquals(0.04, tracker.snapshot(22, false, false, false, 1.6, 1).cooldownProgress(), 1.0E-12);
        assertEquals(1, tracker.snapshot(50, false, false, false, 1.6, 0).cooldownProgress(), 1.0E-12);
    }

    @Test
    void invalidSpeedAndUnknownFallbackCannotBecomeReadiness() {
        assertTrue(Double.isNaN(tracker.snapshot(1, false, false, false, Double.NaN, 1).cooldownProgress()));
        assertTrue(Double.isNaN(tracker.snapshot(1, false, false, false, 0, 1).cooldownProgress()));
        assertTrue(Double.isNaN(tracker.snapshot(1, false, false, false, 1.6, Double.NaN).cooldownProgress()));
        assertEquals(1, tracker.snapshot(1, false, false, false, 1.6, 2).cooldownProgress());
        assertEquals(0, tracker.snapshot(1, false, false, false, 1.6, -1).cooldownProgress());
    }

    @Test
    void ambiguousAnimationInvalidatesCooldownWithoutDiscardingMotionOrInput() {
        tracker.swing(1);
        tracker.input(2, true);
        tracker.sprint(2, true);
        tracker.movement(12, false, -0.075, true);
        assertEquals(0.92, tracker.snapshot(12, false, true, true, 1.6, 1).cooldownProgress(), 1.0E-12);
        tracker.ambiguousSwing(12);
        var ambiguous = tracker.snapshot(12, false, true, true, 1.6, 1);
        assertTrue(Double.isNaN(ambiguous.cooldownProgress()), "shared-handler fallback cannot resolve ambiguous animation");
        assertTrue(ambiguous.validMotion());
        assertTrue(ambiguous.descending());
        assertTrue(ambiguous.forwardHeld());
        assertTrue(ambiguous.sprinting());
        assertTrue(Double.isNaN(tracker.snapshot(100, false, false, false, 1.6, 1).cooldownProgress()),
                "elapsed time cannot choose which reset history was correct");
        tracker.swing(100);
        assertEquals(0.04, tracker.snapshot(100, false, false, false, 1.6, 1).cooldownProgress(), 1.0E-12);
        assertEquals(1, tracker.snapshot(112, false, false, false, 1.6, 0).cooldownProgress(), 1.0E-12);
    }

    @Test
    void ambiguousAnimationBeforeFirstAttackBlocksFallbackUntilAcceptedResetOrExplicitReset() {
        tracker.ambiguousSwing(1);
        assertTrue(Double.isNaN(tracker.snapshot(1, true, false, false, 1.6, 1).cooldownProgress()));
        tracker.swing(1);
        assertEquals(0.04, tracker.snapshot(1, true, false, false, 1.6, 1).cooldownProgress(), 1.0E-12);
        tracker.ambiguousSwing(2);
        assertTrue(Double.isNaN(snapshot(2, true).cooldownProgress()));
        tracker.reset();
        assertEquals(0.7, tracker.snapshot(2, true, false, false, 1.6, 0.7).cooldownProgress(), 1.0E-12);
    }

    @Test
    void backwardsTickResetsHistoriesAndRejectsTheBackwardsObservation() {
        tracker.movement(10, false, -0.075, true);
        tracker.input(10, true);
        tracker.swing(10);
        var backwards = tracker.snapshot(9, false, false, false, 1.6, 1);
        assertFalse(backwards.validMotion());
        assertFalse(backwards.forwardHeld());
        assertTrue(Double.isNaN(backwards.cooldownProgress()));
        tracker.movement(12, false, -0.075, true);
        tracker.movement(11, false, -0.075, true);
        assertFalse(snapshot(12, false).validMotion());
        assertEquals(1, snapshot(12, false).cooldownProgress(), "backwards reset also clears own swing history");
    }

    private TriggerBotCombatTracker.Snapshot snapshot(long tick, boolean grounded) {
        return tracker.snapshot(tick, grounded, false, false, 1.6, 1);
    }
}
