package ac.grim.grimac.platform.api.manager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Platform-side (Bukkit) attribution support for the deep-debug report:
 * resolves which plugin owns a given class, and lists plugins subscribed to
 * the movement-relevant server events. The common module only sees this
 * interface; the Bukkit module installs the real implementation.
 */
public interface PluginAttributionProvider {
    PluginAttributionProvider NOOP = new PluginAttributionProvider() {
        @Override
        public @Nullable String pluginForClass(@NotNull String className) {
            return null;
        }

        @Override
        public @NotNull List<String> movementEventHandlerSubscribers() {
            return List.of();
        }
    };

    /** @return the owning plugin name for the class, or null when the class is not plugin-loaded. */
    @Nullable String pluginForClass(@NotNull String className);

    /** @return human-readable lines describing event-handler subscribers for movement-relevant events. */
    @NotNull List<String> movementEventHandlerSubscribers();
}
