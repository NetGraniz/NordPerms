package dev.nordfjell.perms;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.Test;

class PolicyTest {
    private final PolicyLoader loader = new PolicyLoader();
    private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String VALID = """
        schema-version: 1
        groups:
          players:
            permissions: {test.play: true, test.admin: false}
          moderators:
            permissions: {test.admin: true}
        members:
          "00000000-0000-0000-0000-000000000001": moderators
        """;

    @Test void parsesAndInheritsOnlyAfterAuthentication() {
        Policy policy = PermissionEngine.expand(loader.parse(VALID), Map.of());
        assertTrue(policy.permissions(STAFF, true).get("test.play"));
        assertTrue(policy.permissions(STAFF, true).get("test.admin"));
        assertFalse(policy.permissions(STAFF, false).get("test.admin"));
        assertFalse(policy.permissions(UUID.randomUUID(), true).get("test.admin"));
    }
    @Test void malformedConfigsNeverProducePolicy() {
        for (String text : new String[] {
            VALID.replace("schema-version: 1", "schema-version: 2"),
            VALID + "unknown: true\n", VALID.replace("test.play: true", "test.play: 'true'"),
            VALID.replace("test.play", "Test.Play"), VALID.replace("test.play", "*"),
            VALID.replace("test.play", "test.*"), VALID.replace("test.play: true", "test.play: true, test.play: false"),
            VALID.replace(STAFF.toString(), "Bob"), VALID.replace(": moderators", ": owners"),
            VALID.replace("permissions: {test.admin: true}", "permissions: null"),
            "!!java.util.Date {}", "schema-version: &x {a: true}\ngroups: *x\nmembers: {}"
        }) assertThrows(RuntimeException.class, () -> loader.parse(text));
    }
    @Test void snapshotsAreImmutable() {
        Map<String, Boolean> mutable = new HashMap<>(Map.of("a.b", true));
        Set<UUID> members = new HashSet<>(Set.of(STAFF));
        Policy policy = Policy.create(mutable, Map.of(), members);
        mutable.clear(); members.clear();
        assertEquals(Boolean.TRUE, policy.players().get("a.b"));
        assertTrue(policy.members().contains(STAFF));
        assertThrows(UnsupportedOperationException.class, () -> policy.players().clear());
    }
    @Test void registeredChildrenAndExplicitOverrides() {
        Permission parent = new Permission("test.parent", PermissionDefault.FALSE, Map.of("test.yes", true, "test.inverted", false));
        Policy policy = PermissionEngine.expand(Policy.create(Map.of("test.yes", false), Map.of("test.parent", true), Set.of(STAFF)), Map.of("test.parent", parent));
        assertTrue(policy.moderators().get("test.parent"));
        assertFalse(policy.moderators().get("test.yes"));
        assertFalse(policy.moderators().get("test.inverted"));
        assertFalse(policy.players().get("test.parent"));
        assertFalse(policy.players().get("test.inverted"));
    }
    @Test void absentStaffNodesAndChildrenAreReserved() {
        Permission parent = new Permission("test.admin", PermissionDefault.FALSE, Map.of("test.child", true));
        Policy policy = PermissionEngine.expand(Policy.create(Map.of(), Map.of("test.admin", true), Set.of(STAFF)), Map.of("test.admin", parent));
        assertFalse(policy.players().get("test.admin"));
        assertFalse(policy.players().get("test.child"));
        assertTrue(policy.moderators().get("test.child"));
    }
    @Test void cyclesRejected() {
        Permission cycle = new Permission("test.cycle", PermissionDefault.FALSE, Map.of("test.cycle", true));
        assertThrows(IllegalArgumentException.class, () -> PermissionEngine.expand(Policy.create(Map.of("test.cycle", true), Map.of(), Set.of()), Map.of("test.cycle", cycle)));
    }
    @Test void caseNormalizationUsesRootLocale() {
        assertEquals("plugin.admin", Policy.normalize("PLUGIN.ADMIN"));
        assertThrows(IllegalArgumentException.class, () -> Policy.normalize(null));
    }
    @Test void offlineModeWithoutIdentityProviderCannotAuthenticateStaff() {
        AuthGate offline = new AuthGate(null, false);
        assertFalse(offline.ready());
        assertFalse(offline.authenticated(null));
        AuthGate online = new AuthGate(null, true);
        assertTrue(online.ready());
        assertTrue(online.authenticated(null));
    }
    @Test void invalidReloadKeepsReferenceUnchanged() {
        AtomicReference<Policy> reference = new AtomicReference<>(loader.parse(VALID));
        Policy before = reference.get();
        assertThrows(RuntimeException.class, () -> reference.set(loader.parse("broken: yaml")));
        assertSame(before, reference.get());
    }
    @Test void sixHundredIdentitiesAndConcurrentRevocation() throws Exception {
        Set<UUID> users = new HashSet<>();
        for (int i = 0; i < 600; i++) users.add(new UUID(0, i));
        Policy granted = PermissionEngine.expand(Policy.create(Map.of("test.admin", false), Map.of("test.admin", true), users), Map.of());
        Policy denied = PermissionEngine.expand(Policy.create(Map.of("test.admin", false), Map.of("test.admin", true), Set.of()), Map.of());
        AtomicReference<Policy> reference = new AtomicReference<>(granted);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 4; thread++) futures.add(executor.submit(() -> {
                for (int i = 0; i < 100_000; i++) {
                    Policy snapshot = reference.get();
                    UUID uuid = new UUID(0, i % 600);
                    assertEquals(snapshot.members().contains(uuid), snapshot.permissions(uuid, true).get("test.admin"));
                }
            }));
            for (int i = 0; i < 10_000; i++) reference.set((i & 1) == 0 ? granted : denied);
            reference.set(denied);
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
            for (UUID user : users) assertFalse(reference.get().permissions(user, true).get("test.admin"));
        } finally { executor.shutdownNow(); }
    }
}
