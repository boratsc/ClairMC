# Clair MC Bridge — Proof of Concept (Paper/Purpur 1.21.10)

Cel: zrobić minimalną integrację Minecraft (Paper/Purpur) ↔ Clair (bot + panel) w sposób bezpieczny i rozwojowy.
Plugin nie łączy się bezpośrednio z Discordem. Plugin gada tylko z Bridge (API), a Bridge jest spięty z botem i panelem.

---

## 1. Elementy systemu

### A) Plugin: `clair-mc-bridge` (Paper/Purpur)
Odpowiedzialność:
- łapie eventy z serwera (join/quit/death/chat/advancement)
- ma komendy `/link`, `/unlink`
- wysyła eventy do Bridge
- odbiera polecenia z Bridge (np. wyślij wiadomość na czat, whitelist, kick)
- nigdy nie blokuje ticków: sieć async, akcje MC na main thread

Wrzucasz:
`plugins/clair-mc-bridge.jar`

### B) Bridge: `clair-game-bridge` (HTTP + WebSocket)
Odpowiedzialność:
- autoryzacja serwerów (token + podpis / HMAC)
- przechowuje mapowania DiscordId ↔ UUID
- robi “pending link codes” (kody do łączenia kont)
- jest jedynym miejscem, z którym rozmawia plugin
- przekazuje eventy do bota / panelu
- trzyma lastSeen/heartbeat serwera i podstawowe staty

### C) Bot + panel: Clair
Odpowiedzialność:
- UI na Discordzie (komendy /link /server /profile)
- publikacja logów (kanały join/quit/death)
- role / uprawnienia (np. po linkowaniu)
- panel www (podgląd serwera, logi, powiązania)

---

## 2. Jak to działa w runtime (krok po kroku)

### 2.1 Start serwera
1) Paper startuje i ładuje plugin.
2) Plugin czyta `config.yml`:
  - bridge.serverId, bridge.secret
  - bridge.url (np. wss://dev.clairbot.app/api/mc-bridge)
3) Plugin odpala połączenie WebSocket do Bridge:
   - wysyła handshake + podpis
4) Bridge zapisuje `lastSeen` i potwierdza połączenie.

### 2.2 Eventy z Minecrafta do Discorda (przez Bridge)
Przykład: join
1) gracz wchodzi → plugin łapie `PlayerJoinEvent`
2) plugin wysyła do Bridge event JSON `player_join`
3) Bridge publikuje to do bota
4) bot wysyła embed na Discord (np. kanał #logi-serwera)

### 2.3 Polecenia z Discorda do Minecrafta (przez Bridge)
Przykład: /say
1) admin na Discord: `/say Witajcie!`
2) bot wysyła do Bridge komendę `send_chat`
3) Bridge pcha komendę po WS do pluginu
4) plugin wykonuje `Bukkit.broadcastMessage(...)` na main thread
5) plugin odsyła ACK (powodzenie/błąd)

---

## 3. Linkowanie kont Discord ↔ Minecraft (kręgosłup integracji)

### Flow
1) Discord: user wpisuje `/link`
2) Bot prosi Bridge o kod:
   - Bridge generuje kod `AB12CD` ważny 5 minut
3) Bot mówi userowi: “W grze wpisz: `/link AB12CD`”
4) MC: gracz wpisuje `/link AB12CD`
5) Plugin wysyła do Bridge:
   - kod, uuid, nick
6) Bridge:
   - sprawdza czy kod istnieje i nie wygasł
   - zapisuje mapowanie `discordId ↔ uuid`
7) Bot dostaje event `linked`:
   - nadaje role, odblokowuje kanały, itp.

---

## 4. MVP (minimalny zakres Proof of Concept)

### Moduł 1: Heartbeat / status
- co 30–60s plugin wysyła `server_heartbeat`
- bot/panel pokazuje “ONLINE”, liczba graczy, wersja

### Moduł 2: Eventy podstawowe
- join / quit
- death
- chat (opcjonalnie)
- advancement (opcjonalnie)

### Moduł 3: Linkowanie kont
- `/link <kod>`
- `/unlink`

### Moduł 4: Polecenia z Discord do MC
- `send_chat`
- `whitelist_add`
- `kick`
- `run_console_command` (tylko allowlista!)

---

## 5. Bezpieczeństwo (POC minimum)

### 5.1 Autoryzacja
Każda wiadomość plugin → bridge zawiera:
- serverId
- timestamp
- payload
- signature = HMAC_SHA256(secret, serverId + timestamp + payload)

Bridge odrzuca:
- zły podpis
- stary timestamp (np. > 60s)
- nieznany serverId

### 5.2 Allowlista komend
Jeśli w ogóle dopuszczasz `run_console_command`, to tylko z listy:
- say
- whitelist add
- kick
- ban
- lp user ... parent set (jeśli LuckPerms)
Nigdy “dowolnej komendy”.

### 5.3 Nie blokujemy ticków
- wszystkie requesty sieciowe async
- wszystko co dotyka Bukkit API wykonywane przez scheduler na main thread

---

## 6. Kontrakty wiadomości (JSON)

### 6.1 Event: player_join
```json
{
  "type": "event",
  "event": "player_join",
  "serverId": "pogranicze-1",
  "ts": 1738976400,
  "payload": {
    "player": {
      "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "name": "Gracz"
    }
  },
  "signature": "..."
}
```

### 6.2 Event: server_heartbeat
```json
{
  "type": "event",
  "event": "server_heartbeat",
  "serverId": "pogranicze-1",
  "ts": 1738976400,
  "payload": {
    "status": {
      "online": true,
      "playersOnline": 12,
      "playersMax": 60,
      "version": "1.21.10",
      "brand": "Paper"
    }
  },
  "signature": "..."
}
```

### 6.3 Command: send_chat (Bridge → Plugin)
```json
{
  "type": "command",
  "cmd": "send_chat",
  "serverId": "pogranicze-1",
  "id": "cmd_9f2a",
  "ts": 1738976500,
  "payload": {
    "message": "[Clair] Witajcie na evencie!"
  },
  "signature": "..."
}
```

### 6.4 Command ACK (Plugin → Bridge)
```json
{
  "type": "ack",
  "serverId": "pogranicze-1",
  "id": "cmd_9f2a",
  "ts": 1738976501,
  "ok": true,
  "error": null,
  "payload": {},
  "signature": "..."
}
```

### 6.5 Request: claim_link_code (Plugin → Bridge)
```json
{
  "type": "request",
  "req": "claim_link_code",
  "id": "req_9f2a1b2c3d4e",
  "serverId": "pogranicze-1",
  "ts": 1738976400,
  "payload": {
    "code": "AB12CD",
    "player": {
      "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "name": "Gracz"
    }
  },
  "signature": "..."
}
```

## 7. Przykładowy config.yml pluginu

```yaml
bridge:
  url: "wss://dev.clairbot.app/api/mc-bridge"
  serverId: "pogranicze-1"
  secret: "SUPER_TAJNY_TOKEN_SERWERA"

features:
  heartbeatSeconds: 30
  sendJoinQuit: true
  sendDeaths: true
  sendChat: false
  sendAdvancements: false
  heartbeatPlayersList: false
  heartbeatTps: false

commands:
  allowConsoleCommands:
    - "say"
    - "whitelist add"
    - "kick"
    - "ban"
```