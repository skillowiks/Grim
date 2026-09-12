# NoSlow item-use recovery, 2026-09-12

Report: https://paste.grim.ac/EGz7W9aeKM

The simfix13 recording for nt1111 contains 47 NoSlow flags in 170 unique
movements across three overlapping windows. All flagged movements fit the
unslowed candidate (`flipItem=true`), while Grim retains `useItem=true`,
`useTransaction=20896` and a golden carrot in slot 2. This state is visible for
7.7 seconds. The usual residual is `0.16 * 0.98 * (1 - 0.2) = 0.12544`;
crouching reduces it to approximately 0.037632. At 18:59:33.840 a new USE_ITEM
is followed by the expected slowed input. Repeated Grim setbacks do not
resolve the old use state.

The user reports that setbacks prevented eating. The initial use-state
transition precedes the retained window, so this report does not establish
the original cause or independently exclude a client-side NoSlow modification.
The item duration of 1.6 seconds is not an automatic client-side deadline:
the exact 1.21.4 LivingEntity bytecode completes consumption on the server side.

## Recovery behavior

After an accepted NoSlow violation reaches an authorized setback, Grim queues
one entity-thread task to resend the current server living-flags byte to that
player. This path supports Bukkit servers and clients from 1.17 onward. Other
platforms keep their prior behavior through an optional API default.

PacketEvents reads the complete native metadata, including default/non-dirty
values. Only the living-flags entry is sent, unchanged, through the ordinary
packet listeners. Existing Grim metadata handling supplies the transaction
boundary. No prediction state is cleared directly.

Exact client 1.21.4 bytecode establishes that SynchedEntityData.assignValues
calls onSyncedDataUpdated even for an equal value. LocalPlayer clears a stale
local use state for inactive metadata, starts use if active metadata restores
an inactive client, and preserves the running timer when already active.
Resending the authoritative state therefore does not force cancellation or
complete the food. In particular, status 9 is not synthesized, because that
would invoke client consumption completion.

This is recovery from persistent disagreement, not a proof or repair of the
unobserved initial cause. NoSlow thresholds, violation levels, ordinary
setbacks and prediction candidates are unchanged. Continued unslowed movement
while use remains active is still checked. Recovery respects packet-modification
permissions, keeps at most one entity task pending, and schedules at most once
per two seconds per player. There is no periodic polling, packet-thread wait,
or additional work on normal non-violating movement.

## Validation

NoSlowUseResyncTest checks queued-task bounds, cooldown, retirement/rejection,
exception cleanup and continued NoSlow evidence across a recovery request.
NoSlowBufferTest and MovementSpeedIntervalNoSlowTest cover the existing
consecutive evidence and speed-interval behavior.

On 2026-09-12, `:common:test` passed all 387 tests in 46 suites with no
failures, errors or skipped tests. Bukkit compilation and `:bukkit:shadowJar`
also passed. The server distribution is packaged with `-PshadePE=false`
(`+lite`) to retain the existing external PacketEvents setup.

A live server reproduction is still required to confirm recovery for this
incident: begin debug before eating, reproduce the stall, and verify the
`NOSLOW_USE_RESYNC` record followed by SELF_USE_FLAGS and consistent movement.
