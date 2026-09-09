# Attribute delivery and ground state after resync, 2026-09-09

Report: [tutufrutu](https://paste.grim.ac/qC5JzszXXZ), recorded on
`2.3.74-simfix11-a098727+lite`, client/server 1.21.11, Fabric, 87 ms ping
and 20 TPS. The summary contains 29 Simulation flags and one NoSlow flag.
The three retained movement windows contain 14 Simulation flags and that
NoSlow flag. The other 15 Simulation flags cannot be classified from this
report's summary alone.

## Attribute delivery boundary

Trace 1 begins with seven consecutive Simulation flags, at extracted text
lines 696–702. The first movement acknowledges transaction 15645, the ping
sent **before** a movement-speed attribute update. Grim applies the new
attribute on this acknowledgement; that acknowledgement does not establish
that the subsequent attribute packet has already been processed by the client.

The update removes Slowness III's modifier while retaining Speed III and
the sphere's additive-base modifier. The resulting walking speed changes
from `0.09415999799966812` to `0.1712000072002411` after float conversion.
The first movement still matches the former speed. Subsequent movements
match the latter speed, with the first error carried through ground friction
of approximately `0.546`. The seven offsets are approximately:

```
0.058722 0.032062 0.017506 0.009558 0.005219 0.002849 0.001556
```

Vanilla's `ClientPacketListener.handleUpdateAttributes` applies the attribute
directly; this is not a general one-tick cache in `Player.getSpeed`.
The relevant distinction is the order of packet processing around the ping.
Allowing the previous attribute must therefore end at a trailing packet's
acknowledgement, rather than after an arbitrary number of milliseconds or ticks.

The correction snapshots the previous movement-speed value when the leading
ping is acknowledged and records the actual sequence number of a ping sent
after the attribute packet. Normal ground prediction evaluates both speeds
only inside that interval. Both candidates use the existing input, collision
and item-slowdown processing, and the chosen candidate supplies next-tick
inertia. Failed trailing sends do not enable the alternative; callback storage
is bounded and handles reused ping IDs. Vehicle, airborne, flight and gliding
prediction are excluded. A change to the sprint modifier or a local sprint/
attack transition invalidates the old-speed alternative; mixed sprint-order
cases are not claimed fixed by this narrow correction.

## Ground state during a corrective teleport sequence

The initial plugin teleport in each later window preserves the client's
internal ground state. Its acknowledgement advertises `onGround=false` but
does not itself change the client's internal `onGround`. Grim already handles
that initial distinction.

The later divergence follows movements rejected while a corrective resync
is pending. Such a movement consumes a client tick. The client can land in
that tick, even though Grim does not run prediction for its rejected position.
A subsequent teleport preserves that newer client ground state while Grim
can retain the older airborne state.

The numerical evidence is particularly distinct with the sphere's +7% speed:

| Window and line | Actual acceleration | Selected prediction |
|---|---|---|
| Trace 2, 799 | Ground: `0.107 × 0.98 ≈ 0.10486` | Air: `0.02 × 0.98 ≈ 0.0196` |
| Trace 3, 901–902 | Ground, using item: `0.107 × 0.196 ≈ 0.020972` | Air, flipped item state: `0.02 × 0.98 ≈ 0.0196` |

Using ground acceleration and carrying the corrected velocity through ground
friction reproduces the two retained Trace 2 Simulation movements and the
five retained Trace 3 flags, plus Trace 3's unflagged precursor. This does
not justify treating every teleport destination as grounded, or accepting
rejected movement coordinates as the player's predicted position.

The ground correction records only a finite, regular position packet rejected
specifically for a pending resync. It requires a claimed landing with collision
support directly at the feet and no body intersection. It can restore ground
only on acceptance of that same Grim correction (matching teleport ID and
transaction), with absolute position/velocity, nonpositive replacement Y and
independently verified support at its destination. Normal accepted movements,
different teleports, malformed packets, and later false or unsupported ground
claims clear the observation. Rejected positions never replace predicted
coordinates. This additional validation is deliberately narrower than an
unconditional post-teleport ground allowance.

## NoSlow remains a separate finding

Trace 2 line 800 contains both Simulation and NoSlow. Grim records use of
`dried_kelp`, with use-speed multiplier `0.2` and consumption time `0.8 s`.
The two movements at lines 799–800 match **full** ground input, rather than
slowed ground input.

Correcting the ground state and inherited velocity reduces the NoSlow
offset from `0.12329299037939305` to approximately `0.083886968624051816`.
That still exceeds this server's configured `0.03` threshold. This report
does not establish whether the item-use state is stale or the client actually
omitted the slowdown. No exemption for food use follows from this evidence.

Additional bounded deep-debug snapshots record item-use/release and held-slot
packets, server inventory updates and item-use metadata transitions. They
are diagnostic observations, not new database writes or changes to check
thresholds. Receive-time cancellation snapshots do not claim to show the
final cancellation state after every packet listener has run.

## Numerical validation

The local replay script
`.gradle/debug-analysis/tutufrutu_window_replay.py` verifies all 14 retained
Simulation movements and the unflagged precursor, with maximum residual
`5.54e-13`. It also reconstructs the reported NoSlow offset and its remaining
error after the ground correction. This is a numerical reconstruction from
the report, not a live-server test or proof about the 15 missing flags.

The full `:common:test -PshadePE=false --offline` suite passes: 192 tests in
27 suites, with no failures, errors or skipped tests. The 19 added tests
cover transaction boundaries and failed/reused callback IDs, immutable
attribute snapshots, old-speed inertia, continued NoSlow detection, and
ground recovery with unrelated teleports, unsupported claims, upward motion,
wall-only contact and intersecting geometry. The NoSlow test exercises the
candidate minimum and buffer; it is not a full game-client integration test.
