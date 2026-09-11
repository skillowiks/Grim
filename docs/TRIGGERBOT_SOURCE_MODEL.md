# TriggerBot: source model and supported evidence

This document maps the supplied `system-source — копия` implementation to the
experimental server-side opportunity detector. The supplied source targets
Minecraft 1.21.4. Its presence does not establish that a running 26.1 client uses
the same binary, settings, hooks, or module order. The analysis is static; the
client and its native libraries are not executed.

The detector observes whether attacks repeatedly follow independently sampled
target and readiness opportunities. It does not identify a client by name or
prove cheating from cooldown, jumping, sprint resets, or a single quick attack.
The existing grounded reacquisition model remains separate.

## Source behavior and coverage

| Source option or branch | Behavior in the supplied source | Server model and limits |
| --- | --- | --- |
| Smart mode | Rejects ascending movement outside exceptional environments. In the air it requires descending velocity below approximately `-0.01`. Its ordinary ground fallback requires jump not held and at least five eligible ground callbacks. | Model descending opportunities and the conservative stable-ground fallback. Jump input is permitted during descending opportunities. Observed ground intervals approximate callbacks; GUI and inventory interruptions remain exclusions. |
| OnlyCrit mode | Custom-cooldown branch requires descending or an exceptional environment. The default helper compares the Cyrillic `Только криты` against the setting's visually similar `Tолько кpиты`; they differ literally, so its ordinary fallback can allow ground attacks. | `ONLY_CRIT` is an explicit descending-only hypothesis, not a claim to reproduce the naming defect. The `SMART` hypothesis can explain supported ground behavior. Neither hypothesis identifies the configured mode with certainty. |
| Default cooldown, generic main hand or sword | Air threshold `0.75`; ground or exceptional-environment threshold `0.8`. | Fixed source profile. Generic hands and tools are supported when the adapter's other context requirements hold. |
| Default cooldown, main-hand axe or trident | Air threshold `0.85`; ground threshold `0.95`. | Separate source thresholds with the actual compensated attack speed. |
| Main-hand or offhand mace | Mace branch requires threshold `0.4` and descending or an exceptional environment. It bypasses the custom slider and the later mode branches. TPS adjustment still applies. | Fixed `0.4` floor, full-readiness profile, and prospectively trained empirical TPS floor bounded below by `0.4`. Offhand mace changes the modeled category while main-hand identity and attack speed remain part of the context. |
| Custom cooldown slider | Configurable `0..100%`, after the shared motion restrictions; Smart and OnlyCrit retain their distinct ground rules. | A separate training block supplies a frozen empirical attack floor. It is a timing hypothesis, not the recovered slider setting. A zero threshold does not create artificial cooldown transitions after every attack. |
| TPS synchronization | Multiplies a threshold by `20 / clientEstimatedTPS`, capped at `1`; the client estimates TPS from received world-time packets. | Source, full-readiness, and trained profiles cover a bounded set of hypotheses. Server TPS is not assumed to equal the client's estimate. Arbitrary changing TPS/settings are not reconstructed exactly. |
| Sprinting while descending and ready | Saves the target, locally clears sprint and forward input, then rechecks the target and readiness in later callbacks before direct attack. Restores physical forward state through `KeyBindings.update4`. | Corroborating branch requires prior sprint and forward input, an observed sprint/forward release near the independently sampled opportunity, and forward restoration. Grounded sprint attacks are not classified as this queue. No universal one-tick packet order is assumed. |
| “Do not sweep” | Optionally rejects the final descending tick when projected motion will collide below. | A caller-provided `landingSoon` guard excludes such uncertain opportunities. This does not infer whether the client enabled the option. |
| Weapon-only filter | Optional and disabled in the supplied defaults; checks both hands. Crossbow in either hand is rejected independently. | Do not exclude ordinary empty hands/tools merely because the source is a combat module. Crossbow is an explicit unsupported context. |
| Range, player/mob target selection, friends, armor, invisibility | Filters target selection; range is configurable. | Observe the accepted recent PvP target within bounded compensated geometry. Hidden friend/filter settings and mob targeting are not inferred. Unsupported range/components are excluded. |
| Through entities and through grass | Changes entity selection and plant-block handling during initial acquisition. Cached-target validation does not repeat the block raycast. | Retain conservative visibility and entity-selection checks. Do not turn ambiguous selection or a blocked ray into successful evidence to imitate these settings. |
| Water, lava, climbing, gliding, vehicles and related exceptions | `CritChecks.check()` can replace the normal critical-hit gate; surface-water jumping has an additional veto. | Excluded from this model. Implementing a supported normal-air branch does not justify guessing readiness in every exceptional environment. |
| Inventory and other modules | `TickCounter` can suppress decisions after inventory actions; shared animation events and interaction callbacks can modify or cancel behavior. | Censor disrupted intervals and reset incompatible context. A missed modeled opportunity is not itself a cheat signal. |

Sources: [TriggerBot.java](<../system-source — копия/src/main/java/client/module/combat/TriggerBot.java>),
[CritChecks.java](<../system-source — копия/src/main/java/client/util/CritChecks.java>),
[KeyBindings.java](<../system-source — копия/src/main/java/client/util/KeyBindings.java>),
[TpsTracker.java](<../system-source — копия/src/main/java/client/util/TpsTracker.java>),
[TickCounter.java](<../system-source — копия/src/main/java/client/util/TickCounter.java>).

## Tick order and observed time

The source makes its decision at the HEAD of the local player tick and can cache
a target and rotation during world rendering. Current-target readiness uses
cooldown partial tick `0`; the render-cache branch uses `-1`. A direct attack can
therefore precede that tick's movement packet. Synthetic attack input uses another
path through `Minecraft.startAttack`. Sprint is changed locally before any
corresponding packet is necessarily sent.

The observer captures pre-reset attack context and compares sampled client
intervals. Its cooldown estimate and motion state are not the client's exact
render-frame state. A response allowance of zero to two sampled intervals is an
uncertainty allowance, not a measured visual reaction time; ping is not subtracted.
Packet timing problems, corrections, unsupported movement, and uncertain geometry
do not increase suspicion.

Sources: [TargetAnimation.java](<../system-source — копия/src/main/java/client/util/TargetAnimation.java>),
[GameMenuHooks.java](<../system-source — копия/src/main/java/client/render/GameMenuHooks.java>),
and [the detailed source review](TRIGGERBOT_SOURCE_REVIEW.md).

## Frozen profiles and independent opportunities

Each stable target/weapon/attack-speed context first collects 12 valid accepted
attack samples. Their minimum observed cooldown, capped at full readiness, creates
one empirical profile. UNKNOWN geometry may contribute to this parameter-training
block because it does not become scored opportunity evidence. Definite OUTSIDE,
missing geometry, invalid pre-attack state, or invalid context cannot train it.

Training samples never enter evaluated windows. Profiles are frozen before
evaluation: at most six profiles for generic/sword/axe/trident contexts, or three
for mace. Matching profiles are deduplicated. No profile is fitted to the same
window being scored, and matching several profiles cannot reuse the same data for
several alerts.

An opportunity starts when observable target presence, motion readiness, and the
profile's cooldown gate first become available together. Its last independent
blocker is recorded:

- **Acquisition:** three preceding definite OUTSIDE samples were already motion-
  and cooldown-ready, then the same target becomes INSIDE.
- **Falling:** the target was already INSIDE and cooled before the descending
  phase became ready.
- **Cooldown:** the target was already INSIDE and motion-ready before a sampled
  below-threshold cooldown became ready.

Simultaneous ambiguous gate changes are censored rather than assigned whichever
kind best fits a desired result. Continuous ready aiming does not create another
opportunity every tick. An attack reset alone also does not invent a cooldown
edge: an actual below-threshold sample must precede the next crossing.

Valid definite-INSIDE attacks outside a profile's modeled availability discard
that profile's accumulated window and support. They cannot disappear while only
matching attacks are retained. This is conservative rejection of a timing
hypothesis, not a claim that the server knows exact client readiness. Other profiles
remain independent.

## Combined evidence required for a review alert

Every evaluated window contains 32 completed opportunities, including misses and
censored outcomes. A passing window requires no censored episodes, at least 30
attacks within zero to two sampled intervals, at least three acquisition/opportunity
gap buckets (`<=20`, `<=40`, `<=80`, `>80` ticks), and at least 400 elapsed ticks.
It must also satisfy one of the following families:

| Family | Additional requirements in each window |
| --- | --- |
| Mixed acquisition | At least eight acquisition opportunities and at least eight falling or cooldown opportunities. |
| Readiness plus control coupling | At least eight falling and eight cooldown opportunities; at least 24 queued opportunities with a prompt hit and confirmed forward release/restoration; at least four prompt non-sprinting control opportunities. |

Both families require two fresh, disjoint passing windows for the same frozen
profile and family. Failed windows remove prior support. Partial windows and idle
support expire after 120 seconds; support also has a five-minute maximum age.
Identity, weapon, attack-speed, disable/reload and corrected-context resets prevent
unrelated sequences from being pooled.

These numbers are conservative experimental policy constants, not measured human
limits. Regular 12-tick critical attacks, repeated jump phases, sprint-stop age,
or even regular sprint/forward sequences alone cannot pass. The second family
requires observable variation in what made the opportunity available and a
non-sprinting comparison branch.

## Verification and practical limits

The pure tests cover both positive families, separate frozen training, UNKNOWN
training versus evaluated geometry, custom and mace profiles, jumping while
descending, ascending/landing exclusions, and independent per-profile rejection.
Negative fixtures include periodic critical hits with resprinting, absent forward
evidence/restoration, absent non-sprinting controls, varied human delays, missed or
censored opportunities, repeated ready attacks, expiration and context resets.

These fixtures establish program behavior, not an empirical false-positive rate.
A real recording can still be outside the observable scope because moving-target
geometry is uncertain, the same gate dominates every cycle, forward input never
changes, or too few valid opportunities remain. New captures should compare
opportunity kinds, discarded contexts, and fresh-window counts for labeled manual
and automated play. They should not reinterpret excluded samples as successes or
claim that enabling a source profile guarantees detection of every setting.

The class performs bounded state updates over at most six profiles. It does not
query geometry, mutate player state, send packets, or execute punishments.
Immutable diagnostic snapshots are copied on the owning packet thread and can be
formatted elsewhere. Alert-only enforcement is handled separately by the check's
integration.
