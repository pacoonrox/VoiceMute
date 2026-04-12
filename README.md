# VoiceMute (LabyMutePlugin)

A MySQL-backed Bukkit/Spigot plugin for muting players' LabyMod voice chat features. Supports timed and permanent mutes, full history tracking, and optional Discord webhook logging.

## Features
- Timed mutes with flexible duration syntax (`30m`, `7d`, `2w`, etc.) or permanent
- Mutes persist across restarts — players are re-muted on join
- Full per-player mute history with paginated `/labyhist`
- Prune individual history entries with `/labyprune`
- Discord webhook notifications for mutes and unmutes
- MySQL backend with HikariCP connection pooling — supports multi-server BungeeCord/Velocity networks
- Requires [LabyModServerAPI](https://github.com/LabyMod/labymod4-server-api/releases/download/1.0.9/labymod-server-api-bukkit-1.0.9.jar)

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/labymute <player> <duration\|perm> <reason>` | Mute a player | `labymute.use` |
| `/labyunmute <player> [reason]` | Remove a mute | `labymute.use` |
| `/labyhist <player> [page]` | View mute history | `labymute.use` |
| `/labyprune <player> <id>` | Delete a history entry | `labymute.admin` |
| `/labyreload` | Reload config | `labymute.admin` |

## Duration Format

| Suffix | Unit |
|--------|------|
| `s` | seconds |
| `m` | minutes |
| `h` | hours |
| `d` | days |
| `w` | weeks |
| `mo` | months |
| `y` | years (caps to permanent) |

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
```

Create the `voicemute` database before starting the plugin. The table is created automatically on first launch.

## Building

**Requirements:** Java 21, Maven 3.6+

```bash
git clone https://github.com/pacoonrox/VoiceMute.git
cd VoiceMute
mvn package
```

The compiled JAR will be at `target/LabyMutePlugin-2.3.jar`.

## Requirements
- Paper/Spigot 1.21+ (or FlamePaper 1.8.x)
- Java 21 (required to compile)
- MySQL 8.0+ or MariaDB 10.6+
- [LabyMod Server API v1.0.9](https://github.com/LabyMod/labymod4-server-api/releases/download/1.0.9/labymod-server-api-bukkit-1.0.9.jar)
