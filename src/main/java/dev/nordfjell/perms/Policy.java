package dev.nordfjell.perms;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable policy. One volatile publication changes every player's decision at once. */
record Policy(Map<String, Boolean> players, Map<String, Boolean> moderators, Set<UUID> members) {
    Policy {
        players = Map.copyOf(players);
        moderators = Map.copyOf(moderators);
        members = Set.copyOf(members);
    }

    static Policy create(Map<String, Boolean> players, Map<String, Boolean> overrides, Set<UUID> members) {
        Map<String, Boolean> basic = new HashMap<>(players);
        Map<String, Boolean> staff = new HashMap<>(players);
        staff.putAll(overrides);
        return new Policy(basic, staff, members);
    }

    Map<String, Boolean> permissions(UUID uuid, boolean authenticated) {
        return authenticated && members.contains(uuid) ? moderators : players;
    }

    static String normalize(String node) {
        if (node == null) throw new IllegalArgumentException("Permission must not be null");
        return node.toLowerCase(Locale.ROOT);
    }
}
