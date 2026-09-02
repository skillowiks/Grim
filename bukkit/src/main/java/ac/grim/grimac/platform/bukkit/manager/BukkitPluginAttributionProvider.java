package ac.grim.grimac.platform.bukkit.manager;

import ac.grim.grimac.platform.api.manager.PluginAttributionProvider;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.PluginClassLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bukkit implementation of {@link PluginAttributionProvider}: resolves the
 * owning plugin of a class via its {@link PluginClassLoader}, and dumps the
 * subscribers of the movement-relevant Bukkit events. Also hosts the
 * stack-trace caller attribution used by the deep-debug event capture —
 * Bukkit dispatches events synchronously, so the frame that fired the event
 * (the plugin that called {@code setVelocity} / teleport / effect APIs) is
 * still on the stack when our MONITOR listener runs.
 */
public final class BukkitPluginAttributionProvider implements PluginAttributionProvider {
    private static final Map<String, Optional<String>> CLASS_TO_PLUGIN = new ConcurrentHashMap<>();

    @Override
    public @Nullable String pluginForClass(@NotNull String className) {
        return pluginForClassStatic(className);
    }

    static @Nullable String pluginForClassStatic(String className) {
        return CLASS_TO_PLUGIN.computeIfAbsent(className, BukkitPluginAttributionProvider::resolvePluginForClass).orElse(null);
    }

    private static Optional<String> resolvePluginForClass(String className) {
        // Fast path: classic plugins share the global PluginClassLoaderGroup pool, so a
        // lookup through Grim's own classloader resolves already-loaded foreign classes.
        try {
            ClassLoader loader = Class.forName(className, false, BukkitPluginAttributionProvider.class.getClassLoader()).getClassLoader();
            while (loader != null) {
                if (loader instanceof PluginClassLoader pluginClassLoader) {
                    return Optional.ofNullable(pluginClassLoader.getPlugin().getName());
                }
                loader = loader.getParent();
            }
        } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
            // Fall through to the per-plugin loader pass.
        }
        // Slow path: paper-plugin.yml plugins live in separate loader groups the pool
        // does not consult — try every installed plugin's classloader directly.
        return resolveViaPluginLoaders(className);
    }

    private static Optional<String> resolveViaPluginLoaders(String className) {
        for (org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
            ClassLoader loader = plugin.getClass().getClassLoader();
            if (loader == null) continue;
            try {
                ClassLoader defining = loader.loadClass(className).getClassLoader();
                if (defining instanceof PluginClassLoader pluginClassLoader && pluginClassLoader.getPlugin() != null) {
                    return Optional.of(pluginClassLoader.getPlugin().getName());
                }
                if (defining == loader) {
                    // PaperPluginClassLoader and friends: the loader we asked is the defining one.
                    return Optional.of(plugin.getName());
                }
            } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
                // Try the next plugin's loader.
            }
        }
        return Optional.empty();
    }

    private record EventHandlers(String eventName, HandlerList handlers) {
    }

    @Override
    public @NotNull List<String> movementEventHandlerSubscribers() {
        List<EventHandlers> lists = List.of(
                new EventHandlers("PlayerVelocityEvent", PlayerVelocityEvent.getHandlerList()),
                new EventHandlers("PlayerTeleportEvent", PlayerTeleportEvent.getHandlerList()),
                new EventHandlers("EntityPotionEffectEvent", EntityPotionEffectEvent.getHandlerList()),
                new EventHandlers("PlayerGameModeChangeEvent", PlayerGameModeChangeEvent.getHandlerList()),
                new EventHandlers("PlayerToggleSprintEvent", PlayerToggleSprintEvent.getHandlerList()));

        List<String> out = new ArrayList<>();
        for (EventHandlers entry : lists) {
            for (RegisteredListener listener : entry.handlers().getRegisteredListeners()) {
                String listenerClass = listener.getListener().getClass().getName();
                if (listenerClass.startsWith("ac.grim.grimac.")) continue; // our own deep-debug capture
                out.add(entry.eventName() + " <- " + listener.getPlugin().getName()
                        + " (" + listener.getPriority() + (listener.isIgnoringCancelled() ? ", ignoresCancelled" : "") + "): "
                        + listenerClass);
            }
        }
        return out;
    }

    /**
     * Best-effort attribution of the frame that fired a synchronous Bukkit event:
     * the first stack frame belonging to a plugin (a mutating listener or the
     * original API caller), else the first server-side frame.
     */
    public static String attributeCaller(StackTraceElement[] stack) {
        String firstServer = null;
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.startsWith("ac.grim.grimac.")) continue; // our own listener
            String plugin = pluginForClassStatic(className);
            if (plugin != null) {
                return plugin + " (" + className + "." + element.getMethodName() + ":" + element.getLineNumber() + ")";
            }
            if (firstServer == null && (className.startsWith("net.minecraft.") || className.startsWith("org.bukkit.craftbukkit."))) {
                firstServer = className + "." + element.getMethodName() + ":" + element.getLineNumber();
            }
        }
        return firstServer != null ? "server (" + firstServer + ")" : "unknown";
    }
}
