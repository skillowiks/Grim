package ac.grim.grimac.manager.deepdebug;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, self-contained view of the cached blocks at the movement endpoint. */
final class FluidDebugSnapshot {
    static final int MAX_CELLS = 256;
    private static final int MAX_STATE_DESCRIPTION = 160;

    interface BlockSource {
        int globalIdAt(int x, int y, int z);

        String describe(int globalId);
    }

    private FluidDebugSnapshot() {
    }

    static String capture(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                          BlockSource source) {
        if (!validAxis(minX, maxX) || !validAxis(minY, maxY) || !validAxis(minZ, maxZ)) {
            return "{phase=endpointCachedWorld,unavailable=invalid-bounds}";
        }

        int x0 = (int) Math.floor(minX) - 1;
        int y0 = (int) Math.floor(minY) - 1;
        int z0 = (int) Math.floor(minZ) - 1;
        int x1 = (int) Math.floor(maxX) + 1;
        int y1 = (int) Math.floor(maxY) + 1;
        int z1 = (int) Math.floor(maxZ) + 1;
        long total = saturatedProduct((long) x1 - x0 + 1, (long) y1 - y0 + 1, (long) z1 - z0 + 1);

        Map<Integer, Integer> palette = new LinkedHashMap<>();
        StringBuilder paletteText = new StringBuilder();
        StringBuilder runs = new StringBuilder();
        int sampled = 0;
        int lastIndex = -1;
        int runLength = 0;
        boolean descriptionsTruncated = false;
        sampling:
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (sampled == MAX_CELLS) break sampling;
                    int globalId = source.globalIdAt(x, y, z);
                    Integer paletteIndex = palette.get(globalId);
                    if (paletteIndex == null) {
                        paletteIndex = palette.size();
                        palette.put(globalId, paletteIndex);
                        if (!paletteText.isEmpty()) paletteText.append(';');
                        String description = source.describe(globalId);
                        boolean shortened = description.length() > MAX_STATE_DESCRIPTION;
                        descriptionsTruncated |= shortened;
                        if (shortened) description = description.substring(0, MAX_STATE_DESCRIPTION) + "...";
                        paletteText.append(paletteIndex).append(':').append(globalId).append(':');
                        appendQuoted(paletteText, description);
                    }
                    if (lastIndex != paletteIndex && runLength != 0) {
                        appendRun(runs, lastIndex, runLength);
                        runLength = 0;
                    }
                    lastIndex = paletteIndex;
                    runLength++;
                    sampled++;
                }
            }
        }
        appendRun(runs, lastIndex, runLength);

        return "{phase=endpointCachedWorld,bbox=" + minX + "," + minY + "," + minZ + ":" + maxX + "," + maxY + "," + maxZ
                + ",min=" + x0 + "," + y0 + "," + z0 + ",maxInclusive=" + x1 + "," + y1 + "," + z1
                + ",order=x-z-y,sampled=" + sampled + ",total=" + total + ",truncated=" + (sampled < total)
                + ",paletteFormat=index:globalId:state,palette=[" + paletteText + "]"
                + ",descriptionsTruncated=" + descriptionsTruncated + ",runs=index*count:[" + runs + "]}";
    }

    private static boolean validAxis(double min, double max) {
        return Double.isFinite(min) && Double.isFinite(max) && min <= max
                && min >= Integer.MIN_VALUE + 2.0 && max <= Integer.MAX_VALUE - 2.0;
    }

    private static long saturatedProduct(long x, long y, long z) {
        if (x > Long.MAX_VALUE / y || x * y > Long.MAX_VALUE / z) return Long.MAX_VALUE;
        return x * y * z;
    }

    private static void appendRun(StringBuilder target, int index, int length) {
        if (!target.isEmpty()) target.append(',');
        target.append(index).append('*').append(length);
    }

    private static void appendQuoted(StringBuilder target, String value) {
        target.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\', '"' -> target.append('\\').append(c);
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> target.append(c);
            }
        }
        target.append('"');
    }
}
