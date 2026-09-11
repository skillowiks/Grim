package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.deepdebug.DeepDebugManager;
import ac.grim.grimac.manager.deepdebug.DeepDebugSession;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.enums.Pose;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.*;

/**
 * Shared bounded geometry for the experimental check and optional deep-debug.
 * Never calls Reach.checkReach or mutates movement, rotation or packets.
 */
public final class TriggerBotObserver extends GrimProcessor implements PacketReceiveListener {
    private static final int MAX_TARGETS = 4;
    private static final int TARGET_LIFETIME_TICKS = 100;
    private static final int MAX_ENTITIES = 128;
    private static final int MAX_VISIBILITY_BLOCKS = 256;
    private static final long FAILURE_RETRY_NANOS = 30_000_000_000L;
    private volatile boolean enabled;
    private Recording recording;

    public TriggerBotObserver(GrimPlayer player) {
        super(player, false);
        reload();
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("TriggerBot.observation-enabled", true);
    }

    /** Reads only, before cooldown/sprint prediction consumes inputs. No debug session is required. */
    public void captureBeforeAttack(PacketReceiveEvent event, int id) {
        if (player.uuid == null || !player.supportsEndTick() || event.isCancelled()
                || event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        try {
            DeepDebugSession session = activeDebugSession();
            if (session == null && !detector().isDetectionEnabled()) return;
            PacketEntity entity = player.compensatedEntities.entityMap.get(id);
            if (entity == null || entity.getType() != EntityTypes.PLAYER || entity.isDead) return;
            ensureRecording(session);
            Recording r = recording;
            if (r.failed) return;
            long interval = r.tick + 1; // Same label as the next tick-end, including same-interval attacks.
            r.preAttackCount++;
            r.preAttackTarget = id;
            r.preAttackReady = player.attackCooldown.getMinimumProgress() >= 1.0F;
            r.preAttackContext = groundedContext(r, interval);
            r.preAttackWeapon = player.inventory.getHeldItem().getType().getName().toString();
            r.preAttackSpeed = player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED);
            if (session != null) r.attackSamples.record(new TriggerBotAttackSamples.Sample(interval, id,
                    player.attackCooldown.getMinimumProgress(),
                    player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED),
                    player.actualMovement == null ? Double.NaN : player.actualMovement.getY(),
                    player.onGround, player.isSprinting, player.packetStateData.isSlowedByUsingItem(),
                    age(interval, r.lastSprintStartTick), age(interval, r.lastSprintStopTick),
                    age(interval, r.lastAttackSampleTick), player.inventory.getHeldItem().getType().getName().toString()));
            r.lastAttackSampleTick = interval;
        } catch (RuntimeException exception) {
            failRecording(exception);
        }
    }

    private TriggerBot detector() {
        return player.checkManager.get(TriggerBot.class);
    }

    private DeepDebugSession activeDebugSession() {
        if (!enabled) return null;
        DeepDebugSession session = DeepDebugManager.get().getSession(player.uuid);
        return session == null || session.isStopped() ? null : session;
    }

    private boolean groundedContext(Recording r, long tick) {
        return player.onGround && player.lastOnGround && !player.inVehicle() && !player.isFlying
                && !player.isGliding && !player.isSwimming && !player.isSneaking && player.pose == Pose.STANDING
                && !player.packetStateData.knownInput.jump() && !player.packetStateData.knownInput.shift()
                && !player.packetStateData.isSlowedByUsingItem() && player.actualMovement != null
                && Math.abs(player.actualMovement.getY()) < 1.0E-6
                && (r.lastSprintStartTick < 0 || tick - r.lastSprintStartTick >= 3)
                && (r.lastSprintStopTick < 0 || tick - r.lastSprintStopTick >= 3);
    }

    private static long age(long tick, long previous) {
        return previous < 0 ? -1 : tick - previous;
    }

    private void ensureRecording(DeepDebugSession session) {
        long generation = session == null ? 0 : GrimAPI.INSTANCE.getTriggerBotReportPublisher().generation();
        if (recording == null || recording.session != session || recording.publisherGeneration != generation
                || session == null && recording.failed && System.nanoTime() - recording.failedAtNanos >= FAILURE_RETRY_NANOS) {
            recording = new Recording(session, generation);
            detector().resetDetector();
        }
    }

    private void failRecording(RuntimeException exception) {
        if (recording == null) return;
        recording.failed = true;
        recording.failedAtNanos = System.nanoTime();
        detector().resetDetector();
        if (recording.session != null) recording.session.setTriggerBotReport("Observation stopped: " + exception.getClass().getSimpleName()
                + ". No verdict; restart debug after reporting this error.");
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.uuid == null) return;
        DeepDebugSession session = activeDebugSession();
        if (session == null && !detector().isDetectionEnabled()) {
            if (recording != null) detector().resetDetector();
            recording = null;
            return;
        }
        // Outside debug, allocate and scan only after an actual PvP attack.
        if (session == null && recording == null) return;
        if (session == null && recording.failed) {
            // A transient failure must not disable the background check for the
            // rest of the login. Wait for another PvP attack after a bounded pause.
            if (System.nanoTime() - recording.failedAtNanos >= FAILURE_RETRY_NANOS) recording = null;
            return;
        }
        ensureRecording(session);
        Recording r = recording;
        if (r.failed) return;
        try {
            PacketTypeCommon type = event.getPacketType();
            WrapperPlayClientEntityAction.Action action = type == PacketType.Play.Client.ENTITY_ACTION && !event.isCancelled()
                    ? new WrapperPlayClientEntityAction(event).getAction() : null;
            int attackedEntity = -1;
            if (type == PacketType.Play.Client.INTERACT_ENTITY && !event.isCancelled()) {
                var packet = new WrapperPlayClientInteractEntity(event);
                if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) attackedEntity = packet.getEntityId();
            }
            int targetId = attackedEntity;
            boolean consumedSync = isConsumedSyncReply(type, player.packetStateData.lastTransactionPacketWasValid);
            boolean movementReset = player.packetStateData.lastPacketWasTeleport
                    || player.packetStateData.lastPacketWasOnePointSeventeenDuplicate;
            // PacketEvents executes post tasks on the same receive thread after
            // all listeners, including for cancelled packets. Decode only here:
            // the buffer may already have been cleared when the task runs.
            event.getPostTasks().add(() -> {
                if (recording != r || r.failed) return;
                try {
                    process(type, action, targetId, event.isCancelled(), consumedSync, movementReset, r);
                } catch (RuntimeException exception) {
                    failRecording(exception);
                }
            });
        } catch (RuntimeException exception) {
            // Fail closed for this recording; an optional observer must never break
            // the packet pipeline. Do not expose exception messages/item payloads.
            failRecording(exception);
        }
    }

    private void process(PacketTypeCommon type, WrapperPlayClientEntityAction.Action action, int targetId,
                         boolean cancelled, boolean consumedSync, boolean movementReset, Recording r) {
        if (!player.supportsEndTick()) {
            if (r.session != null) r.session.setTriggerBotReport("Unsupported client/server tick-end combination. No observations or verdict.");
            return;
        }
        if (!cancelled) {
            if (action == WrapperPlayClientEntityAction.Action.START_SPRINTING) r.lastSprintStartTick = r.tick + 1;
            else if (action == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) r.lastSprintStopTick = r.tick + 1;
        }
        if (type == PacketType.Play.Client.ANIMATION) r.swung = true;
        if (type == PacketType.Play.Client.PLAYER_DIGGING) r.detectorTransition = true;
        boolean disruptiveCancellation = cancelled;
        if (disruptiveCancellation && consumedSync) {
            // PacketPingListener consumes our valid PONGs at LOWEST. They are
            // normal synchronization, not rejected combat/movement. Resetting
            // quietTicks on these recurring replies starves every observation.
            r.consumedSyncReplies++;
            disruptiveCancellation = false;
        }
        if (disruptiveCancellation) r.disruptiveCancellations++;
        if (disruptiveCancellation || movementReset) {
            r.invalidInterval = true;
            r.quietTicks = 10;
            if (movementReset) detector().resetDetector();
        }
        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE
                || type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.USE_ITEM
                || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            r.invalidInterval = true;
            if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) detector().resetDetector();
        }
        if (targetId != -1 && !cancelled) {
            r.attacks++;
            int id = targetId;
            PacketEntity entity = player.compensatedEntities.entityMap.get(id);
            if (entity != null && entity.getType() == EntityTypes.PLAYER && !entity.isDead) {
                Target previous = r.targets.get(id);
                if (r.primaryTarget != id || previous == null || previous.entity != entity) detector().resetDetector();
                if (r.session == null && r.primaryTarget != id) r.targets.clear();
                r.primaryTarget = id;
                r.lastCombatTick = r.tick;
                if (previous == null || previous.entity != entity) {
                    if (previous == null && r.targets.size() == MAX_TARGETS) {
                        // Invalidate open episodes on eviction rather than inventing misses.
                        r.episodes.invalidate();
                        r.targets.remove(r.targets.keySet().iterator().next());
                    }
                    r.targets.put(id, new Target(entity, r.tick));
                } else previous.lastAttackTick = r.tick;
                if (r.pendingAttacks.size() < MAX_TARGETS) r.pendingAttacks.add(id);
                else r.invalidInterval = true;
            } else r.invalidInterval = true;
        }
        if (type != PacketType.Play.Client.CLIENT_TICK_END) return;
        r.tick++;
        long now = System.nanoTime();
        if (r.session == null && r.tick - r.lastCombatTick > TARGET_LIFETIME_TICKS) {
            recording = null;
            detector().resetDetector();
            return;
        }
        long elapsed = r.lastTickNanos == 0 ? 0 : now - r.lastTickNanos;
        r.lastTickNanos = now;
        // Arrival timing only rejects batching/stalls. It is NOT reaction time,
        // never subtracts ping/2, and never increases suspicion.
        boolean irregular = elapsed < 20_000_000L || elapsed > 150_000_000L;
        if (irregular) r.quietTicks = 3;
        String reason = eligibilityProblem();
        ItemStack item = player.inventory.getHeldItem();
        String weapon = item.getType().getName().toString();
        double reach = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double attackSpeed = player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED);
        if (r.weapon != null && (!r.weapon.equals(weapon) || r.reach != reach || r.attackSpeed != attackSpeed)) {
            r.quietTicks = 3;
            detector().resetDetector();
        }
        r.weapon = weapon;
        r.reach = reach;
        r.attackSpeed = attackSpeed;
        if (reason == null && (item.getComponentOr(ComponentTypes.ATTACK_RANGE, null) != null
                || player.inventory.getStartOfTickStack().getComponentOr(ComponentTypes.ATTACK_RANGE, null) != null)) {
            reason = "item-attack-range"; // Custom/moving reach requires a separate model.
        }
        if (reason == null && (!Double.isFinite(reach) || reach <= 0 || reach > 4)) reason = "unsupported-range";
        if (reason == null && (r.invalidInterval || r.quietTicks > 0)) reason = irregular ? "packet-timing" : "transition";
        if (reason != null) {
            r.excludedTicks++;
            r.exclusions.merge(reason, 1L, Long::sum);
            r.lastExclusion = reason;
            r.episodes.invalidate();
            if (reason.equals("teleport-or-resync") || player.disableGrim) detector().resetDetector();
            else detector().invalidate(r.tick, now);
        } else {
            r.observedTicks++;
            sample(r, weapon, reach, now);
        }
        r.quietTicks = Math.max(0, r.quietTicks - 1);
        r.invalidInterval = false;
        r.pendingAttacks.clear();
        r.preAttackCount = 0;
        r.swung = false;
        r.detectorTransition = false;
        // At most one immutable snapshot per second and per 20 client tick-ends.
        // Formatting is never performed here, including when the queue is full.
        if (r.session != null && r.tick - r.lastPublishTick >= 20 && now - r.lastPublishNanos >= 1_000_000_000L) r.publish(now);
    }

    static boolean isConsumedSyncReply(PacketTypeCommon packetType, boolean validTransaction) {
        // Validity is reset by PacketPingListener for each PONG, but may remain
        // true on other packet types. Never exempt them based on this bit alone.
        // Tick-end clients use PONG; legacy window acknowledgements can also be
        // cancelled by checks for reasons other than synchronization.
        return packetType == PacketType.Play.Client.PONG && validTransaction;
    }

    private String eligibilityProblem() {
        if (player.disableGrim || player.compensatedEntities.self.isDead) return "inactive-or-dead";
        if (player.gamemode != GameMode.SURVIVAL && player.gamemode != GameMode.ADVENTURE) return "gamemode";
        if (!player.cameraEntity.isSelf() || player.inVehicle() || player.isFlying
                || player.isGliding || player.isSwimming || player.pose != Pose.STANDING) return "camera-or-pose";
        if (!player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport
                || player.getSetbackTeleportUtil().shouldBlockMovement()) return "teleport-or-resync";
        if (player.compensatedEntities.entityMap.size() > MAX_ENTITIES) return "entity-budget";
        if (player.packetStateData.isSlowedByUsingItem()) return "using-item";
        if (player.compensatedEntities.self.getAttributeValue(Attributes.SCALE) != 1) return "attacker-scale";
        return null;
    }

    private void sample(Recording r, String weapon, double reach, long now) {
        double attackSpeed = player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED);
        boolean attacked = r.pendingAttacks.size() == 1 && r.pendingAttacks.get(0) == r.primaryTarget;
        boolean attackValid = attacked && r.preAttackCount == 1 && r.preAttackTarget == r.primaryTarget
                && r.preAttackReady && r.preAttackContext && weapon.equals(r.preAttackWeapon) && attackSpeed == r.preAttackSpeed;
        boolean ready = attacked ? attackValid : player.attackCooldown.getMinimumProgress() >= 1.0F;
        boolean contextValid = groundedContext(r, r.tick) && !r.detectorTransition
                && (!r.swung || attacked) && (r.pendingAttacks.isEmpty() || attacked)
                && (weapon.endsWith("_sword") || weapon.endsWith("_axe"))
                && Double.isFinite(attackSpeed) && attackSpeed >= 0.5 && attackSpeed <= 4;
        // Debug retains all descriptive contexts. Background detection spends
        // its geometry budget only on grounded, already-ready combat intervals.
        boolean detectorEligible = contextValid && ready && detector().isDetectionEnabled();
        if (!detectorEligible) {
            detector().invalidate(r.tick, now);
            if (r.session == null) return;
        }
        List<Vec3> eyes = new ArrayList<>();
        for (double eye : player.getPossibleEyeHeights()) eyes.add(new Vec3(player.x, player.y + eye, player.z));
        Vector3dm look = ReachUtils.getLook(player, player.yaw, player.pitch);
        // One tick-end snapshot, not reconstructed render-frame reaction timing.
        List<Vec3> directions = List.of(new Vec3(look.getX(), look.getY(), look.getZ()));
        Map<Integer, Result> results = new LinkedHashMap<>();
        int inside = 0;
        for (Iterator<Map.Entry<Integer, Target>> it = r.targets.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Target> entry = it.next();
            int id = entry.getKey();
            Target target = entry.getValue();
            if (r.tick - target.lastAttackTick > TARGET_LIFETIME_TICKS
                    || player.compensatedEntities.entityMap.get(id) != target.entity) {
                r.episodes.observe(id, target.entity, r.tick, Result.UNKNOWN, weapon, !player.onGround);
                it.remove();
                continue;
            }
            boolean supportedTarget = target.entity.getAttributeValue(Attributes.SCALE) == 1
                    && target.entity.currentPose == Pose.STANDING && target.entity.transitionalPose == null
                    && !target.entity.isDead && target.entity.riding == null;
            SimpleCollisionBox guaranteed = supportedTarget ? target.entity.getGuaranteedCollisionBox() : null;
            Box outer = copy(target.entity.getPossibleCollisionBoxes().copy().expand(player.getMovementThreshold()));
            // Positional packet threshold also applies to the attacker's eye origin.
            Box inner = guaranteed == null ? null : copy(guaranteed.copy().expand(-player.getMovementThreshold()));
            Result result = supportedTarget ? classify(outer, inner, eyes, directions, reach) : Result.UNKNOWN;
            if (result == Result.INSIDE && (!clearBlocks(inner, eyes, directions, reach)
                    || !unambiguousEntity(id, eyes, directions, reach))) result = Result.UNKNOWN;
            results.put(id, result);
            if (result == Result.INSIDE) inside++;
        }
        boolean multipleAttackTargets = r.pendingAttacks.stream().distinct().count() > 1;
        for (Map.Entry<Integer, Result> entry : results.entrySet()) {
            Result result = TriggerBotEpisodes.withAttackEvidence(inside > 1 ? Result.UNKNOWN : entry.getValue(),
                    r.pendingAttacks.contains(entry.getKey()), multipleAttackTargets);
            if (result == Result.UNKNOWN) r.unknownGeometry++;
            r.episodes.observe(entry.getKey(), r.targets.get(entry.getKey()).entity, r.tick, result, weapon, !player.onGround);
        }
        for (int id : r.pendingAttacks) r.episodes.attack(id, r.tick);
        Target primary = r.targets.get(r.primaryTarget);
        Result geometry = results.getOrDefault(r.primaryTarget, Result.UNKNOWN);
        geometry = TriggerBotEpisodes.withAttackEvidence(inside > 1 ? Result.UNKNOWN : geometry, attacked,
                multipleAttackTargets || r.pendingAttacks.size() > 1);
        if (!detectorEligible) return;
        if (primary == null) detector().invalidate(r.tick, now);
        else detector().observe(new TriggerBotDetector.Frame(r.tick, now, primary.entity, weapon, attackSpeed,
                geometry, contextValid, ready, attacked, attackValid));
    }

    private boolean unambiguousEntity(int id, List<Vec3> eyes, List<Vec3> directions, double reach) {
        for (var entry : player.compensatedEntities.entityMap.int2ObjectEntrySet()) {
            if (entry.getIntKey() == id || entry.getIntKey() == player.entityID || entry.getValue().isDead) continue;
            Box box = copy(entry.getValue().getPossibleCollisionBoxes());
            // Even a possibly intersected second entity makes selection ambiguous.
            if (classify(box, null, eyes, directions, reach) != Result.OUTSIDE) return false;
        }
        return true;
    }

    private boolean clearBlocks(Box inner, List<Vec3> eyes, List<Vec3> directions, double reach) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (Vec3 eye : eyes) for (Vec3 direction : directions) {
            double distance = rayDistance(inner, eye, direction);
            if (!Double.isFinite(distance) || distance > reach) return false;
            double length = Math.sqrt(direction.x() * direction.x() + direction.y() * direction.y() + direction.z() * direction.z());
            double x = eye.x() + direction.x() / length * distance;
            double y = eye.y() + direction.y() / length * distance;
            double z = eye.z() + direction.z() / length * distance;
            minX = Math.min(minX, Math.min(eye.x(), x)); maxX = Math.max(maxX, Math.max(eye.x(), x));
            minY = Math.min(minY, Math.min(eye.y(), y)); maxY = Math.max(maxY, Math.max(eye.y(), y));
            minZ = Math.min(minZ, Math.min(eye.z(), z)); maxZ = Math.max(maxZ, Math.max(eye.z(), z));
        }
        if (!finiteWorldCoordinate(minX) || !finiteWorldCoordinate(maxX)
                || !finiteWorldCoordinate(minY) || !finiteWorldCoordinate(maxY)
                || !finiteWorldCoordinate(minZ) || !finiteWorldCoordinate(maxZ)) return false;
        int x0 = (int) Math.floor(minX - BOUNDARY_EPSILON), x1 = (int) Math.floor(maxX + BOUNDARY_EPSILON);
        int y0 = (int) Math.floor(minY - BOUNDARY_EPSILON), y1 = (int) Math.floor(maxY + BOUNDARY_EPSILON);
        int z0 = (int) Math.floor(minZ - BOUNDARY_EPSILON), z1 = (int) Math.floor(maxZ + BOUNDARY_EPSILON);
        if (x1 - x0 > 12 || y1 - y0 > 12 || z1 - z0 > 12) return false;
        long volume = ((long) x1 - x0 + 1) * ((long) y1 - y0 + 1) * ((long) z1 - z0 + 1);
        if (volume <= 0 || volume > MAX_VISIBILITY_BLOCKS) return false;
        // Deliberately conservative: require the whole prism to be known air.
        // Grass, fluids and partial blocks exclude a sample rather than counting a miss.
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (player.compensatedWorld.getChunk(x >> 4, z >> 4) == null) return false;
            for (int y = y0; y <= y1; y++) if (!player.compensatedWorld.getBlock(x, y, z).getType().isAir()) return false;
        }
        return true;
    }

    private static Box copy(SimpleCollisionBox box) {
        return new Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static boolean finiteWorldCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000;
    }

    private static final class Target {
        final PacketEntity entity;
        long lastAttackTick;
        Target(PacketEntity entity, long tick) { this.entity = entity; this.lastAttackTick = tick; }
    }

    private static final class Recording {
        final DeepDebugSession session;
        final long publisherGeneration;
        final TriggerBotStatistics statistics = new TriggerBotStatistics();
        final TriggerBotAttackSamples attackSamples = new TriggerBotAttackSamples();
        final TriggerBotEpisodes episodes;
        final Map<Integer, Target> targets = new LinkedHashMap<>();
        final List<Integer> pendingAttacks = new ArrayList<>(MAX_TARGETS);
        final Map<String, Long> exclusions = new LinkedHashMap<>();
        long tick, lastTickNanos, observedTicks, excludedTicks, unknownGeometry, attacks;
        long consumedSyncReplies, disruptiveCancellations;
        long lastCombatTick;
        int primaryTarget = Integer.MIN_VALUE, preAttackTarget, preAttackCount;
        boolean preAttackReady, preAttackContext, swung, detectorTransition;
        String preAttackWeapon;
        double preAttackSpeed;
        long lastSprintStartTick = -1, lastSprintStopTick = -1, lastAttackSampleTick = -1;
        long lastPublishTick, lastPublishNanos, skippedReports;
        final AtomicBoolean publicationPending = new AtomicBoolean();
        int quietTicks;
        String weapon, lastExclusion = "none";
        double reach, attackSpeed;
        boolean invalidInterval, failed;
        long failedAtNanos;

        Recording(DeepDebugSession session, long publisherGeneration) {
            this.session = session;
            this.publisherGeneration = publisherGeneration;
            lastPublishNanos = System.nanoTime();
            episodes = new TriggerBotEpisodes(statistics::record);
            if (session != null) session.setTriggerBotReport("Waiting for a shared observation snapshot. Experimental alerts use separate grounded evidence.");
        }

        void publish(long now) {
            lastPublishTick = tick;
            lastPublishNanos = now;
            if (!publicationPending.compareAndSet(false, true)) {
                skippedReports++;
                return;
            }
            ReportSnapshot snapshot = new ReportSnapshot(tick, observedTicks, excludedTicks, unknownGeometry,
                    attacks, lastExclusion, Map.copyOf(exclusions), episodes.pendingEpisodes(), skippedReports,
                    consumedSyncReplies, disruptiveCancellations,
                    statistics.copyEpisodes(), attackSamples.copySamples());
            long version = session.reserveTriggerBotReport();
            if (version < 0) {
                publicationPending.set(false);
                return;
            }
            // Capture only immutable data, the publication slot and its gate, never player/cache/recorder state.
            DeepDebugSession destination = session;
            AtomicBoolean gate = publicationPending;
            boolean accepted = GrimAPI.INSTANCE.getTriggerBotReportPublisher().trySubmit(() -> {
                try {
                    destination.publishTriggerBotReport(version, snapshot.format());
                } catch (RuntimeException exception) {
                    destination.publishTriggerBotReport(version, "Observation report unavailable: "
                            + exception.getClass().getSimpleName() + ". No verdict.");
                } finally {
                    gate.set(false);
                }
            });
            if (!accepted) {
                publicationPending.set(false);
                skippedReports++;
            }
        }
    }

    private record ReportSnapshot(long tick, long observedTicks, long excludedTicks, long unknownGeometry,
                                  long attacks, String lastExclusion, Map<String, Long> exclusions,
                                  int pendingEpisodes, long skippedReports,
                                  long consumedSyncReplies, long disruptiveCancellations,
                                  List<TriggerBotStatistics.Episode> episodes,
                                  List<TriggerBotAttackSamples.Sample> attackSamples) {
        String format() {
            return "Mode: descriptive debug; the separate experimental TriggerBot check can emit review alerts from qualified grounded evidence.\n"
                    + "Delay = sampled client tick-end intervals, NOT visual reaction time. Same-tick hits are not evidence of cheating.\n"
                    + "Recent attacked targets only (max 4); first encounter excluded. Clear standing-player geometry only.\n"
                    + "ticks=" + tick + ", observed=" + observedTicks + ", excluded=" + excludedTicks
                    + ", unknownGeometry=" + unknownGeometry + ", attacks=" + attacks + ", lastExclusion=" + lastExclusion + "\n"
                    + "excludedTicksByReason=" + exclusions + ", pendingCensored=" + pendingEpisodes + "\n"
                    + "consumedSyncReplies=" + consumedSyncReplies + ", disruptiveCancelledPackets=" + disruptiveCancellations
                    + "; packet counts, not excluded ticks. Valid consumed PONGs do not interrupt observations.\n"
                    + "Asynchronous snapshot through tick=" + tick + ", skippedPublications=" + skippedReports
                    + "; may lag; packet processing does not wait for report completion.\n"
                    + TriggerBotStatistics.formatReport(episodes) + TriggerBotAttackSamples.formatReport(attackSamples);
        }
    }
}
