package me.pacotaco.laby;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

public class DatabaseManager {

    private static final long PERMANENT_EXPIRY = LabyMutePlugin.PERMANENT_EXPIRY;

    private final LabyMutePlugin plugin;
    private final SimpleDateFormat estFormat;

    public DatabaseManager(LabyMutePlugin plugin, SimpleDateFormat estFormat) {
        this.plugin = plugin;
        this.estFormat = estFormat;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder() + "/mutes.db");
    }

    public void setupDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            try (Connection conn = getConnection(); Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS mutes (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "uuid TEXT, target_name TEXT, staff_name TEXT, reason TEXT, expiry LONG, " +
                        "active BOOLEAN, created_at LONG, unmuted_by TEXT)");
                s.execute("PRAGMA journal_mode=WAL;");
            }
        } catch (ClassNotFoundException | SQLException e) {
            plugin.getLogger().severe("Could not initialize database!");
            e.printStackTrace();
        }
    }

    public void saveMuteToDB(UUID uuid, String name, String staff, String reason, long expiry, long now) {
        executeUpdate(
                "INSERT INTO mutes (uuid, target_name, staff_name, reason, expiry, active, created_at) VALUES (?, ?, ?, ?, ?, 1, ?)",
                uuid.toString(), name, staff, reason, expiry, now);
    }

    public boolean executeUpdate(String query, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Database update failed: " + e.getMessage());
            return false;
        }
    }

    /** Returns true if the entry was found and deleted. */
    public boolean pruneEntry(int id, String target) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM mutes WHERE id = ? AND (LOWER(target_name) = LOWER(?) OR uuid = ?)")) {
            ps.setInt(1, id);
            ps.setString(2, target);
            ps.setString(3, target);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to prune entry #" + id + ": " + e.getMessage());
            return false;
        }
    }

    public boolean isCurrentlyMuted(String uuid, String name) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM mutes WHERE (uuid = ? OR LOWER(target_name) = LOWER(?)) AND active = 1 AND expiry > ?")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** Runs async; sends results back on main thread via Bukkit scheduler. */
    public void showHistoryPage(CommandSender sender, String target, int page) {
        final int pageSize = 8;
        int offset = (page - 1) * pageSize;

        try (Connection conn = getConnection()) {
            int total = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM mutes WHERE LOWER(target_name) = LOWER(?) OR uuid = ?")) {
                ps.setString(1, target);
                ps.setString(2, target);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            if (total == 0) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§cNo history found for " + target));
                return;
            }

            final int finalTotal = total;
            Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§2§lLabyMod History: §a" + target + " §7(" + finalTotal + " entries)"));

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM mutes WHERE LOWER(target_name) = LOWER(?) OR uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, target);
                ps.setString(2, target);
                ps.setInt(3, pageSize);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    long now = System.currentTimeMillis();
                    while (rs.next()) {
                        int id        = rs.getInt("id");
                        long expiry   = rs.getLong("expiry");
                        long created  = rs.getLong("created_at");
                        String staff  = rs.getString("staff_name");
                        String reason = rs.getString("reason");
                        String unmutedBy = rs.getString("unmuted_by");

                        String statusText;
                        String timeLeftText = "";

                        if (unmutedBy != null) {
                            statusText = unmutedBy.contains("Overwritten") ? "§c[Overwritten]" : "§7[Unmuted]";
                        } else if (expiry >= PERMANENT_EXPIRY) {
                            statusText = "§4[Permanent]";
                        } else if (now >= expiry) {
                            statusText = "§8[Expired]";
                        } else {
                            statusText = "§c[Active]";
                            timeLeftText = " §8(§e" + TimeUtil.formatLongTime(expiry - now) + " left§8)";
                        }

                        final String msg = "§8§m--------------------------------------\n" +
                                "§6ID: #" + id + "  " + statusText + timeLeftText + " §7Staff: " + staff + "\n" +
                                "§7Reason: §f" + reason + "\n" +
                                "§7Date: §f" + estFormat.format(new Date(created)) +
                                (expiry < PERMANENT_EXPIRY ? "\n§7Length: §f" + TimeUtil.formatLongTime(expiry - created) : "") +
                                (unmutedBy != null && !unmutedBy.contains("Overwritten") ? "\n§7Unmuted by: §f" + unmutedBy : "");

                        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(msg));
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage("§8§m--------------------------------------");
                        if (finalTotal > offset + pageSize)
                            sender.sendMessage("§7View more: §f/labyhist " + target + " " + (page + 1));
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load history for " + target + ": " + e.getMessage());
        }
    }
}
