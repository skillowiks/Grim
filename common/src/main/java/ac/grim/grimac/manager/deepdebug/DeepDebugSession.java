package ac.grim.grimac.manager.deepdebug;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.impl.movement.NoSlow;
import ac.grim.grimac.checks.impl.prediction.OffsetHandler;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.enums.FluidTag;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemConsumable;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemUseEffects;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;

/**
 * Per-target deep-debug recording session. Collects flag records with full
 * movement context, server interference events (velocity/teleport/...),
 * attribute anomalies and sprint-input churn, and streams compact live lines
 * to the subscribed staff while recording.
 *
 * <p>All mutating entry points are safe to call from the netty/prediction
 * threads; the lists are synchronized and capped, and the live stream is
 * rate-limited so a flag storm cannot flood staff chat.</p>
 */
public final class DeepDebugSession {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_FLAG_RECORDS = 256;
    private static final int MAX_INTERFERENCE_RECORDS = 256;
    private static final int MAX_ATTRIBUTE_ANOMALIES = 64;
    /** Live lines per second before the stream collapses into a suppressed counter. */
    private static final int LIVE_LINE_RATE = 20;

    public final GrimPlayer target;
    public final long startedAtMs = System.currentTimeMillis();
    final long deadlineMs;
    final String targetName;
    /** Staff GrimPlayer that started the session, for P/A/O stream teardown (null for console). */
    private final @Nullable GrimPlayer initiatorPlayer;

    private final List<FlagRecord> flags = Collections.synchronizedList(new ArrayList<>());
    private final List<InterferenceRecord> interference = Collections.synchronizedList(new ArrayList<>());
    private final List<String> attributeAnomalies = Collections.synchronizedList(new ArrayList<>());
    private final MovementTraceRecorder movementTraces = new MovementTraceRecorder();
    public final SprintChurn sprintChurn = new SprintChurn();
    private final Set<Sender> listeners = new CopyOnWriteArraySet<>();

    private volatile boolean stopped = false;
    private volatile long stoppedAtMs;
    // Live-line rate limiting state (netty thread only in practice)
    private long liveWindowStartMs;
    private int liveLinesInWindow;
    private int suppressedLines;

    DeepDebugSession(GrimPlayer target, Sender initiator, long timeoutMs) {
        this.target = target;
        this.deadlineMs = startedAtMs + timeoutMs;
        this.targetName = target.user.getName() == null ? target.user.getUUID().toString() : target.user.getName();
        this.initiatorPlayer = initiator != null && initiator.isPlayer()
                ? GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(initiator.getUniqueId())
                : null;
        if (initiator != null) listeners.add(initiator);
    }

    /** Called once at creation: mirror the classic live P/A/O stream to the initiator. */
    void attachPredictionStream() {
        if (initiatorPlayer != null) {
            target.checkManager.getDebugHandler().toggleListener(initiatorPlayer);
        }
    }

    public boolean isExpired() {
        return !stopped && System.currentTimeMillis() > deadlineMs;
    }

    public boolean isStopped() {
        return stopped;
    }

    public void addListener(Sender listener) {
        listeners.add(listener);
    }

    void recordFlag(GrimPlayer player, Check check, Supplier<String> verbose) {
        if (stopped) return;
        String rendered = safeGet(verbose);
        if (check instanceof OffsetHandler || check instanceof NoSlow) {
            movementTraces.flag(check.getDisplayName() + " " + rendered);
        }
        double offset = player.actualMovement != null && player.predictedVelocity != null
                ? player.predictedVelocity.vector.distance(player.actualMovement) : -1;
        MovementContext movement = MovementContext.snapshot(player, offset);
        FlagRecord record = new FlagRecord(System.currentTimeMillis(), check.getDisplayName(),
                (int) check.getViolations(), rendered, movement, player.isSprinting, player.food,
                player.packetStateData.slowedByUsingItemTransaction != Integer.MIN_VALUE, player.onGround);
        flags.add(record);
        synchronized (flags) {
            if (flags.size() > MAX_FLAG_RECORDS) flags.remove(0);
        }
        broadcast(record);
    }

    void addInterference(InterferenceRecord record) {
        if (stopped) return;
        interference.add(record);
        if (interference.size() > MAX_INTERFERENCE_RECORDS) interference.remove(0);
        if (record.kind() == InterferenceRecord.Kind.ATTACK || record.kind() == InterferenceRecord.Kind.EXPLOSION
                || record.kind() == InterferenceRecord.Kind.TELEPORT) {
            // Keep the events with their movement window even if the session's
            // global interference ring has already evicted them by report time.
            movementTraces.event("timeMs=" + record.timeMs() + " " + record);
        }
    }

    void addAttributeAnomaly(String line) {
        if (stopped) return;
        attributeAnomalies.add(line);
        if (attributeAnomalies.size() > MAX_ATTRIBUTE_ANOMALIES) attributeAnomalies.remove(0);
    }

    void stop() {
        stopped = true;
        stoppedAtMs = System.currentTimeMillis();
        // Chat I/O stays outside the session monitor (netty threads broadcast through it).
        Component pending = drainSuppressedLine();
        if (pending != null) sendLine(pending);
        if (initiatorPlayer != null) {
            target.checkManager.getDebugHandler().removeListener(initiatorPlayer);
        }
    }

    public List<FlagRecord> flagsSnapshot() {
        synchronized (flags) {
            return new ArrayList<>(flags);
        }
    }

    public List<InterferenceRecord> interferenceSnapshot() {
        synchronized (interference) {
            return new ArrayList<>(interference);
        }
    }

    public List<String> attributeAnomaliesSnapshot() {
        synchronized (attributeAnomalies) {
            return new ArrayList<>(attributeAnomalies);
        }
    }

    public long durationMs() {
        long end = stopped ? stoppedAtMs : System.currentTimeMillis();
        return Math.max(0, end - startedAtMs);
    }

    void recordPredictionEvent(String detail) {
        if (!stopped) movementTraces.stateEvent("timeMs=" + System.currentTimeMillis() + " " + detail);
    }

    /** Runs after all prediction checks, so both Simulation and NoSlow mark this movement. */
    public void recordMovement(boolean checked) {
        if (stopped) return;
        GrimPlayer p = target;
        if (p.predictedVelocity == null || p.actualMovement == null) return;
        movementTraces.movement("timeMs=" + System.currentTimeMillis() + " checked=" + checked
                + " transaction=" + p.lastTransactionReceived.get()
                + " pos=" + p.x + "," + p.y + "," + p.z + " yaw=" + p.yaw
                + " predicted=" + p.predictedVelocity.vector + " actual=" + p.actualMovement
                + " startVel=" + p.startTickClientVel + " speed=" + p.speed + " friction=" + p.friction
                + " ground=" + p.onGround + "/" + p.lastOnGround + " sprint=" + p.isSprinting
                + " lastSprinting=" + p.lastSprinting
                + " sprintAttribute=" + p.compensatedEntities.hasSprintingAttributeEnabled
                + " movementThreshold=" + p.getMovementThreshold()
                + " water=" + p.wasTouchingWater + " previousWater=" + p.wasWasTouchingWater + " lava=" + p.wasTouchingLava
                + " swimming=" + p.isSwimming + " wasSwimming=" + p.wasSwimming + " pose=" + p.pose
                + " waterHeight=" + p.getFluidHeight(FluidTag.WATER) + " lavaHeight=" + p.getFluidHeight(FluidTag.LAVA)
                + " depthStrider=" + p.depthStriderLevel
                + " baseTickAddition=" + p.baseTickAddition + " baseTickWaterPushing=" + p.baseTickWaterPushing
                + " useItem=" + p.packetStateData.isSlowedByUsingItem()
                + " useTransaction=" + p.packetStateData.slowedByUsingItemTransaction
                + " hand=" + p.packetStateData.itemInUseHand
                + " selectedSlot=" + p.packetStateData.lastSlotSelected
                + " usingSlot=" + p.packetStateData.getSlowedByUsingItemSlot()
                + " usingStack=" + describeUseItem(p.inventory.getItemInHand(p.packetStateData.itemInUseHand))
                + " heldType=" + itemType(p.inventory.getHeldItem())
                + " offhandType=" + itemType(p.inventory.getOffHand())
                + " knownInput=" + p.packetStateData.knownInput
                + " slowMovement=" + p.isSlowMovement + " sneakingMultiplier=" + p.sneakingSpeedMultiplier
                + " flipItem=" + p.predictedVelocity.isFlipItem() + " flipSneak=" + p.predictedVelocity.isFlipSneaking()
                + " attackSlow=" + p.minAttackSlow + "/" + p.maxAttackSlow
                + " attackSlowVector=" + p.predictedVelocity.isAttackSlow()
                + " predictedInput=" + p.predictedVelocity.input
                + " nextVelocity=" + p.clientVelocity
                + " hiddenCollisionAxes=" + p.uncertaintyHandler.hiddenHorizontalCollisionAxes
                + " previousPositionPackets=" + p.packetStateData.didLastMovementIncludePosition
                + "/" + p.packetStateData.didLastLastMovementIncludePosition
                + " skippedTick=" + p.skippedTickInActualMovement
                + " firstKB=" + (p.firstBreadKB == null ? "none" : p.firstBreadKB.vector)
                + " likelyKB=" + (p.likelyKB == null ? "none" : p.likelyKB.vector)
                + " firstExplosion=" + (p.firstBreadExplosion == null ? "none" : p.firstBreadExplosion.vector)
                + " likelyExplosion=" + (p.likelyExplosions == null ? "none" : p.likelyExplosions.vector)
                + " vectorType=" + p.predictedVelocity.vectorType
                + " fluidBlocks=" + captureFluidBlocks(p));
    }

    private static String captureFluidBlocks(GrimPlayer player) {
        try {
            return FluidDebugSnapshot.capture(player.boundingBox.minX, player.boundingBox.minY, player.boundingBox.minZ,
                player.boundingBox.maxX, player.boundingBox.maxY, player.boundingBox.maxZ, new FluidDebugSnapshot.BlockSource() {
                    @Override
                    public int globalIdAt(int x, int y, int z) {
                        // Keep the missing-column/section markers instead of presenting them as known air.
                        return player.compensatedWorld.getBlockStateId(x, y, z);
                    }

                    @Override
                    public String describe(int globalId) {
                        return switch (globalId) {
                            case -1 -> "unavailable:negative-y-on-legacy-world";
                            case -2 -> "unavailable:column-or-height";
                            case -3 -> "unavailable:section-or-read";
                            default -> WrappedBlockState.getByGlobalId(CompensatedWorld.blockVersion, globalId).toString();
                        };
                    }
                });
        } catch (RuntimeException failure) {
            // A diagnostic cache/mapping read must not interrupt prediction bookkeeping.
            return "{phase=endpointCachedWorld,unavailable=" + failure.getClass().getSimpleName() + "}";
        }
    }

    private static String itemType(@Nullable ItemStack item) {
        return item == null ? "none" : item.getType().getName().toString();
    }

    /** Only movement-relevant components; never include item NBT or display text. */
    private static String describeUseItem(@Nullable ItemStack item) {
        if (item == null) return "none";
        ItemUseEffects useEffects = item.getComponentOr(ComponentTypes.USE_EFFECTS, null);
        ItemConsumable consumable = item.getComponentOr(ComponentTypes.CONSUMABLE, null);
        return itemType(item) + "{useEffectsSpeed=" + (useEffects == null ? "default" : useEffects.getSpeedMultiplier())
                + ",consumeSeconds=" + (consumable == null ? "none" : consumable.getConsumeSeconds()) + "}";
    }

    public List<String> movementTracesSnapshot() {
        return movementTraces.snapshot();
    }

    private void broadcast(FlagRecord record) {
        long now = record.timeMs;
        if (listeners.isEmpty()) return;
        Component pending = null;
        synchronized (this) {
            if (now - liveWindowStartMs > 1000L) {
                pending = drainSuppressedLine();
                liveWindowStartMs = now;
                liveLinesInWindow = 0;
            }
            if (++liveLinesInWindow > LIVE_LINE_RATE) {
                suppressedLines++;
                return;
            }
        }
        // Chat I/O outside the monitor: netty-thread flags must not stall on sends.
        if (pending != null) sendLine(pending);
        sendLine(renderLine(record));
    }

    /** Caller must hold the session monitor; the returned line is sent after release. */
    private synchronized Component drainSuppressedLine() {
        if (suppressedLines <= 0) return null;
        Component line = Component.text("[" + LocalTime.now().format(TIME) + "] ... " + suppressedLines
                + " more flags suppressed (rate limit)", NamedTextColor.DARK_GRAY);
        suppressedLines = 0;
        return line;
    }

    private void sendLine(Component line) {
        for (Sender listener : listeners) {
            try {
                listener.sendMessage(line);
            } catch (RuntimeException ignored) {
                // A dead listener must never break the flag path.
            }
        }
    }

    private Component renderLine(FlagRecord record) {
        String context = "sprint=" + bool(record.sprinting) + " food=" + record.food
                + (record.usingItem ? " useItem" : "") + " ground=" + bool(record.onGround);
        String raw = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("deep-debug-flag-line",
                "&8[&bDD&8] &7%time% &b%check% &f%verbose% &8(x%vl%) &7%context%");
        raw = raw.replace("%time%", LocalTime.now().format(TIME))
                .replace("%check%", record.checkName)
                .replace("%verbose%", MessageUtil.miniMessageSafe(record.verbose))
                .replace("%vl%", Integer.toString(record.vl))
                .replace("%context%", context)
                .replace("%player%", targetName);
        return MessageUtil.miniMessage(raw);
    }

    private static String bool(boolean value) {
        return value ? "T" : "F";
    }

    private static String safeGet(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
