package ac.grim.grimac.predictionengine;

import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorizontalCollisionUncertaintyTest {
    private static final double WALL_MOVEMENT = 0.1853089801134047;

    @Test
    void preservesBothNextTickOutcomesAfterSeekingEpsilonHidesWall() {
        // Report 0H2c58hhyE: uncertainty selected the exact distance to the Z wall.
        // Carrying that distance through friction gives the recorded bad start.
        Vector3dm raw = new Vector3dm(-0.11648390620484861, 0.15094515046878357, 0.26381959604247235);
        Vector3dm collided = raw.clone().setZ(WALL_MOVEMENT);
        Vector3dm probe = collided.clone().setZ(WALL_MOVEMENT + SimpleCollisionBox.COLLISION_EPSILON);
        assertTrue(GrimMath.equal(probe.getZ(), collided.getZ()));
        int axes = HorizontalCollisionUncertainty.hiddenAxes(raw, probe, collided);

        Vector3dm nextVelocity = collided.clone().multiply((double) 0.91F, 1, (double) 0.91F);
        assertEquals(0.16863117676311967, nextVelocity.getZ(), 1e-15);
        Set<VectorData> candidates = normal(nextVelocity);
        HorizontalCollisionUncertainty.addCandidates(candidates, nextVelocity, axes);

        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(v -> v.vector == nextVelocity));
        VectorData reset = candidates.stream().filter(v -> v.vectorType == VectorData.VectorType.CollisionReset).findFirst().orElseThrow();
        assertEquals(nextVelocity.getX(), reset.vector.getX());
        assertEquals(nextVelocity.getY(), reset.vector.getY());
        double inputZ = -0.017932950110662205;
        assertEquals(inputZ, reset.vector.getZ() + inputZ);
        assertTrue(nextVelocity.getZ() + inputZ > 0);
        reset.vector.setX(10);
        assertEquals(-0.10600035770132425, nextVelocity.getX(), 1e-15);
    }

    @Test
    void mirrorsNegativeAxisAndKeepsCornerAlternatives() {
        Vector3dm raw = new Vector3dm(-0.3, -0.2, 0.4);
        Vector3dm collided = new Vector3dm(-0.1, -0.2, 0.2);
        Vector3dm probe = collided.clone().add(new Vector3dm(-SimpleCollisionBox.COLLISION_EPSILON, 0, SimpleCollisionBox.COLLISION_EPSILON));
        Set<VectorData> candidates = normal(raw);
        HorizontalCollisionUncertainty.addCandidates(candidates, raw, HorizontalCollisionUncertainty.hiddenAxes(raw, probe, collided));
        assertEquals(4, candidates.size());
        assertTrue(candidates.stream().anyMatch(v -> v.vector.getX() == 0 && v.vector.getZ() == 0));
        assertTrue(candidates.stream().allMatch(v -> v.vector.getY() == -0.2));
    }

    @Test
    void doesNotResetGenuineSubToleranceClipping() {
        Vector3dm raw = new Vector3dm(0.1, 0, WALL_MOVEMENT + 0.000001);
        Vector3dm probe = raw.clone().setZ(raw.getZ() + SimpleCollisionBox.COLLISION_EPSILON);
        assertEquals(0, HorizontalCollisionUncertainty.hiddenAxes(raw, probe, raw.clone().setZ(WALL_MOVEMENT)));
    }

    @Test
    void requiresAnActualProbeCollisionAndReducedMovement() {
        Vector3dm raw = new Vector3dm(0, 0, 0.3);
        Vector3dm noWall = new Vector3dm(0, 0, 0.1);
        assertEquals(0, HorizontalCollisionUncertainty.hiddenAxes(raw, noWall, noWall));
        Vector3dm probe = noWall.clone().setZ(0.1 + SimpleCollisionBox.COLLISION_EPSILON);
        assertEquals(0, HorizontalCollisionUncertainty.hiddenAxes(new Vector3dm(0, 0, 0.01), probe, noWall));
        assertEquals(0, HorizontalCollisionUncertainty.hiddenAxes(new Vector3dm(0, 0, -0.3), probe, noWall));
    }

    @Test
    void ordinaryCollisionOrEmptyMaskDoesNotAddAlternatives() {
        Vector3dm raw = new Vector3dm(0.3, 0.2, 0.4);
        Vector3dm collided = new Vector3dm(0.1, 0.2, 0.1);
        assertFalse(GrimMath.equal(raw.getZ(), collided.getZ()));
        int axes = HorizontalCollisionUncertainty.hiddenAxes(raw, raw, collided);
        assertEquals(0, axes);
        Set<VectorData> candidates = normal(raw);
        HorizontalCollisionUncertainty.addCandidates(candidates, raw, axes);
        assertEquals(1, candidates.size());
    }

    private static Set<VectorData> normal(Vector3dm velocity) {
        Set<VectorData> result = new HashSet<>();
        result.add(new VectorData(velocity, VectorData.VectorType.Normal));
        return result;
    }
}
