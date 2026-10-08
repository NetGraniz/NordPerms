package dev.nordfjell.perms;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.java.JavaPlugin;

public final class NordPermsPlugin extends JavaPlugin implements Listener {
    private final PermissionEngine engine = new PermissionEngine();
    private final Injector injector = new Injector();
    private final PolicyLoader loader = new PolicyLoader();
    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile Policy source;
    private volatile Map<String, Permission> registry = Map.of();
    private ExecutorService io;
    private boolean started;

    @Override public void onEnable() {
        io = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("NordPerms-config").daemon(true).factory());
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        try {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) throw new IllegalStateException("Remove LuckPerms before starting NordPerms; never run both");
            injector.validatePlatform();
            updateRegistry();
            engine.auth = new AuthGate(Bukkit.getPluginManager().getPlugin("NordAuth"), Bukkit.getOnlineMode());
            source = loader.load(getDataFolder().toPath().resolve("config.yml"));
            engine.policy.set(PermissionEngine.expand(source, registry));
            started = true;
            if (!engine.auth.ready()) getLogger().warning("No trusted identity provider: moderator permissions will remain denied");
            getLogger().info("NordPerms ready; " + source.members().size() + " moderator UUIDs. Reload is console-only.");
        } catch (Exception error) {
            getLogger().severe("NordPerms startup refused: " + error.getClass().getSimpleName()
                + ". Check config.yml and provider/platform compatibility; arbitrary YAML contents are not logged.");
            engine.policy.set(null);
            // Keep the plugin loaded to reject logins; console can repair a malformed config.
        }
        if (!Bukkit.getOnlinePlayers().isEmpty()) fatal("Live plugin loading is unsupported; use a full restart");
    }

    @Override public void onDisable() {
        closing.set(true);
        engine.policy.set(null);
        if (io != null) io.shutdownNow();
        // Restoring vanilla defaults during a hot disable could expose administrative permissions.
        if (started && !Bukkit.isStopping()) Bukkit.shutdown();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void login(PlayerConnectionValidateLoginEvent event) {
        var connection = event.getConnection();
        var profile = connection instanceof PlayerLoginConnection login ? login.getUnsafeProfile()
            : connection instanceof PlayerConfigurationConnection configuration ? configuration.getProfile() : null;
        var id = profile == null ? null : profile.getId();
        boolean operator = id != null && Bukkit.getOperators().stream().anyMatch(op -> id.equals(op.getUniqueId()));
        if (engine.policy.get() == null || operator) {
            event.kickMessage(Component.text("Permission provider unavailable or OP account unsupported. Contact the administrator."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) {
        if (event.getPlayer().isOp() || engine.policy.get() == null) {
            event.getPlayer().kick(Component.text("Permission provider unavailable or OP account unsupported."));
            return;
        }
        try {
            injector.install(event.getPlayer(), engine);
            injector.verify(event.getPlayer());
            event.getPlayer().updateCommands();
        } catch (Exception error) { fatal("Cannot install or verify permissible on join"); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectAccounts(PlayerCommandPreprocessEvent event) {
        String[] arguments = event.getMessage().substring(1).trim().split("\\s+", 4);
        String label = arguments[0].toLowerCase(java.util.Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        Policy snapshot = engine.policy.get();
        if (label.equals("register") && snapshot != null && snapshot.members().contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Staff accounts must already be registered before their UUID is added to NordPerms. Contact the console administrator.");
            return;
        }
        if (!label.equals("resetpassword") || arguments.length < 2) return;
        Player target = Bukkit.getPlayerExact(arguments[1]);
        // Offline names cannot be mapped to a trusted UUID without an identity directory.
        // Console retains offline recovery; moderators can only reset online ordinary players.
        if (snapshot == null || target == null || target.getUniqueId().equals(event.getPlayer().getUniqueId())
            || snapshot.members().contains(target.getUniqueId()) || target.isOp()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Staff/self/offline password recovery is console-only. Moderators may reset online ordinary players only.");
        }
    }

    @EventHandler
    public void enabled(PluginEnableEvent event) {
        if (closing.get()) return;
        if (event.getPlugin().getName().equals("LuckPerms")) { fatal("Conflicting LuckPerms provider enabled"); return; }
        if (event.getPlugin().getName().equals("NordAuth")) {
            engine.auth = new AuthGate(event.getPlugin(), Bukkit.getOnlineMode());
        }
        try {
            updateRegistry();
            if (source != null) engine.policy.set(PermissionEngine.expand(source, registry));
        } catch (Exception error) { fatal("Cannot expand registered permission children"); }
    }

    private void updateRegistry() {
        Map<String, Permission> copy = new HashMap<>();
        Bukkit.getPluginManager().getPermissions().forEach(permission -> copy.put(Policy.normalize(permission.getName()), permission));
        registry = Map.copyOf(copy);
    }

    private void fatal(String message) {
        closing.set(true);
        engine.policy.set(null);
        getLogger().severe(message + "; stopping the server rather than granting fallback rights");
        Bukkit.shutdown();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("NordPerms management is available only from the server console.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            Policy active = engine.policy.get();
            sender.sendMessage("NordPerms: " + (active == null ? "UNAVAILABLE" : "ready; moderator UUIDs=" + active.members().size())
                + "; trusted identity=" + (engine.auth != null && engine.auth.ready()));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) return false;
        if (closing.get() || Bukkit.isStopping()) { sender.sendMessage("Refused: server is stopping."); return true; }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) { sender.sendMessage("Refused: LuckPerms is present."); return true; }
        if (!loading.compareAndSet(false, true)) { sender.sendMessage("NordPerms reload already in progress."); return true; }
        io.execute(() -> {
            try {
                Policy candidate = loader.load(getDataFolder().toPath().resolve("config.yml"));
                // Only preparation runs on this worker; publication is serialized on the global scheduler.
                Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                    try {
                        if (closing.get() || !isEnabled() || Bukkit.isStopping()) return;
                        updateRegistry();
                        Policy expanded = PermissionEngine.expand(candidate, registry);
                        source = candidate;
                        engine.policy.set(expanded);
                        started = true;
                        Bukkit.getOnlinePlayers().forEach(player -> player.getScheduler().run(this, ignored -> player.updateCommands(), null));
                        getLogger().info("NordPerms reloaded; moderator UUIDs=" + candidate.members().size());
                    } catch (Exception error) { getLogger().warning("NordPerms reload rejected; previous policy retained: " + error.getClass().getSimpleName()); }
                    finally { loading.set(false); }
                });
            } catch (Exception error) {
                // Do not echo arbitrary YAML contents into logs (it may contain a pasted secret).
                getLogger().warning("NordPerms reload rejected; previous policy retained: " + error.getClass().getSimpleName());
                loading.set(false);
            }
        });
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return sender instanceof ConsoleCommandSender && args.length == 1 ? List.of("reload", "status") : List.of();
    }
}
