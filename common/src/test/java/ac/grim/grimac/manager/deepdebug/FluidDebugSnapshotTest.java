package ac.grim.grimac.manager.deepdebug;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidDebugSnapshotTest {
    @Test
    void includesAllNeighborsAcrossNegativeAndExactBlockBoundaries() {
        Set<String> visited = new HashSet<>();
        String snapshot = FluidDebugSnapshot.capture(-.3, 32, -.3, .3, 33.8, .3, source((x, y, z) -> {
            visited.add(x + "," + y + "," + z);
            return 0;
        }));
        assertEquals(64, visited.size());
        assertTrue(visited.contains("-2,31,-2"));
        assertTrue(visited.contains("1,34,1"));
        assertTrue(snapshot.contains("min=-2,31,-2,maxInclusive=1,34,1"));
        assertTrue(snapshot.contains("sampled=64,total=64,truncated=false"));
        assertTrue(snapshot.contains("runs=index*count:[0*64]"));
    }

    @Test
    void preservesFlowLevelsAndMissingBlocksWithAnExplicitPaletteAndOrder() {
        List<String> visited = new ArrayList<>();
        String snapshot = FluidDebugSnapshot.capture(0, 0, 0, 0, 0, 0, source((x, y, z) -> {
            visited.add(x + "," + y + "," + z);
            return x == -1 ? 80 : x == 0 ? 88 : -2;
        }));
        assertEquals(List.of("-1,-1,-1", "0,-1,-1", "1,-1,-1", "-1,-1,0"), visited.subList(0, 4));
        assertTrue(snapshot.contains("0:80:\"minecraft:water[level=0]\";1:88:\"minecraft:water[level=8]\";2:-2:\"unavailable\""));
        assertTrue(snapshot.contains("order=x-z-y"));
        assertTrue(snapshot.contains("runs=index*count:[0*1,1*1,2*1,0*1"));
    }

    @Test
    void capsReadsForLargeScaleAndMarksPartialData() {
        AtomicInteger reads = new AtomicInteger();
        String snapshot = FluidDebugSnapshot.capture(-100, -100, -100, 100, 100, 100, source((x, y, z) -> {
            reads.incrementAndGet();
            return 0;
        }));
        assertEquals(FluidDebugSnapshot.MAX_CELLS, reads.get());
        assertTrue(snapshot.contains("sampled=256,total=8365427,truncated=true"));
        assertTrue(snapshot.contains("runs=index*count:[0*256]"));
    }

    @Test
    void enormousValidBoundsCannotOverflowTheCellCountOrLoseTheReadCap() {
        AtomicInteger reads = new AtomicInteger();
        String snapshot = FluidDebugSnapshot.capture(-1e9, -1e9, -1e9, 1e9, 1e9, 1e9, source((x, y, z) -> {
            reads.incrementAndGet();
            return 0;
        }));
        assertEquals(256, reads.get());
        assertTrue(snapshot.contains("total=" + Long.MAX_VALUE + ",truncated=true"));
    }

    @Test
    void invalidBoundsDoNotReadTheWorld() {
        FluidDebugSnapshot.BlockSource source = source((x, y, z) -> {
            throw new AssertionError("Invalid bounds must not access cached blocks");
        });
        assertTrue(FluidDebugSnapshot.capture(Double.NaN, 0, 0, 1, 1, 1, source).contains("invalid-bounds"));
        assertTrue(FluidDebugSnapshot.capture(1, 0, 0, 0, 1, 1, source).contains("invalid-bounds"));
        assertTrue(FluidDebugSnapshot.capture(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1, source).contains("invalid-bounds"));
        assertTrue(FluidDebugSnapshot.capture(-1e20, 0, 0, 1, 1, 1, source).contains("invalid-bounds"));
    }

    @Test
    void everySnapshotIsSelfContainedAndStateDescriptionsAreBoundedAndQuoted() {
        AtomicInteger descriptions = new AtomicInteger();
        FluidDebugSnapshot.BlockSource source = new FluidDebugSnapshot.BlockSource() {
            public int globalIdAt(int x, int y, int z) {
                return 1;
            }

            public String describe(int id) {
                descriptions.incrementAndGet();
                return "test:\"state\"\\\n" + "x".repeat(300);
            }
        };
        String first = FluidDebugSnapshot.capture(0, 0, 0, 1, 1, 1, source);
        String second = FluidDebugSnapshot.capture(0, 0, 0, 1, 1, 1, source);
        assertEquals(first, second);
        assertEquals(2, descriptions.get());
        assertTrue(first.contains("descriptionsTruncated=true"));
        assertTrue(first.contains("test:\\\"state\\\"\\\\\\n"));
        assertTrue(first.length() < 600);
    }

    private static FluidDebugSnapshot.BlockSource source(IdSource ids) {
        return new FluidDebugSnapshot.BlockSource() {
            public int globalIdAt(int x, int y, int z) {
                return ids.at(x, y, z);
            }

            public String describe(int id) {
                return switch (id) {
                    case 80 -> "minecraft:water[level=0]";
                    case 88 -> "minecraft:water[level=8]";
                    case -2 -> "unavailable";
                    default -> "minecraft:air";
                };
            }
        };
    }

    @FunctionalInterface
    private interface IdSource {
        int at(int x, int y, int z);
    }
}
