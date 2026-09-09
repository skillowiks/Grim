package ac.grim.grimac.utils.latency;

import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.VanillaMath;
import ac.grim.grimac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementSpeedChangeTrackerTest {
    private static final double OLD = 0.09415999799966812;
    private static final double NEW = 0.17120000350773337;

    @Test
    void reportUsesOldSpeedAtPrePingAndNewSpeedAfterActualPostPingWithCarriedInertia() {
        MovementSpeedChangeTracker tracker = new MovementSpeedChangeTracker();
        MovementSpeedChangeTracker.Pending pending = tracker.pending(15645);
        tracker.sentAfter(pending, 15647); // Another packet interleaved; this is not pre + 1.
        tracker.activate(pending, OLD, false, NEW, false, false, false);

        assertTrue(tracker.previousSpeed(15644, NEW, false, false, false).isEmpty());
        float oldSpeed = (float) tracker.previousSpeed(15645, NEW, false, false, false).orElseThrow();
        Vector3dm movement = move(new Vector3dm(-0.04931651734324675, 0, -0.12167245379819033), oldSpeed);
        assertEquals(-0.08650858301643893, movement.getX(), 1e-11);
        assertEquals(-0.20612218950464012, movement.getZ(), 1e-11);
        assertTrue(tracker.previousSpeed(15646, NEW, false, false, false).isPresent());
        assertTrue(tracker.previousSpeed(15647, NEW, false, false, false).isEmpty());

        Vector3dm next = move(movement.multiply(0.54600006F), (float) NEW);
        assertEquals(-0.11485563368114526, next.getX(), 1e-11);
        assertEquals(-0.2660877122725651, next.getZ(), 1e-11);
    }

    @Test
    void missingFailedOrNonTrailingPingNeverEnablesOldSpeed() {
        MovementSpeedChangeTracker tracker = new MovementSpeedChangeTracker();
        MovementSpeedChangeTracker.Pending pending = tracker.pending(10);
        tracker.activate(pending, OLD, false, NEW, false, false, false);
        assertTrue(tracker.previousSpeed(10, NEW, false, false, false).isEmpty());
        tracker.sentAfter(pending, 10);
        assertTrue(tracker.previousSpeed(10, NEW, false, false, false).isEmpty());
        tracker.sentAfter(pending, 13);
        assertTrue(tracker.previousSpeed(12, NEW, false, false, false).isPresent());
        tracker.acknowledgedAfter(pending);
        assertTrue(tracker.previousSpeed(12, NEW, false, false, false).isEmpty());
    }

    @Test
    void sprintCommandRoundTripAndAttackInvalidationCannotReviveSnapshot() {
        MovementSpeedChangeTracker tracker = new MovementSpeedChangeTracker();
        MovementSpeedChangeTracker.Pending pending = tracker.pending(10);
        tracker.sentAfter(pending, 20);
        tracker.activate(pending, OLD, false, NEW, false, false, false);
        tracker.invalidateSprinting(); // START, STOP or an attack's local setSprinting.
        tracker.invalidateSprinting();
        assertTrue(tracker.previousSpeed(12, NEW, false, false, false).isEmpty());
        // Delayed pre-ping callback cannot resurrect a packet sent before the transition.
        tracker.activate(pending, OLD, false, NEW, false, false, false);
        assertTrue(tracker.previousSpeed(12, NEW, false, false, false).isEmpty());
    }

    @Test
    void packetSprintAttributeIsIndependentOfPacketSprinting() {
        MovementSpeedChangeTracker tracker = new MovementSpeedChangeTracker();
        MovementSpeedChangeTracker.Pending pending = tracker.pending(10);
        tracker.sentAfter(pending, 20);
        tracker.activate(pending, OLD, false, NEW, false, true, true);
        assertEquals((float) OLD, tracker.previousSpeed(12, NEW, false, true, true).orElseThrow());
        assertTrue(tracker.previousSpeed(12, NEW, true, true, true).isEmpty());
        assertTrue(tracker.previousSpeed(12, NEW, false, true, false).isEmpty());

        tracker.activate(pending, OLD, true, NEW, true, true, true);
        assertEquals((float) (OLD + OLD * 0.3F), tracker.previousSpeed(12, NEW, true, true, true).orElseThrow());
        tracker.activate(pending, OLD, false, NEW, true, true, true);
        assertTrue(tracker.previousSpeed(12, NEW, true, true, true).isEmpty());
    }

    @Test
    void laterUpdateSupersedesEarlierIntervalAndOldAckCannotEraseIt() {
        MovementSpeedChangeTracker tracker = new MovementSpeedChangeTracker();
        MovementSpeedChangeTracker.Pending first = tracker.pending(10);
        MovementSpeedChangeTracker.Pending second = tracker.pending(13);
        tracker.sentAfter(first, 12);
        tracker.sentAfter(second, 16);
        tracker.activate(first, OLD, false, NEW, false, false, false);
        tracker.activate(second, NEW, false, 0.2, false, false, false);
        tracker.acknowledgedAfter(first);
        assertEquals((float) NEW, tracker.previousSpeed(14, 0.2, false, false, false).orElseThrow());
        assertTrue(tracker.previousSpeed(14, 0.21, false, false, false).isEmpty());
    }

    @Test
    void zeroNewSpeedDoesNotRequireDivisionAndEqualFloatSpeedsDoNotDuplicateCandidates() {
        MovementSpeedChangeTracker tracker = new MovementSpeedChangeTracker();
        MovementSpeedChangeTracker.Pending pending = tracker.pending(10);
        tracker.sentAfter(pending, 12);
        tracker.activate(pending, OLD, false, 0, false, false, false);
        assertEquals((float) OLD, tracker.previousSpeed(10, 0, false, false, false).orElseThrow());
        tracker.activate(pending, OLD, false, OLD + 1e-12, false, false, false);
        assertTrue(tracker.previousSpeed(10, OLD + 1e-12, false, false, false).isEmpty());
    }

    private static Vector3dm move(Vector3dm start, float speed) {
        float yaw = GrimMath.radians(-743.7706F);
        double input = -0.98F * (double) speed;
        return start.clone().add(-input * VanillaMath.sin(yaw), 0, input * VanillaMath.cos(yaw));
    }
}
