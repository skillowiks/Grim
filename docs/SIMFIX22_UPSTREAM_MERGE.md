# SIMFIX22: upstream integration

Integrated all 45 missing commits from `GrimAnticheat/Grim` branch `2.0`, through
`1ae2d5f34`, with local HEAD `4a7645da3`. Local movement, NoSlow recovery,
knockback, logging, punishment profiles and deep-debug changes are retained.
The pre-merge state is available at `backup/before-upstream-20261022`.

The experimental combat observer and check have been removed, including their
geometry helpers, asynchronous report publisher, configuration, punishment
groups, feature documentation and dedicated tests. Normal attack prediction and
deep-debug attack traces remain.

Conflict resolution keeps upstream's 26.3 interpolation and packet translation,
packet-based piston tracking, datastore recovery, Fabric serializers and build
updates. Relative entity rotations remain in degrees: PacketEvents
`2.14.1+addb80e-SNAPSHOT` already performs wire-angle conversion. The local resync
guard is combined with upstream's position-bearing teleport confirmations.

## Validation

On 2026-10-23, with Java 25 and Gradle 9.8.2:

```powershell
.\gradlew.bat :common:test :bukkit:shadowJar :fabric:assemble --max-workers=2 --no-configuration-cache --console=plain
```

- 268 tests in 37 suites passed, without errors, failures or skipped tests.
- All 23 entity-rotation cases passed, including relative updates reaching their
  target under both legacy and 26.3 stepped interpolation.
- Bukkit and Fabric distributions, including all Fabric version modules, built.
- 25 configuration and punishment YAML files parsed with SnakeYAML 2.2.
- The Bukkit archive contains no removed observer/check classes.

Test fixtures now initialize PacketEvents server version and datastore write-path
configuration explicitly, then restore the previous global state. Production
initialization was not relaxed to accommodate those fixtures.

This is build and automated-test validation; no live server was started or deployed.
