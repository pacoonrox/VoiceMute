package me.pacotaco.laby;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.UUID;

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
        plugin.reload(sender);
        return true;
    }

    public boolean handleMute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(STAFF_PERM)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 2) { sender.sendMessage("§cUsage: /labymute <player> <duration/perm> <reason>"); return true; }

        // Online lookup first; for offline, pull from usercache via getOfflinePlayer (safe — player has joined before)
        Player onlineTarget = Bukkit.getPlayer(args[0]);
        @SuppressWarnings("deprecation")
        OfflinePlayer offlineTarget = (onlineTarget != null) ? onlineTarget : Bukkit.getOfflinePlayer(args[0]);

        if (onlineTarget == null && !offlineTarget.hasPlayedBefore()) {
            sender.sendMessage("§cPlayer not found. They must have joined the server at least once.");
            return true;
        }

        UUID targetUuid = offlineTarget.getUniqueId();
        String targetName = (offlineTarget.getName() != null) ? offlineTarget.getName() : args[0];
        boolean isOnline = onlineTarget != null;

        String reason = (args.length > 2) ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        if (reason.trim().isEmpty()) { sender.sendMessage("§cError: A reason is required."); return true; }
        if (reason.length() > 512) { sender.sendMessage("§cReason is too long (max 512 characters)."); return true; }

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
                sender.sendMessage("§eNote: 1 year or more defaults to permanent.");
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
                    "Overwritten by " + sender.getName(), targetUuid.toString());

            db.saveMuteToDB(targetUuid, targetName, sender.getName(), reason, finalExpiry, now);

            Bukkit.getScheduler().runTask(plugin, () -> {
                String displayTime = finalIsPerm ? "Permanent" : durationInput;
                if (isOnline) {
                    boolean applied = voice.mute(onlineTarget, reason, finalExpiry.toEpochMilli());
                    plugin.markMuted(targetUuid);
                    notifyStaff("§c" + sender.getName() + " §7Laby-muted §c" + targetName +
                            " §7(§c" + displayTime + "§7): §c" + reason);
                    if (!applied) {
                        sender.sendMessage("§e[LabyMute] §7Note: §f" + targetName +
                                " §7is not using LabyMod voice chat — mute saved to DB but not applied live.");
                    }
                    onlineTarget.sendMessage("§cYou have been Laby-muted from VoiceChat for: §f" + reason);
                } else {
                    notifyStaff("§c" + sender.getName() + " §7Laby-muted §c" + targetName +
                            " §7(§c" + displayTime + "§7): §c" + reason);
                }
            });

            discord.sendMute(targetName, finalIsPerm ? "Permanent" : durationInput, reason);
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
                    "UPDATE mutes SET active = 0, unmuted_by = ?, unmute_reason = ? " +
                    "WHERE (uuid = ? OR LOWER(target_name) = LOWER(?)) AND active = 1 AND expiry > UTC_TIMESTAMP()",
                    sender.getName(), unmuteReason, uuidStr, nameOrUuid);

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
                    plugin.markUnmuted(target.getUniqueId());
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
            sender.sendMessage("§cInvalid ID — must be a positive number.");
            return true;
        }
        if (entryId <= 0) {
            sender.sendMessage("§cInvalid ID — must be a positive number.");
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
