# Attack / velocity ordering, 2026-09-08

Report: [poslezavtro](https://paste.grim.ac/hFdlA2aD0a), recorded on
`2.3.74-simfix8-ab9692a+lite`, server 1.21.11, client 1.21.4 / Fabric,
9 ms ping. Summary: 101 Simulation, two AntiKB and one AntiExplosion.
The bounded movement windows do not contain every flag in the summary.

## Confirmed correction

At 13:22:30.636, the client moved by the exact required entity velocity:

```
attack:             transaction 15984, required slowdown
entity velocity:    transaction 15991, (0.107375, 0.200875, 0.214625)
movement:          transaction 15995, (0.107375, 0.200875, 0.214625)
old best candidate:                  (0.064989, 0.200875, 0.129339)
Simulation / AntiKB offset: 0.095238
```

The old predictor applied the pending attack's horizontal factor 0.6 to all
starting candidates, including the newer replacement knockback. The reported
best vector also includes uncertainty, so its X/Z are not exactly 0.6 of KB.

The official 1.21.4 client handles entity motion and ping on the main thread.
`ClientPacketListener.handleSetEntityMotion` calls `Entity.lerpMotion`, which
sets the velocity. The pong for the transaction preceding that packet therefore
establishes a boundary: an attack observed before that boundary happened before
the replacement motion and cannot slow the replacement vector.

Grim now records the latest transaction among attacks contributing to the
pending slowdown. A knockback candidate is not slowed by those attacks when its
own first transaction is strictly newer. First-bread and required candidates
use their respective velocity records. Normal movement still receives attack
slowdown; the knockback candidate keeps its provenance and remains checked by
AntiKB. Same-transaction and later attacks retain the previous behavior.

Debug movements now include the attack slowdown transaction and both knockback
transactions, making this ordering visible in future reports.

This deliberately does not resolve mixed sequences with attacks both before
and after a replacement packet, or ambiguity within the same transaction
interval. No global tolerance or velocity exemption was added.

## Other findings

The repeated positive-ID, `plugin=true` teleports are consistent with the
BreakItems freeze snowball: Slowness X and `PlayerMoveEvent.setTo(from)` for
three seconds. In the available global tail all 34 tracked teleports have
matching acceptances. Movements immediately before and after the flagged KB
match prediction within 7e-13. This report does not establish a separate Grim
teleport recovery defect.

Four earlier primary Simulation movements match an additional horizontal
factor 0.6 after an attack (offsets 0.068479, 0.008364, 0.046909 and 0.091727).
However, the recorded attack state has no sprint and zero attack-knockback
attribute. A nearby control attack requires no slowdown. The numerical
difference is reproducible, but its state-level cause is not established;
these cases are not claimed fixed. Automatically allowing slowdown on every
attack is not supported by the reference client.

The first AntiKB and the AntiExplosion have no detailed triggering movement
in the saved windows. They cannot be attributed to the corrected KB ordering
from the summary alone. PacketOrderJ and NoSlow behavior are unchanged.

## Validation

Regression tests exercise the logged knockback, normal movement in the same
candidate set, first-bread ordering, optional and repeated attack slowdowns,
same/later transactions, missing ordering evidence, and decorated KB/explosion
vectors. Live reproduction with this build remains to be checked.

`gradlew :common:test -PshadePE=false --offline --no-daemon --console=plain`
passed: 162 tests, zero failures, errors or skips (including eight ordering
regressions). `git diff --check` also passed.
