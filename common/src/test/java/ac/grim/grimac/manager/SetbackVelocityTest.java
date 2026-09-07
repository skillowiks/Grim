package ac.grim.grimac.manager;

import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetbackVelocityTest {
    @Test
    void repeatedSetbacksDoNotAccumulateExplosionOrFrictionInPendingKnockback() {
        Vector3dm previous = new Vector3dm(0.1, -0.2, 0.3);
        VelocityData knockback = new VelocityData(1, 10, false, new Vector3dm(0.2, 0.4, -0.1));
        VelocityData explosion = new VelocityData(-1, 12, false, new Vector3dm(0.5, 1.2, 0.25));
        for (int i = 0; i < 10; i++) {
            Vector3dm setback = SetbackVelocity.compose(previous, knockback, explosion);
            assertVector(0.7, 1.6, 0.15, setback);
            // The caller performs collisions and friction on the returned vector.
            setback.setX(0);
            setback.multiply(0.91, 0.98, 0.91);
            setback.add(0, -0.08, 0);
        }
        assertVector(0.1, -0.2, 0.3, previous);
        assertVector(0.2, 0.4, -0.1, knockback.vector);
        assertVector(0.5, 1.2, 0.25, explosion.vector);
    }

    @Test
    void laterKnockbackOverridesEarlierExplosion() {
        VelocityData knockback = new VelocityData(1, 12, false, new Vector3dm(0.2, 0.4, -0.1));
        VelocityData explosion = new VelocityData(-1, 10, false, new Vector3dm(0.5, 1.2, 0.25));
        assertVector(0.2, 0.4, -0.1, SetbackVelocity.compose(new Vector3dm(), knockback, explosion));
    }

    @Test
    void explosionWithoutKnockbackAddsToPreviousVelocityWithoutChangingIt() {
        Vector3dm previous = new Vector3dm(0.1, -0.2, 0.3);
        VelocityData explosion = new VelocityData(-1, 10, false, new Vector3dm(0.5, 1.2, 0.25));
        assertVector(0.6, 1.0, 0.55, SetbackVelocity.compose(previous, null, explosion));
        assertVector(0.1, -0.2, 0.3, previous);
    }

    @Test
    void doesNotReuseEarlierSetbackVelocity() {
        Vector3dm previous = new Vector3dm(0.1, -0.2, 0.3);
        VelocityData knockback = new VelocityData(1, 10, true, new Vector3dm(0.2, 0.4, -0.1));
        VelocityData explosion = new VelocityData(-1, 12, false, new Vector3dm(0.5, 1.2, 0.25));
        Vector3dm result = SetbackVelocity.compose(previous, knockback, explosion);
        assertVector(0.1, -0.2, 0.3, result);
        result.setY(0);
        assertVector(0.1, -0.2, 0.3, previous);
    }

    private static void assertVector(double x, double y, double z, Vector3dm actual) {
        assertEquals(x, actual.getX(), 1e-12);
        assertEquals(y, actual.getY(), 1e-12);
        assertEquals(z, actual.getZ(), 1e-12);
    }
}
