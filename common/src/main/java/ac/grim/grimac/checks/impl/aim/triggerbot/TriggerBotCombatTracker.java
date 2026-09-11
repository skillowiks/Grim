package ac.grim.grimac.checks.impl.aim.triggerbot;

/**
 * Bounded packet/prediction history. All ticks belong to the same observer clock.
 * This helper describes sampled state; it does not establish a client's exact
 * cooldown, current velocity, critical hit, or automated input.
 */
public final class TriggerBotCombatTracker {
    private static final double STABLE_Y_EPSILON = 1.0E-6;
    private long latestTick = -1;
    private long movementTick = -1;
    private double nextVelocityY = Double.NaN;
    private boolean movementGrounded;
    private long groundTick = -1;
    private double groundY = Double.NaN;
    private int groundTicks;
    private boolean inputKnown, forwardHeld, sprintKnown, observedSprinting;
    private long forwardReleaseTick = -1;
    private long sprintStopTick = -1;
    private long forwardRestoreTick = -1;
    private long swingTick = -1;
    private boolean cooldownUnknown;

    public record Snapshot(boolean validMotion, boolean grounded, boolean descending,
                           boolean jump, int groundTicks, boolean sprinting,
                           boolean sprintReleased, boolean forwardHeld,
                           boolean forwardRestored, double cooldownProgress) {
    }

    /** Called only for an accepted position after prediction has completed. */
    public void movement(long tick, boolean grounded, double nextVelocityY, boolean checked) {
        if (!advance(tick)) return;
        this.movementTick = checked && Double.isFinite(nextVelocityY) ? tick : -1;
        this.nextVelocityY = this.movementTick >= 0 ? nextVelocityY : Double.NaN;
        this.movementGrounded = grounded;
        if (!grounded) clearGround();
    }

    /** Call only when a real input packet supplies the forward state. */
    public void input(long tick, boolean forward) {
        if (!advance(tick)) return;
        if (inputKnown && forwardHeld != forward) {
            if (!forward) {
                forwardReleaseTick = tick;
                forwardRestoreTick = -1;
            } else {
                // A first false input followed by true is not a restore. Require
                // the preceding actual release and observed sprint stop as well.
                forwardRestoreTick = releasePairFresh(tick) ? tick : -1;
            }
        }
        inputKnown = true;
        forwardHeld = forward;
    }

    /** Call for observed client sprint actions, not inferred sprint attributes. */
    public void sprint(long tick, boolean sprinting) {
        if (!advance(tick)) return;
        if (sprintKnown && observedSprinting && !sprinting) sprintStopTick = tick;
        sprintKnown = true;
        observedSprinting = sprinting;
    }

    /** Call after a swing/attack is accepted; capture pre-attack state first. */
    public void swing(long tick) {
        if (!advance(tick)) return;
        swingTick = tick;
        cooldownUnknown = false;
    }

    /**
     * An animation without an accepted attack can be a miss or a use/interaction
     * swing. Those do not share a guaranteed cooldown reset. Keep motion/input
     * history, but require the next accepted attack to establish a new reset.
     */
    public void ambiguousSwing(long tick) {
        if (!advance(tick)) return;
        cooldownUnknown = true;
    }

    /**
     * Ground stability comes from consecutive tick-end positions. The last
     * prediction displacement may still describe landing and is not used here.
     */
    public void endTick(long tick, boolean grounded, double y) {
        if (!advance(tick)) return;
        if (!grounded || !Double.isFinite(y)) {
            clearGround();
            if (!Double.isFinite(y)) {
                movementTick = -1;
                nextVelocityY = Double.NaN;
            }
            return;
        }
        // Once an endpoint establishes landing, a later airborne snapshot needs
        // a new checked position rather than the preceding flight prediction.
        if (!movementGrounded) {
            movementTick = -1;
            nextVelocityY = Double.NaN;
        }
        if (tick == groundTick) {
            // Duplicate callbacks cannot lengthen the stable run. A changed
            // position within the duplicate interval still breaks continuity.
            if (Math.abs(y - groundY) > STABLE_Y_EPSILON) groundTicks = 1;
        } else {
            groundTicks = fresh(tick, groundTick, 1) && tick != groundTick
                    && Math.abs(y - groundY) <= STABLE_Y_EPSILON
                    ? Math.min(groundTicks, Integer.MAX_VALUE - 1) + 1 : 1;
        }
        groundTick = tick;
        groundY = y;
    }

    public Snapshot snapshot(long tick, boolean grounded, boolean jump, boolean sprinting,
                             double attackSpeed, double fallbackCooldown) {
        boolean clockValid = advance(tick);
        int stableGroundTicks = grounded && fresh(tick, groundTick, 1) ? groundTicks : 0;
        boolean validMotion = clockValid && (grounded ? stableGroundTicks >= 2
                : !movementGrounded && fresh(tick, movementTick, 1) && Double.isFinite(nextVelocityY));
        boolean released = clockValid && inputKnown && !forwardHeld && !sprinting
                && !observedSprinting && releasePairFresh(tick);
        boolean restored = clockValid && inputKnown && forwardHeld && fresh(tick, forwardRestoreTick, 2);
        double cooldown = Double.NaN;
        if (clockValid && !cooldownUnknown && Double.isFinite(attackSpeed) && attackSpeed > 0) {
            if (swingTick >= 0) cooldown = Math.min(1.0, (tick - swingTick + 0.5) * attackSpeed / 20.0);
            else if (Double.isFinite(fallbackCooldown)) cooldown = Math.max(0.0, Math.min(1.0, fallbackCooldown));
        }
        return new Snapshot(validMotion, grounded, validMotion && !grounded && nextVelocityY < (double) -0.01F,
                jump, stableGroundTicks, sprinting, released, inputKnown && forwardHeld, restored, cooldown);
    }

    public void reset() {
        latestTick = movementTick = groundTick = forwardReleaseTick = sprintStopTick = forwardRestoreTick = swingTick = -1;
        nextVelocityY = groundY = Double.NaN;
        movementGrounded = inputKnown = forwardHeld = sprintKnown = observedSprinting = false;
        cooldownUnknown = false;
        groundTicks = 0;
    }

    private boolean releasePairFresh(long tick) {
        return fresh(tick, forwardReleaseTick, 2) && fresh(tick, sprintStopTick, 2)
                && Math.abs(forwardReleaseTick - sprintStopTick) <= 1;
    }

    private void clearGround() {
        groundTick = -1;
        groundY = Double.NaN;
        groundTicks = 0;
    }

    private boolean advance(long tick) {
        if (tick < 0 || tick < latestTick) {
            reset();
            return false;
        }
        latestTick = tick;
        return true;
    }

    private static boolean fresh(long tick, long previous, long maximumAge) {
        return previous >= 0 && tick >= previous && tick - previous <= maximumAge;
    }
}
