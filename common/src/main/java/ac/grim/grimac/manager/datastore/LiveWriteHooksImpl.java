package ac.grim.grimac.manager.datastore;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.model.VerboseFormat;
import ac.grim.grimac.internal.storage.checks.CheckRegistry;
import ac.grim.grimac.internal.storage.checks.StableKeyMapping;
import ac.grim.grimac.internal.storage.identity.PlayerIdentityService;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.protocol.player.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete {@link LiveWriteHooks}. Caches display-name → checkId locally so
 * the hot path skips the {@code synchronized intern(...)} call on
 * {@link CheckRegistry}.
 */
public final class LiveWriteHooksImpl implements LiveWriteHooks {

    private final DataStore store;
    private final PlayerIdentityService identityService;
    private final CheckRegistry checkRegistry;
    private final SessionTracker sessionTracker;
    /** display name (lowercased) → checkId. Populated lazily via intern. */
    private final Map<String, Integer> checkIdCache = new ConcurrentHashMap<>();
    /** display-name-lowercase of checks we've already warned about. Prevents log spam. */
    private final Set<String> missingStableKeyLogged = ConcurrentHashMap.newKeySet();
    /**
     * Last stored-row timestamp per (player, check), for the binary-verbose rate
     * cap. Binary rows already pass the [log] punishment command's
     * threshold:interval gate; this collapses bursts that still slip past it.
     */
    private final Map<UUID, Map<AbstractCheck, Long>> lastBinaryRowMs = new ConcurrentHashMap<>();
    /** database.write-path.violation-store-min-interval-ms; 0 = store every [log] execution. */
    private final long binaryRowMinIntervalMs;
    /** database.write-path.violation-store-min-interval-per-check; lowercased check name → interval. */
    private final Map<String, Long> perCheckMinIntervalMs;

    public LiveWriteHooksImpl(
            @NotNull DataStore store,
            @NotNull PlayerIdentityService identityService,
            @NotNull CheckRegistry checkRegistry,
            @NotNull SessionTracker sessionTracker) {
        this.store = store;
        this.identityService = identityService;
        this.checkRegistry = checkRegistry;
        this.sessionTracker = sessionTracker;
        this.binaryRowMinIntervalMs = GrimAPI.INSTANCE.getConfigManager().getConfig()
                .getLongElse("database.write-path.violation-store-min-interval-ms", 0L);
        this.perCheckMinIntervalMs = readPerCheckIntervals();
    }

    /** Lowercased check-name → interval map; numeric values only, anything else ignored. */
    private static @NotNull Map<String, Long> readPerCheckIntervals() {
        Map<String, Object> raw = GrimAPI.INSTANCE.getConfigManager().getConfig()
                .getMapElse("database.write-path.violation-store-min-interval-per-check", Map.of());
        Map<String, Long> out = new ConcurrentHashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getValue() instanceof Number n) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), n.longValue());
            }
        }
        return out;
    }

    @Override
    public void onJoin(
            @NotNull UUID uuid,
            @Nullable String name,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        identityService.observe(uuid, name, now);
        sessionTracker.observeActivity(uuid, now, meta);
        // Fresh session: give the new session's first flags a fresh rate window.
        lastBinaryRowMs.remove(uuid);
    }

    @Override
    public void onQuit(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta) {
        sessionTracker.close(uuid, now, meta);
        // Check instances are per-player; dropping the map frees them.
        lastBinaryRowMs.remove(uuid);
    }

    @Override
    public void observeBrand(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta) {
        sessionTracker.observeActivity(uuid, now, meta);
    }

    @Override
    public void recordFlag(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            @Nullable String verbose,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        UUID sessionId = sessionTracker.currentSessionId(playerUuid);
        if (sessionId == null) {
            // Two real cases: a check fired on a packet between LOGIN_SUCCESS
            // and PlayerJoinEvent (no session yet), or a test harness called
            // recordFlag directly. Synthesise a session so the violation has
            // somewhere to land; PlayerJoinEvent's onJoin will extend it.
            sessionId = sessionTracker.observeActivity(playerUuid, now, meta);
        }
        final int checkId = resolveCheckId(check);
        final UUID sid = sessionId;
        store.submit(Categories.VIOLATION, e -> e
                .sessionId(sid)
                .playerUuid(playerUuid)
                .checkId(checkId)
                .vl(vl)
                .occurredEpochMs(now)
                .verbose(verbose)
                .verboseFormat(VerboseFormat.TEXT));
    }

    @Override
    public void recordFlagData(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            byte @Nullable [] verboseData,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        UUID sessionId = sessionTracker.currentSessionId(playerUuid);
        if (sessionId == null) {
            // See recordFlag(): binary verbose uses the same synthesized-session fallback.
            sessionId = sessionTracker.observeActivity(playerUuid, now, meta);
        }
        final int checkId = resolveCheckId(check);
        final UUID sid = sessionId;
        final byte[] payload = verboseData == null ? null : verboseData.clone();
        store.submit(Categories.VIOLATION, e -> e
                .sessionId(sid)
                .playerUuid(playerUuid)
                .checkId(checkId)
                .vl(vl)
                .occurredEpochMs(now)
                .verboseData(payload)
                .verboseFormat(VerboseFormat.STRUCTURED_V1));
    }

    @Override
    public void onJoinFromUserLogin(@NotNull PlatformPlayer player, @NotNull User user, long now) {
        GrimPlayer gp = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        onJoin(player.getUniqueId(), player.getName(), now, LiveWriteHooks.clientMetaFor(user, gp));
    }

    @Override
    public void onQuitFromUserDisconnect(@NotNull User user, @Nullable GrimPlayer grimPlayer, long now) {
        UUID uuid = user.getUUID();
        if (uuid == null) return; // disconnected pre-LOGIN_SUCCESS — no session to close
        onQuit(uuid, now, LiveWriteHooks.clientMetaFor(user, grimPlayer));
    }

    @Override
    public void observeBrandFromCheck(@NotNull GrimPlayer grimPlayer) {
        UUID uuid = grimPlayer.user.getUUID();
        if (uuid == null) return;
        observeBrand(uuid, System.currentTimeMillis(), LiveWriteHooks.clientMetaFor(grimPlayer.user, grimPlayer));
    }

    @Override
    public void recordFlagFromCheck(
            @NotNull GrimPlayer player,
            @NotNull AbstractCheck check,
            double vl,
            @Nullable String verbose) {
        try {
            recordFlag(player.uuid, check, vl, verbose, System.currentTimeMillis(), SessionTracker.ClientMeta.empty());
        } catch (RuntimeException e) {
            // Don't let a datastore issue break the alert path; the legacy
            // write already ran when we got here. One warn, then swallow.
            LogUtil.warn("v1 datastore recordFlag failed: " + e.getMessage());
        }
    }

    @Override
    public void recordFlagDataFromCheck(
            @NotNull GrimPlayer player,
            @NotNull AbstractCheck check,
            double vl,
            byte @Nullable [] verboseData) {
        try {
            long now = System.currentTimeMillis();
            if (shouldStoreBinaryRow(player.uuid, check, now)) {
                recordFlagData(player.uuid, check, vl, verboseData, now, SessionTracker.ClientMeta.empty());
            }
        } catch (RuntimeException e) {
            // Don't let a datastore issue break the check path.
            LogUtil.warn("v1 datastore recordFlag failed: " + e.getMessage());
        }
    }

    /**
     * Rate cap for binary-verbose rows: at most one stored row per (player, check)
     * per interval. Sparse flagging (legit players, one flag every few seconds) is
     * unaffected — every [log] execution still lands. Only bursts collapse: a
     * 20 flags/sec storm stores at most one row per interval while still recording
     * the rising VL on each stored row. The in-memory violation counters, alerts
     * and setbacks are NOT throttled — only the database row is. The window counts
     * write attempts, not successes: a failing datastore is not re-hit every flag.
     */
    private boolean shouldStoreBinaryRow(@NotNull UUID uuid, @NotNull AbstractCheck check, long now) {
        long interval = perCheckMinIntervalMs.isEmpty()
                ? binaryRowMinIntervalMs
                : perCheckMinIntervalMs.getOrDefault(check.getCheckName().toLowerCase(Locale.ROOT), binaryRowMinIntervalMs);
        if (interval <= 0L) return true;
        Map<AbstractCheck, Long> perCheck = lastBinaryRowMs.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        Long last = perCheck.get(check);
        if (last != null && now - last < interval) return false;
        perCheck.put(check, now);
        return true;
    }

    private int resolveCheckId(@NotNull AbstractCheck check) {
        String display = check.getCheckName();
        String key = display.toLowerCase(Locale.ROOT);
        Integer cached = checkIdCache.get(key);
        if (cached != null) return cached;

        String declaredStable = check.getStableKey();
        String stable;
        if (declaredStable != null && !declaredStable.isEmpty()) {
            stable = declaredStable;
        } else {
            // Check hasn't adopted the stable-key contract yet. Fall back to
            // the legacy map, and warn exactly once per display so the
            // missing declaration surfaces without spamming the log.
            if (missingStableKeyLogged.add(key)) {
                LogUtil.warn("[grim-history] check " + display
                        + " has no stableKey declared; falling back to StableKeyMapping. "
                        + "Populate the @CheckData.stableKey / CheckInfo.stableKey field.");
            }
            stable = StableKeyMapping.stableKeyFor(display)
                    .orElse(StableKeyMapping.legacyFallback(display));
        }
        String description = check.getDescription();
        String introducedVersion = safePluginVersion();
        int id = checkRegistry.intern(stable, display, description, introducedVersion);
        checkIdCache.put(key, id);
        return id;
    }

    private static @Nullable String safePluginVersion() {
        try {
            return GrimAPI.INSTANCE.getExternalAPI().getGrimVersion();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
