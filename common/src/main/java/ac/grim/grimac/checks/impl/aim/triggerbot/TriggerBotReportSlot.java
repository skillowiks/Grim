package ac.grim.grimac.checks.impl.aim.triggerbot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable, versioned publication: a late worker cannot overwrite a newer status/snapshot. */
public final class TriggerBotReportSlot {
    private record State(long version, String text, boolean frozen) { }
    private final AtomicReference<State> state = new AtomicReference<>(
            new State(0, "No TriggerBot observations captured yet.", false));

    public long reserve() {
        State reserved = state.updateAndGet(old -> old.frozen ? old : new State(old.version + 1, old.text, false));
        return reserved.frozen ? -1 : reserved.version;
    }

    public boolean publish(long version, String text) {
        Objects.requireNonNull(text);
        State expected = state.get();
        return !expected.frozen && expected.version == version
                && state.compareAndSet(expected, new State(version, text, false));
    }

    public void replace(String text) {
        Objects.requireNonNull(text);
        state.updateAndGet(old -> old.frozen ? old : new State(old.version + 1, text, false));
    }

    public void freeze() {
        state.updateAndGet(old -> old.frozen ? old : new State(old.version + 1, old.text, true));
    }

    public String text() {
        return state.get().text;
    }
}
