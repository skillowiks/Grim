# simfix13: Dantes225 / BJ1hH8gSFQ

Report: https://paste.grim.ac/BJ1hH8gSFQ

The report was captured with **simfix11**, server 1.21.11 and client 1.21.4
(Fabric). It contains 18 Simulation flags and 5 Phase flags. All 18 Simulation
movements survive in its three trace windows. There are no NoSlow flags in
this report. This build also includes the previous simfix12 changes.

## Stair collision seam (Trace 1)

Lines 626, 630 and 631 start at the corrective teleport position
`6233.199999988079,37,5975.699999988079`. The player's positive X face is
exactly `6233.5`. The captured world includes a north-facing, top-half,
straight andesite stair at `6233,38,5975`.

Grim represented the lower half of that stair as two adjacent octets. Their
internal X=0.5 boundary blocked motion from inside the stair even though the
client's shape has no boundary there. This is particularly visible during
block escape after a correction: predicted X is zero, while the client
moves approximately 0.1 plus its item-slowed backward input.

Vanilla 1.21.4 `StairBlock.makeStairShape` calls `Shapes.or`, which performs
an optimized union. The straight stair has no subdivision on its full-width
axis. The fix combines the two adjacent octets for modern straight stairs.
It preserves the occupied volume and external faces. Legacy stair shapes
and inner/outer stair shapes keep their existing representation. Phase's
check logic is unchanged; eliminating the artificial component boundary
also removes that particular source of apparent new-box intersection.

Reconstructing the three displacements as `0.1 + slowed input` gives a
maximum residual of `4.25e-13`. The regression test runs their captured
block palette through the actual collision parts: the old split clips X
to zero, while the merged shape preserves the reconstructed movement and
the real Z wall.

Primary source verification used the locally cached Mojang 1.21.4 client
and mappings, decompiled with CFR 0.152: `StairBlock` (`drm`), `Shapes`
(`fbs`), `VoxelShape` (`fbv`). The raw report and scratch reconstructions
are ignored local artifacts under `.gradle/debug-analysis/`.

## Cases that remain unconfirmed

* **Trace 2, lines 729/734:** Grim clips movement against an east-facing
  player wall head at `6247,56,5953`; the client moves beyond that boundary.
  The decoded state and Grim's collision box match vanilla 1.21.4, including
  the entire `PlayerWallHeadBlock` / `WallSkullBlock` / `AbstractSkullBlock`
  inheritance chain and block registration. A cached-world snapshot does
  not establish what block the client actually saw at that moment. No
  blanket exception for heads or Phase was added.
* **Trace 3, lines 832–844:** the first horizontal difference decays with
  normal air/ground drag through thirteen flags. This establishes a carried
  velocity discrepancy, but the report lacks the entity push context needed
  to establish its cause. It is not evidence for allowing arbitrary extra
  acceleration. Diagnostic snapshots are expanded to resolve that gap.
  The preceding movement's accepted velocity was carried correctly;
  attributing this to lost inertia on that preceding tick is ruled out.

The fix is not a claim that all 18 Simulation flags or all 5 Phase flags are
explained. No thresholds or punishment rules were weakened.

The added diagnostics record the chosen input/uncertainty and carry vector
before the movement ticker resets directional allowances. Separate endpoint
snapshots describe nearby cached entities and team collision rules, limited
to 256 inspected entities and eight descriptions with omission markers.
They distinguish cached estimates from actual client positions. The old
`collidingEntities=3` and `piston=5/5/5` fields incorrectly reported queue
lengths; their replacements show actual maxima and sample histories.

## Debug messages

The displayed upload URL now has an explicit `COPY_TO_CLIPBOARD` action
containing only the URL. A separate Open button retains browser navigation.
The target player name is retained across asynchronous upload start,
success and failure messages. Live deep-debug lines, suppressed-line
summaries and prediction debug lines include the target as well.

Old message templates without `%player%` receive a target prefix without a
configuration reset. Target names and URLs are literal Adventure components.
The `/grim dump` cache stores the URL rather than a rendered chat message,
so its subsequent output uses the same copy/open rendering.

## Validation

`:common:test -PshadePE=false --offline --no-daemon` passes all **210 tests**
in 30 suites, with no failures, errors or skips. The added cases cover the
recorded stair world, mirrored seams, external collision, occupied volume,
shape copy isolation, clipboard/open actions, simultaneous targets, old
message templates, click/hover/insertion placeholders, diagnostic bounds
and the reconstructed candidate reference after movement.

The offline trace reconstruction accounts for all 18 retained Simulation
movements with maximum residual `8.37e-13`, but only three have a
source-backed fix: the others are explicitly a collision counterfactual or
an unexplained initial difference followed by its drag decay. This is not
a live-server test. The remaining two scenarios need fresh reproduction.

The Bukkit artifact is built with `:bukkit:shadowJar -PshadePE=false`.
Its final hash/version and ZIP/class checks are recorded separately in the
ignored `.gradle/debug-analysis/simfix13-verification.json`.
