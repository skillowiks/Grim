package ac.grim.grimac.manager.deepdebug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded, immutable text snapshots, including movements before a flag. */
final class MovementTraceRecorder {
    private static final int HISTORY = 60;
    private static final int FOLLOWING = 40;
    private static final int MAX_TRACES = 3;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final ArrayDeque<List<String>> traces = new ArrayDeque<>();
    private List<String> active;
    private int remaining;
    private String pendingFlags = "";
    private final List<String> pendingEvents = new ArrayList<>();
    private int omittedEvents;

    synchronized void event(String snapshot) {
        if (pendingEvents.size() < 8) pendingEvents.add(snapshot);
        else omittedEvents++;
    }

    synchronized void flag(String name) {
        pendingFlags += pendingFlags.isEmpty() ? name : ", " + name;
    }

    synchronized void movement(String snapshot) {
        boolean triggered = !pendingFlags.isEmpty();
        if (triggered && active == null) {
            active = new ArrayList<>(history);
            traces.addLast(active);
            if (traces.size() > MAX_TRACES) traces.removeFirst();
            remaining = FOLLOWING + 1; // Triggering movement plus following movements.
        }
        String line = (triggered ? "FLAG [" + pendingFlags + "] " : "") + snapshot;
        if (!pendingEvents.isEmpty()) {
            line += " events=[" + String.join(" | ", pendingEvents)
                    + (omittedEvents == 0 ? "" : " | omitted=" + omittedEvents) + "]";
        }
        pendingEvents.clear();
        omittedEvents = 0;
        pendingFlags = "";
        if (active != null) {
            active.add(line);
            if (--remaining == 0) active = null;
        }
        history.addLast(line);
        if (history.size() > HISTORY) history.removeFirst();
    }

    synchronized List<String> snapshot() {
        List<String> result = new ArrayList<>();
        for (List<String> trace : traces) result.add(String.join("\n", trace));
        return result;
    }
}
