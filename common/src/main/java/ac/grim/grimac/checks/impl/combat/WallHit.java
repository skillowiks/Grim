package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayList;
import java.util.List;

// Invoked by Reach, which already computes every possible eye position / look vector intercept with the target.
// Vanilla only lets the entity raycast win if it is closer than the block raycast (GameRenderer#pick),
// so if every possible ray to the target is obstructed by a block, the hit went through a wall.
@CheckData(name = "WallHit", stableKey = "grim.combat.wallhit", description = "Attacked an entity through a block")
public class WallHit extends Check {
    public WallHit(GrimPlayer player) {
        super(player);
    }

    /**
     * @return true if the segment from the eye to the entity intercept is not obstructed by any solid block
     */
    public boolean isRayClear(Vector3d eyePos, Vector3d entityIntercept) {
        return WorldRayTrace.traverseBlocks(player, eyePos, entityIntercept, (block, pos) -> getObstruction(block, pos, eyePos, entityIntercept)) == null;
    }

    private HitData getObstruction(WrappedBlockState block, Vector3i pos, Vector3d eyePos, Vector3d entityIntercept) {
        if (block.getType().isAir()) return null;

        // Be lenient: only blocks that you can't walk through (no grass, flowers, torches, etc.) block hits
        if (CollisionData.getData(block.getType()).getMovementCollisionBox(player, player.getClientVersion(), block, pos.getX(), pos.getY(), pos.getZ()).isNull()) {
            return null;
        }

        CollisionBox data = HitboxData.getBlockHitbox(player, null, player.getClientVersion(), block, false, pos.getX(), pos.getY(), pos.getZ());
        if (data.isNull()) return null;

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        data.downCast(boxes);

        for (SimpleCollisionBox box : boxes) {
            // Eye height uncertainty can put the eye inside a block, don't count that block
            if (ReachUtils.isVecInside(box, eyePos)) continue;

            Vector3d intercept = ReachUtils.calculateIntercept(box, eyePos, entityIntercept).first();
            if (intercept != null) {
                return new HitData(pos, Vector3dm.from(intercept), null, block);
            }
        }

        return null;
    }
}
