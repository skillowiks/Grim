package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Locale;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotStatistics.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class TriggerBotStatisticsTest {
    @Test
    void variedLatenciesHaveDescriptiveDispersion() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        for (int delay : new int[]{0, 1, 2, 4, 5, 7}) stats.record(hit(delay));
        var all = stats.snapshot().all();
        assertEquals(6, all.hits());
        assertEquals(3, all.medianDelayTicks());
        assertEquals(2, all.madDelayTicks());
        assertEquals(0, all.modalBucket()); // deterministic tie
        assertEquals(1 / 6d, all.modalShare());
    }

    @Test
    void regularLatenciesAreDescribedWithoutAClassification() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        for (int i = 0; i < 20; i++) stats.record(hit(2));
        var all = stats.snapshot().all();
        assertEquals(2, all.medianDelayTicks());
        assertEquals(0, all.madDelayTicks());
        assertEquals(2, all.modalBucket());
        assertEquals(1, all.modalShare());
        assertTrue(stats.formatReport().contains("not a cheating probability or verdict"));
    }

    @Test
    void missesReduceHitRateAndUnknownsReduceResolvedFraction() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        stats.record(hit(1));
        stats.record(hit(3));
        stats.record(nonHit(MISSED));
        stats.record(nonHit(MISSED));
        stats.record(nonHit(UNKNOWN));
        var all = stats.snapshot().all();
        assertEquals(5, all.sampled());
        assertEquals(4, all.eligible());
        assertEquals(.5, all.hitRate());
        assertEquals(.8, all.resolvedFraction());
        assertEquals(2, all.medianDelayTicks());
        assertEquals(1, all.madDelayTicks());
        assertEquals(1, all.unknown());
    }

    @Test
    void medianAndMadUseTrueDelaysBeyondTheHistogramOverflowBucket() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        for (int delay : new int[]{13, 20, 40, 100}) stats.record(hit(delay));
        var all = stats.snapshot().all();
        assertEquals(4, all.hitDelayHistogram().get(13));
        assertEquals(13, all.modalBucket());
        assertEquals(1, all.modalShare());
        assertEquals(30, all.medianDelayTicks());
        assertEquals(13.5, all.madDelayTicks());
    }

    @Test
    void separatesTargetSwitchesAndAirborneContext() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        stats.record(new TriggerBotStatistics.Episode(HIT, 12, 4, 1, false, "minecraft:sword", false));
        stats.record(new TriggerBotStatistics.Episode(MISSED, 13, 5, -1, true, "minecraft:axe", true));
        stats.record(new TriggerBotStatistics.Episode(UNKNOWN, 14, 6, -1, true, "minecraft:axe", false));
        var snapshot = stats.snapshot();
        assertEquals(1, snapshot.acquisitions().sampled());
        assertEquals(2, snapshot.switches().sampled());
        assertEquals(1, snapshot.airborne().missed());
        assertEquals(2, snapshot.grounded().sampled());
        assertEquals(2, snapshot.distinctWeapons());
        assertEquals(12, snapshot.episodes().get(0).targetId());
    }

    @Test
    void evictsOldOutcomesInsteadOfKeepingLifetimeCounters() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        stats.record(hit(0));
        stats.record(hit(1));
        for (int i = 0; i < TriggerBotStatistics.MAX_EPISODES; i++) stats.record(nonHit(MISSED));
        var all = stats.snapshot().all();
        assertEquals(128, all.sampled());
        assertEquals(128, all.missed());
        assertEquals(0, all.hits());
        assertEquals(0, all.hitRate());
        assertTrue(Double.isNaN(all.medianDelayTicks()));
    }

    @Test
    void resetAndSubsequentWritesCannotMutateAnEarlierSnapshot() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        stats.record(hit(2));
        var snapshot = stats.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.episodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.all().hitDelayHistogram().set(2, 9));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.weapons().clear());
        stats.reset();
        assertEquals(0, stats.snapshot().all().sampled());
        stats.record(nonHit(MISSED));
        assertEquals(1, snapshot.all().hits());
        assertEquals(1, snapshot.weapons().get(0).stats().hits());
        assertEquals(HIT, snapshot.episodes().get(0).outcome());
        assertEquals(1, stats.snapshot().all().missed());
    }

    @Test
    void emptyAndUnknownOnlySamplesHaveNoInventedHitRateOrLatency() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        var empty = stats.snapshot().all();
        assertTrue(Double.isNaN(empty.resolvedFraction()));
        assertTrue(Double.isNaN(empty.hitRate()));
        assertEquals(-1, empty.modalBucket());
        stats.record(nonHit(UNKNOWN));
        var unknown = stats.snapshot().all();
        assertEquals(0, unknown.resolvedFraction());
        assertTrue(Double.isNaN(unknown.hitRate()));
        assertTrue(Double.isNaN(unknown.medianDelayTicks()));
        assertTrue(Double.isNaN(unknown.madDelayTicks()));
        assertTrue(Double.isNaN(unknown.modalShare()));
        assertTrue(stats.formatReport().contains("Small or correlated samples"));
    }

    @Test
    void boundsWeaponTextAndRejectsInvalidDelaysWithoutOverflow() {
        var bounded = new TriggerBotStatistics.Episode(HIT, Integer.MIN_VALUE, Long.MAX_VALUE, 1200,
                false, "minecraft:\n" + "x".repeat(1000), false);
        assertEquals(64, bounded.weapon().length());
        assertFalse(bounded.weapon().contains("\n"));
        assertEquals("unknown", new TriggerBotStatistics.Episode(UNKNOWN, -1, 0, -1, false, null, false).weapon());
        assertThrows(IllegalArgumentException.class, () -> hit(-1));
        assertThrows(IllegalArgumentException.class, () -> hit(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new TriggerBotStatistics.Episode(HIT, 0, -1, 0, false, "x", false));
        assertThrows(IllegalArgumentException.class, () -> new TriggerBotStatistics.Episode(MISSED, 0, 0, 0, false, "x", false));
        assertThrows(IllegalArgumentException.class, () -> new TriggerBotStatistics.Episode(UNKNOWN, 0, 0, 0, false, "x", false));
        assertThrows(NullPointerException.class, () -> new TriggerBotStatistics.Episode(null, 0, 0, -1, false, "x", false));
    }

    @Test
    void formattingIsDeterministicAcrossLocalesAndShowsOpaqueEpisodeFields() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        stats.record(new TriggerBotStatistics.Episode(HIT, 123456789, 1, 2, false, "minecraft:diamond_sword", false));
        stats.record(nonHit(MISSED));
        String expected = stats.formatReport();
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            assertEquals(expected, stats.formatReport());
        } finally {
            Locale.setDefault(original);
        }
        assertTrue(expected.contains("attackRequestRate=50.0%"));
        assertTrue(expected.contains("resolvedFraction=100.0%"));
        assertTrue(expected.contains("episode targetId=123456789 entryTick=1 outcome=HIT delayTicks=2 switchedTarget=false airborneAtEntry=false weapon=\"minecraft:diamond_sword\""));
        assertTrue(expected.contains("HIT means an attack request, not confirmed damage"));
        assertTrue(expected.contains("cooldown/critical-hit readiness is not conditioned"));
    }

    @Test
    void weaponGroupsKeepDifferentLatencyAndOutcomeContextsSeparate() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        stats.record(new TriggerBotStatistics.Episode(HIT, 1, 1, 1, false, "sword", false));
        stats.record(new TriggerBotStatistics.Episode(HIT, 1, 2, 3, false, "sword", false));
        stats.record(new TriggerBotStatistics.Episode(MISSED, 1, 3, -1, false, "sword", false));
        stats.record(new TriggerBotStatistics.Episode(HIT, 2, 4, 9, true, "axe", true));
        stats.record(new TriggerBotStatistics.Episode(UNKNOWN, 2, 5, -1, true, "axe", true));
        var groups = stats.snapshot().weapons();
        assertEquals(2, groups.size());
        assertEquals("sword", groups.get(0).weapon());
        assertEquals(2, groups.get(0).stats().medianDelayTicks());
        assertEquals(2 / 3d, groups.get(0).stats().hitRate());
        assertEquals(1, groups.get(0).stats().resolvedFraction());
        assertEquals("axe", groups.get(1).weapon());
        assertEquals(9, groups.get(1).stats().medianDelayTicks());
        assertEquals(.5, groups.get(1).stats().resolvedFraction());
        assertFalse(groups.get(0).combinedOther());
        assertFalse(groups.get(1).combinedOther());
    }

    @Test
    void boundsWeaponGroupsWithoutDroppingOutcomesOrMixingOtherWithANamedWeapon() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        for (int i = 0; i < 10; i++) {
            stats.record(new TriggerBotStatistics.Episode(i % 2 == 0 ? MISSED : UNKNOWN, 1, i, -1,
                    false, "weapon" + i, false));
        }
        // A literal weapon named other is still distinct from the aggregate group.
        stats.record(new TriggerBotStatistics.Episode(HIT, 1, 10, 0, false, "other", false));
        stats.record(new TriggerBotStatistics.Episode(HIT, 1, 11, 2, false, "other", false));
        var snapshot = stats.snapshot();
        var groups = snapshot.weapons();
        assertEquals(11, snapshot.distinctWeapons());
        assertEquals(8, groups.size());
        assertEquals("other", groups.get(0).weapon());
        assertFalse(groups.get(0).combinedOther());
        assertEquals("weapon0", groups.get(1).weapon()); // lexical order for equal sizes
        var combined = groups.get(7);
        assertTrue(combined.combinedOther());
        assertEquals(4, combined.weaponCount());
        assertEquals(4, combined.stats().sampled());
        assertEquals(snapshot.all().sampled(), groups.stream().mapToInt(group -> group.stats().sampled()).sum());
        assertEquals(snapshot.all().hits(), groups.stream().mapToInt(group -> group.stats().hits()).sum());
        assertEquals(snapshot.all().missed(), groups.stream().mapToInt(group -> group.stats().missed()).sum());
        assertEquals(snapshot.all().unknown(), groups.stream().mapToInt(group -> group.stats().unknown()).sum());
        assertTrue(stats.formatReport().contains("weaponGroup=other (4 types)"));
        assertTrue(stats.formatReport().contains("weapon=\"other\""));
    }

    @Test
    void weaponGroupsAreRecomputedWhenTheRollingWindowEvictsAContext() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        stats.record(new TriggerBotStatistics.Episode(HIT, 1, 0, 0, false, "old", false));
        for (int i = 0; i < TriggerBotStatistics.MAX_EPISODES; i++) stats.record(nonHit(MISSED));
        assertEquals(1, stats.snapshot().distinctWeapons());
        assertEquals(1, stats.snapshot().weapons().size());
        assertEquals("minecraft:diamond_sword", stats.snapshot().weapons().get(0).weapon());
    }

    @Test
    void rawRowsAreBoundedByRecordingOrderAndKeepWeaponPayloadOnOneLine() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        for (int i = 0; i < 40; i++) {
            // Entry times intentionally descend: rows preserve completion/recording order.
            stats.record(new TriggerBotStatistics.Episode(HIT, i, 100 - i, i, false, "sword", false));
        }
        String report = stats.formatReport();
        var rows = report.lines().filter(line -> line.startsWith("episode ")).toList();
        assertEquals(32, rows.size());
        assertTrue(rows.get(0).startsWith("episode targetId=8 entryTick=92 "));
        assertTrue(rows.get(31).startsWith("episode targetId=39 entryTick=61 "));
        assertEquals(40, stats.snapshot().episodes().size());
        stats.record(new TriggerBotStatistics.Episode(UNKNOWN, -1, 0, -1, true, "sword\"\\\nforged", true));
        var finalRows = stats.formatReport().lines().filter(line -> line.startsWith("episode ")).toList();
        assertEquals(32, finalRows.size());
        assertTrue(finalRows.get(31).contains("outcome=UNKNOWN delayTicks=-1 switchedTarget=true airborneAtEntry=true"));
        assertTrue(finalRows.get(31).endsWith("weapon=\"sword\\\"\\\\?forged\""));
        assertFalse(stats.formatReport().lines().anyMatch(line -> line.startsWith("forged")));
    }

    @Test
    void immutableEpisodeHandoffCanBeFormattedAfterTheLiveRecorderChanges() {
        TriggerBotStatistics stats = new TriggerBotStatistics();
        stats.record(hit(2));
        var handoff = stats.copyEpisodes();
        String expected = stats.formatReport();
        assertThrows(UnsupportedOperationException.class, handoff::clear);
        stats.reset();
        stats.record(nonHit(MISSED));
        assertEquals(expected, TriggerBotStatistics.formatReport(handoff));
        assertEquals(HIT, handoff.get(0).outcome());
        assertNotEquals(expected, stats.formatReport());
        assertThrows(IllegalArgumentException.class, () -> TriggerBotStatistics.formatReport(
                Collections.nCopies(TriggerBotStatistics.MAX_EPISODES + 1, hit(0))));
    }

    private static TriggerBotStatistics.Episode hit(int delay) {
        return new TriggerBotStatistics.Episode(HIT, 1, 0, delay, false, "minecraft:diamond_sword", false);
    }

    private static TriggerBotStatistics.Episode nonHit(TriggerBotStatistics.Outcome outcome) {
        return new TriggerBotStatistics.Episode(outcome, 1, 0, -1, false, "minecraft:diamond_sword", false);
    }
}
