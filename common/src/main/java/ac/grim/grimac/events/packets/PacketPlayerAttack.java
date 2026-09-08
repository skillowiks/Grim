package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.combat.InvalidInteractTarget;
import ac.grim.grimac.manager.deepdebug.DeepDebugManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHorse;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSpectateEntity;

public class PacketPlayerAttack extends PacketListenerAbstract {

    public PacketPlayerAttack() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public boolean isPreVia() {
        return true;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());

            if (player == null) return;

            int entityId = interact.getEntityId();

            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttack(event, player, entityId);
            } else {
                if (isInvalidEntity(event, player, entityId)) return;

                if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT) {
                    // Interacting with a horse in versions 1.13- will cause the client to
                    // set the player's rotation to the horse's rotation
                    if (player.compensatedEntities.getEntity(entityId) instanceof PacketEntityHorse
                            && player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_13)) {
                        player.packetStateData.horseInteractCausedForcedRotation = true;
                    }
                }
            }
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
            if (packet.getAction() == DiggingAction.STAB) {
                GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
                if (player == null) return;

                DeepDebugManager.AttackDebug debug = DeepDebugManager.get().beginAttack(player, event, -1);
                try {
                    if (player.isResetItemUsageOnAttack()) {
                        GrimAPI.INSTANCE.getItemResetHandler().resetItemUsage(player.platformPlayer);
                    }
                } finally {
                    if (debug != null) debug.finish(player, event.isCancelled(), "stab-item-reset-only");
                }
            }
        }

        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            WrapperPlayClientAttack packet = new WrapperPlayClientAttack(event);
            onAttack(event, player, packet.getEntityId());
        }

        if (event.getPacketType() == PacketType.Play.Client.SPECTATE_ENTITY) {
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            WrapperPlayClientSpectateEntity packet = new WrapperPlayClientSpectateEntity(event);
            onAttack(event, player, packet.getEntityId());
        }
    }

    private void onAttack(PacketReceiveEvent event, GrimPlayer player, int entityId) {
        DeepDebugManager.AttackDebug debug = DeepDebugManager.get().beginAttack(player, event, entityId);
        String outcome = "interrupted";
        try {
            outcome = handleAttack(event, player, entityId);
        } finally {
            if (debug != null) debug.finish(player, event.isCancelled(), outcome);
        }
    }

    private String handleAttack(PacketReceiveEvent event, GrimPlayer player, int entityId) {
        if (isInvalidEntity(event, player, entityId)) return "invalid-target";

        if (player.isResetItemUsageOnAttack()) {
            GrimAPI.INSTANCE.getItemResetHandler().resetItemUsage(player.platformPlayer);
        }

        // This is not vanilla behaviour as the attack damage attribute is marked as not synced to the client
        // However, plugins can still set this by sending an attributes packet
        if (player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_DAMAGE) <= 0) return "nonpositive-damage";

        ItemStack heldItem = player.inventory.getHeldItem();
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        String outcome = "target-not-simulated";

        if (entity != null && (!entity.isLivingEntity || entity.getType() == EntityTypes.PLAYER || entity.getType() == EntityTypes.PAINTING
                || entity.getType() == EntityTypes.ENDER_DRAGON && player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2))) {
            int knockbackEnchantment = player.getClientVersion().isOlderThan(ClientVersion.V_1_21) && heldItem != null
                    ? heldItem.getEnchantmentLevel(EnchantmentTypes.KNOCKBACK)
                    : 0;
            float knockback = clientAttackKnockback(player.getClientVersion(), knockbackEnchantment,
                    player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_KNOCKBACK));
            final boolean hasNegativeKB = knockback < 0;

            final boolean isLegacyPlayer = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
            // assume cooldown is full on 1.8 servers
            final boolean sufficientCooldownProgress = isLegacyPlayer || player.attackCooldown.getMinimumProgress() > 0.9F;

            if (!isLegacyPlayer) {
                knockback = Math.max(knockback, 0);
            }

            // 1.8 players who are packet sprinting WILL get slowed
            // 1.9+ players who are packet sprinting might not, based on attack cooldown
            // A positive client-side knockback bonus slows the attacker even
            // without sprinting or a fully charged attack.

            if (player.lastSprinting && !hasNegativeKB && sufficientCooldownProgress || knockback > 0) {
                player.minAttackSlow++;
                player.maxAttackSlow++;

                // Only sprint-based knockback is limited to one slowdown per tick.
                if (knockback == 0) {
                    player.maxAttackSlow = player.minAttackSlow = 1;
                }

                // A successful knockback attack calls setSprinting(false) on the client,
                // which also removes the sprinting speed modifier. The client can restart
                // sprinting before sending movement without sending another START_SPRINTING
                // packet. Preserve isSprinting as the last packet state so the movement
                // runner detects that restart against this post-attack state.
                player.lastSprinting = false;
                player.compensatedEntities.hasSprintingAttributeEnabled = false;
                outcome = "required-slow";
            } else if (!isLegacyPlayer && player.lastSprinting) {
                // 1.9+ players who have attack speed cannot slow themselves twice in one tick because their attack cooldown gets reset on swing.
                if (player.maxAttackSlow > 0
                        && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9)
                        && player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED) < 16) { // 16 is a reasonable limit
                    return "multiple-attack-cooldown-limit";
                }

                // 1.9+ player who might have been slowed, but we can't be sure
                player.maxAttackSlow++;
                outcome = "possible-slow";
            } else {
                outcome = "no-sprint-or-knockback-slow";
            }
        }

        if (player.gamemode != GameMode.SPECTATOR) {
            player.attackCooldown.reset();
        }
        return outcome;
    }

    static float clientAttackKnockback(ClientVersion version, int enchantmentLevel, double attributeValue) {
        if (version.isOlderThan(ClientVersion.V_1_21)) {
            return enchantmentLevel;
        }

        // From 1.21 enchantment effects run only on the server. The client reads
        // the compensated attribute instead, which stays zero unless received.
        float knockback = (float) attributeValue;
        // 1.21.11 moved the factor from Player.attack into LivingEntity.getKnockback.
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_11) ? knockback / 2.0F : knockback;
    }

    private boolean isInvalidEntity(PacketReceiveEvent event, GrimPlayer player, int entityId) {
        // The entity does not exist
        if (!player.compensatedEntities.entityMap.containsKey(entityId) && !player.compensatedEntities.serverPositionsMap.containsKey(entityId)
                // the list of entities used to raytrace isn't the same as the list of entities in the world in pre-1.14 (wtf mojang)
                && (!player.compensatedEntities.entitiesRemovedThisTick.contains(entityId) || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14))) {
            final InvalidInteractTarget check = player.checkManager.get(InvalidInteractTarget.class);
            if (check.flag("entityId=" + entityId) && check.shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            return true;
        }
        return false;
    }
}
