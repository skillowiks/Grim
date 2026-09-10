package ac.grim.grimac.checks.impl.aim.triggerbot;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Bounded, descriptive pre-reset packet observations; never a check or classifier. */
public final class TriggerBotAttackSamples {
    public static final int MAX_SAMPLES = 64;
    public static final int MAX_REPORT_SAMPLES = 32;
    public static final int MAX_WEAPON_LENGTH = 64;

    /**
     * tick and ages use the observer's interval counter, not arrival milliseconds.
     * cooldownMin is Grim's estimate before an attack reset. verticalMovement is
     * the last observed actualMovementY, not the client's current vertical velocity.
     * Unknown ages use -1 and unknown numeric estimates use NaN.
     */
    public record Sample(long tick, int targetId, float cooldownMin, double attackSpeed,
                         double verticalMovement, boolean onGround, boolean sprinting, boolean usingItem,
                         long ticksSinceSprintStart, long ticksSinceSprintStop, long ticksSincePreviousAttack,
                         String weapon) {
        public Sample {
            if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
            if (!Float.isFinite(cooldownMin) || cooldownMin < 0 || cooldownMin > 1) cooldownMin = Float.NaN;
            if (!Double.isFinite(attackSpeed) || attackSpeed <= 0) attackSpeed = Double.NaN;
            if (!Double.isFinite(verticalMovement)) verticalMovement = Double.NaN;
            ticksSinceSprintStart = validAge(ticksSinceSprintStart, tick);
            ticksSinceSprintStop = validAge(ticksSinceSprintStop, tick);
            ticksSincePreviousAttack = validAge(ticksSincePreviousAttack, tick);
            weapon = boundedWeapon(weapon);
        }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>(MAX_SAMPLES);

    public synchronized void record(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        if (samples.size() == MAX_SAMPLES) samples.removeFirst();
        samples.addLast(sample);
    }

    /** Immutable stable snapshot for a report worker; recording order is preserved. */
    public synchronized List<Sample> copySamples() {
        return List.copyOf(samples);
    }

    /** Render only on a report worker, never on the packet-processing thread. */
    public static String formatReport(List<Sample> samples) {
        Objects.requireNonNull(samples, "samples");
        int first = Math.max(0, samples.size() - MAX_REPORT_SAMPLES);
        StringBuilder report = new StringBuilder("Pre-attack state snapshots: retained=")
                .append(samples.size()).append(", shown=").append(samples.size() - first).append('\n')
                .append("State is sampled before attack reset; it may precede the client's current state.\n")
                .append("cooldownMin is a Grim estimate, not exact client cooldown or readiness. ")
                .append("lastActualMovementY is the last observed movement, NOT client velocity.\n")
                .append("Ticks and ages use observer intervals, not visual reaction time; unknown ages are -1. ")
                .append("Sprint reset can also occur in manual play. ")
                .append("Later checks may cancel a request. Attack requests do not establish damage or a critical hit. ")
                .append("No cheating verdict is inferred.\n");
        for (int index = first; index < samples.size(); index++) {
            Sample sample = Objects.requireNonNull(samples.get(index), "sample");
            report.append("tick=").append(sample.tick())
                    .append(" target=").append(sample.targetId())
                    .append(" cooldownMin=").append(number(sample.cooldownMin()))
                    .append(" attackSpeed=").append(number(sample.attackSpeed()))
                    .append(" lastActualMovementY=").append(number(sample.verticalMovement()))
                    .append(" onGround=").append(sample.onGround())
                    .append(" sprinting=").append(sample.sprinting())
                    .append(" usingItem=").append(sample.usingItem())
                    .append(" sprintStartAgeTicks=").append(sample.ticksSinceSprintStart())
                    .append(" sprintStopAgeTicks=").append(sample.ticksSinceSprintStop())
                    .append(" attackGapTicks=").append(sample.ticksSincePreviousAttack())
                    .append(" weapon=").append(sample.weapon()).append('\n');
        }
        return report.toString();
    }

    private static long validAge(long age, long tick) {
        return age < 0 || age > tick ? -1 : age;
    }

    private static String boundedWeapon(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        StringBuilder bounded = new StringBuilder(Math.min(MAX_WEAPON_LENGTH, value.length()));
        for (int index = 0; index < value.length() && bounded.length() < MAX_WEAPON_LENGTH; index++) {
            char character = value.charAt(index);
            bounded.append(character >= 32 && character <= 126 ? character : '?');
        }
        return bounded.toString();
    }

    private static String number(float value) {
        return Float.isFinite(value) ? Float.toString(value) : "unknown";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "unknown";
    }
}
