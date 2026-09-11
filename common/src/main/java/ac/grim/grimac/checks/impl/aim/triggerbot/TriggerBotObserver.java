package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.manager.deepdebug.DeepDebugManager;
import ac.grim.grimac.manager.deepdebug.DeepDebugSession;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.enums.Pose;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.Collisions;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
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
public final class TriggerBotObserver extends GrimProcessor implements PacketReceiveListener, PostPredictionListener, PacketSendListener {
    private static final int MAX_TARGETS = 4;
    private static final int TARGET_LIFETIME_TICKS = 100;
    private static final int MAX_ENTITIES = 128;
    private static final int MAX_VISIBILITY_BLOCKS = 256;
    private static final long FAILURE_RETRY_NANOS = 30_000_000_000L;
    private volatile boolean enabled;
    private Recording recording;
    private final TriggerBotStateFence poseFence = new TriggerBotStateFence();
    private final TriggerBotStateFence motionFence = new TriggerBotStateFence();

    public TriggerBotObserver(GrimPlayer player) {
        super(player, false);
        reload();
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("TriggerBot.observation-enabled", true);
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        Recording r = recording;
        if (r == null || r.failed) return;
        // A persistent "last prediction checked" bit can survive an early return
        // on the NEXT movement. Require a completion for this actual packet.
        r.predictionSeen = true;
        long generation = motionFence.generation();
        r.predictionChecked = complete.isChecked() && !complete.getData().isTeleport()
                && Double.isFinite(complete.getOffset()) && complete.getOffset() <= 0.03
                && !player.skippedTickInActualMovement && !motionFence.isPending(player.lastTransactionReceived.get());
        r.predictionFenceGeneration = generation;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !player.supportsEndTick()) return;
        // Send callbacks can use another thread. Only these constant-space atomic
        // fences are written here, never the receive-thread recording or detector.
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_METADATA) {
            var metadata = new WrapperPlayServerEntityMetadata(event);
            if (metadata.getEntityId() == player.entityID
                    && metadata.getEntityMetadata().stream().anyMatch(data -> data.getIndex() == 6)) {
                fenceAfterSend(event, poseFence);
            }
        } else if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            if (new WrapperPlayServerEntityVelocity(event).getEntityId() == player.entityID) fenceAfterSend(event, motionFence);
        } else if (type == PacketType.Play.Server.EXPLOSION) {
            fenceAfterSend(event, motionFence);
        } else if (type == PacketType.Play.Server.ENTITY_EFFECT) {
            var effect = new WrapperPlayServerEntityEffect(event);
            if (effect.getEntityId() == player.entityID && affectsCombatMotion(effect.getPotionType())) fenceAfterSend(event, motionFence);
        } else if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
            var effect = new WrapperPlayServerRemoveEntityEffect(event);
            if (effect.getEntityId() == player.entityID && affectsCombatMotion(effect.getPotionType())) fenceAfterSend(event, motionFence);
        } else if (type == PacketType.Play.Server.UPDATE_ATTRIBUTES) {
            var attributes = new WrapperPlayServerUpdateAttributes(event);
            if (attributes.getEntityId() == player.entityID && attributes.getProperties().stream().anyMatch(property ->
                    property.getAttribute() == Attributes.ATTACK_SPEED || property.getAttribute() == Attributes.GRAVITY
                            || property.getAttribute() == Attributes.SCALE || property.getAttribute() == Attributes.ENTITY_INTERACTION_RANGE)) {
                fenceAfterSend(event, motionFence);
            }
        }
    }

    private void fenceAfterSend(PacketSendEvent event, TriggerBotStateFence fence) {
        var token = fence.pending();
        // Wait for an EXISTING later transaction. Do not inject a packet, wait,
        // cancel/rewrite the event, or schedule work on the server's tick thread.
        event.getTasksAfterSend().add(() -> fence.sent(token, player.lastTransactionSent.get()));
    }

    static boolean affectsCombatMotion(PotionType potion) {
        return potion == PotionTypes.LEVITATION || potion == PotionTypes.SLOW_FALLING
                || potion == PotionTypes.JUMP_BOOST || potion == PotionTypes.BLINDNESS;
    }

    /** Reads only, before cooldown/sprint prediction consumes inputs. No debug session is required. */
    public void captureBeforeAttack(PacketReceiveEvent event, int id) {
        if (player.uuid == null || !player.supportsEndTick() || event.isCancelled()
                || !isSupportedAttackPacket(event.getPacketType())) return;
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
            r.prePoseFenceGeneration = poseFence.generation();
            r.preMotionFenceGeneration = motionFence.generation();
            if (event.getPacketType() == PacketType.Play.Client.ATTACK) r.preModernAttacks++;
            else r.preLegacyAttacks++;
            r.preAttackTarget = id;
            r.preAttackReady = isFullyCooled(player.attackCooldown.getMinimumProgress());
            r.preAttackContext = groundedContext(r, interval);
            r.preAttackWeapon = player.inventory.getHeldItem().getType().getName().toString();
            r.preAttackSpeed = player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED);
            r.preCombat = combatSnapshot(r, interval, r.preAttackSpeed);
            r.preOpportunityValid = opportunityProblem(r.preCombat) == null;
            r.preLandingSoon = r.preOpportunityValid && landingSoon(r.preCombat);
            r.preGeometry = Result.UNKNOWN;
            // Exactly one bounded query per interval, before the attack resets
            // cooldown and before a later movement advances target interpolation.
            if (r.preAttackCount == 1 && r.preOpportunityValid && eligibilityProblem() == null) {
                double reach = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
                if (Double.isFinite(reach) && reach > 0 && reach <= 4) {
                    r.preGeometry = evaluateTarget(entity, id, eyePositions(), lookDirections(), reach).result();
                }
            }
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
        return groundedProblem(r, tick) == null;
    }

    private String groundedProblem(Recording r, long tick) {
        if (player.packetStateData.knownInput.jump()) return "jump-input";
        var state = combatSnapshot(r, tick, player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED));
        if (!state.validMotion() || !state.grounded() || state.groundTicks() < 2) return "airborne-or-vertical-motion";
        if (player.inVehicle() || player.isFlying || player.isGliding || player.isSwimming
                || player.isSneaking || player.pose != Pose.STANDING || player.packetStateData.knownInput.shift()) return "pose";
        if (player.packetStateData.isSlowedByUsingItem()) return "using-item";
        if (r.lastSprintStartTick >= 0 && tick - r.lastSprintStartTick < 3
                || r.lastSprintStopTick >= 0 && tick - r.lastSprintStopTick < 3) return "sprint-transition";
        return null;
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
            if (type == PacketType.Play.Client.ATTACK && !event.isCancelled()) {
                attackedEntity = new WrapperPlayClientAttack(event).getEntityId();
            } else if (type == PacketType.Play.Client.INTERACT_ENTITY && !event.isCancelled()) {
                var packet = new WrapperPlayClientInteractEntity(event);
                if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) attackedEntity = packet.getEntityId();
            }
            int targetId = attackedEntity;
            boolean consumedSync = isConsumedSyncReply(type, player.packetStateData.lastTransactionPacketWasValid);
            boolean movementReset = player.packetStateData.lastPacketWasTeleport
                    || player.packetStateData.lastPacketWasOnePointSeventeenDuplicate;
            boolean position = WrapperPlayClientPlayerFlying.isFlying(type)
                    && new WrapperPlayClientPlayerFlying(event).hasPositionChanged();
            boolean predictionChecked = r.predictionSeen && r.predictionChecked
                    && r.predictionFenceGeneration == motionFence.generation();
            long predictionGeneration = r.predictionFenceGeneration;
            if (WrapperPlayClientPlayerFlying.isFlying(type) || type == PacketType.Play.Client.CLIENT_TICK_END) {
                r.predictionSeen = false;
                r.predictionChecked = false;
            }
            // PacketEvents executes post tasks on the same receive thread after
            // all listeners, including for cancelled packets. Decode only here:
            // the buffer may already have been cleared when the task runs.
            event.getPostTasks().add(() -> {
                if (recording != r || r.failed) return;
                try {
                    process(type, action, targetId, event.isCancelled(), consumedSync, movementReset, position, predictionChecked, predictionGeneration, r);
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
                         boolean cancelled, boolean consumedSync, boolean movementReset, boolean position,
                         boolean predictionChecked, long predictionGeneration, Recording r) {
        if (!player.supportsEndTick()) {
            if (r.session != null) r.session.setTriggerBotReport("Unsupported client/server tick-end combination. No observations or verdict.");
            return;
        }
        if (!cancelled) {
            predictionChecked &= predictionGeneration == motionFence.generation()
                    && !motionFence.isPending(player.lastTransactionReceived.get());
            if (action == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
                r.lastSprintStartTick = r.tick + 1;
                r.combat.sprint(r.tick + 1, true);
            } else if (action == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                r.lastSprintStopTick = r.tick + 1;
                r.combat.sprint(r.tick + 1, false);
            }
            if (type == PacketType.Play.Client.PLAYER_INPUT) r.combat.input(r.tick + 1, player.packetStateData.knownInput.forward());
            if (position && !movementReset) r.combat.movement(r.tick + 1, player.onGround,
                    player.clientVelocity.getY(), predictionChecked);
            if (position && !movementReset && predictionChecked) r.confirmedMotionFence = predictionGeneration;
        }
        if (type == PacketType.Play.Client.ANIMATION) {
            r.swung = true;
            // Animation also accompanies right-click/offhand use. It does not
            // establish a vanilla attack-cooldown reset. A preceding accepted
            // attack already supplied a reset; otherwise wait for the next one.
            if (r.pendingAttacks.isEmpty()) r.combat.ambiguousSwing(r.tick + 1);
        }
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
            r.combat.reset();
            if (movementReset) detector().resetDetector();
        }
        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE
                || type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.USE_ITEM
                || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            r.invalidInterval = true;
            if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
                detector().resetDetector();
                if (!cancelled) r.combat.swing(r.tick + 1);
            }
        }
        if (targetId != -1 && !cancelled) {
            r.attacks++;
            if (type == PacketType.Play.Client.ATTACK) r.postModernAttacks++;
            else r.postLegacyAttacks++;
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
            r.combat.swing(r.tick + 1);
        }
        if (type != PacketType.Play.Client.CLIENT_TICK_END) return;
        r.tick++;
        r.combat.endTick(r.tick, player.onGround, player.y);
        long poseGeneration = poseFence.generation();
        if (poseGeneration != r.poseFenceGeneration) r.standingTicks = 0;
        r.poseFenceGeneration = poseGeneration;
        r.standingTicks = standingContext() && !poseFence.isPending(player.lastTransactionReceived.get())
                ? Math.min(10, r.standingTicks + 1) : 0;
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
        String modelWeapon = modelWeapon(weapon);
        double reach = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double attackSpeed = player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED);
        if (r.weapon != null && (!r.weapon.equals(weapon) || !modelWeapon.equals(r.modelWeapon)
                || r.reach != reach || r.attackSpeed != attackSpeed)) {
            r.quietTicks = 3;
            detector().resetDetector();
        }
        r.weapon = weapon;
        r.modelWeapon = modelWeapon;
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
            if (r.session != null) r.detectorExclusions.merge("environment:" + reason, 1L, Long::sum);
            if (r.session != null) r.opportunityExclusions.merge("environment:" + reason, 1L, Long::sum);
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
        r.preCombat = null;
        r.swung = false;
        r.detectorTransition = false;
        // At most one immutable snapshot per second and per 20 client tick-ends.
        // Formatting is never performed here, including when the queue is full.
        if (r.session != null && r.tick - r.lastPublishTick >= 20 && now - r.lastPublishNanos >= 1_000_000_000L) {
            r.publish(now, detector().snapshot(now));
        }
    }

    static boolean isSupportedAttackPacket(PacketTypeCommon type) {
        return type == PacketType.Play.Client.INTERACT_ENTITY || type == PacketType.Play.Client.ATTACK;
    }

    static boolean isFullyCooled(float progress) {
        // The existing float estimate can report 0.99999994 for a full sword
        // cooldown. Tolerate exactly the adjacent float, not an earlier tick.
        return Float.isFinite(progress) && progress >= Math.nextDown(1.0F);
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
        if (!player.inventory.isPacketInventoryActive) return "inventory-not-packet-tracked";
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

    private TriggerBotCombatTracker.Snapshot combatSnapshot(Recording r, long tick, double attackSpeed) {
        return r.combat.snapshot(tick, player.onGround, player.packetStateData.knownInput.jump(),
                player.isSprinting, attackSpeed, player.attackCooldown.getMinimumProgress());
    }

    private String modelWeapon(String weapon) {
        return player.inventory.getOffHand().getType().getName().toString().endsWith("mace")
                && !weapon.endsWith("mace") ? weapon + "+offhand_mace" : weapon;
    }

    private String opportunityProblem(TriggerBotCombatTracker.Snapshot state) {
        if (state == null || !state.validMotion()) return "motion-not-fresh";
        if (motionFence.isPending(player.lastTransactionReceived.get()) || recording == null
                || recording.confirmedMotionFence != motionFence.generation()) return "server-state-transition";
        if (player.wasTouchingWater || player.wasTouchingLava || player.isClimbing
                || player.isFlying || player.isGliding || player.isSwimming || player.inVehicle()) return "special-movement";
        if (player.isSneaking || player.pose != Pose.STANDING || player.packetStateData.knownInput.shift()) return "pose";
        if (player.packetStateData.isSlowedByUsingItem()) return "using-item";
        if (player.inventory.getOffHand().getType().getName().toString().endsWith("crossbow")) return "offhand-crossbow";
        return null;
    }

    private boolean standingContext() {
        return player.pose == Pose.STANDING && !player.isSneaking && !player.packetStateData.knownInput.shift()
                && !player.isFlying && !player.isGliding && !player.isSwimming && !player.inVehicle()
                && !player.wasTouchingWater && !player.wasTouchingLava && !player.isClimbing
                && player.compensatedEntities.self.getAttributeValue(Attributes.SCALE) == 1;
    }

    private boolean certifiedStandingEyes() {
        if (recording == null || recording.standingTicks < 10 || !standingContext()
                || poseFence.isPending(player.lastTransactionReceived.get()) || recording.poseFenceGeneration != poseFence.generation()
                || player.compensatedEntities.entityMap.size() > MAX_ENTITIES) return false;
        SimpleCollisionBox bounds = player.boundingBox.copy();
        if (Math.abs(bounds.maxY - bounds.minY - Pose.STANDING.height) > 1.0E-6
                || Math.abs(bounds.maxX - bounds.minX - Pose.STANDING.width) > 1.0E-6
                || Math.abs(bounds.maxZ - bounds.minZ - Pose.STANDING.width) > 1.0E-6) return false;
        // Vanilla eye height changes immediately with pose. Certify that standing
        // fits even at unsent sub-threshold positions; otherwise keep every eye
        // height. This can fail at the supporting floor without resetting the
        // standing-context history. Airborne samples can still be certified.
        bounds.expand(player.getMovementThreshold() - 1.0E-7);
        if (!knownCollisionChunks(bounds) || !Collisions.isEmpty(player, bounds)) return false;
        return !possibleEntityOverlap(bounds);
    }

    private boolean possibleEntityOverlap(SimpleCollisionBox bounds) {
        // Vanilla noCollision also considers collidable entities. Conservatively
        // veto ANY possible entity overlap instead of trusting a block-only fit.
        if (player.compensatedEntities.entityMap.size() > MAX_ENTITIES) return true;
        for (var entry : player.compensatedEntities.entityMap.int2ObjectEntrySet()) {
            if (entry.getIntKey() == player.entityID || entry.getValue().isDead) continue;
            SimpleCollisionBox other = entry.getValue().getPossibleCollisionBoxes();
            if (!finiteBox(other)) return true;
            if (bounds.maxX > other.minX && bounds.minX < other.maxX && bounds.maxY > other.minY
                    && bounds.minY < other.maxY && bounds.maxZ > other.minZ && bounds.minZ < other.maxZ) return true;
        }
        return false;
    }

    private boolean knownCollisionChunks(SimpleCollisionBox box) {
        if (!finiteBox(box) || box.maxX - box.minX > 2 || box.maxY - box.minY > 8 || box.maxZ - box.minZ > 2) return false;
        int minX = (int) Math.floor(box.minX) - 2, maxX = (int) Math.floor(box.maxX) + 2;
        int minZ = (int) Math.floor(box.minZ) - 2, maxZ = (int) Math.floor(box.maxZ) + 2;
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (player.compensatedWorld.getChunk(x, z) == null) return false;
            }
        }
        return true;
    }

    private static boolean finiteBox(SimpleCollisionBox box) {
        return finiteWorldCoordinate(box.minX) && finiteWorldCoordinate(box.maxX)
                && finiteWorldCoordinate(box.minY) && finiteWorldCoordinate(box.maxY)
                && finiteWorldCoordinate(box.minZ) && finiteWorldCoordinate(box.maxZ)
                && box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ;
    }

    private List<Vec3> eyePositions() {
        if (certifiedStandingEyes()) {
            if (recording.session != null) recording.certifiedEyeSamples++;
            return List.of(new Vec3(player.x, player.y + (double) Pose.STANDING.eyeHeight, player.z));
        }
        List<Vec3> eyes = new ArrayList<>(3);
        for (double eye : player.getPossibleEyeHeights()) eyes.add(new Vec3(player.x, player.y + eye, player.z));
        return eyes;
    }

    private List<Vec3> lookDirections() {
        Vector3dm look = ReachUtils.getLook(player, player.yaw, player.pitch);
        return List.of(new Vec3(look.getX(), look.getY(), look.getZ()));
    }

    private boolean landingSoon(TriggerBotCombatTracker.Snapshot state) {
        if (!state.descending()) return false;
        double velocity = player.clientVelocity.getY();
        // A fast fall or unknown collision volume cannot certify that the source's
        // optional last-falling-tick veto is absent. It censors that opportunity.
        if (!Double.isFinite(velocity) || velocity < -4 || velocity >= 0) return true;
        SimpleCollisionBox swept = player.boundingBox.copy();
        swept.minY += velocity - player.getMovementThreshold();
        if (!knownCollisionChunks(swept)) return true;
        return !Collisions.isEmpty(player, swept) || possibleEntityOverlap(swept);
    }

    private record GeometrySample(Result result, String problem) { }

    private GeometrySample evaluateTarget(PacketEntity target, int id, List<Vec3> eyes, List<Vec3> directions, double reach) {
        if (target.getAttributeValue(Attributes.SCALE) != 1 || target.currentPose != Pose.STANDING
                || target.transitionalPose != null || target.isDead || target.riding != null) {
            return new GeometrySample(Result.UNKNOWN, "unsupported-target");
        }
        Box outer = copy(target.getPossibleCollisionBoxes());
        var states = target.getPossibleHitboxStates();
        Result result = classifyHitboxStates(outer, states, eyes, directions, reach, player.getMovementThreshold());
        String problem = result == Result.UNKNOWN ? "uncertain-ray-or-state" : null;
        if (result == Result.INSIDE) {
            // Certify the full bounded ray prism. A wall beyond the target may
            // conservatively exclude a sample, but cannot create a false INSIDE.
            problem = visibilityProblem(null, eyes, directions, reach);
            if (problem == null && !unambiguousEntity(id, eyes, directions, reach)) problem = "other-entity";
            if (problem != null) result = Result.UNKNOWN;
        }
        return new GeometrySample(result, problem);
    }

    private void sample(Recording r, String weapon, double reach, long now) {
        double attackSpeed = player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED);
        boolean attacked = r.pendingAttacks.size() == 1 && r.pendingAttacks.get(0) == r.primaryTarget;
        boolean attackValid = attacked && r.preAttackCount == 1 && r.preAttackTarget == r.primaryTarget
                && r.preAttackReady && r.preAttackContext && weapon.equals(r.preAttackWeapon) && attackSpeed == r.preAttackSpeed;
        boolean ready = attacked ? attackValid : isFullyCooled(player.attackCooldown.getMinimumProgress());
        var combat = attacked ? r.preCombat : combatSnapshot(r, r.tick, attackSpeed);
        String opportunityProblem = detector().disabledReason();
        if (opportunityProblem == null) opportunityProblem = opportunityProblem(combat);
        if (opportunityProblem == null && (r.detectorTransition || r.swung && !attacked)) opportunityProblem = "digging-or-empty-swing";
        if (opportunityProblem == null && !r.pendingAttacks.isEmpty() && !attacked) opportunityProblem = "multiple-attacks";
        // "Only with weapon" is optional in the source. Generic melee items and
        // an empty hand use its normal .75/.8 branch; crossbows are vetoed there.
        if (opportunityProblem == null && weapon.endsWith("crossbow")) opportunityProblem = "mainhand-crossbow";
        if (opportunityProblem == null && !(Double.isFinite(attackSpeed) && attackSpeed >= 0.5 && attackSpeed <= 4)) opportunityProblem = "attack-speed";
        boolean validOpportunityAttack = attacked && r.preAttackCount == 1 && r.preAttackTarget == r.primaryTarget
                && r.preOpportunityValid && weapon.equals(r.preAttackWeapon) && attackSpeed == r.preAttackSpeed
                && r.prePoseFenceGeneration == poseFence.generation() && r.preMotionFenceGeneration == motionFence.generation();
        if (opportunityProblem == null && attacked && !validOpportunityAttack) opportunityProblem = "invalid-pre-attack-state";
        boolean opportunityEligible = opportunityProblem == null;
        if (!opportunityEligible) {
            detector().invalidateOpportunities(r.tick, now);
            if (r.session != null) r.opportunityExclusions.merge(opportunityProblem, 1L, Long::sum);
        }
        String detectorProblem = detector().disabledReason();
        if (detectorProblem == null) detectorProblem = groundedProblem(r, r.tick);
        if (detectorProblem == null && (r.detectorTransition || r.swung && !attacked)) detectorProblem = "digging-or-empty-swing";
        if (detectorProblem == null && !r.pendingAttacks.isEmpty() && !attacked) detectorProblem = "multiple-attacks";
        if (detectorProblem == null && !(weapon.endsWith("_sword") || weapon.endsWith("_axe"))) detectorProblem = "weapon";
        if (detectorProblem == null && !(Double.isFinite(attackSpeed) && attackSpeed >= 0.5 && attackSpeed <= 4)) detectorProblem = "attack-speed";
        if (detectorProblem == null && attacked && r.preAttackCount == 0) detectorProblem = "missing-pre-attack-state";
        if (detectorProblem == null && attacked && !attackValid) detectorProblem = "invalid-pre-attack-state";
        if (detectorProblem == null && !ready) detectorProblem = "cooldown-not-ready";
        // The source model observes blocked cooldown/rising intervals as well:
        // without them there is no independently observed readiness transition.
        boolean detectorEligible = detectorProblem == null;
        if (!detectorEligible) {
            if (r.session != null) r.detectorExclusions.merge(detectorProblem, 1L, Long::sum);
            detector().invalidateGrounded(r.tick, now);
            if (r.session == null && !opportunityEligible) return;
        }
        List<Vec3> eyes = eyePositions();
        List<Vec3> directions = lookDirections();
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
            GeometrySample evaluated = evaluateTarget(target.entity, id, eyes, directions, reach);
            Result result = evaluated.result();
            String geometryProblem = evaluated.problem();
            results.put(id, result);
            if (r.session != null) {
                r.geometryResults.merge(result, 1L, Long::sum);
                if (geometryProblem != null) r.geometryExclusions.merge(geometryProblem, 1L, Long::sum);
            }
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
        if (opportunityEligible) {
            Result opportunityGeometry = attacked ? r.preGeometry : geometry;
            if (primary == null) {
                detector().invalidateOpportunities(r.tick, now);
                if (r.session != null) r.opportunityExclusions.merge("no-recent-target", 1L, Long::sum);
            } else {
                if (r.session != null) {
                    if (opportunityGeometry == Result.UNKNOWN) r.opportunityExclusions.merge("geometry-unknown", 1L, Long::sum);
                    else r.opportunityEligibleTicks++;
                }
                detector().observeOpportunity(new TriggerBotOpportunityDetector.Frame(r.tick, now, primary.entity,
                        r.modelWeapon, attackSpeed, opportunityGeometry, true, attacked, validOpportunityAttack,
                        combat.cooldownProgress(), combat.grounded(), combat.descending(), combat.jump(), combat.groundTicks(),
                        combat.sprinting(), combat.sprintReleased(), attacked ? r.preLandingSoon : landingSoon(combat),
                        combat.forwardHeld(), combat.forwardRestored()));
            }
        }
        if (!detectorEligible) return;
        if (primary == null) {
            if (r.session != null) r.detectorExclusions.merge("no-recent-target", 1L, Long::sum);
            detector().invalidateGrounded(r.tick, now);
        } else {
            if (r.session != null) {
                if (geometry == Result.UNKNOWN) r.detectorExclusions.merge("geometry-unknown", 1L, Long::sum);
                else r.detectorEligibleTicks++;
            }
            detector().observe(new TriggerBotDetector.Frame(r.tick, now, primary.entity, weapon, attackSpeed,
                    geometry, true, ready, attacked, attackValid));
        }
    }

    private boolean unambiguousEntity(int id, List<Vec3> eyes, List<Vec3> directions, double reach) {
        for (var entry : player.compensatedEntities.entityMap.int2ObjectEntrySet()) {
            if (entry.getIntKey() == id || entry.getIntKey() == player.entityID || entry.getValue().isDead) continue;
            SimpleCollisionBox possible = entry.getValue().getPossibleCollisionBoxes().copy();
            possible.expand(player.getMovementThreshold());
            Box box = copy(possible);
            // Even a possibly intersected second entity makes selection ambiguous.
            if (classify(box, null, eyes, directions, reach) != Result.OUTSIDE) return false;
        }
        return true;
    }

    private String visibilityProblem(Box inner, List<Vec3> eyes, List<Vec3> directions, double reach) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (Vec3 eye : eyes) for (Vec3 direction : directions) {
            double distance = inner == null ? reach : rayDistance(inner, eye, direction);
            if (!Double.isFinite(distance) || distance > reach) return "visibility-ray";
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
                || !finiteWorldCoordinate(minZ) || !finiteWorldCoordinate(maxZ)) return "visibility-coordinates";
        double uncertainty = player.getMovementThreshold() + BOUNDARY_EPSILON;
        int x0 = (int) Math.floor(minX - uncertainty), x1 = (int) Math.floor(maxX + uncertainty);
        int y0 = (int) Math.floor(minY - uncertainty), y1 = (int) Math.floor(maxY + uncertainty);
        int z0 = (int) Math.floor(minZ - uncertainty), z1 = (int) Math.floor(maxZ + uncertainty);
        if (x1 - x0 > 12 || y1 - y0 > 12 || z1 - z0 > 12) return "visibility-budget";
        long volume = ((long) x1 - x0 + 1) * ((long) y1 - y0 + 1) * ((long) z1 - z0 + 1);
        if (volume <= 0 || volume > MAX_VISIBILITY_BLOCKS) return "visibility-budget";
        // Deliberately conservative: require the whole prism to be known air.
        // Grass, fluids and partial blocks exclude a sample rather than counting a miss.
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (player.compensatedWorld.getChunk(x >> 4, z >> 4) == null) return "unknown-chunk";
            for (int y = y0; y <= y1; y++) if (!player.compensatedWorld.getBlock(x, y, z).getType().isAir()) return "block-in-visibility-prism";
        }
        return null;
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
        final TriggerBotCombatTracker combat = new TriggerBotCombatTracker();
        final TriggerBotEpisodes episodes;
        final Map<Integer, Target> targets = new LinkedHashMap<>();
        final List<Integer> pendingAttacks = new ArrayList<>(MAX_TARGETS);
        final Map<String, Long> exclusions = new LinkedHashMap<>();
        final Map<String, Long> detectorExclusions = new LinkedHashMap<>();
        final Map<String, Long> opportunityExclusions = new LinkedHashMap<>();
        final Map<String, Long> geometryExclusions = new LinkedHashMap<>();
        final Map<Result, Long> geometryResults = new java.util.EnumMap<>(Result.class);
        long tick, lastTickNanos, observedTicks, excludedTicks, unknownGeometry, attacks;
        long preModernAttacks, preLegacyAttacks, postModernAttacks, postLegacyAttacks, detectorEligibleTicks;
        long opportunityEligibleTicks;
        long certifiedEyeSamples;
        long consumedSyncReplies, disruptiveCancellations;
        long lastCombatTick;
        int primaryTarget = Integer.MIN_VALUE, preAttackTarget, preAttackCount;
        boolean preAttackReady, preAttackContext, swung, detectorTransition;
        boolean preOpportunityValid, preLandingSoon;
        boolean predictionSeen, predictionChecked;
        long predictionFenceGeneration, confirmedMotionFence, poseFenceGeneration;
        long prePoseFenceGeneration, preMotionFenceGeneration;
        Result preGeometry = Result.UNKNOWN;
        TriggerBotCombatTracker.Snapshot preCombat;
        String preAttackWeapon;
        double preAttackSpeed;
        long lastSprintStartTick = -1, lastSprintStopTick = -1, lastAttackSampleTick = -1;
        long lastPublishTick, lastPublishNanos, skippedReports;
        final AtomicBoolean publicationPending = new AtomicBoolean();
        int quietTicks;
        int standingTicks;
        String weapon, modelWeapon, lastExclusion = "none";
        double reach, attackSpeed;
        boolean invalidInterval, failed;
        long failedAtNanos;

        Recording(DeepDebugSession session, long publisherGeneration) {
            this.session = session;
            this.publisherGeneration = publisherGeneration;
            lastPublishNanos = System.nanoTime();
            episodes = new TriggerBotEpisodes(statistics::record);
            if (session != null) session.setTriggerBotReport("Waiting for a shared observation snapshot. Experimental alerts use grounded and source-model opportunity evidence.");
        }

        void publish(long now, TriggerBot.Snapshot detectorSnapshot) {
            lastPublishTick = tick;
            lastPublishNanos = now;
            if (!publicationPending.compareAndSet(false, true)) {
                skippedReports++;
                return;
            }
            ReportSnapshot snapshot = new ReportSnapshot(tick, observedTicks, excludedTicks, unknownGeometry,
                    attacks, lastExclusion, Map.copyOf(exclusions), episodes.pendingEpisodes(), skippedReports,
                    consumedSyncReplies, disruptiveCancellations,
                    preModernAttacks, preLegacyAttacks, postModernAttacks, postLegacyAttacks,
                    detectorEligibleTicks, Map.copyOf(detectorExclusions), opportunityEligibleTicks, Map.copyOf(opportunityExclusions), certifiedEyeSamples,
                    Map.copyOf(geometryResults), Map.copyOf(geometryExclusions), detectorSnapshot,
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
                                  long preModernAttacks, long preLegacyAttacks, long postModernAttacks, long postLegacyAttacks,
                                  long detectorEligibleTicks, Map<String, Long> detectorExclusions,
                                  long opportunityEligibleTicks, Map<String, Long> opportunityExclusions, long certifiedEyeSamples, Map<Result, Long> geometryResults,
                                  Map<String, Long> geometryExclusions,
                                  TriggerBot.Snapshot detectorSnapshot,
                                  List<TriggerBotStatistics.Episode> episodes,
                                  List<TriggerBotAttackSamples.Sample> attackSamples) {
        String format() {
            return "Mode: descriptive debug; the experimental TriggerBot check evaluates grounded reacquisitions and source-model combat opportunities.\n"
                    + "Delay = sampled client tick-end intervals, NOT visual reaction time. Same-tick hits are not evidence of cheating.\n"
                    + "Recent attacked targets only (max 4); first encounter excluded. Clear standing-player geometry only.\n"
                    + "ticks=" + tick + ", observed=" + observedTicks + ", excluded=" + excludedTicks
                    + ", unknownGeometry=" + unknownGeometry + ", attacks=" + attacks + ", lastExclusion=" + lastExclusion + "\n"
                    + "excludedTicksByReason=" + exclusions + ", pendingCensored=" + pendingEpisodes + "\n"
                    + "consumedSyncReplies=" + consumedSyncReplies + ", disruptiveCancelledPackets=" + disruptiveCancellations
                    + "; packet counts, not excluded ticks. Valid consumed PONGs do not interrupt observations.\n"
                    + "Attack packet formats: preVia ATTACK=" + preModernAttacks + ", INTERACT_ENTITY=" + preLegacyAttacks
                    + "; accepted postVia ATTACK=" + postModernAttacks + ", INTERACT_ENTITY=" + postLegacyAttacks
                    + ". Pre/post are two views of the same requests, not additive.\n"
                    + "Descriptive geometry samples by result=" + geometryResults + "; includes unscored contexts and up to four targets.\n"
                    + "Geometry UNKNOWN reasons=" + geometryExclusions + "; counts target samples, not ticks.\n"
                    + "Grounded detector eligible ticks=" + detectorEligibleTicks + ", excludedTicksByFirstReason=" + detectorExclusions + "\n"
                    + "Opportunity detector eligible ticks=" + opportunityEligibleTicks + ", excludedTicksByFirstReason=" + opportunityExclusions + "\n"
                    + "Standing-eye clearance certificates=" + certifiedEyeSamples + "; uncertified poses retain all possible eye heights.\n"
                    + detectorSnapshot.format()
                    + "Asynchronous snapshot through tick=" + tick + ", skippedPublications=" + skippedReports
                    + "; may lag; packet processing does not wait for report completion.\n"
                    + TriggerBotStatistics.formatReport(episodes) + TriggerBotAttackSamples.formatReport(attackSamples);
        }
    }
}
