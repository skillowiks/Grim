package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result.INSIDE;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result.OUTSIDE;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotStatistics.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class TriggerBotEpisodesTest {
    private final List<TriggerBotStatistics.Episode> recorded = new ArrayList<>();
    private final TriggerBotEpisodes episodes = new TriggerBotEpisodes(recorded::add);
    private final Object target = new Object();

    @Test
    void firstSeenInsideAndContinuousAttacksAreNotAcquisitions() {
        for (int tick = 0; tick < 20; tick++) {
            observe(tick, INSIDE);
            episodes.attack(1, tick);
        }
        assertTrue(recorded.isEmpty());
        observe(20, OUTSIDE);
        observe(21, INSIDE);
        episodes.attack(1, 21);
        assertEquals(1, recorded.size());
        assertEquals(HIT, recorded.get(0).outcome());
        assertEquals(0, recorded.get(0).delayTicks());
    }

    @Test
    void independentTransitionsCountOnceAndSameTickZeroIsValid() {
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        episodes.attack(1, 1);
        episodes.attack(1, 1);
        observe(2, INSIDE);
        episodes.attack(1, 2);
        observe(3, OUTSIDE);
        observe(4, INSIDE);
        observe(5, INSIDE);
        episodes.attack(1, 5);
        assertEquals(List.of(0, 1), recorded.stream().map(TriggerBotStatistics.Episode::delayTicks).toList());
        assertEquals(List.of(1L, 4L), recorded.stream().map(TriggerBotStatistics.Episode::entryTick).toList());
    }

    @Test
    void leavingWithoutAttackRecordsMissButUnknownDoesNot() {
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        observe(2, OUTSIDE);
        observe(3, INSIDE);
        observe(4, TriggerBotGeometry.Result.UNKNOWN);
        observe(5, INSIDE);
        episodes.attack(1, 5);
        assertEquals(List.of(MISSED, UNKNOWN), outcomes());
        assertTrue(recorded.stream().allMatch(episode -> episode.delayTicks() == -1));
        TriggerBotStatistics statistics = new TriggerBotStatistics();
        recorded.forEach(statistics::record);
        assertEquals(1, statistics.snapshot().all().eligible());
        assertEquals(0, statistics.snapshot().all().hitRate());
        assertEquals(0.5, statistics.snapshot().all().coverage());
    }

    @Test
    void unknownCrossingDisarmsUntilAnotherDefiniteOutside() {
        observe(0, OUTSIDE);
        observe(1, TriggerBotGeometry.Result.UNKNOWN);
        observe(2, INSIDE);
        episodes.attack(1, 2);
        assertTrue(recorded.isEmpty());
        observe(3, OUTSIDE);
        observe(4, INSIDE);
        episodes.attack(1, 4);
        assertEquals(List.of(HIT), outcomes());
    }

    @Test
    void timeoutCountsOnlyOneMissForContinuousOpportunity() {
        observe(0, OUTSIDE);
        for (int tick = 1; tick <= 20; tick++) observe(tick, INSIDE);
        episodes.attack(1, 20);
        assertEquals(List.of(MISSED), outcomes());
        assertEquals(1, recorded.get(0).entryTick());
    }

    @Test
    void timeoutBoundaryIsTwelveTicksNotSuspicionThreshold() {
        observe(0, OUTSIDE);
        for (int tick = 1; tick <= 12; tick++) observe(tick, INSIDE);
        episodes.attack(1, 12);
        assertEquals(11, recorded.get(0).delayTicks());
        episodes.reset();
        observe(0, OUTSIDE);
        for (int tick = 1; tick <= 13; tick++) observe(tick, INSIDE);
        episodes.attack(1, 13);
        assertEquals(List.of(HIT, MISSED), outcomes());
    }

    @Test
    void unknownOnTimeoutBoundaryRemainsUnknown() {
        observe(0, OUTSIDE);
        for (int tick = 1; tick <= 12; tick++) observe(tick, INSIDE);
        observe(13, TriggerBotGeometry.Result.UNKNOWN);
        assertEquals(List.of(UNKNOWN), outcomes());
    }

    @Test
    void missedSamplesCannotInventContinuousTimeoutOrAcquisition() {
        observe(0, OUTSIDE);
        observe(2, INSIDE);
        episodes.attack(1, 2);
        assertTrue(recorded.isEmpty());
        observe(3, OUTSIDE);
        observe(4, INSIDE);
        observe(30, INSIDE);
        episodes.attack(1, 30);
        assertEquals(List.of(UNKNOWN), outcomes());
    }

    @Test
    void attackWithStaleGeometryInvalidatesPendingEpisode() {
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        episodes.attack(1, 2);
        observe(2, INSIDE);
        episodes.attack(1, 2);
        assertEquals(List.of(UNKNOWN), outcomes());
    }

    @Test
    void weaponChangeDiscardsPendingAndDoesNotCreateAcquisition() {
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        episodes.observe(1, target, 2, INSIDE, "minecraft:axe", false);
        episodes.attack(1, 2);
        assertEquals(List.of(UNKNOWN), outcomes());
        assertEquals("minecraft:sword", recorded.get(0).weapon());
        episodes.observe(1, target, 3, OUTSIDE, "minecraft:axe", false);
        episodes.observe(1, target, 4, INSIDE, "minecraft:sword", false);
        episodes.attack(1, 4);
        assertEquals(1, recorded.size());
    }

    @Test
    void contextDescribesEntryAndStringsAreBounded() {
        String longWeapon = "a".repeat(1000) + "\n";
        episodes.observe(1, target, 0, OUTSIDE, longWeapon, false);
        episodes.observe(1, target, 1, INSIDE, longWeapon, true);
        episodes.observe(1, target, 2, INSIDE, longWeapon, false);
        episodes.attack(1, 2);
        assertTrue(recorded.get(0).airborne());
        assertEquals(TriggerBotStatistics.MAX_WEAPON_LENGTH, recorded.get(0).weapon().length());
    }

    @Test
    void reusedEntityIdNeedsItsOwnWarmupAndCannotInheritPendingHit() {
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        Object replacement = new Object();
        episodes.observe(1, replacement, 2, INSIDE, "minecraft:sword", false);
        episodes.attack(1, 2);
        assertEquals(List.of(UNKNOWN), outcomes());
        episodes.observe(1, replacement, 3, OUTSIDE, "minecraft:sword", false);
        episodes.observe(1, replacement, 4, INSIDE, "minecraft:sword", false);
        episodes.attack(1, 4);
        assertEquals(List.of(UNKNOWN, HIT), outcomes());
    }

    @Test
    void trackedTargetsHaveSeparateEpisodesAndSwitchDescriptors() {
        Object other = new Object();
        observe(0, OUTSIDE);
        episodes.observe(2, other, 0, OUTSIDE, "minecraft:sword", false);
        observe(1, INSIDE);
        episodes.observe(2, other, 1, OUTSIDE, "minecraft:sword", false);
        episodes.attack(1, 1);
        observe(2, OUTSIDE);
        episodes.observe(2, other, 2, INSIDE, "minecraft:sword", false);
        episodes.attack(2, 2);
        assertEquals(List.of(1, 2), recorded.stream().map(TriggerBotStatistics.Episode::targetId).toList());
        assertFalse(recorded.get(0).switchedTarget());
        assertTrue(recorded.get(1).switchedTarget());
    }

    @Test
    void evictionAndExpiryDoNotTurnUnseenOpportunitiesIntoMisses() {
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        for (int id = 2; id <= 5; id++) episodes.observe(id, new Object(), 1, OUTSIDE, "sword", false);
        episodes.attack(1, 1);
        assertEquals(List.of(UNKNOWN), outcomes());
        episodes.reset();
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        episodes.observe(2, new Object(), 101, OUTSIDE, "sword", false);
        episodes.attack(1, 101);
        assertEquals(List.of(UNKNOWN, UNKNOWN), outcomes());
    }

    @Test
    void identicalSameTickSamplesAreIdempotentAndConflictsDisarm() {
        observe(0, OUTSIDE);
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        observe(1, INSIDE);
        episodes.attack(1, 1);
        assertEquals(List.of(HIT), outcomes());
        observe(2, OUTSIDE);
        observe(2, INSIDE);
        observe(3, INSIDE);
        episodes.attack(1, 3);
        assertEquals(1, recorded.size());
        observe(4, OUTSIDE);
        observe(5, INSIDE);
        observe(5, OUTSIDE);
        episodes.attack(1, 5);
        assertEquals(List.of(HIT, UNKNOWN), outcomes());
    }

    @Test
    void negativeAndRetrogradeTicksInvalidateRatherThanProduceDelays() {
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        episodes.attack(1, -1);
        assertEquals(List.of(UNKNOWN), outcomes());
        observe(2, OUTSIDE);
        observe(3, INSIDE);
        observe(2, OUTSIDE);
        episodes.attack(1, 3);
        assertEquals(List.of(UNKNOWN, UNKNOWN), outcomes());
    }

    @Test
    void invalidInputCannotArmAndLargeTickValuesDoNotOverflow() {
        episodes.observe(1, null, 0, OUTSIDE, "sword", false);
        episodes.observe(1, null, 1, INSIDE, "sword", false);
        episodes.attack(1, 1);
        assertTrue(recorded.isEmpty());
        episodes.reset();
        observe(Long.MAX_VALUE - 2, OUTSIDE);
        observe(Long.MAX_VALUE - 1, INSIDE);
        observe(Long.MAX_VALUE, INSIDE);
        episodes.attack(1, Long.MAX_VALUE);
        assertEquals(1, recorded.get(0).delayTicks());
    }

    @Test
    void invalidateExportsUnknownButResetStartsCleanWithoutExport() {
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        episodes.invalidate();
        episodes.invalidate();
        assertEquals(List.of(UNKNOWN), outcomes());
        observe(2, OUTSIDE);
        observe(3, INSIDE);
        episodes.reset();
        observe(0, INSIDE);
        episodes.attack(1, 0);
        assertEquals(1, recorded.size());
    }

    @Test
    void endpointGeometryAndAttackEvidenceKeepTurnAwayUnknown() {
        TriggerBotStatistics statistics = new TriggerBotStatistics();
        TriggerBotEpisodes recorder = new TriggerBotEpisodes(statistics::record);
        recorder.observe(1, target, 0, geometry(false), "sword", false);
        recorder.observe(1, target, 1, geometry(true), "sword", false);
        assertEquals(1, recorder.pendingEpisodes());
        recorder.observe(1, target, 2,
                TriggerBotEpisodes.withAttackEvidence(geometry(false), true, false), "sword", false);
        recorder.attack(1, 2);
        assertEquals(0, recorder.pendingEpisodes());
        assertEquals(1, statistics.snapshot().all().unknown());
        assertEquals(0, statistics.snapshot().all().missed());
        assertEquals(0, statistics.snapshot().all().eligible());
    }

    @Test
    void multipleTargetAttacksCannotUseSingleEndpointGeometry() {
        TriggerBotStatistics statistics = new TriggerBotStatistics();
        TriggerBotEpisodes recorder = new TriggerBotEpisodes(statistics::record);
        Object other = new Object();
        recorder.observe(1, target, 0, geometry(false), "sword", false);
        recorder.observe(2, other, 0, geometry(false), "sword", false);
        recorder.observe(1, target, 1, geometry(true), "sword", false);
        recorder.observe(2, other, 1, geometry(true), "sword", false);
        assertEquals(2, recorder.pendingEpisodes());
        recorder.observe(1, target, 2,
                TriggerBotEpisodes.withAttackEvidence(geometry(true), true, true), "sword", false);
        recorder.observe(2, other, 2,
                TriggerBotEpisodes.withAttackEvidence(geometry(false), true, true), "sword", false);
        recorder.attack(1, 2);
        recorder.attack(2, 2);
        assertEquals(0, recorder.pendingEpisodes());
        assertEquals(2, statistics.snapshot().all().unknown());
        assertEquals(0, statistics.snapshot().all().eligible());
    }

    @Test
    void repeatedSameIntervalAttacksWithDefiniteGeometryCountOneHit() {
        TriggerBotStatistics statistics = new TriggerBotStatistics();
        TriggerBotEpisodes recorder = new TriggerBotEpisodes(statistics::record);
        recorder.observe(1, target, 0,
                TriggerBotEpisodes.withAttackEvidence(geometry(false), false, false), "sword", false);
        recorder.observe(1, target, 1,
                TriggerBotEpisodes.withAttackEvidence(geometry(true), true, false), "sword", false);
        for (int attack = 0; attack < 5; attack++) recorder.attack(1, 1);
        assertEquals(0, recorder.pendingEpisodes());
        assertEquals(1, statistics.snapshot().all().sampled());
        assertEquals(1, statistics.snapshot().all().hits());
        assertEquals(0, statistics.snapshot().episodes().get(0).delayTicks());
    }

    @Test
    void endpointEvidenceNeverConvertsUnknownToEligibility() {
        assertEquals(INSIDE, TriggerBotEpisodes.withAttackEvidence(INSIDE, false, false));
        assertEquals(OUTSIDE, TriggerBotEpisodes.withAttackEvidence(OUTSIDE, false, false));
        assertEquals(TriggerBotGeometry.Result.UNKNOWN,
                TriggerBotEpisodes.withAttackEvidence(TriggerBotGeometry.Result.UNKNOWN, false, false));
        assertEquals(TriggerBotGeometry.Result.UNKNOWN, TriggerBotEpisodes.withAttackEvidence(null, true, false));
        assertEquals(TriggerBotGeometry.Result.UNKNOWN, TriggerBotEpisodes.withAttackEvidence(INSIDE, false, true));
    }

    @Test
    void unfinishedEpisodesRemainCensoredUntilResolvedOrReset() {
        assertEquals(0, episodes.pendingEpisodes());
        observe(0, OUTSIDE);
        observe(1, INSIDE);
        assertEquals(1, episodes.pendingEpisodes());
        assertTrue(recorded.isEmpty());
        assertEquals(1, episodes.pendingEpisodes()); // Reports are read-only.
        episodes.invalidate();
        assertEquals(0, episodes.pendingEpisodes());
        assertEquals(List.of(UNKNOWN), outcomes());
        observe(2, OUTSIDE);
        observe(3, INSIDE);
        assertEquals(1, episodes.pendingEpisodes());
        episodes.reset();
        assertEquals(0, episodes.pendingEpisodes());
        assertEquals(1, recorded.size());
    }

    private static TriggerBotGeometry.Result geometry(boolean aimingAtTarget) {
        TriggerBotGeometry.Box box = new TriggerBotGeometry.Box(0, 0, 2, 0.6, 1.8, 2.6);
        return TriggerBotGeometry.classify(box, box,
                List.of(new TriggerBotGeometry.Vec3(0.3, 1, 0)),
                List.of(aimingAtTarget ? new TriggerBotGeometry.Vec3(0, 0, 1)
                        : new TriggerBotGeometry.Vec3(1, 0, 0)), 3);
    }

    private void observe(long tick, TriggerBotGeometry.Result result) {
        episodes.observe(1, target, tick, result, "minecraft:sword", false);
    }

    private List<TriggerBotStatistics.Outcome> outcomes() {
        return recorded.stream().map(TriggerBotStatistics.Episode::outcome).toList();
    }
}
