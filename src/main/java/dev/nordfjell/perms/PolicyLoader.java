package dev.nordfjell.perms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class PolicyLoader {
    private static final int MAX_BYTES = 1_048_576;
    private static final Pattern NODE = Pattern.compile("[a-z0-9_:-]+(?:\\.[a-z0-9_:-]+)*");

    Policy load(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) throw new IllegalArgumentException("Config exceeds 1 MiB");
        // Bounded read even if an editor replaces/grows the file during reload.
        byte[] data;
        try (var input = Files.newInputStream(path)) { data = input.readNBytes(MAX_BYTES + 1); }
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Config exceeds 1 MiB");
        return parse(new String(data, StandardCharsets.UTF_8));
    }

    Policy parse(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(12);
        options.setCodePointLimit(MAX_BYTES);
        Map<String, Object> root = mapping(new Yaml(new SafeConstructor(options)).load(text), "root");
        keys(root, Set.of("schema-version", "groups", "members"), "root");
        if (!Integer.valueOf(1).equals(root.get("schema-version"))) throw new IllegalArgumentException("schema-version must be 1");
        Map<String, Object> groups = mapping(root.get("groups"), "groups");
        keys(groups, Set.of("players", "moderators"), "groups");
        Map<String, Boolean> players = permissions(groups.get("players"), "players");
        Map<String, Boolean> moderators = permissions(groups.get("moderators"), "moderators");
        Map<String, Object> rawMembers = mapping(root.get("members"), "members");
        if (rawMembers.size() > 10_000) throw new IllegalArgumentException("Too many members");
        Set<UUID> members = new HashSet<>();
        for (var entry : rawMembers.entrySet()) {
            UUID id;
            try { id = UUID.fromString(entry.getKey()); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Member keys must be UUIDs"); }
            if (!id.toString().equals(entry.getKey()) || !"moderators".equals(entry.getValue())) {
                throw new IllegalArgumentException("Use canonical lowercase UUID: moderators");
            }
            members.add(id);
        }
        return Policy.create(players, moderators, members);
    }

    private Map<String, Boolean> permissions(Object value, String name) {
        Map<String, Object> group = mapping(value, name);
        keys(group, Set.of("permissions"), name);
        Map<String, Object> nodes = mapping(group.get("permissions"), name + ".permissions");
        if (nodes.size() > 10_000) throw new IllegalArgumentException("Too many permissions");
        Map<String, Boolean> result = new HashMap<>();
        for (var entry : nodes.entrySet()) {
            String node = entry.getKey();
            if (node.length() > 200 || !NODE.matcher(node).matches() || !(entry.getValue() instanceof Boolean allowed)) {
                throw new IllegalArgumentException("Permission keys must be lowercase explicit nodes, values must be booleans");
            }
            result.put(node, allowed);
        }
        return result;
    }

    private static Map<String, Object> mapping(Object object, String name) {
        if (!(object instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " must be a mapping (use {} for empty)");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException(name + " keys must be strings");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void keys(Map<String, ?> map, Set<String> expected, String name) {
        if (!map.keySet().equals(expected)) throw new IllegalArgumentException(name + " must contain exactly " + expected);
    }
}
