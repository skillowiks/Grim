package ac.grim.grimac.checks.impl.movement;

/** Evidence for consecutive movements made while an item is certainly in use. */
final class NoSlowBuffer {
    private double bestOffset = 1;
    private boolean violationLastMovement;

    void analyze(double offset) {
        bestOffset = Math.min(bestOffset, offset);
    }

    double bestOffset() {
        return bestOffset;
    }

    void reset() {
        bestOffset = 1;
        violationLastMovement = false;
    }

    boolean complete(boolean checked, boolean usingItem, boolean exempt, double threshold) {
        boolean violating = checked && usingItem && !exempt && bestOffset > threshold;
        boolean shouldFlag = violating && violationLastMovement;
        violationLastMovement = violating;
        bestOffset = 1;
        return shouldFlag;
    }
}
