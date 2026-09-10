package ac.grim.grimac.checks.impl.aim.triggerbot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Bounded descriptive observations, not a classifier or a source of violations. */
public final class TriggerBotStatistics {
    public static final int MAX_EPISODES = 128;
    public static final int MAX_WEAPON_LENGTH = 64;
    /** Includes the combined-other group when there are too many weapon types. */
    public static final int MAX_WEAPON_GROUPS = 8;
    public static final int MAX_REPORT_EPISODES = 32;
    /** Storage validation bound; this is not a suspicious-reaction threshold. */
    public static final int MAX_DELAY_TICKS = 1200;
    public static final int OVERFLOW_BUCKET = 13;

    public enum Outcome {
        HIT, MISSED, UNKNOWN
    }

    /**
     * One acquisition episode. Non-hit outcomes use delayTicks=-1. The caller
     * decides eligibility and uncertainty; this class never infers either.
     * Target identity is an opaque integer, never a player name or UUID.
     */
    public record Episode(Outcome outcome, int targetId, long entryTick, int delayTicks,
                          boolean switchedTarget, String weapon, boolean airborne) {
        public Episode {
            Objects.requireNonNull(outcome, "outcome");
            if (entryTick < 0) throw new IllegalArgumentException("entryTick must be non-negative");
            if (outcome == Outcome.HIT) {
                if (delayTicks < 0 || delayTicks > MAX_DELAY_TICKS) {
                    throw new IllegalArgumentException("hit delayTicks must be between 0 and " + MAX_DELAY_TICKS);
                }
            } else if (delayTicks != -1) {
                throw new IllegalArgumentException("non-hit delayTicks must be -1");
            }
            weapon = boundedWeapon(weapon);
        }
    }

    /** Histogram buckets 0..12 are exact; bucket 13 means 13 or more ticks. */
    public record GroupStats(int sampled, int hits, int missed, int unknown,
                             List<Integer> hitDelayHistogram, double medianDelayTicks,
                             double madDelayTicks, int modalBucket, double modalShare) {
        public GroupStats {
            hitDelayHistogram = List.copyOf(hitDelayHistogram);
        }

        public int eligible() {
            return hits + missed;
        }

        /** UNKNOWN is excluded; NaN means no eligible episodes. */
        public double hitRate() {
            return eligible() == 0 ? Double.NaN : hits / (double) eligible();
        }

        /** Fraction of recorded episodes with a resolved outcome, not global observation coverage. */
        public double resolvedFraction() {
            return sampled == 0 ? Double.NaN : eligible() / (double) sampled;
        }

        /** Alias for resolvedFraction; this does not measure all observable game ticks. */
        public double coverage() {
            return resolvedFraction();
        }
    }

    /** combinedOther groups multiple weapons and must not be treated as one weapon context. */
    public record WeaponStats(String weapon, boolean combinedOther, int weaponCount, GroupStats stats) {
    }

    /** Acquisitions (same/no previous target) and switches are disjoint groups. */
    public record Snapshot(List<Episode> episodes, GroupStats all, GroupStats acquisitions,
                           GroupStats switches, GroupStats airborne, GroupStats grounded,
                           int distinctWeapons, List<WeaponStats> weapons) {
        public Snapshot {
            episodes = List.copyOf(episodes);
            weapons = List.copyOf(weapons);
        }
    }

    // Recording order, not tick arithmetic, defines the rolling window. This
    // also avoids assuming that concurrently completed episodes end in entry order.
    private final ArrayDeque<Episode> episodes = new ArrayDeque<>(MAX_EPISODES);

    public synchronized void record(Episode episode) {
        Objects.requireNonNull(episode, "episode");
        if (episodes.size() == MAX_EPISODES) episodes.removeFirst();
        episodes.addLast(episode);
    }

    public synchronized void reset() {
        episodes.clear();
    }

    /** Cheap packet-thread handoff: copy at most 128 immutable records, without aggregation or formatting. */
    public synchronized List<Episode> copyEpisodes() {
        return List.copyOf(episodes);
    }

    public Snapshot snapshot() {
        return summarizeSnapshot(copyEpisodes());
    }

    private static Snapshot summarizeSnapshot(List<Episode> copy) {
        Map<String, List<Episode>> byWeapon = new HashMap<>();
        for (Episode episode : copy) byWeapon.computeIfAbsent(episode.weapon(), ignored -> new ArrayList<>()).add(episode);
        return new Snapshot(copy, summarize(copy, episode -> true),
                summarize(copy, episode -> !episode.switchedTarget()),
                summarize(copy, Episode::switchedTarget),
                summarize(copy, Episode::airborne), summarize(copy, episode -> !episode.airborne()),
                byWeapon.size(), summarizeWeapons(byWeapon));
    }

    public String formatReport() {
        return formatReport(copyEpisodes());
    }

    /** Pure formatting of a bounded handoff; safe for a worker with no live recorder access. */
    public static String formatReport(List<Episode> episodes) {
        Objects.requireNonNull(episodes, "episodes");
        if (episodes.size() > MAX_EPISODES) throw new IllegalArgumentException("too many episodes");
        Snapshot snapshot = summarizeSnapshot(List.copyOf(episodes));
        StringBuilder report = new StringBuilder("TriggerBot observations: window=")
                .append(snapshot.all().sampled()).append('/').append(MAX_EPISODES)
                .append(", weapons=").append(snapshot.distinctWeapons()).append('\n');
        appendGroup(report, "all", snapshot.all());
        appendGroup(report, "acquisitions", snapshot.acquisitions());
        appendGroup(report, "switches", snapshot.switches());
        appendGroup(report, "airborneAtEntry", snapshot.airborne());
        appendGroup(report, "groundedAtEntry", snapshot.grounded());
        report.append("Per-weapon groups (maximum ").append(MAX_WEAPON_GROUPS).append("):\n");
        for (WeaponStats weapon : snapshot.weapons()) {
            String label = weapon.combinedOther() ? "weaponGroup=other (" + weapon.weaponCount() + " types)"
                    : "weapon=" + quoted(weapon.weapon());
            appendGroup(report, label, weapon.stats());
        }
        report.append("Histogram ticks 0..12,13+: ").append(snapshot.all().hitDelayHistogram()).append('\n')
                .append("HIT means an attack request, not confirmed damage. UNKNOWN is excluded from attackRequestRate; ")
                .append("median/MAD use HIT delays only. resolvedFraction=(HIT+MISSED)/recordedEpisodes, ")
                .append("not global observation coverage. ")
                .append("The 13+ modal bucket combines different delays.\n")
                .append("Delays are sampled ticks; cooldown/critical-hit readiness is not conditioned. ")
                .append("An episode without an attack request is not necessarily an ignored ready-to-attack opportunity.\n")
                .append("Small or correlated samples can appear regular. Compare like weapons and contexts. ")
                .append("The other-weapon group mixes contexts. These observations are not a cheating probability or verdict.\n")
                .append("Last recorded episodes (recording order, maximum ").append(MAX_REPORT_EPISODES).append("):\n");
        List<Episode> rows = snapshot.episodes();
        for (int i = Math.max(0, rows.size() - MAX_REPORT_EPISODES); i < rows.size(); i++) {
            Episode episode = rows.get(i);
            report.append("episode targetId=").append(episode.targetId()).append(" entryTick=").append(episode.entryTick())
                    .append(" outcome=").append(episode.outcome()).append(" delayTicks=").append(episode.delayTicks())
                    .append(" switchedTarget=").append(episode.switchedTarget()).append(" airborneAtEntry=").append(episode.airborne())
                    .append(" weapon=").append(quoted(episode.weapon())).append('\n');
        }
        return report.toString();
    }

    private static List<WeaponStats> summarizeWeapons(Map<String, List<Episode>> byWeapon) {
        List<Map.Entry<String, List<Episode>>> ranked = new ArrayList<>(byWeapon.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, List<Episode>>>comparingInt(entry -> entry.getValue().size())
                .reversed().thenComparing(Map.Entry::getKey));
        boolean overflow = ranked.size() > MAX_WEAPON_GROUPS;
        int namedCount = overflow ? MAX_WEAPON_GROUPS - 1 : ranked.size();
        List<WeaponStats> result = new ArrayList<>(Math.min(ranked.size(), MAX_WEAPON_GROUPS));
        for (int i = 0; i < namedCount; i++) {
            Map.Entry<String, List<Episode>> entry = ranked.get(i);
            result.add(new WeaponStats(entry.getKey(), false, 1, summarize(entry.getValue(), episode -> true)));
        }
        if (overflow) {
            List<Episode> combined = new ArrayList<>();
            for (int i = namedCount; i < ranked.size(); i++) combined.addAll(ranked.get(i).getValue());
            result.add(new WeaponStats("other", true, ranked.size() - namedCount, summarize(combined, episode -> true)));
        }
        return result;
    }

    private static GroupStats summarize(List<Episode> episodes, Predicate<Episode> include) {
        int sampled = 0;
        int missed = 0;
        int unknown = 0;
        int[] histogram = new int[OVERFLOW_BUCKET + 1];
        List<Double> delays = new ArrayList<>();
        for (Episode episode : episodes) {
            if (!include.test(episode)) continue;
            sampled++;
            switch (episode.outcome()) {
                case HIT -> {
                    histogram[Math.min(episode.delayTicks(), OVERFLOW_BUCKET)]++;
                    delays.add((double) episode.delayTicks());
                }
                case MISSED -> missed++;
                case UNKNOWN -> unknown++;
            }
        }
        double[] sorted = delays.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double median = median(sorted);
        double[] deviations = Arrays.stream(sorted).map(delay -> Math.abs(delay - median)).sorted().toArray();
        int modalBucket = -1;
        int modalCount = 0;
        for (int bucket = 0; bucket < histogram.length; bucket++) {
            // Ties deterministically select the earliest bucket; this is only a summary.
            if (histogram[bucket] > modalCount) {
                modalBucket = bucket;
                modalCount = histogram[bucket];
            }
        }
        return new GroupStats(sampled, sorted.length, missed, unknown,
                Arrays.stream(histogram).boxed().toList(), median, median(deviations), modalBucket,
                sorted.length == 0 ? Double.NaN : modalCount / (double) sorted.length);
    }

    private static double median(double[] sorted) {
        if (sorted.length == 0) return Double.NaN;
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }

    private static String boundedWeapon(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        StringBuilder bounded = new StringBuilder(Math.min(MAX_WEAPON_LENGTH, value.length()));
        for (int i = 0; i < value.length() && bounded.length() < MAX_WEAPON_LENGTH; i++) {
            char c = value.charAt(i);
            // Keep reports single-line and plain even if a caller supplies arbitrary text.
            bounded.append(c >= 32 && c <= 126 ? c : '?');
        }
        return bounded.toString();
    }

    private static void appendGroup(StringBuilder report, String label, GroupStats group) {
        report.append(label).append(": sampled=").append(group.sampled())
                .append(", hit=").append(group.hits()).append(", missed=").append(group.missed())
                .append(", unknown=").append(group.unknown()).append(", resolvedFraction=").append(percent(group.resolvedFraction()))
                .append(", attackRequestRate=").append(percent(group.hitRate()))
                .append(", medianTicks=").append(number(group.medianDelayTicks()))
                .append(", madTicks=").append(number(group.madDelayTicks()))
                .append(", modalBucket=").append(group.modalBucket() < 0 ? "n/a"
                        : group.modalBucket() == OVERFLOW_BUCKET ? "13+" : Integer.toString(group.modalBucket()))
                .append(", modalShare=").append(percent(group.modalShare())).append('\n');
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.2f", value);
    }
}
