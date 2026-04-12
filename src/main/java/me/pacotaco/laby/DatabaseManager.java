package me.pacotaco.laby;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager {

    public record ActiveMute(String reason, Instant expiry) {}

    private final LabyMutePlugin plugin;
    private final DateTimeFormatter displayFormatter;
    private HikariDataSource dataSource;

    public DatabaseManager(LabyMutePlugin plugin, ZoneId displayZone) {
        this.plugin           = plugin;
        this.displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a (z)").withZone(displayZone);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void setupDatabase() {
        String  host     = plugin.getConfig().getString("mysql.host", "localhost");
        int     port     = plugin.getConfig().getInt("mysql.port", 3306);
        String  database = plugin.getConfig().getString("mysql.database", "voicemute");
        String  username = plugin.getConfig().getString("mysql.username", "root");
        String  password = plugin.getConfig().getString("mysql.password", "");
        boolean useSSL   = plugin.getConfig().getBoolean("mysql.ssl", false);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=UTF-8"
                + "&serverTimezone=UTC");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);

        Logger.getLogger("me.pacotaco.laby.libs.hikari").setLevel(Level.WARNING);

        try {
            dataSource = new HikariDataSource(config);
            try (Connection conn = getConnection(); Statement s = conn.createStatement()) {
                // Create table with proper DATETIME columns on fresh installs
                s.execute("CREATE TABLE IF NOT EXISTS mutes (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "uuid CHAR(36) NOT NULL, " +
                        "target_name VARCHAR(16) NOT NULL, " +
                        "staff_name VARCHAR(16) NOT NULL, " +
                        "reason TEXT NOT NULL, " +
                        "expiry DATETIME NOT NULL, " +
                        "active TINYINT(1) NOT NULL DEFAULT 1, " +
                        "created_at DATETIME NOT NULL, " +
                        "unmuted_by VARCHAR(64) DEFAULT NULL, " +
                        "INDEX idx_uuid (uuid), " +
                        "INDEX idx_target_name (target_name), " +
                        "INDEX idx_active_expiry (active, expiry)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

                // Migrate existing installs that used BIGINT epoch-ms columns
                try (ResultSet rs = conn.getMetaData().getColumns(null, null, "mutes", "expiry")) {
                    if (rs.next() && "BIGINT".equalsIgnoreCase(rs.getString("TYPE_NAME"))) {
                        plugin.getLogger().info("Migrating mutes table: BIGINT timestamps → DATETIME...");
                        s.execute("ALTER TABLE mutes ADD COLUMN expiry_dt DATETIME, ADD COLUMN created_dt DATETIME");
                        s.execute("UPDATE mutes SET expiry_dt = FROM_UNIXTIME(expiry / 1000), created_dt = FROM_UNIXTIME(created_at / 1000)");
                        s.execute("ALTER TABLE mutes DROP COLUMN expiry, DROP COLUMN created_at");
                        s.execute("ALTER TABLE mutes RENAME COLUMN expiry_dt TO expiry, RENAME COLUMN created_dt TO created_at");
                        plugin.getLogger().info("Migration complete.");
                    }
                }
            }
            plugin.getLogger().info("Connected to MySQL database.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not connect to MySQL database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    /** Returns the active mute for the given UUID, or null if the player is not currently muted. */
    public ActiveMute getActiveMute(UUID uuid) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT reason, expiry FROM mutes WHERE uuid = ? AND active = 1 AND expiry > UTC_TIMESTAMP()")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Instant expiry = rs.getTimestamp("expiry").toInstant();
                    return new ActiveMute(rs.getString("reason"), expiry);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query active mute for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    public void saveMuteToDB(UUID uuid, String name, String staff, String reason, Instant expiry, Instant createdAt) {
        executeUpdate(
                "INSERT INTO mutes (uuid, target_name, staff_name, reason, expiry, active, created_at) VALUES (?, ?, ?, ?, ?, 1, ?)",
                uuid.toString(), name, staff, reason,
                Timestamp.from(expiry), Timestamp.from(createdAt));
    }

    /**
     * Executes an update and returns the number of rows affected, or -1 on failure.
     * Use the return value to distinguish "no rows matched" (0) from a DB error (-1).
     */
    public int executeUpdate(String query, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Database update failed: " + e.getMessage());
            return -1;
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

    /** Returns the properly-cased name stored in the DB for a given UUID or name, or null if not found. */
    public String getCanonicalName(String uuid, String nameOrUuid) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT target_name FROM mutes WHERE uuid = ? OR LOWER(target_name) = LOWER(?) ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, uuid);
            ps.setString(2, nameOrUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("target_name");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get canonical name: " + e.getMessage());
        }
        return null;
    }

    /** Runs async; sends all results back on the main thread in a single batch. */
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

            if (offset >= total) {
                int maxPage = (int) Math.ceil((double) total / pageSize);
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§cPage " + page + " does not exist. Max page: " + maxPage + "."));
                return;
            }

            String canonical = getCanonicalName(target, target);
            final String displayName = (canonical != null) ? canonical : target;
            final int finalTotal = total;

            List<String> lines = new ArrayList<>();
            lines.add("§2§lLabyMod History: §a" + displayName + " §7(" + finalTotal + " entries)");

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM mutes WHERE LOWER(target_name) = LOWER(?) OR uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, target);
                ps.setString(2, target);
                ps.setInt(3, pageSize);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    Instant now = Instant.now();
                    while (rs.next()) {
                        int     id        = rs.getInt("id");
                        Instant expiry    = rs.getTimestamp("expiry").toInstant();
                        Instant createdAt = rs.getTimestamp("created_at").toInstant();
                        String  staff     = rs.getString("staff_name");
                        String  reason    = rs.getString("reason");
                        String  unmutedBy = rs.getString("unmuted_by");

                        String statusText;
                        String timeLeftText = "";

                        if (unmutedBy != null) {
                            statusText = unmutedBy.contains("Overwritten") ? "§c[Overwritten]" : "§7[Unmuted]";
                        } else if (!expiry.isBefore(TimeUtil.PERMANENT_EXPIRY)) {
                            statusText = "§4[Permanent]";
                        } else if (!now.isBefore(expiry)) {
                            statusText = "§8[Expired]";
                        } else {
                            statusText   = "§c[Active]";
                            timeLeftText = " §8(§e" + TimeUtil.formatDuration(now, expiry) + " left§8)";
                        }

                        lines.add("§8§m--------------------------------------\n" +
                                "§6ID: #" + id + "  " + statusText + timeLeftText + " §7Staff: " + staff + "\n" +
                                "§7Reason: §f" + reason + "\n" +
                                "§7Date: §f" + displayFormatter.format(createdAt) +
                                (expiry.isBefore(TimeUtil.PERMANENT_EXPIRY)
                                        ? "\n§7Length: §f" + TimeUtil.formatDuration(createdAt, expiry) : "") +
                                (unmutedBy != null && !unmutedBy.contains("Overwritten")
                                        ? "\n§7Unmuted by: §f" + unmutedBy : ""));
                    }
                }
            }

            lines.add("§8§m--------------------------------------");
            if (finalTotal > offset + pageSize)
                lines.add("§7View more: §f/labyhist " + displayName + " " + (page + 1));

            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String line : lines) sender.sendMessage(line);
            });

        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load history for " + target + ": " + e.getMessage());
        }
    }
}
