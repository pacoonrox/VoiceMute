package me.pacotaco.laby;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
        Instant expiry;
        boolean isPerm;

        if (durationInput.equals("perm")) {
            isPerm = true;
            expiry = TimeUtil.PERMANENT_EXPIRY;
        } else {
            expiry = TimeUtil.parseExpiry(durationInput);
            if (expiry == null) { sender.sendMessage("§cInvalid duration format."); return true; }
            Instant oneYearFromNow = ZonedDateTime.now(ZoneOffset.UTC).plusYears(1).toInstant();
            if (!expiry.isBefore(oneYearFromNow)) {
                isPerm = true;
                expiry = TimeUtil.PERMANENT_EXPIRY;
                sender.sendMessage("§eNote: Over 1 year defaults to permanent.");
            } else {
                isPerm = false;
            }
        }

        final Instant finalExpiry = expiry;
        final boolean finalIsPerm = isPerm;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Instant now = Instant.now();
            db.executeUpdate(
                    "UPDATE mutes SET active = 0, unmuted_by = ? WHERE uuid = ? AND active = 1 AND expiry > UTC_TIMESTAMP()",
                    "Overwritten by " + sender.getName(), target.getUniqueId().toString());

            db.saveMuteToDB(target.getUniqueId(), target.getName(), sender.getName(), reason, finalExpiry, now);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!target.isOnline()) return;
                String displayTime = finalIsPerm ? "Permanent" : durationInput;
                boolean applied = voice.mute(target, reason, finalExpiry.toEpochMilli());
                notifyStaff("§c" + sender.getName() + " §7Laby-muted §c" + target.getName() +
                        " §7(§c" + displayTime + "§7): §c" + reason);
                if (!applied) {
                    sender.sendMessage("§e[LabyMute] §7Note: §f" + target.getName() +
                            " §7is not using LabyMod voice chat — mute saved to DB but not applied live.");
                }
                target.sendMessage("§cYou have been Laby-muted from VoiceChat for: §f" + reason);
            });

            discord.sendMute(target.getName(), finalIsPerm ? "Permanent" : durationInput, reason);
        });
        return true;
    }

    public boolean handleUnmute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(STAFF_PERM)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 1) return false;

        String nameOrUuid   = args[0];
        String unmuteReason = (args.length > 1)
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "No reason specified";

        // Capture on main thread — Bukkit.getPlayer() is not thread-safe
        Player target = Bukkit.getPlayer(nameOrUuid);
        String uuidStr = (target != null) ? target.getUniqueId().toString() : nameOrUuid;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Combine check + deactivate into one UPDATE — eliminates TOCTOU race
            int affected = db.executeUpdate(
                    "UPDATE mutes SET active = 0, unmuted_by = ? " +
                    "WHERE (uuid = ? OR LOWER(target_name) = LOWER(?)) AND active = 1 AND expiry > UTC_TIMESTAMP()",
                    sender.getName(), uuidStr, nameOrUuid);

            if (affected == 0) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§cTarget is not currently laby-muted."));
                return;
            }
            if (affected < 0) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§cDatabase error while attempting to unmute."));
                return;
            }

            String canonical = (target != null) ? target.getName() : db.getCanonicalName(uuidStr, nameOrUuid);
            final String displayName = (canonical != null) ? canonical : nameOrUuid;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (target != null) {
                    voice.unmute(target);
                    target.sendMessage("§aYour LabyMod VoiceChat mute has been lifted.");
                }
                notifyStaff("§c" + sender.getName() + " §7Laby-unmuted §c" + displayName);
            });

            discord.sendUnmute(displayName, sender.getName(), unmuteReason);
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
        Bukkit.getConsoleSender().sendMessage(message.replaceAll("§[0-9a-fk-orA-FK-OR]", ""));
    }
}
