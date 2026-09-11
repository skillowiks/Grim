package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.utils.data.HitboxStateSnapshot;

import java.util.List;

/** Geometry only: no player state, packet handling, or check buffers are accessed. */
public final class TriggerBotGeometry {
    public static final double BOUNDARY_EPSILON = 1.0E-6;
    public static final int MAX_EYES = 6;
    public static final int MAX_DIRECTIONS = 3;
    public static final int MAX_HITBOX_STATES = 2;
    private static final int MAX_STATE_VERTICES = 8;

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
     * Certifies each fixed-size hitbox represented by the supplied feet envelopes,
     * without requiring the boxes to share a common volume. For a finite ray
     * segment S and a fixed box B, the feet positions for which S intersects p+B
     * form the convex Minkowski sum S+(-B). Therefore hitting every vertex of a
     * feet envelope certifies every position inside it. This is applied separately
     * to both interpolation states and every admissible eye/direction pair.
     *
     * The origin uncertainty is an axis-wise displacement bound. Expanding the
     * outer envelope protects OUTSIDE; contracting each candidate box protects
     * INSIDE. Invalid or collapsed geometry stays UNKNOWN. At most two states,
     * eight vertices per state, six eyes and three directions are examined.
     * Occlusion remains the caller's responsibility.
     */
    public static Result classifyHitboxStates(Box outer, List<HitboxStateSnapshot> states,
                                             List<Vec3> eyes, List<Vec3> directions,
                                             double maxReach, double originUncertainty) {
        if (!valid(outer) || !Double.isFinite(maxReach) || maxReach < 0
                || !Double.isFinite(originUncertainty) || originUncertainty < 0
                || states == null || states.isEmpty() || states.size() > MAX_HITBOX_STATES
                || eyes == null || eyes.isEmpty() || eyes.size() > MAX_EYES
                || directions == null || directions.isEmpty() || directions.size() > MAX_DIRECTIONS) {
            return Result.UNKNOWN;
        }
        double margin = originUncertainty + BOUNDARY_EPSILON;
        Box expanded = expand(outer, margin);
        if (!valid(expanded)) return Result.UNKNOWN;

        Box[] candidates = new Box[MAX_HITBOX_STATES * MAX_STATE_VERTICES];
        int candidateCount = 0;
        for (HitboxStateSnapshot state : states) {
            if (!valid(state)) return Result.UNKNOWN;
            Box envelope = new Box(state.minX() - state.width() / 2, state.minY(), state.minZ() - state.depth() / 2,
                    state.maxX() + state.width() / 2, state.maxY() + state.height(), state.maxZ() + state.depth() / 2);
            if (!valid(envelope) || !contains(outer, envelope)) return Result.UNKNOWN;
            for (int corner = 0; corner < MAX_STATE_VERTICES; corner++) {
                // Degenerate feet ranges need only one endpoint per axis.
                if (((corner & 1) != 0 && state.minX() == state.maxX())
                        || ((corner & 2) != 0 && state.minY() == state.maxY())
                        || ((corner & 4) != 0 && state.minZ() == state.maxZ())) continue;
                double x = (corner & 1) == 0 ? state.minX() : state.maxX();
                double y = (corner & 2) == 0 ? state.minY() : state.maxY();
                double z = (corner & 4) == 0 ? state.minZ() : state.maxZ();
                Box candidate = new Box(Math.nextUp(x - state.width() / 2 + margin), Math.nextUp(y + margin),
                        Math.nextUp(z - state.depth() / 2 + margin), Math.nextDown(x + state.width() / 2 - margin),
                        Math.nextDown(y + state.height() - margin), Math.nextDown(z + state.depth() / 2 - margin));
                // Never reorder inverted bounds into an invented positive-volume box.
                if (!valid(candidate)) return Result.UNKNOWN;
                candidates[candidateCount++] = candidate;
            }
        }

        Vec3[] eyeSnapshots = new Vec3[eyes.size()];
        for (int i = 0; i < eyeSnapshots.length; i++) {
            Vec3 eye = eyes.get(i);
            if (!finite(eye) || contains(expanded, eye)) return Result.UNKNOWN;
            eyeSnapshots[i] = eye;
        }
        Vec3[] unitDirections = new Vec3[directions.size()];
        for (int i = 0; i < unitDirections.length; i++) {
            unitDirections[i] = normalize(directions.get(i));
            if (unitDirections[i] == null) return Result.UNKNOWN;
        }

        boolean anyRayHitsOuter = false;
        boolean everyRayInside = true;
        for (Vec3 eye : eyeSnapshots) {
            for (Vec3 direction : unitDirections) {
                if (rayDistanceUnit(expanded, eye, direction) <= maxReach) anyRayHitsOuter = true;
                if (everyRayInside) {
                    for (int i = 0; i < candidateCount; i++) {
                        if (rayDistanceUnit(candidates[i], eye, direction) > maxReach) {
                            everyRayInside = false;
                            break;
                        }
                    }
                }
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

    private static Box expand(Box box, double margin) {
        return new Box(Math.nextDown(box.minX - margin), Math.nextDown(box.minY - margin),
                Math.nextDown(box.minZ - margin), Math.nextUp(box.maxX + margin),
                Math.nextUp(box.maxY + margin), Math.nextUp(box.maxZ + margin));
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

    private static boolean valid(HitboxStateSnapshot state) {
        return state != null && Double.isFinite(state.minX()) && Double.isFinite(state.minY()) && Double.isFinite(state.minZ())
                && Double.isFinite(state.maxX()) && Double.isFinite(state.maxY()) && Double.isFinite(state.maxZ())
                && state.minX() <= state.maxX() && state.minY() <= state.maxY() && state.minZ() <= state.maxZ()
                && Double.isFinite(state.width()) && Double.isFinite(state.height()) && Double.isFinite(state.depth())
                && state.width() > 0 && state.height() > 0 && state.depth() > 0;
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
