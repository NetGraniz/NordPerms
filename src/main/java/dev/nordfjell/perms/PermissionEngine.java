package dev.nordfjell.perms;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

final class PermissionEngine {
    final AtomicReference<Policy> policy = new AtomicReference<>();
    volatile AuthGate auth;

    Map<String, Boolean> permissions(Policy snapshot, Player player) {
        UUID id = player.getUniqueId();
        // No authentication reflection on the ordinary-player hot path.
        return snapshot.permissions(id, snapshot.members().contains(id) && auth.authenticated(player));
    }

    /** Expand registered Bukkit permission children once, never traverse them on a check. */
    static Policy expand(Policy raw, Map<String, Permission> registry) {
        Map<String, Boolean> players = expandNodes(raw.players(), registry);
        Map<String, Boolean> staff = expandNodes(raw.moderators(), registry);
        staff.keySet().forEach(node -> players.putIfAbsent(node, false));
        return new Policy(players, staff, raw.members());
    }

    private static Map<String, Boolean> expandNodes(Map<String, Boolean> source, Map<String, Permission> registry) {
        Map<String, Boolean> result = new HashMap<>();
        int[] budget = {200_000};
        source.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            children(entry.getKey(), entry.getValue(), registry, result, new HashSet<>(), 0, budget));
        // An explicit child overrides every inherited parent regardless of ordering.
        result.putAll(source);
        return result;
    }

    private static void children(String node, boolean value, Map<String, Permission> registry,
                                 Map<String, Boolean> result, Set<String> path, int depth, int[] budget) {
        if (--budget[0] < 0) throw new IllegalArgumentException("Permission child expansion exceeds work limit");
        if (depth > 32 || !path.add(node)) throw new IllegalArgumentException("Cyclic or too-deep permission children");
        Boolean previous = result.putIfAbsent(node, value);
        if (previous != null && previous != value) result.put(node, false); // ambiguous inheritance denies
        if (result.size() > 50_000) throw new IllegalArgumentException("Expanded policy exceeds 50000 nodes");
        Permission permission = registry.get(node);
        if (permission != null) {
            for (var child : permission.getChildren().entrySet()) {
                children(Policy.normalize(child.getKey()), value == child.getValue(), registry, result, path, depth + 1, budget);
            }
        }
        path.remove(node);
    }
}
