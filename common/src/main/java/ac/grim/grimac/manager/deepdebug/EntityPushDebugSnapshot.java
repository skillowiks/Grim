package ac.grim.grimac.manager.deepdebug;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.UncertaintyHandler;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.team.EntityPredicates;
import ac.grim.grimac.utils.team.EntityTeam;
import ac.grim.grimac.utils.team.TeamHandler;

import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Read-only endpoint evidence for investigating the existing entity-push uncertainty. */
public final class EntityPushDebugSnapshot {
    static final int MAX_SCANNED_ENTITIES = 256;
    static final int MAX_DESCRIBED_ENTITIES = 8;

    private EntityPushDebugSnapshot() {
    }

    /** Called once for the chosen prediction, before MovementTicker clears directional uncertainty. */
    public static String capturePrediction(GrimPlayer player, Vector3dm beforeCollision) {
        try {
            UncertaintyHandler u = player.uncertaintyHandler;
            return "PREDICTION_UNCERTAINTY {phase=chosenBeforeMove,transaction=" + player.lastTransactionReceived.get()
                    + ",preUncertainty=" + describePreUncertainty(player.predictedVelocity)
                    + ",beforeCollision=" + beforeCollision + ",carryBeforeMove=" + player.clientVelocity
                    + ",collisionOutput=" + player.predictedVelocity.vector
                    + ",colliding=" + history(u.collidingEntities) + ",riptide=" + history(u.riptideEntities)
                    + ",pistonX=" + history(u.pistonX) + ",pistonY=" + history(u.pistonY)
                    + ",pistonZ=" + history(u.pistonZ)
                    + ",dirs=" + u.xNegativeUncertainty + "/" + u.xPositiveUncertainty
                    + "/" + u.yNegativeUncertainty + "/" + u.yPositiveUncertainty
                    + "/" + u.zNegativeUncertainty + "/" + u.zPositiveUncertainty
                    + ",lastOffsets=" + u.lastHorizontalOffset + "/" + u.lastVerticalOffset + "}";
        } catch (RuntimeException failure) {
            return "PREDICTION_UNCERTAINTY {phase=chosenBeforeMove,unavailable=" + failure.getClass().getSimpleName() + "}";
        }
    }

    static String capture(GrimPlayer player) {
        try {
            UncertaintyHandler u = player.uncertaintyHandler;
            // Same surrounding envelope as MovementTicker.handleEntityCollisions. This is
            // sampled after prediction, not a claim about the client's exact entity positions.
            SimpleCollisionBox queryBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                    player, player.lastX, player.lastY, player.lastZ, 0.6f, 1.8f);
            queryBox.encompass(GetBoundingBox.getBoundingBoxFromPosAndSize(
                    player, player.x, player.y, player.z, 0.6f, 1.8f).expand(player.getMovementThreshold()));
            queryBox.expand(0.2);
            TeamHandler teams = player.checkManager.get(TeamHandler.class);
            EntityTeam playerTeam = teams == null ? null : teams.getPlayerTeam();
            String nearby = scanNearby(player.compensatedEntities.entityMap.int2ObjectEntrySet().iterator(),
                    entry -> queryBox.isCollided(entry.getValue().getPossibleCollisionBoxes()),
                    entry -> {
                        var entity = entry.getValue();
                        EntityTeam entityTeam = teams == null ? null : teams.getEntityTeam(entity);
                        // IDs and rules are sufficient; do not include names, UUIDs or team text.
                        return "{id=" + entry.getIntKey() + ",type=" + entity.getType().getName()
                                + ",possibleBox=" + box(entity.getPossibleCollisionBoxes())
                                + ",trackedPos=" + entity.trackedServerPosition.getPos()
                                + ",pushable=" + entity.isPushable() + ",dead=" + entity.isDead
                                + ",riding=" + (entity.getRiding() != null)
                                + ",teamRule=" + teamRule(entityTeam)
                                + ",sameTeam=" + (entityTeam != null && entityTeam.equals(playerTeam))
                                + ",teamAllowsPush=" + EntityPredicates.canBePushedBy(entityTeam, playerTeam) + "}";
                    });
            return "{phase=endpointCachedEntities,preUncertainty=" + describePreUncertainty(player.predictedVelocity)
                    + ",colliding=" + history(u.collidingEntities) + ",riptide=" + history(u.riptideEntities)
                    + ",pistonX=" + history(u.pistonX) + ",pistonY=" + history(u.pistonY)
                    + ",pistonZ=" + history(u.pistonZ)
                    + ",endpointDirs=" + u.xNegativeUncertainty + "/" + u.xPositiveUncertainty
                    + "/" + u.yNegativeUncertainty + "/" + u.yPositiveUncertainty
                    + "/" + u.zNegativeUncertainty + "/" + u.zPositiveUncertainty
                    + ",endpointLastOffsets=" + u.lastHorizontalOffset + "/" + u.lastVerticalOffset
                    + ",queryBox=" + box(queryBox) + ",playerTeamRule=" + teamRule(playerTeam)
                    + ",inVehicle=" + player.inVehicle() + ",gamemode=" + player.gamemode
                    + ",trackedCount=" + player.compensatedEntities.entityMap.size() + ",nearby=" + nearby + "}";
        } catch (RuntimeException failure) {
            // Cached entities can disappear or lack interpolation data. Debug must not
            // interrupt prediction, and unavailable evidence must not be reported as empty.
            return "{phase=endpointCachedEntities,unavailable=" + failure.getClass().getSimpleName() + "}";
        }
    }

    static String describePreUncertainty(VectorData velocity) {
        if (velocity == null) return "none";
        if (velocity.preUncertainty != null) return "{source=explicit,vector=" + velocity.preUncertainty.vector + "}";
        // MovementTicker.move reconstructs BestVelPicked using its lastVector,
        // so the explicit preUncertainty reference may be lost at the endpoint.
        // That direct predecessor is still the input candidate chosen by PredictionEngine.
        if (velocity.vectorType == VectorData.VectorType.BestVelPicked && velocity.lastVector != null) {
            return "{source=bestCandidate,vector=" + velocity.lastVector.vector + "}";
        }
        return "none";
    }

    static String history(List<? extends Number> values) {
        Number maximum = null;
        for (Number value : values) {
            if (maximum == null || value.doubleValue() > maximum.doubleValue()) maximum = value;
        }
        return "{max=" + (maximum == null ? "none" : maximum) + ",oldestFirst=" + values + "}";
    }

    /** Limits both entity-cache reads and verbose descriptions, independently. */
    static <T> String scanNearby(Iterator<T> entries, Predicate<T> isNearby, Function<T, String> describe) {
        int scanned = 0;
        int nearby = 0;
        int described = 0;
        StringBuilder details = new StringBuilder();
        try {
            while (scanned < MAX_SCANNED_ENTITIES && entries.hasNext()) {
                T entry = entries.next();
                scanned++;
                if (!isNearby.test(entry)) continue;
                nearby++;
                if (described == MAX_DESCRIBED_ENTITIES) continue;
                String detail = describe.apply(entry);
                if (described++ > 0) details.append(';');
                details.append(detail);
            }
            return "{scanned=" + scanned + ",scanTruncated=" + entries.hasNext()
                    + ",nearInScan=" + nearby + ",omittedNear=" + (nearby - described)
                    + ",entries=[" + details + "]}";
        } catch (RuntimeException failure) {
            return "{scanned=" + scanned + ",unavailable=" + failure.getClass().getSimpleName()
                    + ",partialEntries=[" + details + "]}";
        }
    }

    private static String teamRule(EntityTeam team) {
        return team == null ? "default:ALWAYS" : String.valueOf(team.getCollisionRule());
    }

    private static String box(SimpleCollisionBox box) {
        return "[" + box.minX + "," + box.minY + "," + box.minZ
                + ";" + box.maxX + "," + box.maxY + "," + box.maxZ + "]";
    }
}
