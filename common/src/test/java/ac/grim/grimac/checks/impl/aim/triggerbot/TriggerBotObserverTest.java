package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.utils.data.PacketStateData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result.INSIDE;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result.OUTSIDE;
import static org.junit.jupiter.api.Assertions.*;

class TriggerBotObserverTest {
    @Test
    void recurringConsumedPongsDoNotEraseAcquisitionsBetweenTicks() {
        List<TriggerBotStatistics.Episode> recorded = new ArrayList<>();
        TriggerBotEpisodes episodes = new TriggerBotEpisodes(recorded::add);
        Object target = new Object();
        for (int tick = 0; tick < 40; tick++) {
            // PacketPingListener has validated and cancelled a synchronization
            // reply before every tick. It must not invalidate the pending track.
            if (!TriggerBotObserver.isConsumedSyncReply(PacketType.Play.Client.PONG, true)) episodes.invalidate();
            episodes.observe(214, target, tick, tick % 2 == 0 ? OUTSIDE : INSIDE, "sword", false);
            if (tick % 2 == 1) episodes.attack(214, tick);
        }
        assertEquals(20, recorded.size());
        assertTrue(recorded.stream().allMatch(episode -> episode.outcome() == TriggerBotStatistics.Outcome.HIT));
    }

    @Test
    void staleTransactionValidityCannotExemptCancelledCombatOrMovement() {
        PacketStateData state = new PacketStateData();
        state.lastTransactionPacketWasValid = true;
        state.clearPacketFlags();
        assertTrue(state.lastTransactionPacketWasValid); // Actual lifetime of this field.
        for (PacketType.Play.Client type : PacketType.Play.Client.values()) {
            if (type == PacketType.Play.Client.PONG) continue;
            assertFalse(TriggerBotObserver.isConsumedSyncReply(type, state.lastTransactionPacketWasValid), type.toString());
        }
    }

    @Test
    void unknownOrRejectedPongDoesNotReceiveExemption() {
        assertFalse(TriggerBotObserver.isConsumedSyncReply(PacketType.Play.Client.PONG, false));
    }

    @Test
    void legacyConfirmationStillRequiresNormalCancellationHandling() {
        // A recognized transaction ID does not imply accepted=true on legacy
        // WINDOW_CONFIRMATION. BadPacketsS can reject that separate condition.
        assertFalse(TriggerBotObserver.isConsumedSyncReply(PacketType.Play.Client.WINDOW_CONFIRMATION, true));
        assertFalse(TriggerBotObserver.isConsumedSyncReply(PacketType.Play.Client.WINDOW_CONFIRMATION, false));
    }
}
