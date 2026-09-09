package ac.grim.grimac.predictionengine;

import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;

import java.util.List;

/** Ground contact lost while real client movement is blocked by one pending correction. */
public final class RejectedMovementGroundState {
    public static final double SUPPORT_PROBE = 1.0E-4;

    private boolean supportedGround;
    private int teleportId;
    private int transaction;

    public void record(int teleportId, int transaction, boolean claimedGround, boolean supported) {
        this.teleportId = teleportId;
        this.transaction = transaction;
        supportedGround = claimedGround && supported;
    }

    public boolean matches(int teleportId, int transaction) {
        return supportedGround && this.teleportId == teleportId && this.transaction == transaction;
    }

    public boolean consume(int teleportId, int transaction, boolean authoritativeCorrection,
                           double replacementY, boolean destinationSupported) {
        boolean restore = matches(teleportId, transaction) && authoritativeCorrection
                && Double.isFinite(replacementY) && replacementY <= 0 && destinationSupported;
        clear();
        return restore;
    }

    public void clear() {
        supportedGround = false;
    }

    public static boolean hasSupport(SimpleCollisionBox playerBox, List<SimpleCollisionBox> collisions) {
        double downward = -SUPPORT_PROBE;
        for (SimpleCollisionBox collision : collisions) {
            if (collision.isIntersected(playerBox)) return false;
            downward = collision.collideY(playerBox, downward);
        }
        // A nearby floor is insufficient: the downward collision must already be at the feet.
        return downward > -SUPPORT_PROBE && Math.abs(downward) <= SimpleCollisionBox.COLLISION_EPSILON;
    }
}
