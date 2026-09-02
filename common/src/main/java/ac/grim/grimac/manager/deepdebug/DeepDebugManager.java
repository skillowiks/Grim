package ac.grim.grimac.manager.deepdebug;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.platform.api.manager.PluginAttributionProvider;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry of active {@link DeepDebugSession}s — the engine behind
 * {@code /grim debug <nick>}. The flag hot path checks {@link #hasActiveSessions()}
 * (a volatile read, free when nobody is debugging) before doing any work.
 *
 * <p>Server-side interference events (velocity/teleport/potion/gamemode with
 * plugin attribution) and the foreign-listener snapshot are pushed in by the
 * platform module through {@link #recordInterference} and
 * {@link #getAttributionProvider()}.</p>
 */
public final class DeepDebugManager {
    /** Sessions auto-expire after this long so a forgotten debug mode cannot leak. */
    public static final long SESSION_TIMEOUT_MS = 10 * 60 * 1000L;

    private final Map<UUID, DeepDebugSession> sessions = new ConcurrentHashMap<>();
    private volatile PluginAttributionProvider attributionProvider = PluginAttributionProvider.NOOP;
    /** Earliest deadline among live sessions; lets the hot-path check self-clean without a scheduler. */
    private volatile long minDeadlineMs = Long.MAX_VALUE;

    /**
     * Cheap check for the per-flag/packet hot path. When the earliest deadline
     * has passed it opportunistically sweeps expired sessions (an idle online
     * target would otherwise never trigger the lazy expiry inside getSession),
     * keeping the "auto-off after 10 minutes" promise without a scheduler task.
     */
    public boolean hasActiveSessions() {
        if (sessions.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (now < minDeadlineMs) return true;
        sweepExpired(now);
        return !sessions.isEmpty();
    }

    public @Nullable DeepDebugSession getSession(UUID uuid) {
        DeepDebugSession session = sessions.get(uuid);
        if (session == null) return null;
        if (session.isExpired()) {
            stopSession(uuid, "expired");
            return null;
        }
        return session;
    }

    /**
     * Starts a session for the target, or returns the existing one (adding the
     * initiator as a live listener). The P/A/O prediction stream is mirrored to
     * the initiator only on creation, so re-running the command adds a listener
     * without toggling the stream off. Never returns null.
     */
    public DeepDebugSession startSession(GrimPlayer target, @Nullable Sender initiator) {
        boolean[] created = {false};
        DeepDebugSession session = sessions.compute(target.uuid, (uuid, existing) -> {
            if (existing != null && !existing.isStopped() && !existing.isExpired()) return existing;
            created[0] = true;
            return new DeepDebugSession(target, initiator, SESSION_TIMEOUT_MS);
        });
        if (created[0]) {
            session.attachPredictionStream();
            minDeadlineMs = Math.min(minDeadlineMs, session.deadlineMs);
        }
        if (initiator != null) session.addListener(initiator);
        return session;
    }

    public void stopSession(UUID uuid, String reason) {
        DeepDebugSession session = sessions.remove(uuid);
        if (session != null) session.stop();
        recomputeMinDeadline();
    }

    private synchronized void sweepExpired(long now) {
        sessions.values().removeIf(session -> {
            if (!session.isExpired()) return false;
            session.stop();
            return true;
        });
        recomputeMinDeadlineLocked();
    }

    private void recomputeMinDeadline() {
        synchronized (this) {
            recomputeMinDeadlineLocked();
        }
    }

    private void recomputeMinDeadlineLocked() {
        long min = Long.MAX_VALUE;
        for (DeepDebugSession session : sessions.values()) {
            min = Math.min(min, session.deadlineMs);
        }
        minDeadlineMs = min;
    }

    /** Called from {@code PunishmentManager.handleAlert} on every flag; does nothing when idle. */
    public void handleFlag(GrimPlayer player, Check check, Supplier<String> verbose) {
        if (player.uuid == null) return;
        DeepDebugSession session = getSession(player.uuid);
        if (session == null || session.isStopped()) return;
        session.recordFlag(player, check, verbose);
    }

    /** Called by the platform (Bukkit) interference listeners. */
    public void recordInterference(UUID uuid, InterferenceRecord record) {
        DeepDebugSession session = getSession(uuid);
        if (session == null || session.isStopped()) return;
        session.addInterference(record);
    }

    /** Called from the attribute-update path when a non-vanilla movement modifier shows up. */
    public void recordAttributeAnomaly(GrimPlayer player, String line) {
        DeepDebugSession session = getSession(player.uuid);
        if (session == null || session.isStopped()) return;
        session.addAttributeAnomaly(line);
    }

    /** Ends the session when the target disconnects. */
    public void onQuit(UUID uuid) {
        stopSession(uuid, "quit");
    }

    public PluginAttributionProvider getAttributionProvider() {
        return attributionProvider;
    }

    public void setAttributionProvider(PluginAttributionProvider provider) {
        this.attributionProvider = provider == null ? PluginAttributionProvider.NOOP : provider;
    }

    public static DeepDebugManager get() {
        return GrimAPI.INSTANCE.getDeepDebugManager();
    }
}
