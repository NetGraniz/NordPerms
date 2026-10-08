package dev.nordfjell.perms;

import java.lang.reflect.Field;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;

/** One-time CraftBukkit field replacement, conceptually the same integration point as LuckPerms. */
final class Injector {
    void validatePlatform() throws ReflectiveOperationException {
        String craftPackage = org.bukkit.Bukkit.getServer().getClass().getPackageName();
        field(Class.forName(craftPackage + ".entity.CraftHumanEntity"));
    }

    void install(Player player, PermissionEngine engine) throws ReflectiveOperationException {
        Field field = field(player.getClass());
        Object current = field.get(player);
        if (current instanceof NordPermissible) return;
        if (current == null || current.getClass() != PermissibleBase.class) {
            throw new IllegalStateException("Another permission provider replaced this player's permissible");
        }
        field.set(player, new NordPermissible(player, engine, (PermissibleBase) current));
        if (!(field.get(player) instanceof NordPermissible)) throw new IllegalStateException("Permission injection did not stick");
    }

    void verify(Player player) throws ReflectiveOperationException {
        if (!(field(player.getClass()).get(player) instanceof NordPermissible)) {
            throw new IllegalStateException("Permission provider was replaced");
        }
    }

    private Field field(Class<?> type) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField("perm");
                if (field.getType() != PermissibleBase.class || !field.trySetAccessible()) throw new IllegalStateException("Unsupported permissible field");
                return field;
            } catch (NoSuchFieldException ignored) { /* Try CraftHumanEntity's superclass chain. */ }
        }
        throw new NoSuchFieldException("CraftHumanEntity.perm");
    }
}
