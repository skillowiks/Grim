package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.manager.deepdebug.DeepDebugManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "NoSlow", stableKey = "grim.movement.noslow", description = "Was not slowed while using an item", setback = 5)
public class NoSlow extends Check implements PostPredictionListener {
    // The player sends that they switched items the next tick if they switch from an item that can be used
    // to another item that can be used.  What the fuck Mojang.  Affects 1.8 (and most likely 1.7) clients.
    public boolean didSlotChangeLastTick = false;
    private double offsetToFlag;
    private final NoSlowBuffer buffer = new NoSlowBuffer();
    private final NoSlowUseResync useResync = new NoSlowUseResync();
    private volatile boolean useResyncUnavailable;

    public NoSlow(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        boolean usingItem = player.packetStateData.isSlowedByUsingItem();
        // 1.8 users are not slowed the first tick after changing usable items.
        boolean exemptSlotChange = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                && didSlotChangeLastTick;
        if (predictionComplete.isChecked() && usingItem) {
            didSlotChangeLastTick = false;
        }
        double bestOffset = buffer.bestOffset();
        if (buffer.complete(predictionComplete.isChecked(), usingItem, exemptSlotChange, offsetToFlag)) {
            if (flag("offset=" + bestOffset + ", threshold=" + offsetToFlag) && setbackIfAboveSetbackVL()) {
                resyncItemUse();
            }
        } else if (predictionComplete.isChecked() && usingItem && !exemptSlotChange && bestOffset <= offsetToFlag) {
            reward();
        }
    }

    public void handlePredictionAnalysis(double offset) {
        buffer.analyze(offset);
    }

    private void resyncItemUse() {
        // Modern living flags are index 8 on both sides of the supported recovery path.
        // Read the server's current state on its entity thread, never from prediction.
        // Re-sending active=true preserves an already active client's consumption timer;
        // active=false also heals stale prediction when the server was already inactive.
        if (useResyncUnavailable || !shouldModifyPackets() || player.platformPlayer == null
                || player.getClientVersion().isOlderThan(ClientVersion.V_1_17)) return;
        try {
            useResync.request(System.nanoTime(), (run, retired) -> GrimAPI.INSTANCE.getScheduler()
                    .getEntityScheduler().run(player.platformPlayer, GrimAPI.INSTANCE.getGrimPlugin(), run, retired) != null,
                    () -> {
                        if (!shouldModifyPackets() || player.platformPlayer == null) return;
                        try {
                            boolean sent = GrimAPI.INSTANCE.getItemResetHandler().resyncItemUseFlags(player.platformPlayer);
                            useResyncUnavailable = !sent;
                            DeepDebugManager.get().recordPredictionEvent(player,
                                    () -> "NOSLOW_USE_RESYNC authoritative requested=" + sent);
                        } catch (RuntimeException e) {
                            disableUseResync(e);
                        }
                    });
        } catch (RuntimeException e) {
            disableUseResync(e);
        }
    }

    private void disableUseResync(RuntimeException e) {
        useResyncUnavailable = true;
        LogUtil.warn("Could not resynchronize item use for " + player.getName(), e);
    }

    public void reset() {
        buffer.reset();
        didSlotChangeLastTick = false;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        offsetToFlag = config.getDoubleElse(getConfigName() + ".threshold", 0.001);
    }
}
