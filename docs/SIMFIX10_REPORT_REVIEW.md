# Small knockback and hidden movement, 2026-09-08

Report: [winxizzz](https://paste.grim.ac/ZnfejNsSeY), recorded on
`2.3.74-simfix8-ab9692a+lite`, server 1.21.11, client 1.21.11 / Fabric,
96 ms ping and 20 TPS. The summary contains 26 Simulation, two AntiKB and
76 TimerLimit flags. The three saved movement windows contain 18 of the
26 Simulation flags. There are no NoSlow flags in this report; TimerLimit
is outside this review's scope.

The other supplied paste, `5QZL7BMyX6`, returned HTTP 404. No findings are
attributed to its unavailable contents.

## Confirmed correction

Trace 2 contains four Simulation flags after the following replacement
entity velocity:

```
reported motion: (-0.2152841359946286, 0.002990905206616601, 0.0)
player position before the hidden tick:
                (59.30000001192093, 71.00133597911214, 1897.300000011921)
first flagged movement, transaction 13130:
    predicted Y: -0.00040000000000001146
    actual Y:    -0.07840000152587834
    offset:       0.078000
```

The vanilla velocity threshold makes the motion's Y component zero because
its magnitude is below 0.003. This is distinct from the report's 0.0002
position-packet threshold. The cached block sample contains an end stone
brick block at `(58, 72, 1897)` against the player's west side. It blocks the
negative X component while the player's bounding box overlaps its height.
Z is already zero. The resulting movement is zero, so the tick which applies
the knockback can occur without a position packet. The report records
`previousPositionPackets=false/true` and `skippedTick=true` on the next
position movement.

Gravity after that hidden tick gives the four recorded vertical movements:

```
-0.07840000152587834
-0.15523200451660557
-0.23052736891295922
-0.30431682745754074
```

These follow `(velocityY - 0.08) * (double) 0.98F`. The next movement lands
on the recorded floor at Y=70 and ends the discrepancy. This reproduces
the complete four-flag chain, including the floor reset.

The early movement-skipping check previously examined raw knockback before
the velocity threshold. Its nonzero Y incorrectly ruled out the hidden
tick, even though the ordinary movement predictor applies that threshold
later. The correction applies the existing, version-dependent movement
threshold to cloned knockback candidates before this early check.

First-bread and required knockback retain their separate provenance; the
queued velocity records remain unchanged. Existing legacy, modern horizontal,
edge and vehicle threshold rules are shared with ordinary prediction.
Explosion deltas remain additive and are not independently thresholded by
this replacement-motion helper. No offset tolerance or general knockback
exemption is introduced.

## Sprint transitions still require an established cause

Traces 1 and 3 contain seven Simulation flags each. Both begin on the first
movement after `START_SPRINTING`, following a received movement-speed
attribute update without a sprint modifier. In both cases, the recorded
first movement uses the walking speed numerically:

| Trace | First flagged line | First-tick speed matching actual | Grim's sprint speed |
|---|---:|---:|---:|
| 1 | 696 | approximately 0.208 | approximately 0.2704 |
| 3 | 902 | approximately 0.1712 | approximately 0.22256 |

Using that speed only on the first tick, then the reported sprint speed
(`walking speed * 1.3`) on following ticks, reproduces all 14 movements.
The initial discrepancy propagates through ground friction of approximately
0.546; it is not a new independent error on every flagged tick.

This establishes the numerical pattern, not permission to use walking speed
whenever sprint starts. In the vanilla source, sprint state is updated before
travel. A general alternative with walking speed on the first sprint tick
therefore lacks source justification. These 14 flags are not claimed fixed
by simfix10, and no generic sprint allowance is added.

## Packet and teleport context

All 18 retained Simulation movements have `attackSlow=0/0` and
`attackSlowVector=false`. The eight retained movements with a knockback
candidate also have no pending attack slowdown. None demonstrates the
older-attack/newer-replacement-motion case corrected by simfix9. The eight
Simulation flags absent from the windows cannot be classified from the
summary alone, nor can both AntiKB flags be assigned a definite cause.

There are no `MOVEMENT_REJECTED` entries or `cancelled=true` entries. The
available global tail has 14 tracked teleports, IDs 29 through 42, each with
a matching acceptance. They have positive IDs, `plugin=true`, flags zero
and zero delta velocity. Their groups start after BreakItems freeze-snowball
effects, consistent with the previously reviewed plugin freeze behavior.
The report does not demonstrate a Grim setback loop. In Trace 3, approximately
4.26 seconds of correctly predicted movement, including a correctly taken
knockback, separate the last teleport acceptance from the first flag.

## Validation

The independent replay is saved locally at
`.gradle/debug-analysis/winxizzz_window_replay.py` and can be run with:

```
python .gradle/debug-analysis/winxizzz_window_replay.py
```

It verifies all 18 retained Simulation flags and the Trace 2 floor reset;
the maximum residual is approximately 1.18e-13. This is an analytical replay,
not evidence that all 18 flags are corrected by the runtime change.

Five focused regression tests cover the actual production candidate helper:
the reported wall/knockback case, strict version-dependent thresholds,
modern horizontal rules, edge and vehicle behavior, and independent
first-bread/required copies with preserved provenance.

`gradlew :common:test --offline --no-daemon --console=plain`
passed: 167 tests across 21 suites, zero failures, errors or skips, including
the five new `KnockbackMovementSkippingTest` tests. Live-server reproduction
with the corrected build has not been performed.
