package ac.grim.grimac.utils.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketStateDataTest {
    @Test
    void teleportPreservesConfirmedIdleBeforeExplosionMovement() {
        PacketStateData state = new PacketStateData();
        state.recordMovement(true, false);
        assertFalse(state.endClientTick());
        assertTrue(state.endClientTick());

        state.recordMovement(true, true);
        assertFalse(state.didLastMovementIncludePosition);
        assertTrue(state.didLastLastMovementIncludePosition);
        assertFalse(state.didSendMovementBeforeTickEnd);

        // Prediction for the first movement after the teleport can still consider a skipped tick.
        // Recording that real movement then consumes the idle history normally.
        state.recordMovement(true, false);
        assertTrue(state.didLastMovementIncludePosition);
        assertFalse(state.didLastLastMovementIncludePosition);
        assertFalse(state.endClientTick());
    }

    @Test
    void teleportAfterRegularMovementDoesNotInventAnIdleTick() {
        PacketStateData state = new PacketStateData();
        state.recordMovement(true, false);
        state.recordMovement(true, true);
        assertTrue(state.didLastMovementIncludePosition);
        assertFalse(state.didLastLastMovementIncludePosition);
        assertTrue(state.didSendMovementBeforeTickEnd);
        assertFalse(state.endClientTick());
        assertTrue(state.didLastMovementIncludePosition);
    }

    @Test
    void teleportAloneDoesNotProveTheClientSkippedMovementYet() {
        PacketStateData state = new PacketStateData();
        state.recordMovement(true, false);
        assertFalse(state.endClientTick());

        state.recordMovement(true, true);
        assertTrue(state.didLastMovementIncludePosition);
        assertFalse(state.didSendMovementBeforeTickEnd);
        assertTrue(state.endClientTick());
        assertFalse(state.didLastMovementIncludePosition);
        assertTrue(state.didLastLastMovementIncludePosition);
    }

    @Test
    void lookOnlyMovementKeepsNoPositionHistoryAcrossTeleport() {
        PacketStateData state = new PacketStateData();
        state.recordMovement(true, false);
        state.endClientTick();
        state.recordMovement(false, false);
        state.recordMovement(true, true);
        assertFalse(state.didLastMovementIncludePosition);
        assertTrue(state.didLastLastMovementIncludePosition);
        assertFalse(state.endClientTick());
        assertTrue(state.didLastLastMovementIncludePosition);
    }

    @Test
    void repeatedTeleportAcknowledgementsDoNotConsumeIdleHistory() {
        PacketStateData state = new PacketStateData();
        state.recordMovement(true, false);
        state.endClientTick();
        state.endClientTick();

        for (int i = 0; i < 10; i++) state.recordMovement(true, true);
        assertFalse(state.didLastMovementIncludePosition);
        assertTrue(state.didLastLastMovementIncludePosition);
        assertFalse(state.didSendMovementBeforeTickEnd);
        assertTrue(state.endClientTick());
        assertFalse(state.didLastLastMovementIncludePosition);
    }

    @Test
    void regularMovementPacketsContinueToAdvanceBothHistorySlots() {
        PacketStateData state = new PacketStateData();
        state.recordMovement(true, false);
        state.recordMovement(true, false);
        assertTrue(state.didLastMovementIncludePosition);
        assertTrue(state.didLastLastMovementIncludePosition);
        state.recordMovement(false, false);
        assertFalse(state.didLastMovementIncludePosition);
        assertTrue(state.didLastLastMovementIncludePosition);
    }
}
