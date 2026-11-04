# NordPerms 1.0.0 verification

Date: 2026-10-08. Windows, Java 25. All server checks ran separately from production on `127.0.0.1`, using new flat worlds and synthetic accounts. No production NordAuth passwords or databases were read or copied.

## Unit tests

`mvn -B -ntp verify`: all 10 tests passed. Coverage includes strict YAML parsing, UUIDs and groups, rejection of wildcards, duplicates and invalid types, immutable snapshots, Bukkit child-permission inheritance, explicit-node precedence, reserved moderator permissions, cycles, case normalization, denial without a trusted offline identity source, and retention of the previous policy after a load failure.

The concurrency test uses **600 UUIDs**, 4 threads, 400,000 permission checks and 10,000 policy swaps. No UUID retains permissions after its role is removed. This tests logic and cross-thread visibility, not 600 connected clients.

## Real test server implementations

- Folia `26.2-7-ver/26.2.x@14b7fee`, API `26.2.build.7-beta`.
- Paper `26.2-132-ver/26.2@19ebc4a`, API `26.2.build.132-stable`.
- Real NordAuth `1.3.0`, a new empty SQLite file, and two protocol 26.2 test clients.

The following checks passed on both server implementations:

1. A corrupt initial configuration blocks login; a console reload after repair restores operation.
2. An OP account cannot join and does not stop other players.
3. A moderator UUID grants no privileges before NordAuth login.
4. First-time registration is blocked for an account already assigned as a moderator.
5. Authentication grants the required permissions, including a registered child permission.
6. A standard Bukkit attachment works for an unreserved permission but cannot override protected moderator nodes.
7. A temporary attachment expires through the entity scheduler.
8. A failed reload retains the previous permissions.
9. Removing a role affects an already connected player without requiring reconnection.
10. A moderator cannot invoke NordPerms management commands in game.
11. Namespaced self-password reset and offline-account reset are blocked.
12. Password reset for an ordinary online player passes through the real NordAuth plugin.
13. No provider attachment, event, or region-ownership errors occur.
14. Hot disabling NordPerms shuts down the test server correctly.

The scenarios are in `test-support/integration.cjs`. `test-support/build-probe.ps1` builds the helper JAR, which is **not included in the release**. The scenario accepts a fresh test directory, Java path, prepared test server directory, `Paper` or `Folia`, a Mineflayer dependency directory with protocol 26.2 support, and the NordAuth JAR. It copies only runtime files and an already accepted test EULA from the server installation, never old worlds, configuration, or data. An optional final `auth` argument verifies shutdown when NordAuth, rather than NordPerms, is hot-disabled. The scenario uses port 25649 and runs sequentially.

## Measurements and limitations

The test server performed warm-up followed by 1,000,000 `Player.hasPermission` calls for both an ordinary player and an authenticated moderator. Observed runs took approximately **0.07–0.15 microseconds per check**. This is a short synthetic measurement of a warmed-up path, depends on the machine, JIT and permission node, and is not a latency guarantee for every request.

The verification did not include a 600-client gameplay TPS test, an audit of every third-party plugin, an external penetration test, or server version 26.3. Bukkit API verification does not imply compatibility with direct LuckPerms API or Vault usage. Before a production migration, transfer the current permissions and UUIDs from LuckPerms separately and verify commands and proxy behavior. This work did not replace the production LuckPerms installation.
