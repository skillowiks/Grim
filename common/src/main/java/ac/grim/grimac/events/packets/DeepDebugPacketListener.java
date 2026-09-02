package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.deepdebug.DeepDebugSession;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;

/**
 * Sprint-input churn tracker for the deep-debug auto-sprint heuristic.
 * Counts START/STOP_SPRINTING entity actions (pre-1.21.2) and the sprint bit
 * of PLAYER_INPUT (1.21.2+) for players with an active deep-debug session.
 * The whole body is behind a volatile check, so it is free when unused.
 */
public class DeepDebugPacketListener extends PacketListenerAbstract {

    public DeepDebugPacketListener() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public boolean isPreVia() {
        return true;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!GrimAPI.INSTANCE.getDeepDebugManager().hasActiveSessions()) return;

        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;
            DeepDebugSession session = GrimAPI.INSTANCE.getDeepDebugManager().getSession(player.uuid);
            if (session == null) return;

            WrapperPlayClientEntityAction action = new WrapperPlayClientEntityAction(event);
            long now = System.currentTimeMillis();
            switch (action.getAction()) {
                case START_SPRINTING -> session.sprintChurn.onSprintInput(true, now);
                case STOP_SPRINTING -> session.sprintChurn.onSprintInput(false, now);
                default -> { }
            }
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_INPUT) {
            // 1.21.2+ clients carry sprint in the input packet instead of ENTITY_ACTION.
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;
            DeepDebugSession session = GrimAPI.INSTANCE.getDeepDebugManager().getSession(player.uuid);
            if (session == null) return;

            WrapperPlayClientPlayerInput input = new WrapperPlayClientPlayerInput(event);
            session.sprintChurn.onSprintInput(input.isSprint(), System.currentTimeMillis());
        }
    }
}
