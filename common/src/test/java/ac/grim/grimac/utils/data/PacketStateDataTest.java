package ac.grim.grimac.utils.data;

import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void twoCancelledPositionsDoNotBecomeTwoPermittedIdleTicks() {
        PacketStateData state = new PacketStateData();
        state.lastClaimedPosition = new Vector3d(542.6999999880791, 51, 551.943552274604);
        state.packetPlayerOnGround = true;
        state.recordMovement(true, false);
        assertFalse(state.endClientTick());

        for (int tick = 0; tick < 2; tick++) {
            state.recordRejectedMovement(true);
            // Timer already saw the flying packet. Its CLIENT_TICK_END fallback must not count it again.
            assertTrue(state.didSendMovementBeforeTickEnd);
            assertFalse(state.endClientTick());
            assertTrue(state.didLastMovementIncludePosition);
            assertTrue(state.didLastLastMovementIncludePosition);
        }

        // Recording receipt does not accept the rejected position or change the prediction's ground state.
        assertEquals(new Vector3d(542.6999999880791, 51, 551.943552274604), state.lastClaimedPosition);
        assertTrue(state.packetPlayerOnGround);
    }

    @Test
    void cancelledLookOnlyPacketCountsItsTickWithoutClaimingPosition() {
        PacketStateData state = new PacketStateData();
        state.recordMovement(true, false);
        state.endClientTick();
        state.recordRejectedMovement(false);
        assertFalse(state.didLastMovementIncludePosition);
        assertTrue(state.didLastLastMovementIncludePosition);
        assertFalse(state.endClientTick());
        assertTrue(state.didLastLastMovementIncludePosition);
    }

    @Test
    void cancelledTeleportAndDuplicateDoNotConsumeRealIdleHistory() {
        PacketStateData state = new PacketStateData();
        state.recordMovement(true, false);
        state.endClientTick();
        state.endClientTick();
        state.lastPacketWasTeleport = true;
        state.recordRejectedMovement(true);
        state.clearPacketFlags();
        state.lastPacketWasOnePointSeventeenDuplicate = true;
        state.recordRejectedMovement(true);
        state.clearPacketFlags();

        assertFalse(state.didLastMovementIncludePosition);
        assertTrue(state.didLastLastMovementIncludePosition);
        assertFalse(state.didSendMovementBeforeTickEnd);
        // A later genuine idle tick still advances the history once.
        assertTrue(state.endClientTick());
        assertFalse(state.didLastLastMovementIncludePosition);
    }

    @Test
    void earlyReturnCleanupDoesNotExemptTheNextRegularTick() {
        PacketStateData state = new PacketStateData();
        state.lastPacketWasTeleport = true;
        state.lastPacketWasOnePointSeventeenDuplicate = true;
        state.cancelDuplicatePacket = true;
        state.clearPacketFlags();

        assertFalse(state.lastPacketWasTeleport);
        assertFalse(state.lastPacketWasOnePointSeventeenDuplicate);
        assertFalse(state.cancelDuplicatePacket);
        state.recordRejectedMovement(true);
        assertTrue(state.didLastMovementIncludePosition);
        assertFalse(state.endClientTick());
    }
}
