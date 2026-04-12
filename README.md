# VoiceMute (LabyMutePlugin)

A MySQL-backed Bukkit/Spigot plugin for muting players' LabyMod voice chat features. Supports timed and permanent mutes, full history tracking, and optional Discord webhook logging.

## Features
- Timed mutes with flexible duration syntax (`30m`, `7d`, `2w`, etc.) or permanent
- Mutes persist across restarts — players are re-muted on join
- Full per-player mute history with paginated `/labyhist`, including unmute reasons
- Prune individual history entries with `/labyprune`
- Discord webhook notifications for mutes and unmutes (non-blocking async)
- MySQL backend with HikariCP connection pooling — supports multi-server BungeeCord/Velocity networks
- Live config reload via `/labyreload` — reconnects the database and updates the timezone formatter
- Startup warning if JVM timezone is not UTC
- Requires [LabyModServerAPI](https://github.com/LabyMod/labymod4-server-api/releases/download/1.0.9/labymod-server-api-bukkit-1.0.9.jar)

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/labymute <player> <duration\|perm> <reason>` | Mute a player | `labymute.use` |
| `/labyunmute <player> [reason]` | Remove a mute | `labymute.use` |
| `/labyhist <player> [page]` | View mute history | `labymute.use` |
| `/labyprune <player> <id>` | Delete a history entry | `labymute.admin` |
| `/labyreload` | Reload config and reconnect DB | `labymute.admin` |

## Duration Format

| Suffix | Unit |
|--------|------|
| `s` | seconds |
| `m` | minutes |
| `h` | hours |
| `d` | days |
| `w` | weeks |
| `mo` | months |
| `y` | years (1y or more caps to permanent) |

Examples: `30m`, `7d`, `2w`, `1mo`, `perm`

## Permissions

| Permission | Default | Description |
|-----------|---------|-------------|
| `labymute.use` | op | Mute, unmute, history |
| `labymute.admin` | op | Reload and prune |

## Configuration

```yaml
mysql:
  host: localhost
  port: 3306
  database: voicemute
  username: root
  password: ""
  ssl: false

discord:
  enabled: false
  webhook-url: ""

# Timezone used when displaying mute dates in /labyhist
display-timezone: UTC
```

Create the `voicemute` database before starting the plugin. The table and any schema migrations are applied automatically on first launch.

## Installation

1. Place the JAR in your `plugins/` folder
2. Install [LabyMod Server API v1.0.9](https://github.com/LabyMod/labymod4-server-api/releases/download/1.0.9/labymod-server-api-bukkit-1.0.9.jar) as well
3. Start the server — `plugins/LabyMutePlugin/config.yml` is generated automatically
4. Fill in your MySQL credentials and restart, or run `/labyreload`

> **Timezone note:** Add `-Duser.timezone=UTC` to your server start command. The plugin enforces UTC at the JDBC level regardless, but this flag prevents any JVM-level drift and suppresses the startup warning.

## Building

**Requirements:** Java 21, Maven 3.6+

```bash
git clone https://github.com/pacoonrox/VoiceMute.git
cd VoiceMute
mvn package
```

The compiled JAR will be at `target/LabyMutePlugin-2.5.jar`.

## Requirements
- Paper/Spigot 1.21+ (or FlamePaper 1.8.x)
- Java 21 (required to compile)
- MySQL 8.0+ or MariaDB 10.6+
- [LabyMod Server API v1.0.9](https://github.com/LabyMod/labymod4-server-api/releases/download/1.0.9/labymod-server-api-bukkit-1.0.9.jar)
