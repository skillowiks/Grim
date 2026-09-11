package ac.grim.grimac.checks.impl.aim.triggerbot;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/** Experimental notification-only check. Geometry is supplied by the shared observer. */
@CheckData(name = "TriggerBot", stableKey = "grim.aim.triggerbot", experimental = true, setback = -1,
        decay = 1, description = "Experimental consistency of target, cooldown and critical-hit opportunities")
public final class TriggerBot extends Check {
    private final TriggerBotDetector detector = new TriggerBotDetector();
    private final TriggerBotOpportunityDetector opportunities = new TriggerBotOpportunityDetector();
    private volatile boolean configuredEnabled;
    private volatile long alertIntervalNanos;
    private volatile long configGeneration;
    private long appliedGeneration = -1;
    private long lastAlertNanos, lastDecayNanos;
    private long candidateSignals, intervalSuppressedSignals, emittedFlags, suppressedFlags;

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
        return disabledReason() == null;
    }

    String disabledReason() {
        if (!configuredEnabled) return "disabled-in-config";
        if (!isEnabled()) return "missing-or-disabled-punishment-group";
        if (player.disableGrim) return "grim-disabled";
        if (isExemptPermission()) return "exempt-permission";
        if (!player.supportsEndTick()) return "unsupported-tick-end";
        return null;
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
        opportunities.reset();
    }

    private boolean prepare(long now) {
        long generation = configGeneration;
        if (appliedGeneration != generation) {
            resetDetector();
            appliedGeneration = generation;
        }
        if (!isDetectionEnabled()) {
            resetDetector();
            return false;
        }
        if (lastDecayNanos == 0 || now - lastDecayNanos >= TimeUnit.SECONDS.toNanos(30)) {
            reward();
            lastDecayNanos = now;
        }
        return true;
    }

    public void invalidate(long tick, long now) {
        if (prepare(now)) {
            detector.invalidate(tick, now);
            opportunities.invalidate(tick, now);
        }
    }

    public void invalidateGrounded(long tick, long now) {
        if (prepare(now)) detector.invalidate(tick, now);
    }

    public void invalidateOpportunities(long tick, long now) {
        if (prepare(now)) opportunities.invalidate(tick, now);
    }

    public void observe(TriggerBotDetector.Frame frame) {
        if (!prepare(frame.nowNanos())) return;
        TriggerBotDetector.Evidence evidence = detector.accept(frame);
        if (evidence == null) return;
        emitCandidate(frame.nowNanos(), "experimental grounded reacquisitions: samples=" + evidence.episodes()
                + " attacks=" + evidence.hits() + " fast(0-1t)=" + evidence.fastHits()
                + " sameTick=" + evidence.zeroDelayHits() + " span=" + evidence.elapsedTicks()
                + "t; review only");
    }

    public void observeOpportunity(TriggerBotOpportunityDetector.Frame frame) {
        if (!prepare(frame.nowNanos())) return;
        TriggerBotOpportunityDetector.Evidence evidence = opportunities.accept(frame);
        if (evidence == null) return;
        emitCandidate(frame.nowNanos(), "experimental opportunities: profile=" + evidence.profile().name()
                + " mode=" + evidence.profile().mode() + " family=" + evidence.family() + " samples=" + evidence.episodes()
                + " attacks=" + evidence.hits() + " prompt(0-2t)=" + evidence.promptHits()
                + " acquisition=" + evidence.acquisitionOpportunities() + " falling=" + evidence.fallingOpportunities()
                + " cooldown=" + evidence.cooldownOpportunities() + " sprintSequence=" + evidence.sprintCorroboratedHits()
                + " queued=" + evidence.queuedOpportunities() + " promptControls=" + evidence.promptControlHits()
                + " span=" + evidence.elapsedTicks() + "t; review only");
    }

    private void emitCandidate(long now, String verbose) {
        candidateSignals++;
        if (lastAlertNanos != 0 && now - lastAlertNanos < alertIntervalNanos) {
            intervalSuppressedSignals++;
            return;
        }
        // Consume fresh evidence even when an API subscriber suppresses the alert.
        lastAlertNanos = now;
        boolean accepted = flag(verbose);
        if (accepted) emittedFlags++;
        else suppressedFlags++;
    }

    /** Called on the receive thread only; formatting uses this immutable copy in the report worker. */
    Snapshot snapshot(long now) {
        String disabled = disabledReason();
        long remaining = lastAlertNanos == 0 ? 0 : Math.max(0, alertIntervalNanos - (now - lastAlertNanos));
        return new Snapshot(disabled == null ? "enabled" : disabled, configuredEnabled, isEnabled(),
                TimeUnit.NANOSECONDS.toMillis(remaining), candidateSignals, intervalSuppressedSignals,
                emittedFlags, suppressedFlags, detector.snapshot(), opportunities.snapshot());
    }

    record Snapshot(String state, boolean configuredEnabled, boolean punishmentGroupEnabled,
                    long alertCooldownMillis, long candidateSignals, long intervalSuppressedSignals,
                    long emittedFlags, long suppressedFlags, TriggerBotDetector.Snapshot progress,
                    TriggerBotOpportunityDetector.Snapshot opportunityProgress) {
        String format() {
            return "Detector status=" + state + ", configEnabled=" + configuredEnabled
                    + ", punishmentGroupEnabled=" + punishmentGroupEnabled
                    + ", alertCooldownMillis=" + alertCooldownMillis + "\n"
                    + "Grounded reacquisition window=" + progress.completedEpisodes() + "/32, hits=" + progress.hits()
                    + ", fast(0-1t)=" + progress.fastHits() + ", sameTick=" + progress.zeroDelayHits()
                    + ", censored=" + progress.censored() + ", gapBuckets=" + progress.gapBuckets()
                    + ", outsideStreak=" + progress.outsideStreak() + "/3, pending=" + progress.pending()
                    + ", supportWindow=" + progress.supportWindow() + ". A signal requires two fresh passing windows.\n"
                    + formatOpportunities()
                    + "Detector signals since join: candidates=" + candidateSignals
                    + ", intervalSuppressed=" + intervalSuppressedSignals + ", flagsAccepted=" + emittedFlags
                    + ", flagsSuppressed=" + suppressedFlags + ". Flags do not confirm notification delivery.\n";
        }

        private String formatOpportunities() {
            StringBuilder text = new StringBuilder("Opportunity model: trainingAttacks=")
                    .append(opportunityProgress.trainingAttacks()).append("/12, trained=")
                    .append(opportunityProgress.trained()).append("; training is not scored.\n");
            for (var profile : opportunityProgress.profiles()) {
                text.append("  ").append(profile.profile()).append(" window=").append(profile.completedEpisodes())
                        .append("/32 hits=").append(profile.hits()).append(" prompt=").append(profile.promptHits())
                        .append(" censored=").append(profile.censored()).append(" acquisition=").append(profile.acquisitionOpportunities())
                        .append(" falling=").append(profile.fallingOpportunities()).append(" cooldown=").append(profile.cooldownOpportunities())
                        .append(" sprintSequence=").append(profile.sprintCorroboratedHits())
                        .append(" queued=").append(profile.queuedOpportunities()).append(" controls=").append(profile.controlOpportunities())
                        .append(" promptControls=").append(profile.promptControlHits())
                        .append(" gapBuckets=").append(profile.gapBuckets()).append(" pending=").append(profile.pending())
                        .append(" supportWindow=").append(profile.supportWindow()).append('\n');
            }
            return text.toString();
        }
    }
}
