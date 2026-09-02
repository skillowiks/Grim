package ac.grim.grimac.platform.bukkit.initables;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import ac.grim.grimac.platform.bukkit.events.DeepDebugEvent;
import ac.grim.grimac.platform.bukkit.manager.BukkitPluginAttributionProvider;
import org.bukkit.Bukkit;

public class DeepDebugBukkitInit implements StartableInitable {
    public void start() {
        // Plugin attribution (class -> owning plugin, stack traces, handler lists)
        GrimAPI.INSTANCE.getDeepDebugManager().setAttributionProvider(new BukkitPluginAttributionProvider());
        // Interference capture: velocity/teleport/potion/gamemode with source attribution
        Bukkit.getPluginManager().registerEvents(new DeepDebugEvent(), GrimACBukkitLoaderPlugin.LOADER);
    }
}
