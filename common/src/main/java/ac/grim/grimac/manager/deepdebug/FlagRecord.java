package ac.grim.grimac.manager.deepdebug;

import org.jetbrains.annotations.Nullable;

/**
 * One captured flag: check, verbose text, and the movement/client state that
 * surrounded it. Verbose is rendered eagerly (the supplier is already
 * memoized on the alert path) so the report does not depend on mutable
 * player state later.
 */
public final class FlagRecord {
    public final long timeMs;
    public final String checkName;
    public final int vl;
    public final String verbose;
    public final @Nullable MovementContext movement;
    public final boolean sprinting;
    public final int food;
    public final boolean usingItem;
    public final boolean onGround;

    public FlagRecord(long timeMs, String checkName, int vl, String verbose,
                      @Nullable MovementContext movement, boolean sprinting, int food,
                      boolean usingItem, boolean onGround) {
        this.timeMs = timeMs;
        this.checkName = checkName;
        this.vl = vl;
        this.verbose = verbose == null ? "" : verbose;
        this.movement = movement;
        this.sprinting = sprinting;
        this.food = food;
        this.usingItem = usingItem;
        this.onGround = onGround;
    }
}
