package me.pacotaco.laby;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private DateTimeFormatter displayFormatter;
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
        config.setDriverClassName("me.pacotaco.laby.libs.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=UTF-8"
                + "&connectionTimeZone=UTC"
                + "&forceConnectionTimeZoneToSession=true");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);

        Logger.getLogger("me.pacotaco.laby.libs.hikari").setLevel(Level.WARNING);

        try {
            // Build pool as a local first — only assign to dataSource after full success
            // so that a PoolInitializationException (RuntimeException) or any other failure
            // never leaves the plugin in a half-initialised state.
            HikariDataSource newSource = new HikariDataSource(config);
            try (Connection conn = newSource.getConnection(); Statement s = conn.createStatement()) {
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

                // Add unmute_reason column if this is an older install without it
                try (ResultSet rs = conn.getMetaData().getColumns(null, null, "mutes", "unmute_reason")) {
                    if (!rs.next()) {
                        s.execute("ALTER TABLE mutes ADD COLUMN unmute_reason TEXT DEFAULT NULL");
                        plugin.getLogger().info("Added unmute_reason column to mutes table.");
                    }
                }

                // Connection tracking table — maps UUID+IP pairs for alt-account detection
                // ip is stored as a SHA-256 hex hash (CHAR(64)) — never stored in plain text
                s.execute("CREATE TABLE IF NOT EXISTS connections (" +
                        "uuid CHAR(36) NOT NULL, " +
                        "ip CHAR(64) NOT NULL, " +
                        "name VARCHAR(16) NOT NULL, " +
                        "last_seen DATETIME NOT NULL, " +
                        "PRIMARY KEY (uuid, ip), " +
                        "INDEX idx_conn_ip (ip)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

                // Migrate plain-text IPs to SHA-256 hashes (column was VARCHAR(45) before v2.7)
                try (ResultSet colRs = conn.getMetaData().getColumns(null, null, "connections", "ip")) {
                    if (colRs.next() && colRs.getInt("COLUMN_SIZE") <= 45) {
                        plugin.getLogger().info("Migrating connections table: plain-text IPs → SHA-256 hashes...");
                        List<String[]> rows = new ArrayList<>();
                        try (ResultSet rs = s.executeQuery("SELECT uuid, ip FROM connections")) {
                            while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2)});
                        }
                        s.execute("ALTER TABLE connections MODIFY ip CHAR(64) NOT NULL");
                        try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE connections SET ip = ? WHERE uuid = ? AND ip = ?")) {
                            for (String[] row : rows) {
                                upd.setString(1, hashIp(row[1]));
                                upd.setString(2, row[0]);
                                upd.setString(3, row[1]);
                                upd.executeUpdate();
                            }
                        }
                        plugin.getLogger().info("IP migration complete.");
                    }
                }
            }
            // Everything succeeded — publish the new pool
            dataSource = newSource;
            plugin.getLogger().info("Connected to MySQL database.");
        } catch (Exception e) {
            plugin.getLogger().severe("Could not connect to MySQL database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    /**
     * Updates the timezone formatter and attempts to reconnect using the current config values.
     * The old pool is kept alive until the new one is fully established; if reconnect fails,
     * the old pool is restored so in-flight queries are unaffected.
     */
    public void reinitialize(ZoneId newZone) {
        this.displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a (z)").withZone(newZone);

        HikariDataSource oldSource = this.dataSource;
        this.dataSource = null;      // setupDatabase() assigns on success only
        setupDatabase();

        if (this.dataSource != null) {
            // New pool is ready — close old one
            if (oldSource != null && !oldSource.isClosed()) oldSource.close();
        } else {
            // Reconnect failed — restore old pool and warn
            this.dataSource = oldSource;
            plugin.getLogger().warning("Database reconnect failed during reload — continuing with existing connection.");
        }
    }

    /** Returns the active mute for the given UUID, or null if the player is not currently muted. */
    public ActiveMute getActiveMute(UUID uuid) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT reason, expiry FROM mutes WHERE uuid = ? AND active = 1 AND expiry > UTC_TIMESTAMP()")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp expiryTs = rs.getTimestamp("expiry");
                    Instant expiry = (expiryTs != null) ? expiryTs.toInstant() : TimeUtil.PERMANENT_EXPIRY;
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

    /** SHA-256 hashes an IP address. The hash is what gets stored and queried — the raw IP never touches the DB. */
    private static String hashIp(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(ip.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e); // can't happen on any standard JVM
        }
    }

    /** Records or refreshes a player's UUID↔IP mapping. The IP is hashed before storage. */
    public void recordConnection(UUID uuid, String ip, String name) {
        executeUpdate(
                "INSERT INTO connections (uuid, ip, name, last_seen) VALUES (?, ?, ?, UTC_TIMESTAMP()) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = UTC_TIMESTAMP()",
                uuid.toString(), hashIp(ip), name);
    }

    /**
     * Returns an active mute associated with any UUID that has connected from the given IP,
     * or null if no IP-linked mute exists. Used as a fallback when UUID lookup finds nothing.
     */
    public ActiveMute getActiveMuteByIP(String ip) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT m.reason, m.expiry FROM mutes m " +
                     "JOIN connections c ON c.uuid = m.uuid " +
                     "WHERE c.ip = ? AND m.active = 1 AND m.expiry > UTC_TIMESTAMP() LIMIT 1")) {
            ps.setString(1, hashIp(ip));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp expiryTs = rs.getTimestamp("expiry");
                    Instant expiry = (expiryTs != null) ? expiryTs.toInstant() : TimeUtil.PERMANENT_EXPIRY;
                    return new ActiveMute(rs.getString("reason"), expiry);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query active mute by IP: " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the display names of all other accounts that have connected from an IP
     * also used by the given UUID. IPs are never returned — only names.
     */
    public List<String> getLinkedAccountNames(String uuidStr) {
        List<String> names = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT c2.name FROM connections c1 " +
                     "JOIN connections c2 ON c1.ip = c2.ip " +
                     "WHERE c1.uuid = ? AND c2.uuid != ? " +
                     "ORDER BY c2.last_seen DESC")) {
            ps.setString(1, uuidStr);
            ps.setString(2, uuidStr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get linked accounts for " + uuidStr + ": " + e.getMessage());
        }
        return names;
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

            // --- Resolve UUID: check mutes first, then connections (covers IP-muted players with no direct history) ---
            String resolvedUuid = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid FROM mutes WHERE LOWER(target_name) = LOWER(?) OR uuid = ? LIMIT 1")) {
                ps.setString(1, target);
                ps.setString(2, target);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) resolvedUuid = rs.getString("uuid");
                }
            }
            if (resolvedUuid == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT uuid FROM connections WHERE LOWER(name) = LOWER(?) OR uuid = ? LIMIT 1")) {
                    ps.setString(1, target);
                    ps.setString(2, target);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) resolvedUuid = rs.getString("uuid");
                    }
                }
            }
            final String finalResolvedUuid = resolvedUuid;

            // --- Canonical display name (mutes table → connections table → raw input) ---
            String canonical = getCanonicalName(resolvedUuid != null ? resolvedUuid : target, target);
            if (canonical == null && resolvedUuid != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT name FROM connections WHERE uuid = ? ORDER BY last_seen DESC LIMIT 1")) {
                    ps.setString(1, resolvedUuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) canonical = rs.getString("name");
                    }
                }
            }
            final String displayName = (canonical != null) ? canonical : target;

            // --- Direct mute count ---
            int total = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM mutes WHERE LOWER(target_name) = LOWER(?) OR uuid = ?")) {
                ps.setString(1, target);
                ps.setString(2, target);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            // --- IP-linked mutes: mutes on other accounts that share an IP with this player ---
            List<String> ipLinkedLines = new ArrayList<>();
            if (resolvedUuid != null && page == 1) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT m.reason, m.expiry, m.staff_name, m.unmuted_by, c2.name AS origin_name " +
                        "FROM mutes m " +
                        "JOIN connections c2 ON c2.uuid = m.uuid " +
                        "JOIN connections c1 ON c1.ip = c2.ip " +
                        "WHERE c1.uuid = ? AND c2.uuid != ? " +
                        "ORDER BY m.id DESC LIMIT 10")) {
                    ps.setString(1, resolvedUuid);
                    ps.setString(2, resolvedUuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        Instant now = Instant.now();
                        while (rs.next()) {
                            Timestamp expiryTs  = rs.getTimestamp("expiry");
                            Instant   expiry    = (expiryTs != null) ? expiryTs.toInstant() : TimeUtil.PERMANENT_EXPIRY;
                            String    unmutedBy = rs.getString("unmuted_by");
                            String statusText;
                            if (unmutedBy != null) {
                                statusText = unmutedBy.contains("Overwritten") ? "§c[Overwritten]" : "§7[Unmuted]";
                            } else if (!expiry.isBefore(TimeUtil.PERMANENT_EXPIRY)) {
                                statusText = "§4[Permanent]";
                            } else if (!now.isBefore(expiry)) {
                                statusText = "§8[Expired]";
                            } else {
                                statusText = "§c[Active]";
                            }
                            ipLinkedLines.add("§8§m--------------------------------------\n" +
                                    statusText + " §7Via account: §f" + rs.getString("origin_name") +
                                    " §7Staff: " + rs.getString("staff_name") + "\n" +
                                    "§7Reason: §f" + rs.getString("reason"));
                        }
                    }
                }
            }

            // --- Nothing found at all ---
            if (total == 0 && ipLinkedLines.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§cNo history found for " + target));
                return;
            }

            // --- Pagination guard (direct mutes only) ---
            if (total > 0 && offset >= total) {
                int maxPage = (int) Math.ceil((double) total / pageSize);
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§cPage " + page + " does not exist. Max page: " + maxPage + "."));
                return;
            }

            final int finalTotal = total;
            List<String> lines = new ArrayList<>();
            lines.add("§2§lLabyMod History: §a" + displayName + " §7(" + finalTotal + " direct entries)");

            // --- Direct mute entries ---
            if (total > 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM mutes WHERE LOWER(target_name) = LOWER(?) OR uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                    ps.setString(1, target);
                    ps.setString(2, target);
                    ps.setInt(3, pageSize);
                    ps.setInt(4, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        Instant now = Instant.now();
                        while (rs.next()) {
                            int       id           = rs.getInt("id");
                            Timestamp expiryTs     = rs.getTimestamp("expiry");
                            Timestamp createdTs    = rs.getTimestamp("created_at");
                            Instant   expiry       = (expiryTs != null)  ? expiryTs.toInstant()  : TimeUtil.PERMANENT_EXPIRY;
                            Instant   createdAt    = (createdTs != null) ? createdTs.toInstant() : Instant.EPOCH;
                            String    staff        = rs.getString("staff_name");
                            String    reason       = rs.getString("reason");
                            String    unmutedBy    = rs.getString("unmuted_by");
                            String    unmuteReason = rs.getString("unmute_reason");

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
                                            ? "\n§7Unmuted by: §f" + unmutedBy +
                                              (unmuteReason != null ? " §8(§7" + unmuteReason + "§8)" : "")
                                            : ""));
                        }
                    }
                }
                lines.add("§8§m--------------------------------------");
                if (finalTotal > offset + pageSize)
                    lines.add("§7View more: §f/labyhist " + displayName + " " + (page + 1));
            }

            // --- IP-linked mutes section (page 1 only) ---
            if (!ipLinkedLines.isEmpty()) {
                lines.add("§8§m--------------------------------------");
                lines.add("§6IP-Linked Mutes §7(caught via associated account):");
                lines.addAll(ipLinkedLines);
                lines.add("§8§m--------------------------------------");
            }

            // --- Linked accounts section (page 1 only) ---
            if (finalResolvedUuid != null && page == 1) {
                List<String> linked = getLinkedAccountNames(finalResolvedUuid);
                if (!linked.isEmpty()) {
                    lines.add("§6Linked Accounts §7(via shared connection):");
                    for (String linkedName : linked) lines.add("  §7- §f" + linkedName);
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String line : lines) sender.sendMessage(line);
            });

        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load history for " + target + ": " + e.getMessage());
        }
    }
}
