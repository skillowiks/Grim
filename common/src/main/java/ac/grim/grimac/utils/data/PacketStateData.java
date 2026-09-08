package ac.grim.grimac.utils.data;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.util.Vector3d;
import lombok.Getter;

// This is to keep all the packet data out of the main player class
// Helps clean up the player class and makes devs aware they are sync'd to the netty thread
public class PacketStateData {
    public boolean packetPlayerOnGround = false;
    public boolean lastPacketWasTeleport = false;
    public boolean cancelDuplicatePacket, lastPacketWasOnePointSeventeenDuplicate = false;
    public boolean lastTransactionPacketWasValid = false;
    public int lastSlotSelected;
    public InteractionHand itemInUseHand = InteractionHand.MAIN_HAND;
    public long lastRiptide = 0;
    public boolean tryingToRiptide = false;
    public int slowedByUsingItemTransaction = Integer.MIN_VALUE;
    public boolean receivedSteerVehicle = false;
    // Teleport acknowledgements must not advance the real movement history.
    public boolean didLastLastMovementIncludePosition = false;
    public boolean didLastMovementIncludePosition = false;
    // This works on 1.21.2+ only
    public boolean didSendMovementBeforeTickEnd = false;
    public KnownInput knownInput = KnownInput.DEFAULT;
    public Vector3d lastClaimedPosition = new Vector3d(0, 0, 0);
    public float lastHealth, lastSaturation;
    public int lastFood;
    public boolean lastServerTransWasValid = false;
    @Getter
    private int slowedByUsingItemSlot = Integer.MIN_VALUE;
    public boolean sendingBundlePacket;
    public boolean showsDeathScreen = true;

    // If true, the player's rotation was forced to the horse's rotation only on 1.13-
    public boolean horseInteractCausedForcedRotation = false;

    public void recordMovement(boolean hasPosition, boolean isTeleport) {
        // The client sends a PosRot acknowledgement directly from its teleport packet handler,
        // without ticking movement. Keep any confirmed idle tick available to prediction.
        if (isTeleport) return;
        didLastLastMovementIncludePosition = didLastMovementIncludePosition;
        didLastMovementIncludePosition = hasPosition;
        didSendMovementBeforeTickEnd = true;
    }

    /** A server cancellation still proves the client sent a movement packet for this tick. */
    public void recordRejectedMovement(boolean hasPosition) {
        if (lastPacketWasTeleport || lastPacketWasOnePointSeventeenDuplicate) return;
        recordMovement(hasPosition, false);
    }

    public void clearPacketFlags() {
        lastPacketWasTeleport = false;
        lastPacketWasOnePointSeventeenDuplicate = false;
        cancelDuplicatePacket = false;
    }

    /** Returns whether the completed client tick had no regular movement packet. */
    public boolean endClientTick() {
        boolean idle = !didSendMovementBeforeTickEnd;
        if (idle) {
            didLastLastMovementIncludePosition = didLastMovementIncludePosition;
            didLastMovementIncludePosition = false;
        }
        didSendMovementBeforeTickEnd = false;
        return idle;
    }

    public void setSlowedByUsingItem(boolean slowedByUsingItem) {
        slowedByUsingItemSlot = slowedByUsingItem ? lastSlotSelected : Integer.MIN_VALUE;
    }

    public boolean isSlowedByUsingItem() {
        return slowedByUsingItemSlot != Integer.MIN_VALUE;
    }
}
