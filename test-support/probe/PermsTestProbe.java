package dev.nordfjell.perms.test;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated-test-only plugin. Never install on a real server. */
public final class PermsTestProbe extends JavaPlugin {
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length < 2) return true;
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) { getLogger().warning("NP_NO_PLAYER"); return true; }
        player.getScheduler().run(this, ignored -> {
            try {
                switch (args[0]) {
                    case "state" -> getLogger().info("NP_STATE " + player.getName() + " "
                        + player.hasPermission("nordregen.use") + " " + player.hasPermission("nordauth.admin.resetpassword") + " "
                        + player.hasPermission("probe.child") + " " + player.hasPermission("probe.extra") + " "
                        + player.hasPermission("probe.timed") + " " + player.hasPermission("minecraft.command.op") + " "
                        + player.hasPermission("NORDREGEN.USE") + " " + player.isOp());
                    case "attach" -> {
                        var attachment = player.addAttachment(this);
                        attachment.setPermission("nordregen.use", true);
                        attachment.setPermission("probe.child", true);
                        attachment.setPermission("probe.extra", true);
                        getLogger().info("NP_ATTACHED");
                    }
                    case "timed" -> {
                        player.addAttachment(this, "probe.timed", true, 10);
                        getLogger().info("NP_TIMED");
                    }
                    case "bench" -> {
                        int iterations = 1_000_000;
                        int accepted = 0;
                        for (int i = 0; i < 100_000; i++) player.hasPermission("nordregen.use");
                        long start = System.nanoTime();
                        for (int i = 0; i < iterations; i++) if (player.hasPermission("nordregen.use")) accepted++;
                        long elapsed = System.nanoTime() - start;
                        getLogger().info("NP_BENCH " + player.getName() + " " + iterations + " " + elapsed + " " + accepted);
                    }
                    case "disableauth" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                        Bukkit.getPluginManager().disablePlugin(Bukkit.getPluginManager().getPlugin("NordAuth"));
                        getLogger().info("NP_AUTH_DISABLED");
                    });
                    case "disableprovider" -> Bukkit.getGlobalRegionScheduler().execute(this, () ->
                        Bukkit.getPluginManager().disablePlugin(Bukkit.getPluginManager().getPlugin("NordPerms")));
                    default -> throw new IllegalArgumentException();
                }
            } catch (Throwable error) {
                getLogger().severe("NP_PROBE_FAILED " + error);
            }
        }, null);
        return true;
    }
}
