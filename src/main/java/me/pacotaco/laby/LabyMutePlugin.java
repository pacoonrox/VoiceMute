package me.pacotaco.laby;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * LabyMutePlugin - Version 2.1
 * Refactored into separate classes; Discord integration now uses a plain webhook URL.
 */
public class LabyMutePlugin extends JavaPlugin implements CommandExecutor, Listener {

    public static final long PERMANENT_EXPIRY  = 32503680000000L;
    public static final long ONE_YEAR_MILLIS   = 31536000000L;

    private DatabaseManager    db;
    private LabyVoiceManager   voice;
    private DiscordWebhook     discord;
    private MuteCommandHandler commands;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        SimpleDateFormat estFormat = new SimpleDateFormat("yyyy-MM-dd h:mm a (z)");
        estFormat.setTimeZone(TimeZone.getTimeZone("America/New_York"));

        db      = new DatabaseManager(this, estFormat);
        voice   = new LabyVoiceManager();
        discord = new DiscordWebhook(this);
        commands = new MuteCommandHandler(this, db, voice, discord);

        db.setupDatabase();

        for (String cmdName : new String[]{"labymute", "labyunmute", "labyhist", "labyreload", "labyprune"}) {
            org.bukkit.command.PluginCommand cmd = getCommand(cmdName);
            if (cmd != null) {
                cmd.setExecutor(this);
            } else {
                getLogger().severe("CRITICAL: Command '/" + cmdName + "' not found in plugin.yml!");
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("LabyMute v2.1 Enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        return switch (cmd.getName().toLowerCase()) {
            case "labyreload"  -> commands.handleReload(sender);
            case "labymute"    -> commands.handleMute(sender, args);
            case "labyunmute"  -> commands.handleUnmute(sender, args);
            case "labyhist"    -> commands.handleHistory(sender, args);
            case "labyprune"   -> commands.handlePrune(sender, args);
            default            -> false;
        };
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT reason, expiry FROM mutes WHERE uuid = ? AND active = 1 AND expiry > ?")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String reason = rs.getString("reason");
                        long expiry   = rs.getLong("expiry");
                        // Delay to allow LabyMod handshake to complete
                        Bukkit.getScheduler().runTaskLater(this, () -> voice.mute(player, reason, expiry), 60L);
                    }
                }
            } catch (SQLException e) {
                getLogger().severe("Failed to re-apply mute on join for " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
    }
}
