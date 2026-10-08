package dev.nordfjell.perms;

import java.lang.reflect.Method;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Narrow, fail-closed bridge to the existing NordAuth 1.3.x session check. */
final class AuthGate {
    private final Plugin auth;
    private final Method check;
    private final boolean onlineMode;

    AuthGate(Plugin auth, boolean onlineMode) {
        this.auth = auth;
        this.onlineMode = onlineMode;
        Method method = null;
        if (auth != null && auth.getClass().getName().equals("dev.nordfjell.auth.NordAuthPlugin")) {
            try {
                method = auth.getClass().getDeclaredMethod("isAuthenticated", Player.class);
                if (method.getReturnType() != boolean.class || !method.trySetAccessible()) method = null;
            } catch (ReflectiveOperationException | RuntimeException ignored) { method = null; }
        }
        this.check = method;
    }

    boolean authenticated(Player player) {
        if (auth == null) return onlineMode;
        if (check == null || !auth.isEnabled()) return false;
        try { return Boolean.TRUE.equals(check.invoke(auth, player)); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    boolean ready() { return auth == null ? onlineMode : check != null && auth.isEnabled(); }
}
