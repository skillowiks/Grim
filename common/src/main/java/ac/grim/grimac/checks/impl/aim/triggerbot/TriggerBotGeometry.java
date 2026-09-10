package ac.grim.grimac.checks.impl.aim.triggerbot;

import java.util.List;

/** Geometry only: no player state, packet handling, or check buffers are accessed. */
public final class TriggerBotGeometry {
    public static final double BOUNDARY_EPSILON = 1.0E-6;
    public static final int MAX_EYES = 6;
    public static final int MAX_DIRECTIONS = 3;

    private TriggerBotGeometry() {
    }

    public record Vec3(double x, double y, double z) {
    }

    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    public enum Result {
        OUTSIDE, INSIDE, UNKNOWN
    }

    /**
     * Tests every admissible eye/direction pair. The caller supplies an outer union
     * and, if known, an inner intersection of plausible target boxes; this method
     * never treats a shrunken outer union as that intersection. Occlusion is the
     * caller's responsibility and must veto INSIDE when it is ambiguous.
     */
    public static Result classify(Box outer, Box inner, List<Vec3> eyes, List<Vec3> directions, double maxReach) {
        if (!valid(outer) || !Double.isFinite(maxReach) || maxReach < 0
                || eyes == null || eyes.isEmpty() || eyes.size() > MAX_EYES
                || directions == null || directions.isEmpty() || directions.size() > MAX_DIRECTIONS) {
            return Result.UNKNOWN;
        }
        if (inner != null && (!valid(inner) || !contains(outer, inner))) return Result.UNKNOWN;

        Box expanded = expand(outer);
        if (!valid(expanded)) return Result.UNKNOWN;
        Box contracted = inner == null ? null : shrink(inner);
        boolean everyRayInside = valid(contracted);
        Vec3[] eyeSnapshots = new Vec3[eyes.size()];
        for (int i = 0; i < eyeSnapshots.length; i++) {
            Vec3 eye = eyes.get(i);
            // An overlapping eye or an eye on a face gives no useful acquisition event.
            if (!finite(eye) || contains(expanded, eye)) return Result.UNKNOWN;
            eyeSnapshots[i] = eye;
        }

        Vec3[] unitDirections = new Vec3[directions.size()];
        for (int i = 0; i < unitDirections.length; i++) {
            unitDirections[i] = normalize(directions.get(i));
            if (unitDirections[i] == null) return Result.UNKNOWN;
        }

        boolean anyRayHitsOuter = false;
        for (Vec3 eye : eyeSnapshots) {
            for (Vec3 direction : unitDirections) {
                if (rayDistanceUnit(expanded, eye, direction) <= maxReach) anyRayHitsOuter = true;
                if (everyRayInside && rayDistanceUnit(contracted, eye, direction) > maxReach) everyRayInside = false;
            }
        }
        if (!anyRayHitsOuter) return Result.OUTSIDE;
        return everyRayInside ? Result.INSIDE : Result.UNKNOWN;
    }

    /**
     * Forward ray/AABB distance in world units, independent of direction magnitude.
     * Returns zero for an origin inside/on the box and positive infinity for a miss
     * or invalid input. Callers must reject invalid blocker data as unknown first.
     */
    public static double rayDistance(Box box, Vec3 origin, Vec3 direction) {
        if (!valid(box) || !finite(origin)) return Double.POSITIVE_INFINITY;
        Vec3 unit = normalize(direction);
        return unit == null ? Double.POSITIVE_INFINITY : rayDistanceUnit(box, origin, unit);
    }

    private static double rayDistanceUnit(Box box, Vec3 origin, Vec3 direction) {
        double near = 0;
        double far = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double minimum = axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
            double maximum = axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
            double start = axis == 0 ? origin.x : axis == 1 ? origin.y : origin.z;
            double delta = axis == 0 ? direction.x : axis == 1 ? direction.y : direction.z;
            if (delta == 0) {
                if (start < minimum || start > maximum) return Double.POSITIVE_INFINITY;
                continue;
            }
            double first = (minimum - start) / delta;
            double last = (maximum - start) / delta;
            if (first > last) {
                double swap = first;
                first = last;
                last = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, last);
            if (far < near) return Double.POSITIVE_INFINITY;
        }
        return near;
    }

    private static Vec3 normalize(Vec3 direction) {
        if (!finite(direction)) return null;
        double scale = Math.max(Math.abs(direction.x), Math.max(Math.abs(direction.y), Math.abs(direction.z)));
        if (scale == 0) return null;
        double x = direction.x / scale;
        double y = direction.y / scale;
        double z = direction.z / scale;
        double length = Math.sqrt(x * x + y * y + z * z);
        return new Vec3(x / length, y / length, z / length);
    }

    private static Box expand(Box box) {
        return new Box(Math.nextDown(box.minX - BOUNDARY_EPSILON), Math.nextDown(box.minY - BOUNDARY_EPSILON),
                Math.nextDown(box.minZ - BOUNDARY_EPSILON), Math.nextUp(box.maxX + BOUNDARY_EPSILON),
                Math.nextUp(box.maxY + BOUNDARY_EPSILON), Math.nextUp(box.maxZ + BOUNDARY_EPSILON));
    }

    private static Box shrink(Box box) {
        return new Box(Math.nextUp(box.minX + BOUNDARY_EPSILON), Math.nextUp(box.minY + BOUNDARY_EPSILON),
                Math.nextUp(box.minZ + BOUNDARY_EPSILON), Math.nextDown(box.maxX - BOUNDARY_EPSILON),
                Math.nextDown(box.maxY - BOUNDARY_EPSILON), Math.nextDown(box.maxZ - BOUNDARY_EPSILON));
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean valid(Box box) {
        return box != null && Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ;
    }

    private static boolean contains(Box box, Vec3 point) {
        return point.x >= box.minX && point.x <= box.maxX && point.y >= box.minY && point.y <= box.maxY
                && point.z >= box.minZ && point.z <= box.maxZ;
    }

    private static boolean contains(Box outer, Box inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }
}
