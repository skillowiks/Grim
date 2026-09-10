package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Box;
import ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result;
import ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.classify;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.rayDistance;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TriggerBotGeometryTest {
    private static final Box TARGET = new Box(-0.3, 0, 2, 0.3, 1.8, 2.6);
    private static final Vec3 EYE = new Vec3(0, 1.62, 0);
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);

    @Test
    void faceIntersectionsUseWorldDistanceForAnyDirectionMagnitude() {
        assertEquals(2, rayDistance(TARGET, EYE, FORWARD), 1e-12);
        assertEquals(2, rayDistance(TARGET, EYE, new Vec3(0, 0, 50)), 1e-12);
        assertEquals(2, rayDistance(TARGET, EYE, new Vec3(0, 0, Double.MIN_VALUE)), 1e-12);
        assertEquals(2, rayDistance(TARGET, EYE, new Vec3(0, 0, Double.MAX_VALUE)), 1e-12);
        assertEquals(2.4, rayDistance(TARGET, new Vec3(0, 1, 5), new Vec3(0, 0, -2)), 1e-12);
    }

    @Test
    void diagonalCornerDistanceIsNotAnUnnormalizedRayParameter() {
        Box box = new Box(1, 1, 1, 2, 2, 2);
        assertEquals(Math.sqrt(3), rayDistance(box, new Vec3(0, 0, 0), new Vec3(4, 4, 4)), 1e-12);
        assertEquals(Math.sqrt(3), rayDistance(box, new Vec3(0, 0, 0),
                new Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)), 1e-12);
        assertEquals(Double.POSITIVE_INFINITY, rayDistance(box, new Vec3(0, 0, 0), new Vec3(-1, -1, -1)));
    }

    @Test
    void parallelAndBackwardRaysCannotHitTheTarget() {
        assertEquals(Double.POSITIVE_INFINITY, rayDistance(TARGET, EYE, new Vec3(1, 0, 0)));
        assertEquals(Double.POSITIVE_INFINITY, rayDistance(TARGET, EYE, new Vec3(0, 0, -1)));
        assertEquals(Double.POSITIVE_INFINITY, rayDistance(TARGET, new Vec3(0.4, 1, 0), FORWARD));
    }

    @Test
    void confirmedInteriorRequiresTheSuppliedInnerBox() {
        assertEquals(Result.INSIDE, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, null, List.of(EYE), List.of(FORWARD), 3));
        Box innerElsewhere = new Box(0.2, 0, 2, 0.3, 1.8, 2.6);
        assertEquals(Result.UNKNOWN, classify(TARGET, innerElsewhere, List.of(EYE), List.of(FORWARD), 3));
        assertEquals(Result.OUTSIDE, classify(TARGET, null, List.of(EYE), List.of(new Vec3(1, 0, 0)), 3));
    }

    @Test
    void oneAdmissibleMissPreventsInsideAndOneHitPreventsOutside() {
        List<Vec3> looks = List.of(FORWARD, new Vec3(1, 0, 0));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), looks, 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE, new Vec3(1, 1.62, 0)), List.of(FORWARD), 3));
        assertEquals(Result.OUTSIDE, classify(TARGET, TARGET, List.of(new Vec3(1, 1.62, 0), new Vec3(2, 1.62, 0)),
                List.of(FORWARD, new Vec3(0, 0, -1)), 3));
        assertEquals(Result.INSIDE, classify(TARGET, TARGET, List.of(EYE, new Vec3(0, 1.27, 0)),
                List.of(FORWARD, new Vec3(0.01, 0, 1)), 3));
    }

    @Test
    void grazingFacesEdgesAndCornersRemainUnknown() {
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(new Vec3(0.3, 1, 0)), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(new Vec3(0.3, 1.8, 0)), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(new Vec3(0.3 + 0.5e-6, 1, 0)), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(new Vec3(0.3 - 0.5e-6, 1, 0)), List.of(FORWARD), 3));
        assertEquals(Result.OUTSIDE, classify(TARGET, TARGET, List.of(new Vec3(0.3 + 2e-6, 1, 0)), List.of(FORWARD), 3));
        assertEquals(Result.INSIDE, classify(TARGET, TARGET, List.of(new Vec3(0.3 - 2e-6, 1, 0)), List.of(FORWARD), 3));
        Box cube = new Box(1, 1, 1, 2, 2, 2);
        // This ray touches only the front/top edge, then leaves the cube.
        assertEquals(Math.sqrt(6), rayDistance(cube, new Vec3(0, 1, 0), new Vec3(1, 1, 2)), 1e-12);
        assertEquals(Result.UNKNOWN, classify(cube, cube, List.of(new Vec3(0, 1, 0)), List.of(new Vec3(1, 1, 2)), 4));
    }

    @Test
    void reachLimitHasAnUnknownBoundaryBand() {
        assertEquals(Result.OUTSIDE, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD), 1.9));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD), 2));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD), 2 + 0.5e-6));
        assertEquals(Result.INSIDE, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD), 2 + 2e-6));
        assertEquals(Result.OUTSIDE, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD), 0));
    }

    @Test
    void eyesInsideOrOnTheOuterBoxCannotProvideAnAcquisition() {
        for (Vec3 eye : List.of(new Vec3(0, 1, 2.3), new Vec3(0, 1, 2), new Vec3(0, 1, 2 - 0.5e-6))) {
            assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(eye), List.of(FORWARD), 3));
        }
        assertEquals(0, rayDistance(TARGET, new Vec3(0, 1, 2.3), FORWARD));
        assertEquals(0, rayDistance(TARGET, new Vec3(0, 1, 2), new Vec3(0, 0, -1)));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE, new Vec3(0, 1, 2.3)), List.of(FORWARD), 3));
    }

    @Test
    void tinyInnerIntersectionCannotBecomeDefiniteInside() {
        Box thinInner = new Box(-0.5e-6, 0, 2, 0.5e-6, 1.8, 2.6);
        assertEquals(Result.UNKNOWN, classify(TARGET, thinInner, List.of(EYE), List.of(FORWARD), 3));
    }

    @Test
    void invalidAndUnboundedInputsAreUnknownWithoutPartiallyClassifying() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD), invalid));
            assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(new Vec3(invalid, 0, 0)), List.of(FORWARD), 3));
            assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD, new Vec3(invalid, 0, 0)), 3));
            assertEquals(Result.UNKNOWN, classify(new Box(invalid, 0, 2, 1, 2, 3), null, List.of(EYE), List.of(FORWARD), 3));
            assertEquals(Double.POSITIVE_INFINITY, rayDistance(TARGET, EYE, new Vec3(0, invalid, 1)));
        }
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), List.of(new Vec3(0, 0, 0)), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), List.of(FORWARD), -1));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), List.of(), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, null, List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), null, 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, Arrays.asList(EYE, null), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), Arrays.asList(FORWARD, null), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, Collections.nCopies(7, EYE), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, TARGET, List.of(EYE), Collections.nCopies(4, FORWARD), 3));
        assertEquals(Result.INSIDE, classify(TARGET, TARGET, Collections.nCopies(6, EYE), Collections.nCopies(3, FORWARD), 3));
    }

    @Test
    void invalidOrNonContainedBoxesNeverEstablishInside() {
        Box empty = new Box(0, 0, 2, 0, 2, 3);
        Box reversed = new Box(1, 0, 2, 0, 2, 3);
        Box outsideInner = new Box(-0.4, 0, 2, 0.3, 1.8, 2.6);
        assertEquals(Result.UNKNOWN, classify(empty, null, List.of(EYE), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(reversed, null, List.of(EYE), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, empty, List.of(EYE), List.of(FORWARD), 3));
        assertEquals(Result.UNKNOWN, classify(TARGET, outsideInner, List.of(EYE), List.of(FORWARD), 3));
        assertEquals(Double.POSITIVE_INFINITY, rayDistance(empty, EYE, FORWARD));
        assertEquals(Double.POSITIVE_INFINITY, rayDistance(TARGET, null, FORWARD));
        assertEquals(Double.POSITIVE_INFINITY, rayDistance(TARGET, EYE, null));
    }

    @Test
    void callerCanCompareTargetAndBlockerDistancesWithoutChangingEither() {
        Box frontBlocker = new Box(-1, 0, 1, 1, 2, 1.5);
        Box behindBlocker = new Box(-1, 0, 3, 1, 2, 4);
        assertEquals(1, rayDistance(frontBlocker, EYE, FORWARD));
        assertEquals(2, rayDistance(TARGET, EYE, FORWARD));
        assertEquals(3, rayDistance(behindBlocker, EYE, FORWARD));
    }

    @Test
    void classificationDoesNotChangeCallerOwnedListsOrGeometry() {
        List<Vec3> eyes = new ArrayList<>(List.of(EYE));
        List<Vec3> directions = new ArrayList<>(List.of(FORWARD));
        Box original = new Box(-0.3, 0, 2, 0.3, 1.8, 2.6);
        for (int i = 0; i < 5; i++) assertEquals(Result.INSIDE, classify(TARGET, TARGET, eyes, directions, 3));
        assertEquals(List.of(EYE), eyes);
        assertEquals(List.of(FORWARD), directions);
        assertEquals(original, TARGET);
        assertEquals(new Vec3(0, 1.62, 0), EYE);
        assertEquals(new Vec3(0, 0, 1), FORWARD);
    }
}
