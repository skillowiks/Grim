package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.utils.data.HitboxStateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class TriggerBotHitboxStatesTest {
    private static final Vec3 EYE = new Vec3(0, 1, 0);
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);
    private static final HitboxStateSnapshot FIXED = state(0, 0, 2.5, 0, 0, 2.5);
    private static final double UNCERTAINTY = 0.0002;

    @Test
    void depthMovementNeedsNoCommonHitboxVolume() {
        HitboxStateSnapshot moving = state(0, 0, 1.5, 0, 0, 2.8);
        Box outer = envelope(moving);
        assertEquals(Result.UNKNOWN, TriggerBotGeometry.classify(outer, null, List.of(EYE), List.of(FORWARD), 3));
        assertEquals(Result.INSIDE, classifyHitboxStates(outer, List.of(moving), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
    }

    @Test
    void bothOldAndCurrentStatesMustBeHitEvenWhenDisjoint() {
        HitboxStateSnapshot old = state(0, 0, 1.5, 0, 0, 1.5);
        assertTrue(envelope(old).maxZ() < envelope(FIXED).minZ());
        assertEquals(Result.INSIDE, classify(List.of(old, FIXED), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
        HitboxStateSnapshot missed = state(0.7, 0, 2.5, 0.7, 0, 2.5);
        assertEquals(Result.UNKNOWN, classify(List.of(old, missed), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
    }

    @Test
    void oneMissedFeetCornerPreventsInside() {
        HitboxStateSnapshot lateral = state(0, 0, 2.5, 0.5, 0, 2.5);
        assertEquals(Result.UNKNOWN, classify(List.of(lateral), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
        HitboxStateSnapshot far = state(0, 0, 1.5, 0, 0, 3.5);
        assertEquals(Result.UNKNOWN, classify(List.of(far), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
    }

    @Test
    void everyAdmissibleEyeAndDirectionMustHit() {
        assertEquals(Result.INSIDE, classify(List.of(FIXED), List.of(new Vec3(0, 0.4, 0), new Vec3(0, 1.27, 0),
                new Vec3(0, 1.62, 0)), List.of(FORWARD, new Vec3(0.01, 0, 1)), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE, new Vec3(0, 2, 0)), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD, new Vec3(1, 0, 0)), 3, UNCERTAINTY));
        assertEquals(Result.OUTSIDE, classify(List.of(FIXED), List.of(EYE), List.of(new Vec3(0, 0, -1)), 3, UNCERTAINTY));
    }

    @Test
    void originUncertaintyProtectsBothInsideAndOutsideBoundaries() {
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(new Vec3(0.3, 1, 0)), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(new Vec3(0.3001, 1, 0)), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.OUTSIDE, classify(List.of(FIXED), List.of(new Vec3(0.3005, 1, 0)), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.INSIDE, classify(List.of(FIXED), List.of(new Vec3(0.2995, 1, 0)), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD), 2.2, UNCERTAINTY));
        assertEquals(Result.INSIDE, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD), 2.201, UNCERTAINTY));
        assertEquals(Result.OUTSIDE, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD), 2.199, UNCERTAINTY));
    }

    @Test
    void collapsedContractionNeverManufacturesAnInteriorBox() {
        for (double width : new double[]{0.0001, 2 * (UNCERTAINTY + BOUNDARY_EPSILON)}) {
            HitboxStateSnapshot thin = new HitboxStateSnapshot(0, 0, 2.5, 0, 0, 2.5, width, 1.8, 0.6);
            assertEquals(Result.UNKNOWN, classify(List.of(thin), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
            assertEquals(Result.UNKNOWN, classify(List.of(thin), List.of(EYE), List.of(new Vec3(1, 0, 0)), 3, UNCERTAINTY));
        }
    }

    @Test
    void eyeOverlapAndInvalidOuterEnvelopeStayUnknown() {
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(new Vec3(0, 1, 2.5)), List.of(FORWARD), 3, UNCERTAINTY));
        Box incompleteOuter = new Box(-0.2, 0, 2, 0.2, 1.8, 3);
        assertEquals(Result.UNKNOWN, classifyHitboxStates(incompleteOuter, List.of(FIXED), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classifyHitboxStates(null, List.of(FIXED), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
    }

    @Test
    void invalidStatesAreUnknownEvenWhenTheOuterWouldBeMissed() {
        Box outer = envelope(FIXED);
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (HitboxStateSnapshot state : List.of(
                    new HitboxStateSnapshot(0, 0, 2.5, 0, 0, 2.5, invalid, 1.8, 0.6),
                    new HitboxStateSnapshot(0, 0, 2.5, 0, 0, 2.5, 0.6, invalid, 0.6),
                    new HitboxStateSnapshot(0, 0, 2.5, 0, 0, 2.5, 0.6, 1.8, invalid))) {
                assertEquals(Result.UNKNOWN, classifyHitboxStates(outer, List.of(state), List.of(EYE), List.of(new Vec3(1, 0, 0)), 3, UNCERTAINTY));
            }
        }
        for (HitboxStateSnapshot state : List.of(state(1, 0, 2.5, 0, 0, 2.5), state(0, 1, 2.5, 0, 0, 2.5),
                state(0, 0, 3, 0, 0, 2.5), state(Double.NaN, 0, 2.5, 0, 0, 2.5),
                state(0, 0, 2.5, 0, 0, Double.POSITIVE_INFINITY))) {
            assertEquals(Result.UNKNOWN, classifyHitboxStates(outer, List.of(state), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
        }
        assertEquals(Result.UNKNOWN, classifyHitboxStates(outer, null, List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classifyHitboxStates(outer, List.of(), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classifyHitboxStates(outer, Arrays.asList(FIXED, null), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
    }

    @Test
    void invalidRaysReachAndUncertaintyCannotPartiallyClassify() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD), invalid, UNCERTAINTY));
            assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD), 3, invalid));
            assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(new Vec3(0, invalid, 0)), List.of(FORWARD), 3, UNCERTAINTY));
            assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD, new Vec3(invalid, 0, 0)), 3, UNCERTAINTY));
        }
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD), -1, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(FORWARD), 3, -1));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(new Vec3(0, 0, 0)), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), null, List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), null, 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), List.of(), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), Arrays.asList(EYE, null), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), Arrays.asList(FORWARD, null), 3, UNCERTAINTY));
    }

    @Test
    void fixedBudgetsRejectOversizedInputs() {
        assertEquals(Result.UNKNOWN, classify(Collections.nCopies(3, FIXED), List.of(EYE), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), Collections.nCopies(7, EYE), List.of(FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.UNKNOWN, classify(List.of(FIXED), List.of(EYE), Collections.nCopies(4, FORWARD), 3, UNCERTAINTY));
        assertEquals(Result.INSIDE, classify(Collections.nCopies(2, FIXED), Collections.nCopies(6, EYE), Collections.nCopies(3, FORWARD), 3, UNCERTAINTY));
    }

    @Test
    void certifiedVerticesAlsoCoverInteriorPositionsAndDisplacedOrigins() {
        HitboxStateSnapshot moving = new HitboxStateSnapshot(-0.1, -0.1, 1, 0.1, 0.1, 2.5, 0.8, 2, 0.8);
        Vec3 direction = new Vec3(0.03, 0.01, 1);
        double uncertainty = 0.02;
        assertEquals(Result.INSIDE, classify(List.of(moving), List.of(EYE), List.of(direction), 3, uncertainty));
        // Independently sample actual interior boxes and all origin displacement
        // corners, rather than checking only the vertices used by the classifier.
        for (int ix = 0; ix <= 6; ix++) {
            for (int iy = 0; iy <= 6; iy++) {
                for (int iz = 0; iz <= 12; iz++) {
                    double x = -0.1 + ix / 30.0;
                    double y = -0.1 + iy / 30.0;
                    double z = 1 + iz / 8.0;
                    Box actual = new Box(x - 0.4, y, z - 0.4, x + 0.4, y + 2, z + 0.4);
                    for (int corner = 0; corner < 8; corner++) {
                        Vec3 displacedEye = new Vec3((corner & 1) == 0 ? -uncertainty : uncertainty,
                                1 + ((corner & 2) == 0 ? -uncertainty : uncertainty),
                                (corner & 4) == 0 ? -uncertainty : uncertainty);
                        assertTrue(rayDistance(actual, displacedEye, direction) <= 3);
                    }
                }
            }
        }
    }

    @Test
    void classificationLeavesSnapshotsAndListsUnchanged() {
        List<HitboxStateSnapshot> states = new ArrayList<>(List.of(FIXED));
        List<Vec3> eyes = new ArrayList<>(List.of(EYE));
        List<Vec3> directions = new ArrayList<>(List.of(FORWARD));
        assertEquals(Result.INSIDE, classify(states, eyes, directions, 3, UNCERTAINTY));
        assertEquals(List.of(FIXED), states);
        assertEquals(List.of(EYE), eyes);
        assertEquals(List.of(FORWARD), directions);
    }

    private static HitboxStateSnapshot state(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new HitboxStateSnapshot(minX, minY, minZ, maxX, maxY, maxZ, 0.6, 1.8, 0.6);
    }

    private static Box envelope(HitboxStateSnapshot state) {
        return new Box(state.minX() - state.width() / 2, state.minY(), state.minZ() - state.depth() / 2,
                state.maxX() + state.width() / 2, state.maxY() + state.height(), state.maxZ() + state.depth() / 2);
    }

    private static Result classify(List<HitboxStateSnapshot> states, List<Vec3> eyes, List<Vec3> directions,
                                   double reach, double uncertainty) {
        Box outer = envelope(states.get(0));
        for (HitboxStateSnapshot state : states) {
            Box next = envelope(state);
            outer = new Box(Math.min(outer.minX(), next.minX()), Math.min(outer.minY(), next.minY()), Math.min(outer.minZ(), next.minZ()),
                    Math.max(outer.maxX(), next.maxX()), Math.max(outer.maxY(), next.maxY()), Math.max(outer.maxZ(), next.maxZ()));
        }
        return classifyHitboxStates(outer, states, eyes, directions, reach, uncertainty);
    }
}
