package ac.grim.grimac.utils.collisions.blocks;

import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.VanillaMath;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.enums.Half;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicStairTest {
    private static final int[] STRAIGHT_MASKS = {3, 12, 5, 10}; // north, south, west, east

    @Test
    void recordedPushOutMovementsEscapeTheStraightStairSeam() {
        // BJ1hH8gSFQ lines 626, 630, 631: same rollback position and full cached
        // block palette. LocalPlayer.moveTowardsClosestSpace sets X to +0.1;
        // slowed backward input is then added before the normal Y/X/Z collision.
        float[] yaw = {107.07497F, 105.00353F, 103.83834F};
        float groundSpeed = 0.09415999799966812F * (0.21600002F / (0.6F * 0.6F * 0.6F));
        float[] acceleration = {groundSpeed, 0.02F, groundSpeed};
        double[] actualX = {0.11764208329077519, 0.10378639681312052, 0.11791976485437772};
        List<SimpleCollisionBox> modern = recordedWorld(ClientVersion.V_1_21_4);
        List<SimpleCollisionBox> legacy = recordedWorld(ClientVersion.V_1_12_2);

        for (int tick = 0; tick < yaw.length; tick++) {
            double input = (double) (-0.2F * 0.98F) * acceleration[tick];
            double wantedX = 0.1 - input * VanillaMath.sin(GrimMath.radians(yaw[tick]));
            double wantedZ = input * VanillaMath.cos(GrimMath.radians(yaw[tick]));
            assertEquals(actualX[tick], wantedX, 1e-9, "vanilla push-out plus input, tick " + tick);

            SimpleCollisionBox start = recordedPlayerBox();
            double y = clip(modern, start, -0.08545600166320802, 1);
            assertEquals(0, y, 1e-12);
            start.offset(0, y, 0);
            assertEquals(0, clip(legacy, start, wantedX, 0), 1e-12, "old octets block the seam");
            assertEquals(actualX[tick], clip(modern, start, wantedX, 0), 1e-9);
            SimpleCollisionBox end = start.copy().offset(wantedX, 0, 0);
            assertEquals(0, clip(modern, end, wantedZ, 2), 1e-12, "real Z wall is retained");
            assertTrue(entersNewPart(legacy, start, end), "old per-box Phase condition sees a new octet");
            assertFalse(entersNewPart(modern, start, end), "the merged stair was already intersected");
        }
    }

    @Test
    void removesOnlyStraightSeamsInEveryFacingAndHalf() {
        for (Half half : new Half[]{Half.BOTTOM, Half.TOP}) {
            for (int mask : STRAIGHT_MASKS) {
                List<SimpleCollisionBox> modern = parts(ClientVersion.V_1_13, half, mask);
                List<SimpleCollisionBox> legacy = parts(ClientVersion.V_1_12_2, half, mask);
                assertEquals(2, modern.size());
                assertEquals(3, legacy.size());
                int axis = mask == 3 || mask == 12 ? 0 : 2;
                double otherCenter = mask == 3 || mask == 5 ? 0.25 : 0.75;
                double y = half == Half.TOP ? 0.25 : 0.75;
                for (int direction : new int[]{-1, 1}) {
                    double center = direction == 1 ? 0.2 : 0.8;
                    SimpleCollisionBox start = seamBox(axis, center, otherCenter, y);
                    double movement = direction * 0.1;
                    assertEquals(0, clip(legacy, start, movement, axis), 1e-12);
                    assertEquals(movement, clip(modern, start, movement, axis), 1e-12);
                    SimpleCollisionBox end = start.copy().offset(axis == 0 ? movement : 0, 0, axis == 2 ? movement : 0);
                    assertTrue(entersNewPart(legacy, start, end));
                    assertFalse(entersNewPart(modern, start, end));

                    // Approaching the same solid half from outside must still collide.
                    SimpleCollisionBox outside = seamBox(axis, direction == 1 ? -0.4 : 1.4, otherCenter, y);
                    assertEquals(direction * 0.1, clip(modern, outside, direction * 0.4, axis), 1e-12);
                    SimpleCollisionBox throughWall = outside.copy().offset(axis == 0 ? direction * 0.4 : 0, 0, axis == 2 ? direction * 0.4 : 0);
                    assertTrue(entersNewPart(modern, outside, throughWall));
                }
            }
        }
    }

    @Test
    void preservesOccupiedVolumeAndAllOtherStairDecompositions() {
        for (Half half : new Half[]{Half.BOTTOM, Half.TOP}) {
            for (int mask = 0; mask < 16; mask++) {
                List<SimpleCollisionBox> modern = parts(ClientVersion.V_1_21_4, half, mask);
                List<SimpleCollisionBox> legacy = parts(ClientVersion.V_1_12_2, half, mask);
                for (int x = 0; x < 4; x++) {
                    for (int y = 0; y < 4; y++) {
                        for (int z = 0; z < 4; z++) {
                            SimpleCollisionBox sample = new SimpleCollisionBox(x * 0.25 + 0.05, y * 0.25 + 0.05, z * 0.25 + 0.05,
                                    x * 0.25 + 0.20, y * 0.25 + 0.20, z * 0.25 + 0.20);
                            assertEquals(intersects(legacy, sample), intersects(modern, sample));
                        }
                    }
                }
                if (mask != 3 && mask != 5 && mask != 10 && mask != 12) {
                    assertEquals(legacy.toString(), modern.toString(), "inner and outer stairs remain unchanged");
                }
            }
        }
    }

    @Test
    void offsettingReturnedShapeDoesNotMutateCachedOrLegacyShapes() {
        CollisionBox first = DynamicStair.getCollisionShape(ClientVersion.V_1_21_4, Half.TOP, 3);
        first.offset(100, 100, 100);
        assertEquals(0, parts(ClientVersion.V_1_21_4, Half.TOP, 3).get(0).minX);
        assertEquals(0, parts(ClientVersion.V_1_12_2, Half.TOP, 3).get(0).minX);
    }

    private static List<SimpleCollisionBox> recordedWorld(ClientVersion version) {
        // Palette and RLE from line 626. order=x-z-y means X is the fastest axis.
        int[][] runs = {{0, 2}, {1, 1}, {0, 1}, {1, 8}, {2, 1}, {3, 1}, {4, 1}, {5, 1}, {3, 1}, {4, 1},
                {3, 1}, {5, 1}, {4, 5}, {2, 1}, {4, 2}, {6, 1}, {4, 1}, {7, 1}, {3, 1}, {4, 5}, {5, 1},
                {4, 2}, {2, 1}, {4, 1}, {2, 1}, {3, 1}, {4, 4}};
        List<SimpleCollisionBox> result = new ArrayList<>();
        int index = 0;
        for (int[] run : runs) {
            for (int i = 0; i < run[1]; i++, index++) {
                int x = 6232 + index % 3;
                int z = 5974 + index / 3 % 4;
                int y = 36 + index / 12;
                if (run[0] == 4) continue;
                CollisionBox block = run[0] == 6 || run[0] == 7
                        ? DynamicStair.getCollisionShape(version, Half.TOP, run[0] == 6 ? 3 : 5)
                        : new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
                block.offset(x, y, z).downCast(result);
            }
        }
        assertEquals(48, index);
        return result;
    }

    private static SimpleCollisionBox recordedPlayerBox() {
        double radius = (double) 0.6F / 2;
        return new SimpleCollisionBox(6233.199999988079 - radius, 37, 5975.699999988079 - radius,
                6233.199999988079 + radius, 37 + (double) 1.8F, 5975.699999988079 + radius);
    }

    private static SimpleCollisionBox seamBox(int axis, double center, double otherCenter, double y) {
        return axis == 0
                ? new SimpleCollisionBox(center - 0.3, y - 0.1, otherCenter - 0.1, center + 0.3, y + 0.1, otherCenter + 0.1)
                : new SimpleCollisionBox(otherCenter - 0.1, y - 0.1, center - 0.3, otherCenter + 0.1, y + 0.1, center + 0.3);
    }

    private static List<SimpleCollisionBox> parts(ClientVersion version, Half half, int mask) {
        List<SimpleCollisionBox> parts = new ArrayList<>();
        DynamicStair.getCollisionShape(version, half, mask).downCast(parts);
        return parts;
    }

    private static double clip(List<SimpleCollisionBox> boxes, SimpleCollisionBox player, double movement, int axis) {
        for (SimpleCollisionBox box : boxes) {
            movement = switch (axis) {
                case 0 -> box.collideX(player, movement);
                case 1 -> box.collideY(player, movement);
                default -> box.collideZ(player, movement);
            };
        }
        return movement;
    }

    private static boolean entersNewPart(List<SimpleCollisionBox> boxes, SimpleCollisionBox before, SimpleCollisionBox after) {
        return boxes.stream().anyMatch(box -> box.isIntersected(after) && !box.isIntersected(before));
    }

    private static boolean intersects(List<SimpleCollisionBox> boxes, SimpleCollisionBox sample) {
        return boxes.stream().anyMatch(box -> box.isIntersected(sample));
    }
}
