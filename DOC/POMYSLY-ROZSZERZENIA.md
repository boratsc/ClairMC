# Minecraft Bridge — Pomysły na rozszerzenie integracji

Dokument opisuje propozycje nowych funkcji integracji Minecraft ↔ Discord, bazujących na istniejącej infrastrukturze (WebSocket, pg_notify, mc_event_log, mc_links, EXP system, PsychoCoin).

**Obecny stan:**
- Status serwera (heartbeat, gracze online, wersja)
- Synchronizacja czatu dwukierunkowa (MC ↔ Discord przez `chat_channel_id`)
- Powiadomienia join/quit/death/link na kanale eventów
- Linkowanie kont Discord ↔ Minecraft (per guild, kod 6-znakowy)
- Komendy admina z Discorda: say, kick, whitelist, run_console_command

---

## 1. Statystyki gracza w profilu MC

**Cel:** Rozbudowa `/mc-profile` o pełne statystyki — czas online, sesje, śmierci, streak.

**Dane są już w `mc_event_log`** — nie wymaga żadnych zmian w pluginie.

### Metryki do obliczenia

| Metryka | Źródło | Logika |
|---------|--------|--------|
| Łączny czas online | `player_join` + `player_quit` | Różnica timestampów (parowanie po `player_uuid` + `server_id`) |
| Liczba sesji | `player_join` | `COUNT(*)` per gracz |
| Średni czas sesji | j.w. | `SUM(czas) / COUNT(sesji)` |
| Liczba śmierci | `player_death` | `COUNT(*)` per gracz |
| Ostatnio widziany | `player_join` / `player_quit` | `MAX(created_at)` |
| Streak dni | `player_join` | Ciąg unikalnych dat `created_at::date` bez przerw |
| Ulubiony serwer | `player_join` | Serwer z największą liczbą sesji |

### Zapytanie SQL — czas online

```sql
WITH sessions AS (
    SELECT
        j.player_uuid,
        j.server_id,
        j.created_at AS join_time,
        (
            SELECT MIN(q.created_at)
            FROM mc_event_log q
            WHERE q.event_type = 'player_quit'
              AND q.player_uuid = j.player_uuid
              AND q.server_id = j.server_id
              AND q.created_at > j.created_at
        ) AS quit_time
    FROM mc_event_log j
    WHERE j.event_type = 'player_join'
      AND j.player_uuid = $1
)
SELECT
    COUNT(*) AS total_sessions,
    SUM(EXTRACT(EPOCH FROM COALESCE(quit_time, NOW()) - join_time)) AS total_seconds,
    AVG(EXTRACT(EPOCH FROM COALESCE(quit_time, NOW()) - join_time)) AS avg_session_seconds,
    MAX(join_time) AS last_seen
FROM sessions;
```

### Rozbudowa embeda `/mc-profile`

```
⛏️ Profil Minecraft — SteveGracz
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎮 Nick: SteveGracz
🔗 UUID: xxxxxxxx-xxxx-...
📅 Połączony: 15.01.2026

📊 Statystyki
⏱️ Czas online: 47h 23m
📋 Sesji: 142
📏 Śr. sesja: 20m
💀 Śmierci: 38
🔥 Streak: 5 dni
🏠 Ulubiony serwer: survival-1
👁️ Ostatnio: 2 godziny temu
```

### Implementacja

- Nowy plik: `src/utils/mc-player-stats.js` — klasa z metodami `getPlayerStats(guildId, mcUuid)`
- Modyfikacja: `src/commands/minecraft/mc-profile.js` — dodanie sekcji statystyk do embeda
- **Opcjonalnie:** cache w Redis (TTL 5 min) żeby nie liczyć za każdym razem

---

## 2. EXP i PsychoCoiny za granie w MC

**Cel:** Linkowane konto = aktywność w MC generuje nagrody na Discordzie. Gracze MC awansują w rankingu Discord, co motywuje do linkowania kont i buduje jedną społeczność.

### Źródła nagród

| Aktywność | Nagroda | Limit | Trigger |
|-----------|---------|-------|---------|
| Czas online (15 min) | 10 EXP | 100 EXP/dzień | Scheduler (heartbeat + sesje) |
| Pierwszy join w dniu | 25 EXP + 5 CC | 1×/dzień | Event `player_join` |
| Achievement (task) | 15 EXP + 3 CC | - | Event `advancement` |
| Achievement (goal) | 30 EXP + 10 CC | - | Event `advancement` |
| Achievement (challenge) | 50 EXP + 25 CC | - | Event `advancement` |
| Wiadomość na czacie MC | 2 EXP | 20 EXP/dzień | Event `mc_chat` |
| Zabicie bossa (Ender Dragon, Wither) | 100 EXP + 50 CC | 1×/boss/gracz | Event `advancement` (specyficzne klucze) |

### Nowy event z pluginu: `advancement`

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
            "description": "Wydobądź diamenty żelaznym (lub lepszym) kilofem",
            "frame": "task"
        }
    },
    "signature": "..."
}
```

Pole `frame` pochodzi z Minecraft Advancement API:
- `task` — zwykłe zadanie (zielona ramka)
- `goal` — cel (różowa ramka)
- `challenge` — wyzwanie (fioletowa ramka, najrzadsze)

### Zmiany w pluginie

Listener na `PlayerAdvancementDoneEvent`:

```java
@EventHandler
public void onAdvancement(PlayerAdvancementDoneEvent event) {
    Advancement adv = event.getAdvancement();
    // Pomijaj recipe-unlocki (nie są prawdziwymi achievementami)
    if (adv.getKey().getKey().startsWith("recipes/")) return;

    AdvancementDisplay display = adv.getDisplay();
    if (display == null) return;

    bridge.sendEvent("advancement", Map.of(
        "player", playerData(event.getPlayer()),
        "advancement", Map.of(
            "key", adv.getKey().toString(),
            "title", display.getTitle(),
            "description", display.getDescription(),
            "frame", display.getFrame().name().toLowerCase()
        )
    ));
}
```

### Zmiany w bocie

- Nowy feature toggle: `feature_advancements` w `mc_servers` (migracja)
- Obsługa eventu `advancement` w `McBridgeHub.handleEvent()` (dodanie do feature toggle)
- Case `advancement` w `_createEmbed()` w notification handlerze
- Nowy plik: `src/utils/mc-rewards.js` — logika naliczania EXP/CC za eventy MC
  - Pobiera linkowane konto z `mc_links` (mc_uuid → discord_user_id)
  - Wywołuje `expSystem.addExp()` i `psychocoinSystem.addCoins()`
  - Sprawdza limity dzienne (Redis counter z TTL 24h)
- Wywołanie `mc-rewards` w notification handlerze po wysłaniu embeda

### Tabela limitów dziennych (Redis)

```
mc_rewards:{guildId}:{discordUserId}:exp_today     → counter (TTL: do końca dnia)
mc_rewards:{guildId}:{discordUserId}:daily_login    → 1 (TTL: do końca dnia)
mc_rewards:{guildId}:{discordUserId}:chat_exp_today → counter (TTL: do końca dnia)
```

### EXP za czas online — scheduler

Nowy scheduler (`mc-online-exp-scheduler.js`, co 15 min):
1. Pobierz aktywne sesje (gracze z `player_join` bez odpowiadającego `player_quit`)
2. Dla każdego: sprawdź czy ma linkowane konto
3. Nalicz EXP (z respektowaniem dziennego limitu)

Alternatywa: naliczaj przy `player_quit` proporcjonalnie do czasu sesji.

---

## 3. Advancement relay — achievementy na Discordzie

**Cel:** Minecraft advancements jako embedy na kanale eventów z wizualnym rozróżnieniem rzadkości.

### Embedy per frame type

| Frame | Kolor | Emoji | Przykład |
|-------|-------|-------|---------|
| `task` | `#43a047` (zielony) | 📗 | **SteveGracz** zdobył osiągnięcie: *Diamenty!* |
| `goal` | `#e91e63` (różowy) | 📕 | **SteveGracz** osiągnął cel: *Diamentowa zbroja* |
| `challenge` | `#9c27b0` (fioletowy) | 📜 | **SteveGracz** ukończył wyzwanie: *Snajper* |

### Przykład embeda

```
📜 Wyzwanie ukończone!
━━━━━━━━━━━━━━━━━━━━━
SteveGracz ukończył wyzwanie:
🏆 Snajper
Zabij szkielet strzałą z odległości 50+ bloków

🎁 Nagroda: +50 EXP, +25 CC
━━━━━━━━━━━━━━━━━━━━━
survival-1
```

### Tablica osiągnięć

Nowa komenda `/mc-advancements [top|@user]`:
- `top` — ranking: kto ma najwięcej advancementów (z podziałem na typy)
- `@user` — lista advancementów danego gracza

Tabela w DB (nowa migracja):

```sql
CREATE TABLE IF NOT EXISTS mc_advancements (
    id SERIAL PRIMARY KEY,
    guild_id VARCHAR(32) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    advancement_key VARCHAR(256) NOT NULL,
    advancement_title VARCHAR(256),
    frame VARCHAR(16) DEFAULT 'task',
    achieved_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (guild_id, player_uuid, advancement_key)
);
```

`UNIQUE` constraint zapobiega duplikatom (gracz może zdobyć advancement tylko raz).

---

## 4. Komendy Discord → MC z odpowiedzią

**Cel:** Rozszerzenie `/mc-server` o interaktywne komendy zwracające dane z serwera MC.

### Nowe subkomendy

| Subcommand | Komenda do pluginu | Opis |
|------------|-------------------|------|
| `list` | `get_player_list` | Lista graczy online |
| `tps` | `get_tps` | Aktualny TPS serwera |

### Kontrakt: `get_player_list`

Komenda (Bridge → Plugin):
```json
{
    "type": "command",
    "cmd": "get_player_list",
    "id": "cmd_...",
    "serverId": "survival-1",
    "ts": 1738976500,
    "payload": {},
    "signature": "..."
}
```

ACK (Plugin → Bridge):
```json
{
    "type": "ack",
    "id": "cmd_...",
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

### Kontrakt: `get_tps`

ACK payload:
```json
{
    "tps": 19.87,
    "mspt": 12.3
}
```

### Embed `/mc-server list`

```
👥 Gracze online — survival-1 (2/60)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🟢 SteveGracz    ❤️ 18.5  🌍 Overworld
🟢 Alex99        ❤️ 20.0  🌋 Nether
```

### Zmiany w pluginie

Nowe handlery komend w pluginie (na main thread):
```java
case "get_player_list":
    List<Map<String, Object>> players = Bukkit.getOnlinePlayers().stream()
        .map(p -> Map.of(
            "name", p.getName(),
            "uuid", p.getUniqueId().toString(),
            "health", p.getHealth(),
            "world", p.getWorld().getEnvironment().name()
        ))
        .collect(Collectors.toList());
    sendAck(id, true, null, Map.of("players", players, "count", players.size(), "max", Bukkit.getMaxPlayers()));
    break;

case "get_tps":
    double[] tps = Bukkit.getTPS(); // Paper API
    sendAck(id, true, null, Map.of("tps", Math.round(tps[0] * 100.0) / 100.0, "mspt", Bukkit.getAverageTickTime()));
    break;
```

---

## 5. Powiadomienia o statusie serwera

**Cel:** Automatyczne alerty na Discordzie gdy serwer zmienia status — online/offline, lag, pełny.

**Nie wymaga zmian w pluginie** — heartbeat już wysyła `playersOnline`, `playersMax`, `online`. Opcjonalnie dodać `tps` do heartbeat.

### Typy alertów

| Alert | Warunek | Kolor | Cooldown |
|-------|---------|-------|----------|
| 🟢 Serwer online | Pierwszy heartbeat po braku | Zielony | - |
| 🔴 Serwer offline | Brak heartbeat > 90s | Czerwony | - |
| ⚠️ Lag alert | TPS < 15 (jeśli w heartbeat) | Pomarańczowy | 5 min |
| 🔵 Serwer pełny | `playersOnline == playersMax` | Niebieski | 15 min |

### Implementacja

Nowy scheduler: `mc-status-alert-scheduler.js` (co 30s):

```javascript
// Pseudokod
for (const server of activeServers) {
    const lastHeartbeat = server.last_heartbeat;
    const secondsAgo = (Date.now() - lastHeartbeat) / 1000;

    // Offline detection
    if (secondsAgo > 90 && server.is_online) {
        await markOffline(server);
        await sendAlert(server, 'offline');
    }

    // Full server
    if (server.players_online >= server.players_max && server.players_max > 0) {
        await sendAlert(server, 'full');
    }
}
```

### Rozszerzenie heartbeat o TPS (opcjonalne)

Zmiana w pluginie — dodać `tps` do heartbeat payload:

```json
{
    "payload": {
        "status": {
            "online": true,
            "playersOnline": 12,
            "playersMax": 60,
            "version": "1.21.10",
            "brand": "Paper",
            "tps": 19.95,
            "mspt": 8.2
        }
    }
}
```

Nowa kolumna w `mc_servers` (migracja):
```sql
ALTER TABLE mc_servers ADD COLUMN IF NOT EXISTS last_tps NUMERIC(5,2) DEFAULT NULL;
ALTER TABLE mc_servers ADD COLUMN IF NOT EXISTS last_mspt NUMERIC(6,2) DEFAULT NULL;
```

---

## 6. Cross-platform eventy i minigry

**Cel:** Wspólne aktywności łączące graczy MC i Discord w jednym doświadczeniu.

### 6.1. Wyzwania (Challenges)

Admin tworzy wyzwanie na Discordzie, gracze realizują je w MC:

```
/mc-challenge create "Diamentowy wyścig"
  --opis "Kto pierwszy wykopie diament?"
  --nagroda 100
  --czas 1h
```

Bot wysyła embed na kanale, plugin monitoruje event i zgłasza wygranego.

**Monitoring po stronie pluginu:** nowy event `challenge_complete`:
```json
{
    "event": "challenge_complete",
    "payload": {
        "player": { "uuid": "...", "name": "SteveGracz" },
        "challenge": "mine_diamond",
        "timestamp": 1738976500
    }
}
```

Alternatywa (prostsza): wyzwania oparte na achievementach MC, bez custom monitorowania.

### 6.2. Drop PsychoCoinów w MC

Komenda w grze: `/claimcoins` — gracz dostaje dzienne PsychoCoiny na konto Discord.

**Flow:**
1. Gracz wpisuje `/claimcoins` w MC
2. Plugin wysyła request `claim_daily_coins` do Bridge
3. Bridge sprawdza: linkowane konto? Już odebrał dziś?
4. Bridge nalicza PsychoCoiny na konto Discord
5. Plugin wyświetla graczowi potwierdzenie

### 6.3. Quiz Minecraft na Discordzie

Scheduler: codziennie o wybranej godzinie bot zadaje pytanie o MC:
- "Ile bloków obsydianu potrzeba na portal do Netheru?"
- "Jaki mob dropuje Ender Pearle?"

Pierwsza poprawna odpowiedź = nagroda PsychoCoin. Pytania z predefiniowanej puli.

---

## 7. Automatyczny whitelist przez Discord

**Cel:** Linkowanie konta Discord = automatyczny dostęp do serwera MC. Odlinkowanie = usunięcie z whitelisty.

### Flow

```
Discord: /mc-link connect
  → Kod wygenerowany
MC: /link AB12CD
  → Konto połączone
  → Bridge: whitelist_add(playerName)     ← AUTOMATYCZNIE
  → Bot: nadaje rolę "MC Player"          ← AUTOMATYCZNIE

Discord: /mc-link disconnect
  → Konto rozłączone
  → Bridge: whitelist_remove(playerName)  ← AUTOMATYCZNIE
  → Bot: zdejmuje rolę "MC Player"        ← AUTOMATYCZNIE
```

### Zmiany

**Plugin:** nowa komenda `whitelist_remove`:
```java
case "whitelist_remove":
    String playerName = payload.get("playerName").getAsString();
    Bukkit.getScheduler().runTask(plugin, () -> {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist remove " + playerName);
    });
    sendAck(id, true, null, Map.of());
    break;
```

**Bot (mc-link.js):** po udanym linkowaniu/rozłączeniu:
- Wywołaj `POST /mc-bridge/command` z `cmd: 'whitelist_add'` / `'whitelist_remove'`
- Nadaj/zdejmij konfigurowalną rolę Discord

**Nowe pola w `mc_servers`** (migracja):
```sql
ALTER TABLE mc_servers ADD COLUMN IF NOT EXISTS auto_whitelist BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mc_servers ADD COLUMN IF NOT EXISTS link_role_id VARCHAR(32) DEFAULT NULL;
```

### Opcja: wymagany poziom

Jeśli `mc_servers.min_level_for_whitelist` jest ustawiony, whitelist nadawany tylko gdy użytkownik Discord ma odpowiedni poziom EXP:

```sql
ALTER TABLE mc_servers ADD COLUMN IF NOT EXISTS min_level_for_whitelist INTEGER DEFAULT NULL;
```

---

## 8. Tablica śmierci (Death Leaderboard)

**Cel:** Ranking śmierci — kto umierał najczęściej, z kategoriami i okresami.

**Nie wymaga zmian w pluginie** — dane w `mc_event_log`.

### Komenda `/mc-deaths`

| Subcommand | Opis |
|------------|------|
| `top [okres]` | Top 10 graczy z największą liczbą śmierci (tydzień/miesiąc/all-time) |
| `stats [@user]` | Statystyki śmierci gracza |

### Kategorie śmierci

Plugin wysyła `message` w evencie `player_death` — parsowanie po stronie bota:

| Kategoria | Wykrywanie (regex na `message`) |
|-----------|-------------------------------|
| PvP | `was slain by`, `was shot by` (+ nazwa gracza z listy online) |
| Mob | `was slain by`, `was blown up by` (+ nazwa moba) |
| Środowisko | `fell from`, `drowned`, `burned`, `hit the ground`, `starved` |
| Inne | Wszystko co nie pasuje powyżej |

### Embed `/mc-deaths top`

```
💀 Ranking śmierci — Ten tydzień
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🥇 SteveGracz      — 23 śmierci
🥈 Alex99           — 17 śmierci
🥉 Notch2077        — 12 śmierci
4. CreeperLover     — 8 śmierci
5. DiamondKing      — 5 śmierci

📊 Łącznie na serwerze: 142 śmierci
```

### Zapytanie SQL

```sql
SELECT
    el.player_name,
    COUNT(*) AS deaths
FROM mc_event_log el
WHERE el.event_type = 'player_death'
  AND el.guild_id = $1
  AND el.created_at >= NOW() - INTERVAL '7 days'
GROUP BY el.player_name
ORDER BY deaths DESC
LIMIT 10;
```

---

## 9. Live status embed (auto-aktualizujący się panel)

**Cel:** Admin przypina embed na wybranym kanale — embed automatycznie się aktualizuje co X sekund, pokazując pełny status serwera MC w czasie rzeczywistym. Gracze widzą na żywo czy serwer żyje, kto gra, jaki jest TPS.

**Nie wymaga zmian w pluginie** — wszystkie dane pochodzą z heartbeat i `mc_event_log`. Opcjonalnie TPS z rozszerzonego heartbeat.

### Jak działa

1. Admin wpisuje `/mc-status-panel <server> [#kanał]`
2. Bot wysyła embed na wskazany kanał
3. Bot zapisuje `message_id` i `channel_id` w DB
4. Scheduler co 30–60s edytuje ten sam embed najnowszymi danymi
5. Embed zawsze pokazuje aktualny stan — nie trzeba żadnej komendy

### Przykład embeda

```
⛏️ survival-1 — Status na żywo
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📡 Status:    🟢 Online
🎮 Wersja:    Paper 1.21.10
👥 Gracze:    7 / 60
⏱️ TPS:       19.94
📶 Ping:      12ms (MSPT)
🕐 Uptime:    3d 14h 22m

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
👥 Online (7):
  SteveGracz • Alex99 • Notch2077
  DiamondKing • CreeperLover
  Builder42 • Redstone_Pro

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 Statystyki (dziś):
  🟢 Wejść: 23    🔴 Wyjść: 16    💀 Śmierci: 8

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔄 Ostatnia aktualizacja: <t:1738976400:R>
```

Gdy serwer jest offline:

```
⛏️ survival-1 — Status na żywo
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📡 Status:    🔴 Offline
🕐 Ostatnio online: <t:1738970000:R>

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔄 Ostatnia aktualizacja: <t:1738976400:R>
```

### Źródła danych

| Dane | Źródło | Tabela/pole |
|------|--------|-------------|
| Status online/offline | Heartbeat | `mc_servers.is_online` |
| Gracze online (count) | Heartbeat | `mc_servers.players_online`, `players_max` |
| Wersja / brand | Handshake | `mc_servers.version`, `mc_servers.brand` |
| TPS / MSPT | Heartbeat (rozszerzony) | `mc_servers.last_tps`, `mc_servers.last_mspt` |
| Lista graczy online | Komenda `get_player_list` | Odpytanie WS na żywo |
| Uptime | Heartbeat | `mc_servers.last_heartbeat` vs pierwszy heartbeat |
| Statystyki dnia | Event log | `mc_event_log` (COUNT per event_type, dziś) |

### Baza danych — migracja

```sql
CREATE TABLE IF NOT EXISTS mc_status_panels (
    id SERIAL PRIMARY KEY,
    guild_id VARCHAR(32) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(32) NOT NULL,
    message_id VARCHAR(32) NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    update_interval_seconds INTEGER NOT NULL DEFAULT 60,
    show_player_list BOOLEAN NOT NULL DEFAULT TRUE,
    show_daily_stats BOOLEAN NOT NULL DEFAULT TRUE,
    show_tps BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (guild_id, server_id)
);
```

### Komenda `/mc-status-panel`

| Subcommand | Opis |
|------------|------|
| `create <server> [#kanał]` | Tworzy nowy panel na kanale (domyślnie bieżący) |
| `remove <server>` | Usuwa panel (kasuje embed) |
| `config <server>` | Zmiana interwału, toggle sekcji (player list, stats, TPS) |

### Scheduler — `mc-status-panel-scheduler.js`

```javascript
// Co 30s
async function updatePanels(client) {
    const panels = await pool.query(
        `SELECT p.*, s.is_online, s.players_online, s.players_max,
                s.version, s.brand, s.last_heartbeat, s.last_tps, s.last_mspt
         FROM mc_status_panels p
         JOIN mc_servers s ON s.server_id = p.server_id AND s.guild_id = p.guild_id
         WHERE p.active = TRUE AND s.active = TRUE`
    );

    for (const panel of panels.rows) {
        try {
            const channel = client.channels.cache.get(panel.channel_id);
            if (!channel) continue;

            const message = await channel.messages.fetch(panel.message_id).catch(() => null);
            if (!message) {
                // Embed usunięty — dezaktywuj panel
                await pool.query('UPDATE mc_status_panels SET active = FALSE WHERE id = $1', [panel.id]);
                continue;
            }

            const embed = await buildStatusEmbed(panel);
            await message.edit({ embeds: [embed] });
        } catch (error) {
            logger.warn('[StatusPanel] Blad aktualizacji panelu:', {
                panelId: panel.id, error: error.message
            });
        }
    }
}
```

### Lista graczy online — dwie strategie

**Strategia A: Z heartbeat (prosta, bez zmian w pluginie)**
- Plugin wysyła listę graczy w heartbeat payload (rozszerzenie)
- Bot cachuje w `mc_servers.players_list` (JSONB)
- Aktualizacja co 30–60s automatycznie

```json
{
    "status": {
        "playersOnline": 7,
        "playersMax": 60,
        "players": ["SteveGracz", "Alex99", "Notch2077"]
    }
}
```

**Strategia B: Z komendy `get_player_list` (dokładniejsza, wymaga WS)**
- Scheduler wysyła komendę `get_player_list` do pluginu
- Odpowiedź w ACK z pełnymi danymi (health, world)
- Wymaga aktywnego połączenia WS

**Rekomendacja:** Strategia A (lista nicków w heartbeat) — prostsza, nie blokuje WS, wystarczająca dla panelu statusu.

### Obsługa edge cases

| Sytuacja | Zachowanie |
|----------|-----------|
| Embed usunięty ręcznie | Scheduler dezaktywuje panel w DB |
| Serwer offline | Embed zmienia kolor na czerwony, pokazuje "Ostatnio online" |
| Bot restart | Scheduler wznawia aktualizację — pobiera `message_id` z DB |
| Kanał usunięty | `channel.messages.fetch` rzuca błąd → dezaktywacja |
| Rate limit Discord | Interwał 60s = bezpieczny (edit message rate limit: 5/5s per kanał) |
| Wiele serwerów | Jeden panel per serwer per guild, scheduler iteruje po wszystkich |

### Konfiguracja interwału

Domyślnie 60s. Admin może zmienić na 30s–300s:
- 30s — szybka aktualizacja (limit: max 2 panele per guild przy 30s)
- 60s — domyślne, bezpieczne
- 300s — oszczędne, wystarczające dla małych serwerów

Rate limit protection: bot nie edituje embeda jeśli dane się nie zmieniły od ostatniego razu (porównanie hash pól).

---

## Podsumowanie priorytetów

| # | Funkcja | Wartość | Wysiłek | Plugin? | Zależności |
|---|---------|---------|---------|---------|-----------|
| 1 | Statystyki w profilu | ⭐⭐⭐ | Mały | Nie | Dane już w DB |
| 2 | EXP/Coiny za granie | ⭐⭐⭐⭐ | Średni | Tak (advancement) | mc_links, EXP system |
| 3 | Powiadomienia statusu | ⭐⭐⭐ | Mały | Nie (opcj. TPS) | Heartbeat |
| 4 | Advancement relay | ⭐⭐⭐ | Średni | Tak | Nowy event + tabela |
| 5 | Auto-whitelist | ⭐⭐⭐ | Mały | Minimalne | mc_links |
| 6 | Komendy list/tps | ⭐⭐ | Mały | Tak | Nowe handlery |
| 7 | Death leaderboard | ⭐⭐ | Mały | Nie | mc_event_log |
| 8 | Cross-platform eventy | ⭐⭐⭐⭐ | Duży | Tak | Nowe eventy + requesty |
| 9 | Live status embed | ⭐⭐⭐⭐ | Mały–Średni | Nie (opcj. lista graczy) | Heartbeat, scheduler |

**Rekomendowana kolejność implementacji:**
1. Statystyki w profilu (zero zmian w pluginie, szybki efekt)
2. **Live status embed** (wysoka widoczność, scheduler + embed edit, brak zmian w pluginie)
3. Powiadomienia statusu (scheduler, zero zmian w pluginie)
4. Auto-whitelist (mała zmiana, duża wartość UX)
5. Death leaderboard (prosta komenda, dane już są)
6. Advancement relay + EXP/Coiny (razem — wymagają zmian w pluginie)
7. Komendy list/tps
8. Cross-platform eventy (na końcu — największy wysiłek)
