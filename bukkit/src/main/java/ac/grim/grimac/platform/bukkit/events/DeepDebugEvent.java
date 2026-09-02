package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.deepdebug.DeepDebugManager;
import ac.grim.grimac.manager.deepdebug.InterferenceRecord;
import ac.grim.grimac.platform.bukkit.manager.BukkitPluginAttributionProvider;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.UUID;

/**
 * Deep-debug interference capture: records the server-side events that most
 * often provoke anticheat flags, with the initiating source attributed down
 * to the plugin class. Bukkit fires events synchronously, so a stack trace
 * taken in a MONITOR listener still contains the frame that caused the event.
 *
 * <p>Every handler checks the deep-debug volatile gate (and the per-target
 * session) BEFORE building the record — the stack capture + attribution walk
 * is far too expensive to run server-wide when nobody is debugging.</p>
 */
public class DeepDebugEvent implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onVelocity(PlayerVelocityEvent event) {
        // ignoreCancelled=false: a velocity cancelled by another plugin is even more
        // interesting than a live one — the server state desyncs from the client.
        DeepDebugManager manager = GrimAPI.INSTANCE.getDeepDebugManager();
        if (!manager.hasActiveSessions()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (manager.getSession(uuid) == null) return;

        manager.recordInterference(uuid, new InterferenceRecord(System.currentTimeMillis(),
                InterferenceRecord.Kind.VELOCITY,
                "velocity " + event.getVelocity(),
                BukkitPluginAttributionProvider.attributeCaller(Thread.currentThread().getStackTrace()),
                event.isCancelled()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        DeepDebugManager manager = GrimAPI.INSTANCE.getDeepDebugManager();
        if (!manager.hasActiveSessions()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (manager.getSession(uuid) == null) return;

        manager.recordInterference(uuid, new InterferenceRecord(System.currentTimeMillis(),
                InterferenceRecord.Kind.TELEPORT,
                "teleport " + event.getFrom().toVector().toBlockVector() + " -> " + event.getTo().toVector().toBlockVector()
                        + " cause " + event.getCause(),
                BukkitPluginAttributionProvider.attributeCaller(Thread.currentThread().getStackTrace()),
                event.isCancelled()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        DeepDebugManager manager = GrimAPI.INSTANCE.getDeepDebugManager();
        if (!manager.hasActiveSessions()) return;
        UUID uuid = player.getUniqueId();
        if (manager.getSession(uuid) == null) return;

        manager.recordInterference(uuid, new InterferenceRecord(System.currentTimeMillis(),
                InterferenceRecord.Kind.POTION_EFFECT,
                "potion " + (event.getNewEffect() == null ? event.getOldEffect() : event.getNewEffect()).getType()
                        + " " + event.getAction() + " cause " + event.getCause(),
                BukkitPluginAttributionProvider.attributeCaller(Thread.currentThread().getStackTrace()),
                event.isCancelled()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        DeepDebugManager manager = GrimAPI.INSTANCE.getDeepDebugManager();
        if (!manager.hasActiveSessions()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (manager.getSession(uuid) == null) return;

        manager.recordInterference(uuid, new InterferenceRecord(System.currentTimeMillis(),
                InterferenceRecord.Kind.GAME_MODE,
                "gamemode -> " + event.getNewGameMode(),
                BukkitPluginAttributionProvider.attributeCaller(Thread.currentThread().getStackTrace()),
                event.isCancelled()));
    }
}
