package ac.grim.grimac.predictionengine;

import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RejectedMovementGroundStateTest {
    private static final int CORRECTION_ID = -214873815;
    private static final int CORRECTION_TRANSACTION = 19030;
    private static final SimpleCollisionBox FLOOR = new SimpleCollisionBox(6247, 36, 6022, 6250, 37, 6025);

    @Test
    void landingDuringRejectedMovementSurvivesOnlyItsCorrection() {
        // qC5JzszXXZ, Trace 3: the next correction returns to this floor with zero velocity.
        // The recorded acceleration is grounded (.107 * .196), while stale air uses .02 * .98.
        SimpleCollisionBox rejected = playerBox(6248.500036078718, 37, 6023.496080166017);
        SimpleCollisionBox destination = playerBox(6248.5, 37, 6023.5);
        RejectedMovementGroundState state = new RejectedMovementGroundState();
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, supported(rejected));
        assertTrue(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, 0, supported(destination)));
        assertEquals(0.020972, 0.107 * 0.196, 1e-15);
        assertEquals(0.001372, 0.107 * 0.196 - 0.02 * 0.98, 1e-15);
        assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, 0, true));
        assertEquals(37, rejected.minY); // Probing must not move either rejected or accepted positions.
        assertEquals(6248.5 - (double) 0.6F / 2, destination.minX);
    }

    @Test
    void ordinaryTeleportWithoutRejectedLandingDoesNotChangeGround() {
        RejectedMovementGroundState state = new RejectedMovementGroundState();
        assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, 0, true));
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
        // Plugin teleports, relative corrections and vehicle/non-ground movement are ineligible.
        assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, false, 0, true));
        assertFalse(state.matches(CORRECTION_ID, CORRECTION_TRANSACTION));
    }

    @Test
    void unrelatedTeleportOrResentTransactionDiscardsOldObservation() {
        RejectedMovementGroundState state = new RejectedMovementGroundState();
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
        assertFalse(state.consume(CORRECTION_ID + 1, CORRECTION_TRANSACTION, true, 0, true));
        assertFalse(state.matches(CORRECTION_ID, CORRECTION_TRANSACTION));
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
        assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION + 1, true, 0, true));
        assertFalse(state.matches(CORRECTION_ID, CORRECTION_TRANSACTION));
    }

    @Test
    void latestFalseOrUnsupportedClaimReplacesEarlierLanding() {
        RejectedMovementGroundState state = new RejectedMovementGroundState();
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, false, true);
        assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, 0, true));
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, false);
        assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, 0, true));
    }

    @Test
    void acceptedMovementOrBadPacketClearsPendingGround() {
        RejectedMovementGroundState state = new RejectedMovementGroundState();
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
        state.clear();
        assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, 0, true));
    }

    @Test
    void upwardVelocityOrUnsupportedDestinationCannotRestoreGround() {
        for (double velocity : new double[]{0.001, 0.42, Double.NaN, Double.POSITIVE_INFINITY}) {
            RejectedMovementGroundState state = new RejectedMovementGroundState();
            state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
            assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, velocity, true));
        }
        RejectedMovementGroundState state = new RejectedMovementGroundState();
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
        assertFalse(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, -0.08, false));
        state.record(CORRECTION_ID, CORRECTION_TRANSACTION, true, true);
        assertTrue(state.consume(CORRECTION_ID, CORRECTION_TRANSACTION, true, -0.08, true));
    }

    @Test
    void supportRequiresActualFeetContactInsteadOfNearbyFloorOrWall() {
        assertTrue(supported(playerBox(6248.5, 37, 6023.5)));
        assertFalse(supported(playerBox(6248.5, 37.00001, 6023.5)));
        assertFalse(supported(playerBox(6248.5, 37.029, 6023.5)));
        SimpleCollisionBox box = playerBox(6248.5, 37, 6023.5);
        assertFalse(RejectedMovementGroundState.hasSupport(box, List.of()));
        SimpleCollisionBox wall = new SimpleCollisionBox(box.maxX, 36, 6022, box.maxX + 1, 40, 6025);
        assertFalse(RejectedMovementGroundState.hasSupport(box, List.of(wall)));
    }

    @Test
    void bodyIntersectionRejectsOtherwiseValidFloorAndSlabContactWorks() {
        SimpleCollisionBox box = playerBox(6248.5, 37, 6023.5);
        SimpleCollisionBox intersecting = new SimpleCollisionBox(6248.4, 37.1, 6023.4, 6248.6, 37.2, 6023.6);
        assertFalse(RejectedMovementGroundState.hasSupport(box, List.of(FLOOR, intersecting)));
        assertFalse(supported(playerBox(6248.5, 36.99, 6023.5)));
        SimpleCollisionBox slab = new SimpleCollisionBox(6248, 36, 6023, 6249, 36.5, 6024);
        assertTrue(RejectedMovementGroundState.hasSupport(playerBox(6248.5, 36.5, 6023.5), List.of(slab)));
    }

    private static boolean supported(SimpleCollisionBox box) {
        return RejectedMovementGroundState.hasSupport(box, List.of(FLOOR));
    }

    private static SimpleCollisionBox playerBox(double x, double y, double z) {
        double halfWidth = (double) 0.6F / 2;
        return new SimpleCollisionBox(x - halfWidth, y, z - halfWidth, x + halfWidth, y + (double) 1.8F, z + halfWidth);
    }
}
