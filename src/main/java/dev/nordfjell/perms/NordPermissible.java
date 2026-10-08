package dev.nordfjell.perms;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.plugin.Plugin;

/** Retains Bukkit's attachment machinery but publishes immutable read snapshots. */
final class NordPermissible extends PermissibleBase {
    private final Player player;
    private final PermissionEngine engine;
    private final PermissibleBase delegate;
    private volatile Map<String, PermissionAttachmentInfo> attached = Map.of();

    NordPermissible(Player player, PermissionEngine engine, PermissibleBase delegate) {
        super(player); // The virtual recalculatePermissions call is intentionally a no-op until delegate is set.
        this.player = player;
        this.engine = engine;
        this.delegate = delegate;
        refresh();
    }

    private void refresh() {
        Map<String, PermissionAttachmentInfo> copy = new HashMap<>();
        delegate.getEffectivePermissions().forEach(info -> copy.put(info.getPermission(), info));
        attached = Map.copyOf(copy);
    }

    @Override public boolean isOp() { return false; }
    @Override public void setOp(boolean value) { throw new UnsupportedOperationException("NordPerms does not manage OP"); }
    @Override public boolean isPermissionSet(String name) {
        String node = Policy.normalize(name);
        Policy snapshot = engine.policy.get();
        return snapshot != null && (engine.permissions(snapshot, player).containsKey(node) || attached.containsKey(node));
    }
    @Override public boolean isPermissionSet(Permission permission) { return isPermissionSet(permission.getName()); }
    @Override public boolean hasPermission(String name) {
        String node = Policy.normalize(name);
        Policy snapshot = engine.policy.get();
        if (snapshot == null) return false;
        Boolean configured = engine.permissions(snapshot, player).get(node);
        if (configured != null) return configured;
        PermissionAttachmentInfo info = attached.get(node);
        if (info != null) return info.getValue();
        Permission registered = Bukkit.getPluginManager().getPermission(node);
        return registered == null ? Permission.DEFAULT_PERMISSION.getValue(false) : registered.getDefault().getValue(false);
    }
    @Override public boolean hasPermission(Permission permission) {
        Policy snapshot = engine.policy.get();
        if (snapshot == null) return false;
        String node = Policy.normalize(permission.getName());
        Boolean configured = engine.permissions(snapshot, player).get(node);
        if (configured != null) return configured;
        PermissionAttachmentInfo info = attached.get(node);
        return info != null ? info.getValue() : permission.getDefault().getValue(false);
    }
    @Override public synchronized void recalculatePermissions() {
        if (delegate == null) return;
        delegate.recalculatePermissions();
        refresh();
    }
    @Override public synchronized void clearPermissions() {
        if (delegate == null) return;
        delegate.clearPermissions();
        attached = Map.of();
    }
    @Override public synchronized PermissionAttachment addAttachment(Plugin plugin) {
        PermissionAttachment result = delegate.addAttachment(plugin);
        refresh();
        return result;
    }
    @Override public synchronized PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        PermissionAttachment attachment = addAttachment(plugin);
        attachment.setPermission(name, value);
        return attachment;
    }
    @Override public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        if (ticks <= 0) throw new IllegalArgumentException("Attachment duration must be positive");
        PermissionAttachment attachment = addAttachment(plugin);
        var task = player.getScheduler().runDelayed(plugin, ignored -> attachment.remove(), null, ticks);
        if (task == null) { attachment.remove(); return null; }
        return attachment;
    }
    @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        PermissionAttachment attachment = addAttachment(plugin, ticks);
        if (attachment != null) attachment.setPermission(name, value);
        return attachment;
    }
    @Override public synchronized void removeAttachment(PermissionAttachment attachment) {
        delegate.removeAttachment(attachment);
        refresh();
    }
    @Override public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        Policy snapshot = engine.policy.get();
        if (snapshot == null) return Set.of();
        Map<String, PermissionAttachmentInfo> copy = new HashMap<>(attached);
        engine.permissions(snapshot, player).forEach((node, value) -> copy.put(node, new PermissionAttachmentInfo(player, node, null, value)));
        return new HashSet<>(copy.values());
    }
}
