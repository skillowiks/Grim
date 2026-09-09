package ac.grim.grimac.utils.latency;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/** Callbacks awaiting their own outbound ping; failed writes must not retain them indefinitely. */
public final class TransactionSendCallbacks {
    static final int MAX_PENDING = 128;
    private final Map<Short, IntConsumer> callbacks = new LinkedHashMap<>();

    public synchronized void put(short id, @Nullable IntConsumer callback) {
        // Also remove on ordinary sends: an ID can be reused after a failed write.
        callbacks.remove(id);
        if (callback == null) return;
        if (callbacks.size() >= MAX_PENDING) callbacks.remove(callbacks.keySet().iterator().next());
        callbacks.put(id, callback);
    }

    public synchronized @Nullable IntConsumer take(short id) {
        return callbacks.remove(id);
    }

    public synchronized void remove(short id, IntConsumer callback) {
        callbacks.remove(id, callback);
    }
}
