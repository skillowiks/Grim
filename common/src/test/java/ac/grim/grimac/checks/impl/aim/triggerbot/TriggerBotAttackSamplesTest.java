package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TriggerBotAttackSamplesTest {
    @Test
    void ringRetainsOnlyNewestSixtyFourInRecordingOrder() {
        TriggerBotAttackSamples recorder = new TriggerBotAttackSamples();
        for (int tick = 0; tick < 100; tick++) recorder.record(sample(tick, "minecraft:diamond_sword"));
        List<TriggerBotAttackSamples.Sample> copy = recorder.copySamples();
        assertEquals(64, copy.size());
        assertEquals(36, copy.get(0).tick());
        assertEquals(99, copy.get(63).tick());
        for (int index = 0; index < copy.size(); index++) assertEquals(index + 36, copy.get(index).tick());
    }

    @Test
    void publishedSnapshotsAreImmutableAndUnaffectedByLaterAttacks() {
        TriggerBotAttackSamples recorder = new TriggerBotAttackSamples();
        recorder.record(sample(1, "first"));
        List<TriggerBotAttackSamples.Sample> first = recorder.copySamples();
        assertThrows(UnsupportedOperationException.class, () -> first.add(sample(2, "second")));
        assertThrows(UnsupportedOperationException.class, first::clear);
        recorder.record(sample(2, "second"));
        assertEquals(1, first.size());
        assertEquals("first", first.get(0).weapon());
        assertEquals(2, recorder.copySamples().size());
    }

    @Test
    void weaponNamesCannotInjectExtraReportLinesOrGrowWithoutBound() {
        TriggerBotAttackSamples.Sample sanitized = sample(1, "sword\nforged=true\r\t\u001b\u00A7\uD83D\uDE00");
        assertEquals("sword?forged=true??????", sanitized.weapon());
        assertTrue(sanitized.weapon().chars().allMatch(character -> character >= 32 && character <= 126));
        assertEquals(64, sample(2, "x".repeat(10000)).weapon().length());
        assertEquals("unknown", sample(3, null).weapon());
        assertEquals("unknown", sample(4, "").weapon());
        assertEquals(1, TriggerBotAttackSamples.formatReport(List.of(sanitized)).lines()
                .filter(line -> line.startsWith("tick=")).count());
    }

    @Test
    void unavailableOrInvalidEstimatesRemainUnknownInsteadOfBecomingReady() {
        TriggerBotAttackSamples.Sample invalid = new TriggerBotAttackSamples.Sample(3, 17,
                Float.POSITIVE_INFINITY, -1, Double.NaN, false, false, false,
                -20, 4, Long.MAX_VALUE, "sword");
        assertTrue(Float.isNaN(invalid.cooldownMin()));
        assertTrue(Double.isNaN(invalid.attackSpeed()));
        assertTrue(Double.isNaN(invalid.verticalMovement()));
        assertEquals(-1, invalid.ticksSinceSprintStart());
        assertEquals(-1, invalid.ticksSinceSprintStop());
        assertEquals(-1, invalid.ticksSincePreviousAttack());
        String report = TriggerBotAttackSamples.formatReport(List.of(invalid));
        assertTrue(report.contains("cooldownMin=unknown attackSpeed=unknown lastActualMovementY=unknown"));
        assertFalse(report.contains("Infinity"));
        assertFalse(report.contains("NaN"));
    }

    @Test
    void cooldownAndAgeBoundariesKeepValidZeroOneAndFirstAttackUnknown() {
        TriggerBotAttackSamples.Sample zero = new TriggerBotAttackSamples.Sample(0, -1,
                0, 1.6, -0.075, false, true, false, 0, -1, -1, "sword");
        TriggerBotAttackSamples.Sample full = new TriggerBotAttackSamples.Sample(Long.MAX_VALUE, 17,
                1, 0.6, 0, true, false, true, Long.MAX_VALUE, 0, 11, "mace");
        assertEquals(0, zero.cooldownMin());
        assertEquals(0, zero.ticksSinceSprintStart());
        assertEquals(-1, zero.ticksSincePreviousAttack());
        assertEquals(1, full.cooldownMin());
        assertEquals(Long.MAX_VALUE, full.ticksSinceSprintStart());
        assertEquals(11, full.ticksSincePreviousAttack());
        assertTrue(Float.isNaN(new TriggerBotAttackSamples.Sample(1, 1,
                1.01f, 1.6, 0, false, false, false, -1, -1, -1, "sword").cooldownMin()));
        assertThrows(IllegalArgumentException.class, () -> sample(-1, "sword"));
    }

    @Test
    void reportShowsOnlyLastThirtyTwoPreservingRawSampleValues() {
        TriggerBotAttackSamples recorder = new TriggerBotAttackSamples();
        for (int tick = 0; tick < 80; tick++) recorder.record(sample(tick, "sword"));
        List<TriggerBotAttackSamples.Sample> copy = recorder.copySamples();
        String report = TriggerBotAttackSamples.formatReport(copy);
        List<String> rows = report.lines().filter(line -> line.startsWith("tick=")).toList();
        assertEquals(32, rows.size());
        assertTrue(rows.get(0).startsWith("tick=48 "));
        assertTrue(rows.get(31).startsWith("tick=79 "));
        assertTrue(rows.get(0).contains("cooldownMin=0.8 attackSpeed=1.6 lastActualMovementY=-0.07544406518948656"));
        assertEquals(64, copy.size());
        assertEquals(copy, recorder.copySamples());
    }

    @Test
    void repeatedHumanLikeOrRegularInputRemainsDescriptiveWithoutClassification() {
        List<TriggerBotAttackSamples.Sample> regular = new ArrayList<>();
        List<TriggerBotAttackSamples.Sample> varied = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            regular.add(sample(index * 10L, "sword"));
            varied.add(new TriggerBotAttackSamples.Sample(index * 13L, 22 + index % 3,
                    index % 2 == 0 ? 0.8f : 1, 1.6, -0.02 * index, index % 3 == 0,
                    index % 2 == 0, false, -1, -1, index == 0 ? -1 : 13, "sword"));
        }
        for (List<TriggerBotAttackSamples.Sample> samples : List.of(regular, varied)) {
            String report = TriggerBotAttackSamples.formatReport(samples);
            assertTrue(report.contains("not exact client cooldown or readiness"));
            assertTrue(report.contains("NOT client velocity"));
            assertTrue(report.contains("Sprint reset can also occur in manual play"));
            assertTrue(report.contains("Attack requests do not establish damage or a critical hit"));
            assertTrue(report.contains("No cheating verdict is inferred"));
            assertEquals(samples.size(), report.lines().filter(line -> line.startsWith("tick=")).count());
            String lower = report.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("score="));
            assertFalse(lower.contains("probability="));
            assertFalse(lower.contains("critical=true"));
            assertFalse(lower.contains("cheating=true"));
        }
    }

    @Test
    void emptyWindowHasExplicitContextWithoutInventedSamples() {
        TriggerBotAttackSamples recorder = new TriggerBotAttackSamples();
        String report = TriggerBotAttackSamples.formatReport(recorder.copySamples());
        assertTrue(report.startsWith("Pre-attack state snapshots: retained=0, shown=0"));
        assertEquals(0, report.lines().filter(line -> line.startsWith("tick=")).count());
        assertThrows(NullPointerException.class, () -> recorder.record(null));
    }

    private static TriggerBotAttackSamples.Sample sample(long tick, String weapon) {
        return new TriggerBotAttackSamples.Sample(tick, 17, 0.8f, 1.6, -0.07544406518948656,
                false, false, false, -1, -1, -1, weapon);
    }
}
