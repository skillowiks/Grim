# Movement report review, 2026-09-08

All four reports below were recorded on `simfix6-e67ed19`, before the
`simfix7-61939d1` corrections. Their summaries contain Simulation flags, but
no NoSlow flags. The saved movement windows are bounded and sometimes overlap;
they are not a complete trace of every flag in the session.

| Report | Evidence | Status |
| --- | --- | --- |
| [PixelSoul56](https://paste.grim.ac/Ur1q8dPRov) | Two attacks require one horizontal `0.6` slowdown that was not predicted. Another jump requires movement speed `0.208` instead of `0.16`, without a sprint-jump impulse. | Exact movement differences are reproduced, but the report lacks sprint/attribute packet history needed to establish all state transitions. Simfix7 records that history. |
| [Zxcursed_plane](https://paste.grim.ac/hTodQLIt1I) | A charged sprint attack is followed by a jump with a missing sprint speed modifier. Another mismatch includes movement accumulated across two rejected position packets. | These mechanisms were corrected in simfix7: sprint restart state, cancelled position bookkeeping and setback recovery. A new report is needed to assess the installed fix. |
| [ch4karka](https://paste.grim.ac/gdjHvOAiWT) | Six primary attack movements match previous horizontal velocity multiplied by `0.99F`, plus independently calculated water input, within `3e-13`. A preceding control attack matches vanilla `0.6`. Three affected attacks have movement speed zero. | This is not explained by a missing sprint speed modifier. Vanilla 1.21.11 still applies `0.6`; accepting `0.99F` as vanilla would weaken the check. The report does not identify the source of the different movement. |
| [qwerty4556](https://paste.grim.ac/ykezdSjukX) | Several first residuals equal a water push from an additional prior tick: horizontal current times `0.91F`, or vertical `-0.014 * 0.98F`. Another water segment differs in flow magnitude. | Water state/timing remains unresolved. The old report lacks the block states needed to reproduce the current. Simfix8 records the cached block neighborhood and fluid state. |

## Confirmed source corrections in simfix8

### Fluid flow uses own height

Vanilla `FlowingFluid.getFlow` uses each fluid state's own height (`amount / 9`)
for the source block, the adjacent block, and the block below the adjacent block.
The same rule is present in the 1.13 and 1.21.4 reference sources.

Grim previously used immersion height, clamped to `8/9`. Immersion height becomes
one when the same fluid is above; clamping it does not recover the own height of
a flowing block. This can change the direction, not just introduce rounding:
a level-7 center block with water above, an eastern source, and a northern level-7
neighbor produces a westward vanilla flow but a northward old Grim flow.

Only the modern flow gradient now uses own height. Immersion height and legacy
fluid physics retain their existing behavior. This corrects a verified source
mismatch; it is not proof that this particular layout existed in qwerty4556's
report.

### Received attack-knockback attribute

From 1.21, the client reads `ATTACK_KNOCKBACK` for attack knockback; enchantment
effects are evaluated on the server. Grim now tracks an explicitly received
attribute and accounts for its positive contribution to attacker slowdown.
Without an attribute packet its default remains zero. The attribute is not
normally synchronized by vanilla, so merely setting it through Bukkit is not
evidence that the client received it.

Pre-1.21 clients retain enchantment behavior. The 1.21.11 float division in
`getKnockback` is preserved, including underflow to zero. Attribute tracking is
a compatibility correction, not a proven explanation for PixelSoul56: nearby
attacks in the old report do not consistently require a nonzero bonus.

## Diagnostic boundary

The exact 1.21.4 exclusion from post-move fluid pushing is intentional. In its
vanilla client, `Player.isControlledByClient()` makes `Entity.move` skip the
`checkFallDamage` path that would perform the extra fluid update. Removing that
exclusion solely to match the reported extra push would contradict the source.

New fluid snapshots describe the endpoint bounding box and current compensated
world. They are bounded and self-contained, include block state descriptions,
and explicitly report truncation. They do not prove what blocks a modified
client actually saw or retroactively capture pre-move world state.

PacketOrderJ is outside this change. No blanket movement tolerance, water/attack
exemption, or NoSlow exemption was introduced.

## Validation

`gradlew :common:test :bukkit:shadowJar -PshadePE=false --offline --no-daemon`
completed successfully: 154 tests, zero failures/errors/skips. New fixtures cover
fluid levels/gradients, client-version attack-knockback semantics and bounded
debug snapshots. Live reproduction on the server remains outstanding.
