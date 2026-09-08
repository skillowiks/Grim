package ac.grim.grimac.manager.deepdebug;

/**
 * A movement-relevant server event or client attack, captured while a
 * deep-debug session is active. The source is attributed down to the plugin
 * class when possible.
 */
public record InterferenceRecord(long timeMs, Kind kind, String detail, String source, boolean cancelled) {

    public enum Kind {
        VELOCITY, TELEPORT, EXPLOSION, POTION_EFFECT, GAME_MODE, ATTACK
    }

    @Override
    public String toString() {
        return kind + (cancelled ? " (cancelled)" : "") + " " + detail + " <- " + source;
    }
}
