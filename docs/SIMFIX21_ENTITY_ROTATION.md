# SIMFIX21: keep replicated entity angles in degrees

PacketEvents already decodes relative-movement/rotation and rotation-only angles
to degrees. Grim multiplied them by `0.7111111F` again, while full-position syncs
and spawn packets retained degrees. A 90/45-degree full-sync target therefore
became 64/32 on an otherwise identical relative update.

Remove the second conversion in the two `PacketEntityReplication.onPacketSend`
branches. Tracker data, deferred `PacketEntity.onFirstTransaction` callbacks and
vehicle rotation consumers now receive the same units as full-position syncs.
No packet payload, velocity formula, Hitboxes/Reach threshold, transaction rule or
version-specific interpolation branch is changed.

For clients 1.21.9+, the incorrect units could make `restatesTarget` reject an
identical interpolation target and unnecessarily restart interpolation. Older
versions retain their existing restart/freeze rules. This is a specific modeled
state error; it does not establish that every reported Hitboxes flag has this cause.

## Validation

`EntityRotationReplicationTest` runs the real outbound packet listener, real
`LatencyUtils` transaction queue and real `PacketEntity` interpolation transitions.
Only player/platform/connection inputs are mocked. PacketEvents uses its cached
wrapper handoff; this fixture does not decode wire bytes or run actual clients.

The 21 cases cover signed packed-angle representatives, rotation-only and combined
updates following a full sync, delayed application, genuine position/rotation
changes, and client protocol representatives 1.20, 1.20.2, 1.21.4, 1.21.5, 1.21.7
(the protocol representative for 1.21.7/1.21.8), 1.21.9 and 1.21.11.

On the original listener, 19 cases fail with assertion errors and the two genuine
change controls pass. With the fix, all 21 pass. The complete `common` test run
passes 408 tests across 47 suites, with no skipped or failed cases. Mockito and
ViaVersion are test-only dependencies needed to construct the controlled player
and resolve its optional API types; production dependencies are unchanged.

Windows validation command used in this workspace (the encoding options avoid
Gradle worker bootstrap failures with Cyrillic paths):

```powershell
.\gradlew.bat :common:test --configure-on-demand --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=windows-1251" "-Dfile.encoding=windows-1251"
```

These checks do not reproduce a live Hitboxes flag or certify arbitrary Leaf
async tracker/plugin combinations. They do not replace a client/transaction trace
when investigating a remaining false positive. No live configuration or server
deployment is performed.
