package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.utils.latency.MovementSpeedChangeTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementSpeedIntervalNoSlowTest {
    @Test
    void oldSpeedSlowedCandidateClearsEvidenceButDoesNotExemptUnslowedInput() {
        MovementSpeedChangeTracker tracker = new MovementSpeedChangeTracker();
        MovementSpeedChangeTracker.Pending pending = tracker.pending(10);
        tracker.sentAfter(pending, 15);
        tracker.activate(pending, 0.09416, false, 0.1712, false, false, false);
        double previousSpeed = tracker.previousSpeed(10, 0.1712, false, false, false).orElseThrow();
        double oldSlowed = (0.98F * 0.2F) * previousSpeed;
        double newSlowed = (0.98F * 0.2F) * (float) 0.1712;
        NoSlowBuffer buffer = new NoSlowBuffer();

        // Both non-flipped speed candidates participate in the same minimum.
        buffer.analyze(Math.abs(newSlowed - oldSlowed));
        buffer.analyze(0);
        assertFalse(buffer.complete(true, true, false, 0.001));

        double unslowed = 0.98F * previousSpeed;
        for (int movement = 0; movement < 2; movement++) {
            buffer.analyze(Math.abs(newSlowed - unslowed));
            buffer.analyze(Math.abs(oldSlowed - unslowed));
            assertEquals(movement == 1, buffer.complete(true, true, false, 0.001));
        }
        assertTrue(tracker.previousSpeed(15, 0.1712, false, false, false).isEmpty());
    }
}
