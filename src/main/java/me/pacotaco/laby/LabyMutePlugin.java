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

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * LabyMutePlugin - Version 2.4
 * MySQL-backed mute system; Discord integration uses a plain webhook URL.
 */
public class LabyMutePlugin extends JavaPlugin implements CommandExecutor, Listener {

    private DatabaseManager    db;
    private LabyVoiceManager   voice;
    private DiscordWebhook     discord;
    private MuteCommandHandler commands;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ZoneId displayZone;
        try {
            displayZone = ZoneId.of(getConfig().getString("display-timezone", "UTC"));
        } catch (DateTimeException e) {
            getLogger().warning("Invalid display-timezone in config.yml — falling back to UTC.");
            displayZone = ZoneId.of("UTC");
        }

        db       = new DatabaseManager(this, displayZone);
        voice    = new LabyVoiceManager();
        discord  = new DiscordWebhook(this);
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
        getLogger().info("LabyMute v2.4 Enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        return switch (cmd.getName().toLowerCase()) {
            case "labyreload" -> commands.handleReload(sender);
            case "labymute"   -> commands.handleMute(sender, args);
            case "labyunmute" -> commands.handleUnmute(sender, args);
            case "labyhist"   -> commands.handleHistory(sender, args);
            case "labyprune"  -> commands.handlePrune(sender, args);
            default           -> false;
        };
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            DatabaseManager.ActiveMute mute = db.getActiveMute(player.getUniqueId());
            if (mute != null) {
                // Delay to allow LabyMod handshake to complete; guard against disconnect during delay
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (player.isOnline()) voice.mute(player, mute.reason(), mute.expiry().toEpochMilli());
                }, 60L);
            }
        });
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (db != null) db.close();
    }
}
