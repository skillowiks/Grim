package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KnockbackMovementSkippingTest {
    @Test
    void reportWallKnockbackCanHideItsThresholdedTickBeforeGravity() {
        // ZnfejNsSeY, trace 2: replacement motion while touching end stone bricks.
        VelocityData motion = motion(-0.2152841359946286, 0.002990905206616601, 0);
        VectorData candidate = candidates(ClientVersion.V_1_21_11, false, false, null, motion).iterator().next();
        SimpleCollisionBox player = new SimpleCollisionBox(59, 71.00133597911214, 1897,
                59.60000002384186, 72.80133593142842, 1897.600000023842);
        SimpleCollisionBox wall = new SimpleCollisionBox(58, 72, 1897, 59, 73, 1898);

        Vector3dm hiddenMovement = candidate.vector.clone();
        hiddenMovement.setX(wall.collideX(player, hiddenMovement.getX()));
        assertEquals(0, hiddenMovement.lengthSquared());
        assertTrue(Math.abs(motion.vector.getY()) > 0.0002); // Raw motion incorrectly rules out a hidden tick.
        assertTrue(Math.abs(candidate.vector.getX()) > 0.2); // Still cannot hide this KB without the wall.

        double nextY = (hiddenMovement.getY() - 0.08) * (double) 0.98F;
        double[] recordedY = {-0.07840000152587834, -0.15523200451660557,
                -0.23052736891295922, -0.30431682745754074};
        for (double actual : recordedY) {
            assertEquals(actual, nextY, 1e-12);
            nextY = (nextY - 0.08) * (double) 0.98F;
        }
        assertTrue(candidate.isKnockback());
        assertFalse(candidate.isFirstBreadKb());
        assertEquals(0.002990905206616601, motion.vector.getY());
        assertEquals(-0.2152841359946286, motion.vector.getX());
    }

    @Test
    void verticalThresholdIsStrictAndVersionDependent() {
        for (ClientVersion version : new ClientVersion[]{ClientVersion.V_1_8, ClientVersion.V_1_21_4, ClientVersion.V_1_21_11}) {
            double threshold = version == ClientVersion.V_1_8 ? 0.005 : 0.003;
            for (int sign : new int[]{-1, 1}) {
                assertEquals(0, normalized(version, 0.2, sign * Math.nextDown(threshold), 0).getY());
                assertEquals(sign * threshold, normalized(version, 0.2, sign * threshold, 0).getY());
                assertEquals(sign * Math.nextUp(threshold), normalized(version, 0.2, sign * Math.nextUp(threshold), 0).getY());
            }
        }
    }

    @Test
    void modernDiagonalUsesHorizontalLengthInsteadOfComponentThresholds() {
        assertVector(0, 0, 0, normalized(ClientVersion.V_1_21_4, 0.0025, 0.002, -0.0025));
        assertVector(0.0025, 0, -0.0025, normalized(ClientVersion.V_1_21_5, 0.0025, 0.002, -0.0025));
        assertVector(0, 0, 0, normalized(ClientVersion.V_1_21_11, 0.002, 0.002, -0.002));
        assertVector(0.003, 0, 0, normalized(ClientVersion.V_1_21_11, 0.003, 0.002, 0));
    }

    @Test
    void edgeAlternativeAndVehicleThresholdRulesArePreserved() {
        VelocityData motion = motion(0.002, 0.002, -0.002);
        Set<VectorData> edge = candidates(ClientVersion.V_1_21_11, false, true, motion, null);
        assertEquals(2, edge.size());
        assertTrue(edge.stream().allMatch(v -> v.isKnockback() && v.isFirstBreadKb() && v.vector.getY() == 0));
        assertTrue(edge.stream().anyMatch(v -> v.vector.getX() == 0 && v.vector.getZ() == 0));
        assertTrue(edge.stream().anyMatch(v -> v.vector.getX() == 0.002 && v.vector.getZ() == -0.002));
        assertVector(0.002, 0.002, -0.002, motion.vector);

        Set<VectorData> vehicle = candidates(ClientVersion.V_1_21_11, true, true, motion(0.0025, 0.002, -0.0025), null);
        assertEquals(1, vehicle.size());
        assertVector(0, 0, 0, vehicle.iterator().next().vector);
    }

    @Test
    void firstAndRequiredKnockbackKeepIndependentCopiesAndProvenance() {
        VelocityData first = motion(0.1, 0.002, 0.2);
        VelocityData likely = motion(0.1, 0.002, 0.2);
        Set<VectorData> result = candidates(ClientVersion.V_1_21_11, false, false, first, likely);
        assertEquals(2, result.size());
        VectorData firstCandidate = result.stream().filter(VectorData::isFirstBreadKb).findFirst().orElseThrow();
        VectorData required = result.stream().filter(v -> !v.isFirstBreadKb()).findFirst().orElseThrow();
        firstCandidate.vector.setX(10);
        assertVector(0.1, 0, 0.2, required.vector);
        assertVector(0.1, 0.002, 0.2, first.vector);
        assertVector(0.1, 0.002, 0.2, likely.vector);
        assertTrue(result.stream().allMatch(VectorData::isKnockback));
        assertTrue(candidates(ClientVersion.V_1_21_11, false, false, null, null).isEmpty());
    }

    private static Vector3dm normalized(ClientVersion version, double x, double y, double z) {
        return candidates(version, false, false, null, motion(x, y, z)).iterator().next().vector;
    }

    private static Set<VectorData> candidates(ClientVersion version, boolean inVehicle, boolean edge,
                                               VelocityData first, VelocityData likely) {
        return PredictionEngine.knockbackForMovementSkipping(version, inVehicle, edge, first, likely);
    }

    private static VelocityData motion(double x, double y, double z) {
        return new VelocityData(1, 100, false, new Vector3dm(x, y, z));
    }

    private static void assertVector(double x, double y, double z, Vector3dm vector) {
        assertEquals(x, vector.getX());
        assertEquals(y, vector.getY());
        assertEquals(z, vector.getZ());
    }
}
