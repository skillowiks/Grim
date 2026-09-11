package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result;
import static ac.grim.grimac.checks.impl.aim.triggerbot.TriggerBotGeometry.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class TriggerBotOpportunityDetectorTest {
    private static final int[] GAPS = {18, 32, 62, 90};
    private enum Kind { ACQUISITION, FALLING, COOLDOWN }
    private final Fixture f = new Fixture();

    @Test
    void trainingIsSeparateFrozenBoundedAndNotRetrospectivelyScored() {
        f.train(0.82);
        var frozen = f.detector.snapshot();
        assertEquals(12, frozen.trainingAttacks());
        assertTrue(frozen.trained());
        assertEquals(6, frozen.profiles().size());
        assertTrue(frozen.profiles().stream().allMatch(p -> p.completedEpisodes() == 0));
        assertTrue(frozen.profiles().stream().anyMatch(p -> p.profile().name().equals("trained-floor")
                && p.profile().airborneThreshold() == 0.82));
        for (int i = 0; i < 20; i++) f.send(INSIDE, 0.3, true);
        assertEquals(frozen.profiles().stream().map(TriggerBotOpportunityDetector.ProfileSnapshot::profile).toList(),
                f.detector.snapshot().profiles().stream().map(TriggerBotOpportunityDetector.ProfileSnapshot::profile).toList());
        assertTrue(f.evidence.isEmpty());
        assertEquals(0, frozen.profiles().get(0).completedEpisodes(), "earlier snapshots are immutable");
        assertThrows(UnsupportedOperationException.class, () -> frozen.profiles().clear());
    }

    @Test
    void unknownGeometryCanTrainButCannotScoreAndInvalidRequestsCannotTrain() {
        for (int i = 0; i < 12; i++) f.send(OUTSIDE, 1, true);
        assertEquals(0, f.detector.snapshot().trainingAttacks());
        f.attackValid = false;
        for (int i = 0; i < 12; i++) f.send(UNKNOWN, 1, true);
        assertEquals(0, f.detector.snapshot().trainingAttacks());
        f.attackValid = true;
        for (int i = 0; i < 12; i++) f.send(UNKNOWN, 0.82, true);
        assertTrue(f.detector.snapshot().trained());
        for (int i = 0; i < 64; i++) f.send(UNKNOWN, 0.82, true);
        assertTrue(f.detector.snapshot().profiles().stream().allMatch(p -> p.completedEpisodes() == 0));
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void mixedAirborneAcquisitionAndFallingNeedsTwoFreshWindows() {
        f.train(1);
        f.mixed(32);
        assertTrue(f.evidence.isEmpty());
        assertTrue(f.detector.snapshot().profiles().stream().anyMatch(TriggerBotOpportunityDetector.ProfileSnapshot::supportWindow));
        f.mixed(32);
        assertEquals(1, f.evidence.size());
        var evidence = f.evidence.get(0);
        assertEquals("mixed-acquisition", evidence.family());
        assertEquals(64, evidence.episodes());
        assertEquals(64, evidence.hits());
        assertEquals(32, evidence.acquisitionOpportunities());
        assertEquals(32, evidence.fallingOpportunities());
        assertTrue(evidence.elapsedTicks() >= 800);
        assertTrue(f.detector.snapshot().profiles().stream().noneMatch(TriggerBotOpportunityDetector.ProfileSnapshot::supportWindow));
        f.mixed(32);
        assertEquals(1, f.evidence.size(), "profiles and windows cannot reuse the same evidence");
    }

    @Test
    void airborneJumpInputIsAllowedButAscendingIsNeverReady() {
        f.train(1);
        f.descending = false;
        for (int i = 0; i < 200; i++) f.send(INSIDE, 1, true);
        assertTrue(f.detector.snapshot().profiles().stream().allMatch(p -> p.completedEpisodes() == 0));
        f.mixed(64); // Fixture keeps jumpInput=true while descending.
        assertEquals(1, f.evidence.size());
    }

    @Test
    void continuousAimCanQualifyOnlyWithVariedReadinessAndActualControlSequences() {
        f.train(1);
        for (int i = 0; i < 64; i++) {
            f.opportunity(i % 2 == 0 ? Kind.FALLING : Kind.COOLDOWN, GAPS[i % 4], 1, i % 4 != 3, true, false, false);
        }
        assertEquals(1, f.evidence.size());
        var e = f.evidence.get(0);
        assertEquals("readiness-control-coupling", e.family());
        assertEquals(0, e.acquisitionOpportunities());
        assertEquals(32, e.fallingOpportunities());
        assertEquals(32, e.cooldownOpportunities());
        assertEquals(48, e.sprintCorroboratedHits());
        assertEquals(16, e.promptControlHits());
    }

    @Test
    void fixedCritCadenceWithNormalResprintingCannotQualify() {
        f.train(1);
        for (int i = 0; i < 128; i++) f.opportunity(Kind.FALLING, 12, 1, true, true, false, false);
        assertTrue(f.evidence.isEmpty());
        // Varied gaps still do not supply a second independent readiness kind.
        for (int i = 0; i < 96; i++) f.opportunity(Kind.FALLING, GAPS[i % 4], 1, i % 4 != 3, true, false, false);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void variedReadinessAloneAndSprintStopsWithoutForwardRestorationCannotQualify() {
        f.train(1);
        for (int i = 0; i < 64; i++) f.opportunity(i % 2 == 0 ? Kind.FALLING : Kind.COOLDOWN,
                GAPS[i % 4], 1, false, false, false, false);
        assertTrue(f.evidence.isEmpty());
        for (int i = 0; i < 64; i++) f.opportunity(i % 2 == 0 ? Kind.FALLING : Kind.COOLDOWN,
                GAPS[i % 4], 1, i % 4 != 3, false, false, false);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void controlCouplingRequiresNonSprintingComparisonOpportunities() {
        f.train(1);
        for (int i = 0; i < 96; i++) f.opportunity(i % 2 == 0 ? Kind.FALLING : Kind.COOLDOWN,
                GAPS[i % 4], 1, true, true, false, false);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void sprintStopFlagsCannotReplaceObservedPriorForwardInput() {
        f.train(1);
        f.allowForwardEvidence = false;
        for (int i = 0; i < 64; i++) f.opportunity(i % 2 == 0 ? Kind.FALLING : Kind.COOLDOWN,
                GAPS[i % 4], 1, i % 4 != 3, true, false, false);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void groundedSprintingDoesNotInventTheSourcesAirborneQueue() {
        f.train(1);
        f.grounded = true;
        f.groundTicks = 20;
        f.jump = false;
        f.prepare(Kind.ACQUISITION, 30, true);
        f.descending = false;
        f.send(INSIDE, 1, true);
        assertTrue(f.detector.snapshot().profiles().stream().filter(p -> p.profile().mode() == TriggerBotOpportunityDetector.Mode.SMART)
                .allMatch(p -> p.hits() == 1 && p.queuedOpportunities() == 0 && !p.pending()));
    }

    @Test
    void genericHandsAndToolsUseTheSourceGenericThresholds() {
        for (String weapon : List.of("minecraft:air", "minecraft:diamond_pickaxe", "minecraft:stick")) {
            Fixture generic = new Fixture();
            generic.weapon = weapon;
            generic.train(1);
            assertTrue(generic.detector.snapshot().profiles().stream().filter(p -> p.profile().name().equals("source"))
                    .allMatch(p -> p.profile().airborneThreshold() == 0.75 && p.profile().groundedThreshold() == 0.8));
            generic.mixed(64);
            assertEquals(1, generic.evidence.size());
        }
    }

    @Test
    void separatelyTrainedCustomFloorIsValidatedProspectively() {
        f.train(0.6);
        f.prepare(Kind.COOLDOWN, 30, false);
        f.send(INSIDE, 0.59, false);
        assertTrue(f.detector.snapshot().profiles().stream().allMatch(p -> p.hits() == 0));
        f.send(INSIDE, 0.6, true);
        assertTrue(f.detector.snapshot().profiles().stream().filter(p -> p.profile().name().equals("trained-floor"))
                .allMatch(p -> p.hits() == 1 && p.cooldownOpportunities() == 1));
        assertTrue(f.detector.snapshot().profiles().stream().filter(p -> !p.profile().name().equals("trained-floor"))
                .allMatch(p -> p.hits() == 0));
    }

    @Test
    void simultaneousUnknownGateEdgesAreCensoredRatherThanAssignedTheBestKind() {
        f.train(1);
        f.descending = false;
        for (int i = 0; i < 4; i++) f.send(INSIDE, 0, false);
        f.descending = true;
        f.send(INSIDE, 1, true);
        assertTrue(f.detector.snapshot().profiles().stream().allMatch(p -> p.censored() == 1 && p.hits() == 0));
    }

    @Test
    void continuousReadySpamDoesNotInventRepeatedCooldownTransitions() {
        f.train(0);
        for (int i = 0; i < 500; i++) f.send(INSIDE, 1, true);
        assertTrue(f.evidence.isEmpty());
        assertTrue(f.detector.snapshot().profiles().stream().allMatch(p -> p.completedEpisodes() == 0));
    }

    @Test
    void offProfileAttacksDiscardSupportInsteadOfSelectingOnlyMatchingAttacks() {
        f.train(1);
        f.mixed(32);
        assertTrue(f.detector.snapshot().profiles().stream().anyMatch(TriggerBotOpportunityDetector.ProfileSnapshot::supportWindow));
        f.send(INSIDE, 0.7, true);
        assertTrue(f.detector.snapshot().profiles().stream().noneMatch(TriggerBotOpportunityDetector.ProfileSnapshot::supportWindow));
        f.mixed(32);
        assertTrue(f.evidence.isEmpty(), "contradicted support cannot combine with the next window");
        f.grounded = true;
        f.groundTicks = 20;
        f.descending = false;
        f.jump = false;
        f.send(INSIDE, 1, true);
        assertTrue(f.detector.snapshot().profiles().stream().filter(p -> p.profile().mode() == TriggerBotOpportunityDetector.Mode.ONLY_CRIT)
                .noneMatch(TriggerBotOpportunityDetector.ProfileSnapshot::supportWindow));
        assertTrue(f.detector.snapshot().profiles().stream().filter(p -> p.profile().mode() == TriggerBotOpportunityDetector.Mode.SMART)
                .anyMatch(TriggerBotOpportunityDetector.ProfileSnapshot::supportWindow), "other models remain independent");
    }

    @Test
    void humanDelaysMissesAndCensoredOpportunitiesRemainInDenominators() {
        f.train(1);
        for (int i = 0; i < 96; i++) f.opportunity(i % 2 == 0 ? Kind.ACQUISITION : Kind.FALLING,
                GAPS[i % 4], i % 5, false, false, false, false);
        assertTrue(f.evidence.isEmpty());
        f.detector.reset();
        f.train(1);
        for (int i = 0; i < 64; i++) f.opportunity(i % 2 == 0 ? Kind.ACQUISITION : Kind.FALLING,
                GAPS[i % 4], 0, false, false, i % 8 == 0, false);
        assertTrue(f.evidence.isEmpty());
        f.detector.reset();
        f.train(1);
        for (int i = 0; i < 64; i++) f.opportunity(i % 2 == 0 ? Kind.ACQUISITION : Kind.FALLING,
                GAPS[i % 4], 0, false, false, false, i % 32 == 31);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void unknownOrUnobservableDataNeverCompletesAHit() {
        f.train(1);
        f.prepare(Kind.FALLING, 30, false);
        f.descending = true;
        f.send(INSIDE, 1, false);
        f.context = false;
        f.send(INSIDE, 1, true);
        assertTrue(f.detector.snapshot().profiles().stream().allMatch(p -> p.censored() == 1 && p.hits() == 0));
        f.context = true;
        for (int i = 0; i < 100; i++) f.send(UNKNOWN, 1, true);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void maceKeepsFixedMinimumAndModelsProspectiveTpsDelay() {
        f.weapon = "minecraft:mace";
        f.train(0.9);
        var snapshot = f.detector.snapshot();
        assertEquals(3, snapshot.profiles().size());
        assertEquals(0.4, snapshot.profiles().get(0).profile().airborneThreshold());
        f.prepare(Kind.COOLDOWN, 30, false);
        f.send(INSIDE, 0.39, false);
        assertFalse(f.detector.snapshot().profiles().get(0).pending());
        f.send(INSIDE, 0.4, true);
        assertEquals(1, f.detector.snapshot().profiles().get(0).hits());
        f.weapon = "minecraft:diamond_sword+offhand_mace";
        f.train(0.9);
        assertEquals(3, f.detector.snapshot().profiles().size());
        assertEquals(0.4, f.detector.snapshot().profiles().get(0).profile().airborneThreshold());
        assertTrue(f.detector.snapshot().profiles().stream().anyMatch(p -> p.profile().airborneThreshold() == 1));
        assertTrue(f.detector.snapshot().profiles().stream().anyMatch(p -> p.profile().airborneThreshold() == 0.9));
    }

    @Test
    void landingSoonAndGroundedOnlyCritDoNotCreateReadiness() {
        f.train(1);
        f.grounded = true;
        f.groundTicks = 20;
        f.jump = false;
        f.descending = false;
        for (int i = 0; i < 50; i++) f.send(INSIDE, 1, true);
        assertTrue(f.detector.snapshot().profiles().stream().filter(p -> p.profile().mode() == TriggerBotOpportunityDetector.Mode.ONLY_CRIT)
                .allMatch(p -> p.completedEpisodes() == 0));
        f.grounded = false;
        f.descending = true;
        f.landing = true;
        for (int i = 0; i < 50; i++) f.send(INSIDE, 1, true);
        assertTrue(f.evidence.isEmpty());
    }

    @Test
    void identityWeaponAttributeAndClockResetsDiscardTrainingAndEvidence() {
        f.train(1);
        f.mixed(32);
        f.identity = new Object();
        f.send(OUTSIDE, 1, false);
        assertFalse(f.detector.snapshot().trained());
        f.train(1);
        f.weapon = "minecraft:diamond_axe";
        f.send(OUTSIDE, 1, false);
        assertEquals(0, f.detector.snapshot().trainingAttacks());
        f.train(1);
        f.speed = 1;
        f.send(OUTSIDE, 1, false);
        assertFalse(f.detector.snapshot().trained());
        f.train(1);
        assertNull(f.detector.accept(f.last));
        assertFalse(f.detector.snapshot().trained());
    }

    @Test
    void staleSupportAndPartialWindowsExpireAndSnapshotReadsDoNotAdvanceThem() {
        f.train(1);
        f.mixed(32);
        var supported = f.detector.snapshot();
        for (int i = 0; i < 100; i++) assertEquals(supported, f.detector.snapshot());
        f.now += 121_000_000_000L;
        f.mixed(32);
        assertTrue(f.evidence.isEmpty());
        f.detector.reset();
        f.train(1);
        f.mixed(31);
        f.now += 121_000_000_000L;
        f.mixed(33);
        assertTrue(f.evidence.isEmpty());
    }

    private static final class Fixture {
        final TriggerBotOpportunityDetector detector = new TriggerBotOpportunityDetector();
        final List<TriggerBotOpportunityDetector.Evidence> evidence = new ArrayList<>();
        Object identity = new Object();
        String weapon = "minecraft:diamond_sword";
        double speed = 1.6;
        long tick = -1, now, lastEntry = -1;
        boolean grounded, descending = true, jump = true, sprinting, release, forward, restore, landing, context = true;
        boolean attackValid = true;
        boolean allowForwardEvidence = true;
        int groundTicks;
        TriggerBotOpportunityDetector.Frame last;

        void train(double cooldown) {
            for (int i = 0; i < 12; i++) send(INSIDE, cooldown, true);
        }

        void mixed(int count) {
            for (int i = 0; i < count; i++) opportunity(i % 2 == 0 ? Kind.ACQUISITION : Kind.FALLING,
                    GAPS[i % 4], i % 3, false, false, false, false);
        }

        void prepare(Kind kind, int gap, boolean queued) {
            long entry = Math.max(tick + 5, lastEntry + gap);
            descending = kind != Kind.FALLING;
            sprinting = queued;
            forward = queued && allowForwardEvidence;
            while (tick + 1 < entry) send(kind == Kind.ACQUISITION ? OUTSIDE : INSIDE, kind == Kind.COOLDOWN ? 0 : 1, false);
            lastEntry = entry;
        }

        void opportunity(Kind kind, int gap, int delay, boolean queued, boolean restored, boolean missed, boolean unknown) {
            prepare(kind, gap, queued);
            descending = true;
            for (int elapsed = 0; elapsed <= Math.max(delay, missed ? 3 : 0); elapsed++) {
                if (queued && elapsed == 1) {
                    sprinting = false;
                    forward = false;
                    release = true;
                }
                if (unknown && elapsed == delay) {
                    send(INSIDE, 1, false);
                    send(UNKNOWN, 1, true);
                } else send(INSIDE, 1, !missed && elapsed == delay);
            }
            if (queued) {
                restore = restored;
                forward = restored;
                send(INSIDE, 0, false);
                send(INSIDE, 0, false);
            }
        }

        void send(Result geometry, double cooldown, boolean attack) {
            tick++;
            now += 50_000_000L;
            last = new TriggerBotOpportunityDetector.Frame(tick, now, identity, weapon, speed, geometry,
                    context, attack, attackValid, cooldown, grounded, descending, jump, groundTicks,
                    sprinting, release, landing, forward, restore);
            var result = detector.accept(last);
            if (result != null) evidence.add(result);
            release = restore = false;
        }
    }
}
