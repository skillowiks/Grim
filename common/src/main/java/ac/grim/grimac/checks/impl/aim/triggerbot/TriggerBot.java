package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/** Experimental notification-only check. Geometry is supplied by the shared observer. */
@CheckData(name = "TriggerBot", stableKey = "grim.aim.triggerbot", experimental = true, setback = -1,
        decay = 1, description = "Experimental consistency of ready, grounded target reacquisitions")
public final class TriggerBot extends Check {
    private final TriggerBotDetector detector = new TriggerBotDetector();
    private volatile boolean configuredEnabled;
    private volatile long alertIntervalNanos;
    private volatile long configGeneration;
    private long appliedGeneration = -1;
    private long lastAlertNanos, lastDecayNanos;

    public TriggerBot(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        configuredEnabled = config.getBooleanElse("TriggerBot.enabled", true);
        long seconds = Math.max(30, Math.min(3600, config.getLongElse("TriggerBot.alert-interval-seconds", 60)));
        alertIntervalNanos = TimeUnit.SECONDS.toNanos(seconds);
        configGeneration++;
    }

    public boolean isDetectionEnabled() {
        return configuredEnabled && isEnabled() && !player.disableGrim && !isExemptPermission() && player.supportsEndTick();
    }

    @Override
    protected boolean isExperimentalEnabled() {
        // Independent opt-in; enabling this check must not enable unrelated experimental checks.
        return configuredEnabled;
    }

    @Override public boolean isAlertOnly() { return true; }
    @Override public boolean shouldModifyPackets() { return false; }
    @Override public boolean shouldSetback() { return false; }
    @Override public boolean executeViolationSetback() { return false; }

    public void resetDetector() {
        detector.reset();
    }

    private boolean prepare(long now) {
        long generation = configGeneration;
        if (appliedGeneration != generation) {
            detector.reset();
            appliedGeneration = generation;
        }
        if (!isDetectionEnabled()) {
            detector.reset();
            return false;
        }
        if (lastDecayNanos == 0 || now - lastDecayNanos >= TimeUnit.SECONDS.toNanos(30)) {
            reward();
            lastDecayNanos = now;
        }
        return true;
    }

    public void invalidate(long tick, long now) {
        if (prepare(now)) detector.invalidate(tick, now);
    }

    public void observe(TriggerBotDetector.Frame frame) {
        if (!prepare(frame.nowNanos())) return;
        TriggerBotDetector.Evidence evidence = detector.accept(frame);
        if (evidence == null || lastAlertNanos != 0 && frame.nowNanos() - lastAlertNanos < alertIntervalNanos) return;
        // Consume fresh evidence even when an API subscriber suppresses the alert.
        lastAlertNanos = frame.nowNanos();
        flag("experimental grounded reacquisitions: samples=" + evidence.episodes()
                + " attacks=" + evidence.hits() + " fast(0-1t)=" + evidence.fastHits()
                + " sameTick=" + evidence.zeroDelayHits() + " span=" + evidence.elapsedTicks()
                + "t; review only");
    }
}
