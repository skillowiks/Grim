package ac.grim.grimac.predictionengine;

import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;

import java.util.Set;

/** Retains both outcomes when velocity uncertainty hides a horizontal wall collision. */
public final class HorizontalCollisionUncertainty {
    private static final int X = 1;
    private static final int Z = 2;

    private HorizontalCollisionUncertainty() {
    }

    public static int hiddenAxes(Vector3dm beforeUncertainty, Vector3dm collisionProbe, Vector3dm collided) {
        int axes = 0;
        if (hiddenCollision(beforeUncertainty.getX(), collisionProbe.getX(), collided.getX())) axes |= X;
        if (hiddenCollision(beforeUncertainty.getZ(), collisionProbe.getZ(), collided.getZ())) axes |= Z;
        return axes;
    }

    private static boolean hiddenCollision(double original, double probe, double collided) {
        // The probe really hit a wall, but the uncertainty-adjusted movement only
        // differs by the seeking epsilon. Modern clients compare with a larger
        // tolerance, so that probe alone cannot tell us whether momentum was lost.
        return probe != collided && GrimMath.equal(probe, collided)
                && !GrimMath.equal(original, collided)
                && Math.signum(original) == Math.signum(probe)
                && Math.abs(original) > Math.abs(probe)
                && (collided == 0 || Math.signum(collided) == Math.signum(probe));
    }

    public static void addCandidates(Set<VectorData> candidates, Vector3dm velocity, int axes) {
        for (int reset = 1; reset <= (X | Z); reset++) {
            if ((reset & axes) != reset) continue;
            Vector3dm alternative = velocity.clone();
            if ((reset & X) != 0) alternative.setX(0);
            if ((reset & Z) != 0) alternative.setZ(0);
            candidates.add(new VectorData(alternative, VectorData.VectorType.CollisionReset));
        }
    }
}
