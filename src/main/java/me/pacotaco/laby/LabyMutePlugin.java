package me.pacotaco.laby;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LabyMutePlugin - Version 2.5
 * MySQL-backed mute system; Discord integration uses a plain webhook URL.
 */
public class LabyMutePlugin extends JavaPlugin implements CommandExecutor, Listener {

    private DatabaseManager    db;
    private LabyVoiceManager   voice;
    private DiscordWebhook     discord;
    private MuteCommandHandler commands;

    /** UUIDs of online players who currently have a LabyMod mute applied. */
    private final Set<UUID> activeMutes = ConcurrentHashMap.newKeySet();

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
        voice    = new LabyVoiceManager(this);
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

        // Poll every 5 seconds: apply new mutes picked up from DB, and lift expired/removed ones
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                DatabaseManager.ActiveMute mute = db.getActiveMute(uuid);
                if (mute != null && activeMutes.add(uuid)) {
                    // Mute exists in DB but wasn't tracked — apply it
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) voice.mute(player, mute.reason(), mute.expiry().toEpochMilli());
                    });
                } else if (mute == null && activeMutes.remove(uuid)) {
                    // Mute was removed/expired in DB — lift it
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) voice.unmute(player);
                    });
                }
            }
        }, 100L, 100L); // 5-second interval

        if (!ZoneId.systemDefault().equals(ZoneOffset.UTC)) {
            getLogger().warning("JVM timezone is " + ZoneId.systemDefault() +
                    ", not UTC. Add -Duser.timezone=UTC to your start command to prevent timestamp issues.");
        }

        getLogger().info("LabyMute v2.5 Enabled.");
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
        // Capture IP on the main thread — getAddress() is not safe to call async
        String ip = (player.getAddress() != null)
                ? player.getAddress().getAddress().getHostAddress()
                : null;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (ip != null) db.recordConnection(player.getUniqueId(), ip, player.getName());

            // UUID check first; fall back to IP-based check for alt accounts
            DatabaseManager.ActiveMute mute = db.getActiveMute(player.getUniqueId());
            if (mute == null && ip != null) mute = db.getActiveMuteByIP(ip);

            if (mute != null) {
                activeMutes.add(player.getUniqueId());
                final DatabaseManager.ActiveMute finalMute = mute;
                // Delay to allow LabyMod handshake to complete; guard against disconnect during delay
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (player.isOnline()) voice.mute(player, finalMute.reason(), finalMute.expiry().toEpochMilli());
                }, 60L);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        activeMutes.remove(event.getPlayer().getUniqueId());
    }

    void markMuted(UUID uuid)   { activeMutes.add(uuid); }
    void markUnmuted(UUID uuid) { activeMutes.remove(uuid); }

    /** Full config reload: re-reads YAML, reconnects to DB with any new credentials, updates timezone formatter. */
    public void reload(CommandSender sender) {
        reloadConfig();
        ZoneId newZone;
        try {
            newZone = ZoneId.of(getConfig().getString("display-timezone", "UTC"));
        } catch (DateTimeException e) {
            sender.sendMessage("§e[LabyMute] Warning: Invalid display-timezone in config — keeping UTC.");
            newZone = ZoneId.of("UTC");
        }
        db.reinitialize(newZone);
        sender.sendMessage("§8[§6LabyMute§8] §aConfiguration reloaded — database reconnected.");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (db != null) db.close();
    }
}
