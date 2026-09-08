package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
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
            flagWithSetback("offset=" + bestOffset + ", threshold=" + offsetToFlag);
        } else if (predictionComplete.isChecked() && usingItem && !exemptSlotChange && bestOffset <= offsetToFlag) {
            reward();
        }
    }

    public void handlePredictionAnalysis(double offset) {
        buffer.analyze(offset);
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
