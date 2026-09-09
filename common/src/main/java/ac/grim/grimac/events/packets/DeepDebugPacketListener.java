package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.deepdebug.DeepDebugManager;
import ac.grim.grimac.manager.deepdebug.DeepDebugSession;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;

/**
 * Sprint-input churn and item-use packet snapshots for deep debug.
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

        recordItemUsePacket(event);

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

    private static void recordItemUsePacket(PacketReceiveEvent event) {
        PacketType.Play.Client type = event.getPacketType() instanceof PacketType.Play.Client client ? client : null;
        if (type != PacketType.Play.Client.USE_ITEM && type != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                && type != PacketType.Play.Client.PLAYER_DIGGING && type != PacketType.Play.Client.HELD_ITEM_CHANGE) return;

        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null || DeepDebugManager.get().getSession(player.uuid) == null) return;
        // Decode only for the player being investigated. These are received-packet
        // observations, before the ordinary listeners apply or cancel the action.
        try {
            if (type == PacketType.Play.Client.PLAYER_DIGGING
                    && new WrapperPlayClientPlayerDigging(event).getAction() != DiggingAction.RELEASE_USE_ITEM) return;
            DeepDebugManager.get().recordPredictionEvent(player, () -> {
                String action;
                if (type == PacketType.Play.Client.USE_ITEM) {
                    action = "USE_ITEM hand=" + new WrapperPlayClientUseItem(event).getHand();
                } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                    action = "BLOCK_PLACEMENT hand=" + new WrapperPlayClientPlayerBlockPlacement(event).getHand();
                } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
                    action = "HELD_ITEM_CHANGE slot=" + new WrapperPlayClientHeldItemChange(event).getSlot();
                } else {
                    DiggingAction digging = new WrapperPlayClientPlayerDigging(event).getAction();
                    action = "DIGGING action=" + digging;
                }
                return "C2S ITEM_INPUT received " + action + " transaction=" + player.lastTransactionReceived.get()
                        + " cancelledAtReceive=" + event.isCancelled()
                        + " usingBefore=" + player.packetStateData.isSlowedByUsingItem()
                        + " useTransaction=" + player.packetStateData.slowedByUsingItemTransaction
                        + " selectedSlot=" + player.packetStateData.lastSlotSelected;
            });
        } catch (RuntimeException ignored) {
            // A diagnostic decode must not change whether an incoming packet is handled.
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!DeepDebugManager.get().hasActiveSessions()) return;
        PacketType.Play.Server type = event.getPacketType() instanceof PacketType.Play.Server server ? server : null;
        if (type != PacketType.Play.Server.SET_PLAYER_INVENTORY && type != PacketType.Play.Server.SET_SLOT
                && type != PacketType.Play.Server.WINDOW_ITEMS && type != PacketType.Play.Server.ENTITY_STATUS) return;
        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null || DeepDebugManager.get().getSession(player.uuid) == null) return;

        try {
            String detail;
            if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
                WrapperPlayServerSetPlayerInventory slot = new WrapperPlayServerSetPlayerInventory(event);
                detail = "SET_PLAYER_INVENTORY slot=" + slot.getSlot() + " item=" + describeItem(slot.getStack());
            } else if (type == PacketType.Play.Server.SET_SLOT) {
                WrapperPlayServerSetSlot slot = new WrapperPlayServerSetSlot(event);
                if (slot.getWindowId() != 0 && slot.getWindowId() != -2) return;
                if (slot.getWindowId() == 0 && (slot.getSlot() < 36 || slot.getSlot() > 45)) return;
                detail = "SET_SLOT window=" + slot.getWindowId() + " slot=" + slot.getSlot()
                        + " item=" + describeItem(slot.getItem());
            } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                WrapperPlayServerWindowItems items = new WrapperPlayServerWindowItems(event);
                if (items.getWindowId() != 0) return;
                int selected = 36 + player.packetStateData.lastSlotSelected;
                detail = "WINDOW_ITEMS window=" + items.getWindowId() + " slots=" + items.getItems().size();
                if (selected >= 36 && selected <= 44 && selected < items.getItems().size()) {
                    detail += " main=" + describeItem(items.getItems().get(selected));
                }
                if (items.getItems().size() > 45) detail += " offhand=" + describeItem(items.getItems().get(45));
            } else {
                WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
                if (status.getEntityId() != player.entityID || status.getStatus() != 9) return;
                detail = "FINISH_USE_ITEM status=9";
            }
            String snapshot = detail;
            DeepDebugManager.get().recordPredictionEvent(player, () -> "S2C ITEM_UPDATE observed " + snapshot
                    + " transaction=" + player.lastTransactionSent.get() + " cancelledAtObserve=" + event.isCancelled()
                    + " usingItem=" + player.packetStateData.isSlowedByUsingItem()
                    + " selectedSlot=" + player.packetStateData.lastSlotSelected);
        } catch (RuntimeException ignored) {
            // Reporting cannot interfere with inventory delivery.
        }
    }

    private static String describeItem(ItemStack item) {
        return item == null ? "null" : item.getType().getName() + "x" + item.getAmount();
    }
}
