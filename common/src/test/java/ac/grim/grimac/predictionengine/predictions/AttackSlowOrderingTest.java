package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AttackSlowOrderingTest {
    @Test
    void reportKnockbackReplacesEarlierAttackSlowdownWithoutExemptingNormalMovement() {
        // hFdlA2aD0a: attack tx15984, motion tx15991, movement tx15995.
        VelocityData motion = motion(15991);
        VectorData knockback = candidate(motion, false);
        VectorData normal = new VectorData(new Vector3dm(0.1, -0.0784, -0.2), VectorData.VectorType.Normal);
        Set<VectorData> candidates = new HashSet<>(Set.of(knockback, normal));

        assertEquals(0, PredictionEngine.applyAttackSlow(candidates, 1, 1, 15984, null, motion));

        // This is the measured movement, not an offset exemption: the KB candidate
        // keeps its provenance so AntiKB still checks the full replacement vector.
        assertVector(0.10737499999959255, 0.20087500000000347, 0.21462499999961437, knockback.vector);
        assertTrue(knockback.isKnockback());
        assertFalse(knockback.isAttackSlow());
        assertVector(0.06, -0.0784, -0.12, normal.vector);
        assertTrue(normal.isAttackSlow());
        assertEquals(2, candidates.size());
        assertVector(0.107375, 0.200875, 0.214625, motion.vector);
    }

    @Test
    void attackInOrAfterKnockbackIntervalRetainsRequiredSlowdown() {
        for (int attackTransaction : new int[]{15991, 15995}) {
            VelocityData motion = motion(15991);
            VectorData knockback = candidate(motion, false);
            PredictionEngine.applyAttackSlow(new HashSet<>(Set.of(knockback)), 1, 1, attackTransaction, null, motion);
            assertVector(0.064425, 0.200875, 0.128775, knockback.vector);
            assertTrue(knockback.isAttackSlow());
            assertTrue(knockback.isKnockback());
        }
    }

    @Test
    void firstBreadUsesItsOwnTransactionInsteadOfOlderRequiredKnockback() {
        VelocityData firstMotion = motion(15991);
        VelocityData requiredMotion = motion(15980);
        VectorData first = candidate(firstMotion, true);
        VectorData required = candidate(requiredMotion, false);
        Set<VectorData> candidates = new HashSet<>(Set.of(first, required));

        PredictionEngine.applyAttackSlow(candidates, 1, 1, 15984, firstMotion, requiredMotion);

        assertVector(0.107375, 0.200875, 0.214625, first.vector);
        assertTrue(first.isFirstBreadKb());
        assertFalse(first.isAttackSlow());
        assertVector(0.064425, 0.200875, 0.128775, required.vector);
        assertTrue(required.isAttackSlow());
    }

    @Test
    void optionalOldAttackDoesNotInventSlowedReplacementKnockback() {
        VelocityData motion = motion(15991);
        VectorData knockback = candidate(motion, false);
        VectorData normal = new VectorData(new Vector3dm(0.1, 0.2, 0.3), VectorData.VectorType.Normal);
        Set<VectorData> candidates = new HashSet<>(Set.of(knockback, normal));

        assertEquals(0, PredictionEngine.applyAttackSlow(candidates, 0, 1, 15984, null, motion));

        assertEquals(3, candidates.size());
        assertEquals(1, candidates.stream().filter(VectorData::isKnockback).count());
        assertTrue(candidates.stream().anyMatch(data -> !data.isKnockback() && data.isAttackSlow()
                && Math.abs(data.vector.getX() - 0.06) < 1e-12));
        assertVector(0.107375, 0.200875, 0.214625, knockback.vector);
    }

    @Test
    void explosionAddedToNewerFirstBreadKnockbackKeepsReplacementOrdering() {
        VelocityData motion = motion(15991);
        VectorData decorated = candidate(motion, true).returnNewModified(
                motion.vector.clone().add(0.2, 0.3, -0.1), VectorData.VectorType.Explosion);
        PredictionEngine.applyAttackSlow(new HashSet<>(Set.of(decorated)), 1, 1, 15984, motion, motion(15980));
        assertVector(0.307375, 0.500875, 0.114625, decorated.vector);
        assertTrue(decorated.isKnockback());
        assertTrue(decorated.isFirstBreadKb());
        assertTrue(decorated.isExplosion());
        assertFalse(decorated.isAttackSlow());
    }

    @Test
    void explosionWithoutReplacementKnockbackStillUsesAttackSlowdown() {
        VectorData explosion = new VectorData(new Vector3dm(0.1, 0.2, 0.3), VectorData.VectorType.Explosion);
        PredictionEngine.applyAttackSlow(new HashSet<>(Set.of(explosion)), 1, 1, 15984, null, motion(15991));
        assertVector(0.06, 0.2, 0.18, explosion.vector);
        assertTrue(explosion.isAttackSlow());
    }

    @Test
    void missingOrderingEvidenceDoesNotRemoveRequiredSlowdown() {
        VelocityData motion = motion(15991);
        VectorData unknownAttack = candidate(motion, false);
        PredictionEngine.applyAttackSlow(new HashSet<>(Set.of(unknownAttack)), 1, 1, Integer.MAX_VALUE, null, motion);
        assertVector(0.064425, 0.200875, 0.128775, unknownAttack.vector);

        VectorData unknownMotion = candidate(motion, false);
        PredictionEngine.applyAttackSlow(new HashSet<>(Set.of(unknownMotion)), 1, 1, 15984, null, null);
        assertVector(0.064425, 0.200875, 0.128775, unknownMotion.vector);
    }

    @Test
    void newerAttackKeepsAccumulatedSlowdownsAndExistingIterationLimit() {
        VelocityData motion = motion(15991);
        VectorData knockback = candidate(motion, false);
        int remaining = PredictionEngine.applyAttackSlow(new HashSet<>(Set.of(knockback)), 6, 6, 15995, null, motion);
        assertEquals(1, remaining);
        assertVector(0.107375 * Math.pow(0.6, 5), 0.200875, 0.214625 * Math.pow(0.6, 5), knockback.vector);
    }

    private static VelocityData motion(int transaction) {
        return new VelocityData(1, transaction, false, new Vector3dm(0.107375, 0.200875, 0.214625));
    }

    private static VectorData candidate(VelocityData motion, boolean firstBread) {
        VectorData candidate = new VectorData(motion.vector.clone(), VectorData.VectorType.Knockback);
        return firstBread ? candidate.returnNewModified(VectorData.VectorType.FirstBreadKnockback) : candidate;
    }

    private static void assertVector(double x, double y, double z, Vector3dm actual) {
        assertEquals(x, actual.getX(), 1e-12);
        assertEquals(y, actual.getY(), 1e-12);
        assertEquals(z, actual.getZ(), 1e-12);
    }
}
