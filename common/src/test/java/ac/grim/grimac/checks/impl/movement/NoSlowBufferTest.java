package ac.grim.grimac.checks.impl.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoSlowBufferTest {
    private static final double THRESHOLD = 0.03;
    private static final double REPORTED_OFFSET = 0.04613839992494334;

    @Test
    void consecutiveFullSpeedInputsStillFlag() {
        NoSlowBuffer buffer = new NoSlowBuffer();
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        assertTrue(movement(buffer, true, true, false, REPORTED_OFFSET));
    }

    @Test
    void stoppingItemUseSeparatesIsolatedOutliers() {
        NoSlowBuffer buffer = new NoSlowBuffer();
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        assertFalse(movement(buffer, true, false, false, 0));
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        assertTrue(movement(buffer, true, true, false, REPORTED_OFFSET));
    }

    @Test
    void uncheckedMovementClearsConsecutiveEvidenceAndItsMinimum() {
        NoSlowBuffer buffer = new NoSlowBuffer();
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        assertFalse(movement(buffer, false, true, false, 0));
        assertEquals(1, buffer.bestOffset());
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        assertTrue(movement(buffer, true, true, false, REPORTED_OFFSET));
    }

    @Test
    void teleportResetDropsPendingEvidence() {
        NoSlowBuffer buffer = new NoSlowBuffer();
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        buffer.analyze(0);
        buffer.reset();
        assertEquals(1, buffer.bestOffset());
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        assertTrue(movement(buffer, true, true, false, REPORTED_OFFSET));
    }

    @Test
    void legacySlotChangeAndMatchingMovementInterruptTheSequence() {
        NoSlowBuffer buffer = new NoSlowBuffer();
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        assertFalse(movement(buffer, true, true, true, REPORTED_OFFSET));
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
        buffer.analyze(REPORTED_OFFSET);
        assertFalse(movement(buffer, true, true, false, 0));
        assertFalse(movement(buffer, true, true, false, REPORTED_OFFSET));
    }

    private static boolean movement(NoSlowBuffer buffer, boolean checked, boolean usingItem, boolean exempt, double offset) {
        buffer.analyze(offset);
        return buffer.complete(checked, usingItem, exempt, THRESHOLD);
    }
}
