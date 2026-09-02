package ac.grim.grimac.manager.deepdebug;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.UncertaintyHandler;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.math.Vector3dm;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable movement snapshot taken at flag time on the prediction thread.
 * Values are cloned/copied immediately so later ticks cannot mutate them;
 * the report renders them at leisure.
 */
public final class MovementContext {
    public final Vector3dm predicted;
    public final Vector3dm actual;
    public final Vector3dm startTickVelocity;
    public final double offset;
    public final double x, y, z;
    public final float yaw, pitch;
    public final String world;
    public final boolean onGround, clientClaimsGround, lastOnGround;
    public final boolean sprinting, sneaking, gliding, swimming, riptidePose;
    public final boolean touchingWater, touchingLava, inVehicle;
    public final boolean couldSkipTick, skippedTick;
    public final double fallDistance;
    public final int food;
    public final double speed, gravity, friction;
    public final boolean sprintAttributeEnabled;
    public final @Nullable Vector3dm pendingKnockback;
    public final @Nullable Vector3dm pendingExplosion;
    public final @Nullable String pendingKnockbackDetail;
    public final @Nullable String pendingExplosionDetail;
    public final String vectorProvenance;
    public final String uncertaintySummary;

    private MovementContext(GrimPlayer player, double offset) {
        VectorData predictedVector = player.predictedVelocity;
        this.predicted = predictedVector.vector.clone();
        this.actual = player.actualMovement.clone();
        this.startTickVelocity = player.startTickClientVel.clone();
        this.offset = offset;
        this.x = player.x;
        this.y = player.y;
        this.z = player.z;
        this.yaw = player.yaw;
        this.pitch = player.pitch;
        this.world = player.platformPlayer == null ? "null" : player.platformPlayer.getWorld().getName();
        this.onGround = player.onGround;
        this.clientClaimsGround = player.clientClaimsLastOnGround;
        this.lastOnGround = player.lastOnGround;
        this.sprinting = player.isSprinting;
        this.sneaking = player.isSneaking;
        this.gliding = player.isGliding;
        this.swimming = player.isSwimming;
        this.riptidePose = player.isRiptidePose;
        this.touchingWater = player.wasTouchingWater;
        this.touchingLava = player.wasTouchingLava;
        this.inVehicle = player.inVehicle();
        this.couldSkipTick = player.couldSkipTick;
        this.skippedTick = player.skippedTickInActualMovement;
        this.fallDistance = player.fallDistance;
        this.food = player.food;
        this.speed = player.speed;
        this.gravity = player.gravity;
        this.friction = player.friction;
        this.sprintAttributeEnabled = player.compensatedEntities.hasSprintingAttributeEnabled;
        VelocityData kb = player.likelyKB;
        this.pendingKnockback = kb == null ? null : kb.vector.clone();
        this.pendingKnockbackDetail = kb == null ? null : velocityDetail(kb);
        VelocityData explosion = player.likelyExplosions;
        this.pendingExplosion = explosion == null ? null : explosion.vector.clone();
        this.pendingExplosionDetail = explosion == null ? null : velocityDetail(explosion);
        this.vectorProvenance = provenance(predictedVector);
        this.uncertaintySummary = uncertainty(player.uncertaintyHandler);
    }

    private static String velocityDetail(VelocityData data) {
        return data.vector + " (transaction " + data.transaction + ", setback " + data.isSetback + ")";
    }

    private static String provenance(VectorData vector) {
        StringBuilder sb = new StringBuilder();
        appendFlag(sb, vector.isKnockback(), "knockback");
        appendFlag(sb, vector.isFirstBreadKb(), "firstBreadKB");
        appendFlag(sb, vector.isExplosion(), "explosion");
        appendFlag(sb, vector.isFirstBreadExplosion(), "firstBreadExplosion");
        appendFlag(sb, vector.isTrident(), "trident");
        appendFlag(sb, vector.isSwimHop(), "swimhop");
        appendFlag(sb, vector.isJump(), "jump");
        appendFlag(sb, vector.isZeroPointZeroThree(), "0.03");
        appendFlag(sb, vector.isAttackSlow(), "attackSlow");
        appendFlag(sb, vector.vectorType == VectorData.VectorType.SlimePistonBounce, "slimePistonBounce");
        appendFlag(sb, vector.vectorType == VectorData.VectorType.Teleport, "teleport");
        appendFlag(sb, vector.vectorType == VectorData.VectorType.InputResult, "input");
        return sb.length() == 0 ? "normal" : sb.toString();
    }

    private static void appendFlag(StringBuilder sb, boolean set, String name) {
        if (set) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
    }

    private static String uncertainty(UncertaintyHandler u) {
        StringBuilder sb = new StringBuilder();
        if (!u.pistonX.isEmpty() || !u.pistonY.isEmpty() || !u.pistonZ.isEmpty()) {
            appendKeyValue(sb, "piston", u.pistonX.size() + "/" + u.pistonY.size() + "/" + u.pistonZ.size());
        }
        if (!u.slimePistonBounces.isEmpty()) appendKeyValue(sb, "slimePistonBounce", String.valueOf(u.slimePistonBounces));
        if (u.isStepMovement) appendKeyValue(sb, "step", "true");
        if (u.isSteppingOnIce) appendKeyValue(sb, "ice", "true");
        if (u.isSteppingOnHoney) appendKeyValue(sb, "honey", "true");
        if (u.isSteppingOnSlime || u.wasSteppingOnSlime) appendKeyValue(sb, "slime", "true");
        if (u.isSteppingOnBouncyBlock || u.wasSteppingOnBouncyBlock) appendKeyValue(sb, "bouncy", "true");
        if (u.isSteppingNearBubbleColumn) appendKeyValue(sb, "bubble", "true");
        if (u.isSteppingNearScaffolding) appendKeyValue(sb, "scaffolding", "true");
        if (u.isSteppingNearShulker) appendKeyValue(sb, "shulker", "true");
        if (u.isOrWasNearGlitchyBlock) appendKeyValue(sb, "glitchyBlock", "true");
        if (u.onGroundUncertain) appendKeyValue(sb, "groundUncertain", "true");
        if (u.wasAffectedByStuckSpeed()) appendKeyValue(sb, "stuckSpeed", "true");
        if (!u.collidingEntities.isEmpty()) appendKeyValue(sb, "collidingEntities", String.valueOf(u.collidingEntities.size()));
        if (!u.riptideEntities.isEmpty()) appendKeyValue(sb, "riptideEntities", String.valueOf(u.riptideEntities.size()));
        if (!u.fishingRodPulls.isEmpty()) appendKeyValue(sb, "fishingRodPulls", String.valueOf(u.fishingRodPulls.size()));
        if (u.fireworksBox != null) appendKeyValue(sb, "fireworksBox", "set");
        if (u.stuckOnEdge.hasOccurredSince(1)) appendKeyValue(sb, "stuckOnEdge", "true");
        if (u.lastMovementWasZeroPointZeroThree) appendKeyValue(sb, "0.03", "true");
        if (u.lastMovementWasUnknown003VectorReset) appendKeyValue(sb, "0.03reset", "true");
        if (u.wasZeroPointThreeVertically) appendKeyValue(sb, "0.03vertical", "true");
        if (u.lastTeleportTicks.hasOccurredSince(100)) appendKeyValue(sb, "recentTeleport", "true");
        if (u.lastVehicleSwitch.hasOccurredSince(100)) appendKeyValue(sb, "recentVehicleSwitch", "true");
        if (u.lastFlyingTicks.hasOccurredSince(100)) appendKeyValue(sb, "recentFlying", "true");
        appendKeyValue(sb, "dirs", String.format("%.4f/%.4f/%.4f/%.4f/%.4f/%.4f",
                u.xNegativeUncertainty, u.xPositiveUncertainty, u.yNegativeUncertainty,
                u.yPositiveUncertainty, u.zNegativeUncertainty, u.zPositiveUncertainty));
        return sb.toString();
    }

    private static void appendKeyValue(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(key).append('=').append(value);
    }

    /** Snapshot the given player's movement state; offset is the current prediction offset when known. */
    public static @Nullable MovementContext snapshot(GrimPlayer player, double offset) {
        try {
            return new MovementContext(player, offset);
        } catch (RuntimeException ignored) {
            // A forensics snapshot must never break the flag path.
            return null;
        }
    }
}
