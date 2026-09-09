package ac.grim.grimac.utils.latency;

import java.util.OptionalDouble;

/** The old speed is possible only between the pings surrounding its replacement packet. */
public final class MovementSpeedChangeTracker {
    private long sprintRevision;
    private Pending active;

    public synchronized Pending pending(int beforeTransaction) {
        return new Pending(beforeTransaction, sprintRevision);
    }

    public synchronized void activate(Pending pending, double oldBase, boolean oldSprintAttribute,
                                      double newBase, boolean newSprintAttribute, boolean packetSprinting,
                                      boolean lastSprinting) {
        // A later pre-ping is already after earlier attribute packets. Their old
        // values are no longer alternatives, even if their callbacks interleave.
        active = null;
        if (pending.sprintRevision != sprintRevision || oldSprintAttribute != newSprintAttribute
                || packetSprinting != lastSprinting || !Double.isFinite(oldBase) || !Double.isFinite(newBase)) {
            return;
        }
        pending.snapshot = new Snapshot(oldBase, newBase, oldSprintAttribute, packetSprinting);
        active = pending;
    }

    public synchronized void sentAfter(Pending pending, int transaction) {
        // Until an actual post-packet ping is observed, do not enable an alternative.
        if (transaction > pending.beforeTransaction) pending.afterTransaction = transaction;
    }

    public synchronized void acknowledgedAfter(Pending pending) {
        if (active == pending) active = null;
    }

    public synchronized void invalidateSprinting() {
        sprintRevision++;
        active = null;
    }

    public synchronized OptionalDouble previousSpeed(int receivedTransaction, double currentBase,
                                                     boolean sprintAttribute, boolean packetSprinting,
                                                     boolean lastSprinting) {
        Pending pending = active;
        if (pending == null || pending.afterTransaction < 0) return OptionalDouble.empty();
        if (receivedTransaction >= pending.afterTransaction) {
            active = null;
            return OptionalDouble.empty();
        }
        Snapshot snapshot = pending.snapshot;
        if (receivedTransaction < pending.beforeTransaction || snapshot == null
                || snapshot.newBase != currentBase || snapshot.sprintAttribute != sprintAttribute
                || packetSprinting != lastSprinting || snapshot.packetSprinting != packetSprinting) {
            return OptionalDouble.empty();
        }
        float oldSpeed = effectiveSpeed(snapshot.oldBase, snapshot.sprintAttribute);
        return oldSpeed == effectiveSpeed(currentBase, sprintAttribute)
                ? OptionalDouble.empty() : OptionalDouble.of(oldSpeed);
    }

    private static float effectiveSpeed(double base, boolean sprintAttribute) {
        return (float) (sprintAttribute ? base + base * 0.3F : base);
    }

    private record Snapshot(double oldBase, double newBase, boolean sprintAttribute, boolean packetSprinting) {
    }

    public static final class Pending {
        private final int beforeTransaction;
        private final long sprintRevision;
        private int afterTransaction = -1;
        private Snapshot snapshot;

        private Pending(int beforeTransaction, long sprintRevision) {
            this.beforeTransaction = beforeTransaction;
            this.sprintRevision = sprintRevision;
        }
    }
}
