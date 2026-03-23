# VoiceMute (LabyMutePlugin)

A SQLite-backed Bukkit/Spigot plugin for muting players' LabyMod voice chat features. Supports timed and permanent mutes, full history tracking, and optional Discord webhook logging.

## Features
- Timed mutes with flexible duration syntax (`30m`, `7d`, `2w`, etc.) or permanent
- Mutes persist across restarts — players are re-muted on join
- Full per-player mute history with paginated `/labyhist`
- Prune individual history entries with `/labyprune`
- Discord webhook notifications for mutes and unmutes
- Requires [LabyModServerAPI](https://github.com/LabyMod/labymod-server-api)

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
discord:
  enabled: false
  webhook-url: ""
```

## Requirements
- Paper/Spigot 1.21+
- Java 17+
- LabyModServerAPI
