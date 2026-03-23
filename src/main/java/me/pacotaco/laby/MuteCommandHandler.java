package me.pacotaco.laby;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

public class MuteCommandHandler {

    private static final String STAFF_PERM = "labymute.use";
    private static final String ADMIN_PERM  = "labymute.admin";

    private final LabyMutePlugin plugin;
    private final DatabaseManager db;
    private final LabyVoiceManager voice;
    private final DiscordWebhook discord;

    public MuteCommandHandler(LabyMutePlugin plugin, DatabaseManager db,
                               LabyVoiceManager voice, DiscordWebhook discord) {
        this.plugin  = plugin;
        this.db      = db;
        this.voice   = voice;
        this.discord = discord;
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    public boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERM)) { sender.sendMessage("§cNo permission."); return true; }
        plugin.reloadConfig();
        sender.sendMessage("§8[§6LabyMute§8] §aConfiguration reloaded!");
        return true;
    }

    public boolean handleMute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(STAFF_PERM)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 2) { sender.sendMessage("§cUsage: /labymute <player> <duration/perm> <reason>"); return true; }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cPlayer must be online to issue a LabyMod mute.");
            return true;
        }

        String reason = (args.length > 2) ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        if (reason.trim().isEmpty()) { sender.sendMessage("§cError: A reason is required."); return true; }

        String durationInput = args[1].toLowerCase();
        long expiry;
        boolean isPerm;

        if (durationInput.equals("perm")) {
            isPerm = true;
            expiry = LabyMutePlugin.PERMANENT_EXPIRY;
        } else {
            long durationMillis = TimeUtil.parseTimeStrict(durationInput);
            if (durationMillis <= 0) { sender.sendMessage("§cInvalid duration format."); return true; }
            if (durationMillis >= LabyMutePlugin.ONE_YEAR_MILLIS) {
                isPerm = true;
                expiry = LabyMutePlugin.PERMANENT_EXPIRY;
                sender.sendMessage("§eNote: Over 1 year defaults to permanent.");
            } else {
                isPerm = false;
                expiry = System.currentTimeMillis() + durationMillis;
            }
        }

        final long finalExpiry = expiry;
        final boolean finalIsPerm = isPerm;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            db.executeUpdate(
                    "UPDATE mutes SET active = 0, unmuted_by = ? WHERE uuid = ? AND active = 1 AND expiry > ?",
                    "Overwritten by " + sender.getName(), target.getUniqueId().toString(), now);

            db.saveMuteToDB(target.getUniqueId(), target.getName(), sender.getName(), reason, finalExpiry, now);

            Bukkit.getScheduler().runTask(plugin, () -> {
                voice.mute(target, reason, finalExpiry);
                String displayTime = finalIsPerm ? "Permanent" : durationInput;
                notifyStaff("§c" + sender.getName() + " §7Laby-muted §c" + target.getName() +
                        " §7(§c" + displayTime + "§7): §c" + reason);
                target.sendMessage("§cYou have been Laby-muted from VoiceChat for: §f" + reason);
            });

            discord.sendMute(target.getName(), finalIsPerm ? "Permanent" : durationInput, reason);
        });
        return true;
    }

    public boolean handleUnmute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(STAFF_PERM)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 1) return false;

        String nameOrUuid = args[0];
        String unmuteReason = (args.length > 1) ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";

        // Capture on main thread — Bukkit.getPlayer() is not thread-safe
        Player target = Bukkit.getPlayer(nameOrUuid);
        String uuidStr = (target != null) ? target.getUniqueId().toString() : nameOrUuid;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {

            if (!db.isCurrentlyMuted(uuidStr, nameOrUuid)) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§cTarget is not currently laby-muted."));
                return;
            }

            db.executeUpdate(
                    "UPDATE mutes SET active = 0, unmuted_by = ? WHERE (uuid = ? OR LOWER(target_name) = LOWER(?)) AND active = 1 AND expiry > ?",
                    sender.getName(), uuidStr, nameOrUuid, System.currentTimeMillis());

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (target != null) {
                    voice.unmute(target);
                    target.sendMessage("§aYour LabyMod VoiceChat mute has been lifted.");
                }
                notifyStaff("§c" + sender.getName() + " §7Laby-unmuted §c" + nameOrUuid);
            });

            discord.sendUnmute(nameOrUuid, sender.getName(), unmuteReason);
        });
        return true;
    }

    public boolean handleHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission(STAFF_PERM)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 1) { sender.sendMessage("§cUsage: /labyhist <player> [page]"); return true; }

        String target = args[0];
        int page = (args.length >= 2) ? Math.max(1, TimeUtil.tryParseInt(args[1])) : 1;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.showHistoryPage(sender, target, page));
        return true;
    }

    public boolean handlePrune(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERM)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 2) { sender.sendMessage("§cUsage: /labyprune <player> <ID>"); return true; }

        String target = args[0];
        int entryId;
        try {
            entryId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid ID — must be a number.");
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean affected = db.pruneEntry(entryId, target);
            Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage(affected
                            ? "§aSuccessfully pruned #" + entryId
                            : "§cNo entry found for #" + entryId));
        });
        return true;
    }

    // -------------------------------------------------------------------------

    private void notifyStaff(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(STAFF_PERM)) p.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(ChatColor.stripColor(message));
    }
}
