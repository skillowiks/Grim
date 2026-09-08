package ac.grim.grimac.events.packets;

import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.data.SetBackData;
import ac.grim.grimac.utils.data.TeleportData;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckManagerListenerTest {
    @Test
    void forceResyncWaitsForAcknowledgementWithoutBlockingTheAcknowledgement() {
        PacketStateData state = new PacketStateData();
        SetBackData setback = setback(RelativeFlag.YAW);
        assertTrue(CheckManagerListener.shouldDeferPredictionForResync(state, setback, true));

        state.lastPacketWasTeleport = true;
        assertFalse(CheckManagerListener.shouldDeferPredictionForResync(state, setback, true));
        state.clearPacketFlags();
        setback.setComplete(true);
        // Completion releases the guard even if blockOffsets cleanup has not happened yet.
        assertFalse(CheckManagerListener.shouldDeferPredictionForResync(state, setback, true));
    }

    @Test
    void spawnRegularSetbacksAndRelativeTeleportsKeepTheirExistingPredictionPaths() {
        PacketStateData state = new PacketStateData();
        assertFalse(CheckManagerListener.shouldDeferPredictionForResync(state, null, true));
        assertFalse(CheckManagerListener.shouldDeferPredictionForResync(state, setback(RelativeFlag.YAW), false));
        for (RelativeFlag flag : new RelativeFlag[]{RelativeFlag.X, RelativeFlag.Y, RelativeFlag.Z}) {
            assertFalse(CheckManagerListener.shouldDeferPredictionForResync(state, setback(flag), true));
        }
        state.lastPacketWasOnePointSeventeenDuplicate = true;
        assertFalse(CheckManagerListener.shouldDeferPredictionForResync(state, setback(RelativeFlag.YAW), true));
    }

    @Test
    void rejectedFinitePositionAndLookPacketsCanReachTickBookkeeping() {
        assertTrue(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(542.7, 51, 551.96, -110.6f, 3.3f), true, true));
        assertTrue(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(0, 0, 0, 2800, -90), false, true));
        assertTrue(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(29999999, Integer.MAX_VALUE, -29999999, 0, 0), true, false));
    }

    @Test
    void rejectedMalformedPositionsNeverReachNormalPacketChecks() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 30000000, -30000000}) {
            assertFalse(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(invalid, 51, 0, 0, 0), true, false));
            assertFalse(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(0, 51, invalid, 0, 0), true, false));
        }
        assertFalse(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(0, (double) Integer.MAX_VALUE + 1, 0, 0, 0), true, false));
        assertFalse(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(0, Double.NaN, 0, 0, 0), true, false));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertFalse(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(0, 51, 0, invalid, 0), true, true));
            assertFalse(CheckManagerListener.isSafeForRejectedPacketChecks(new Location(0, 0, 0, 0, invalid), false, true));
        }
    }

    private static SetBackData setback(RelativeFlag flags) {
        return new SetBackData(new TeleportData(new Vector3d(0, 51, 0), 0, 0, null, flags, 10, 1),
                0, 0, null, false, false);
    }
}
