# NordPerms

Permission provider for Paper 26.2 and Folia 26.2, Java 25. One JAR supports both platforms with two groups: `players` and `moderators`.

NordPerms stores policy in a server-side YAML file. It has no database, telemetry, web editor or network requests.

## Setup

1. Stop the server. Back up the current permission provider, its data and all working configuration. Keep the NordAuth database.
2. Record the exact permission nodes and server UUIDs you need. Groups, contexts and UUID assignments are not imported automatically.
3. Remove the old provider's JAR from the active `plugins` directory and keep it for rollback. Do not run two permission providers together.
4. Install the NordPerms release JAR. First startup creates `plugins/NordPerms/config.yml` with an empty moderator roster.
5. Edit that file. Register and secure each NordAuth account before assigning it as a moderator. Use the UUID actually seen by the server, not one guessed from a name or website.
6. Run `nordperms reload` in the server console. Use `nordperms status` to inspect the current state.

## Permissions

NordPerms has no management permission node. Both `nordperms reload` and `nordperms status` are console-only. In-game access stays blocked even for moderators, OP users or wildcard grants.

Other plugins' permissions belong under `groups.<group>.permissions`. Use their exact nodes, not command names. NordPerms does not grant rights by adding commands to an allowlist.

This example shows the structure; the UUID is fictitious:

```yaml
schema-version: 1
groups:
  players:
    permissions:
      nordregen.use: false
      nordauth.admin.resetpassword: false
      nordcommands.bypass: false
  moderators:
    permissions:
      nordregen.use: true
      nordauth.admin.resetpassword: true
members:
  "00000000-0000-0000-0000-000000000001": moderators
```

The full bundled template also denies OP/deop, Bukkit reload and NordCommands/NordFireworks bypass. Review those entries before replacing the full template with this shorter example.

`nordregen.use` authorizes the current `/regenchunk` cleanup command, not terrain regeneration.

### Group rules

`moderators` inherits `players`. Explicit moderator values override inherited values. Nodes listed only for moderators are reserved: ordinary players and unauthenticated moderators receive `false`.

Use complete lowercase nodes and boolean values. Wildcards such as `*` and `plugin.*`, names instead of canonical UUIDs, string values and duplicate YAML keys are rejected.

Unlisted permissions retain Bukkit defaults and attachment behavior. This is not a global deny-all policy.

## Security and failure handling

Permission checks use immutable in-memory snapshots. File reload runs away from game threads. Publishing a valid snapshot revokes old rights immediately for all connected players without waiting for a player scan; command-tree updates run on the players' own schedulers.

A failed reload retains the previous policy. Invalid initial configuration blocks new logins until an administrator repairs the file and reloads from the console.

On `online-mode=false`, moderator rights require a verified NordAuth session. The bridge is tested with NordAuth 1.3.0. Missing or disabled NordAuth, a mismatched session-check method or a check error closes privileged access. On `online-mode=true` without NordAuth, Minecraft's verified UUID is the identity source. Other authentication plugins are not supported.

Registration is blocked for a UUID already assigned as a moderator. Register the ordinary account first, then assign the role. A name or offline UUID alone is not proof of authentication.

Moderators can reset passwords only for online, non-moderator accounts. Their own account, another moderator's account and offline accounts require console recovery. The guard also checks namespaced command forms. Normal self-service password changes through NordAuth still require the old password and are unchanged.

OP accounts are refused. Assign roles through the configuration, not OP; NordPerms does not edit `ops.json`. Do not grant OP while the server runs. The console and installed server plugins are trusted.

If another provider has already replaced a player's permission system, NordPerms does not continue with unsafe defaults. Hot disabling NordPerms stops the server. Do not use PlugMan, `/reload` or live JAR replacement.

A permission provider is not an anticheat. It cannot protect against a malicious or vulnerable installed plugin, console/file access, an unsafe proxy or a stolen password. A command that never checks permissions must be fixed in its owning plugin.

This JAR runs only on the backend. Review proxy permissions separately before changing providers.

## Compatibility and performance

Supported Bukkit behavior includes `hasPermission`, `isPermissionSet`, `getEffectivePermissions`, standard and temporary `PermissionAttachment`, registered parent/children permissions and case-insensitive checks.

Explicit server policy overrides other plugins' attachments. Conflicting inheritance denies a node; an explicit node overrides inheritance. If a plugin dynamically changes its registered permission tree, run `nordperms reload`.

The plugin does not implement LuckPerms API or commands, Vault, contexts, prefixes, temporary groups, wildcards or a Velocity permission provider. Plugins that require LuckPerms or Vault directly need a separate compatibility review.

NordCommands remains an independent command filter. Its configuration does not grant permissions.

Integration attaches once to `CraftHumanEntity.perm` after checking the field structure. It uses no `Unsafe` and does not modify the server core. This is an internal server field: test each new core version rather than inferring compatibility from `folia-supported: true`.

Frequent checks use shared immutable group maps and a UUID set, with no file or network I/O on the game path. There is no recurring scan of 600 players or role-recalculation timer.

Tests with 600 synthetic UUIDs and permission microbenchmarks do not prove TPS with 600 real players. See [TESTING.md](TESTING.md) for checks and limits.

## Build

```text
mvn -B -ntp verify
```

The output is `target/NordPerms-1.0.0.jar`. GitHub Actions builds and runs unit tests on Java 25.

The repository contains generic templates and synthetic test data. Do not copy the installed `plugins/NordPerms/config.yml` into it.

## Integration references

- [Paper — Folia support](https://docs.papermc.io/paper/dev/folia-support/)
- [Paper 26.2 — CraftHumanEntity](https://github.com/PaperMC/Paper/blob/ver/26.2/paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftHumanEntity.java)
- [Paper 26.2 — PermissibleBase](https://github.com/PaperMC/Paper/blob/ver/26.2/paper-api/src/main/java/org/bukkit/permissions/PermissibleBase.java)
- [LuckPerms — PermissibleInjector](https://github.com/LuckPerms/LuckPerms/blob/master/bukkit/src/main/java/me/lucko/luckperms/bukkit/inject/permissible/PermissibleInjector.java)

LuckPerms source was consulted as an integration reference. NordPerms code was written separately.
