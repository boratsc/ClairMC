# API changes for clair-mc-bridge 0.1.2

This document lists plugin-side protocol changes introduced in version 0.1.2 that the API/Bridge should support.

## Summary

- New event: `advancement` (optional, gated by `features.sendAdvancements`).
- Heartbeat payload extensions (optional): player list, TPS/MSPT.
- New commands: `get_player_list`, `get_tps` with ACK payloads.
- ACK payload support for command responses.

## Event: advancement (plugin -> bridge)

Sent when a player completes a non-recipe advancement.

Trigger: `PlayerAdvancementDoneEvent`
Config toggle: `features.sendAdvancements` (default: false)

Payload:

```json
{
  "type": "event",
  "event": "advancement",
  "serverId": "survival-1",
  "ts": 1738976400,
  "payload": {
    "player": {
      "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "name": "SteveGracz"
    },
    "advancement": {
      "key": "minecraft:story/mine_diamond",
      "title": "Diamenty!",
      "description": "Wydobadz diamenty zelaznym (lub lepszym) kilofem",
      "frame": "task"
    }
  },
  "signature": "..."
}
```

Notes:
- Advancements with key starting `recipes/` are skipped.
- `title`/`description` are plain text (Adventure component serialized to text).
- `frame` is one of: `task`, `goal`, `challenge`.

## Heartbeat extensions (plugin -> bridge)

### Player list (optional)

Config toggle: `features.heartbeatPlayersList` (default: false)

When enabled, heartbeat includes `status.players` as an array of names:

```json
{
  "event": "server_heartbeat",
  "payload": {
    "status": {
      "players": ["SteveGracz", "Alex99"]
    }
  }
}
```

### TPS and MSPT (optional)

Config toggle: `features.heartbeatTps` (default: false)

When enabled, heartbeat includes:

```json
{
  "event": "server_heartbeat",
  "payload": {
    "status": {
      "tps": 19.94,
      "mspt": 8.2
    }
  }
}
```

## Command: get_player_list (bridge -> plugin)

Returns the current player list and capacity.

Request:

```json
{
  "type": "command",
  "cmd": "get_player_list",
  "id": "cmd_123",
  "serverId": "survival-1",
  "ts": 1738976500,
  "payload": {},
  "signature": "..."
}
```

ACK payload:

```json
{
  "type": "ack",
  "id": "cmd_123",
  "serverId": "survival-1",
  "ts": 1738976501,
  "ok": true,
  "payload": {
    "players": [
      { "name": "SteveGracz", "uuid": "xxx-...", "health": 18.5, "world": "world" },
      { "name": "Alex99", "uuid": "yyy-...", "health": 20.0, "world": "world_nether" }
    ],
    "count": 2,
    "max": 60
  },
  "signature": "..."
}
```

## Command: get_tps (bridge -> plugin)

Returns current TPS and MSPT.

Request:

```json
{
  "type": "command",
  "cmd": "get_tps",
  "id": "cmd_124",
  "serverId": "survival-1",
  "ts": 1738976500,
  "payload": {},
  "signature": "..."
}
```

ACK payload:

```json
{
  "type": "ack",
  "id": "cmd_124",
  "serverId": "survival-1",
  "ts": 1738976501,
  "ok": true,
  "payload": {
    "tps": 19.87,
    "mspt": 12.3
  },
  "signature": "..."
}
```

## Config additions (plugin)

```yaml
features:
  sendAdvancements: false
  heartbeatPlayersList: false
  heartbeatTps: false
```
