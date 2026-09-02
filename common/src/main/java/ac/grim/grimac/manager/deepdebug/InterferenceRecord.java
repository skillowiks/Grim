package ac.grim.grimac.manager.deepdebug;

/**
 * A server-side event that can provoke anticheat flags: velocity, teleport,
 * potion effect or gamemode change. Captured by the platform (Bukkit)
 * listeners while a deep-debug session is active, with the initiating source
 * attributed down to the plugin class when possible.
 */
public record InterferenceRecord(long timeMs, Kind kind, String detail, String source, boolean cancelled) {

    public enum Kind {
        VELOCITY, TELEPORT, POTION_EFFECT, GAME_MODE
    }

    @Override
    public String toString() {
        return kind + (cancelled ? " (cancelled)" : "") + " " + detail + " <- " + source;
    }
}
