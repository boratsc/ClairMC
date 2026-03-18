# Minecraft Bridge — Dokumentacja implementacji

## Przegląd

Integracja Minecraft ↔ Discord umożliwia:
- **Powiadomienia w czasie rzeczywistym** — player_join, player_quit, player_death na kanale Discord
- **Łączenie kont** — Discord ↔ Minecraft (UUID) per guild, z kodem 6-znakowym
- **Komendy z Discorda** — `/mc-server say`, `/mc-server kick` wysyłane na serwer MC
- **Panel webowy** — zarządzanie serwerami MC, powiązaniami kont i logami eventów

Architektura: **per-guild** — każdy serwer Discord niezależnie rejestruje i zarządza swoimi serwerami MC przez panel Hub. Brak globalnych sekretów w `.env`.

---

## Architektura

```
┌─────────────┐     WS (HMAC)     ┌─────────────┐    pg_notify     ┌───────────┐
│  MC Plugin   │ ←──────────────→  │  clair-api   │ ──────────────→ │ clair-bot  │
│ (Paper/Purpur)│                   │ McBridgeHub  │                 │ (Discord)  │
└─────────────┘                    └──────┬───────┘                 └───────────┘
                                          │
                                   ┌──────┴───────┐
                                   │  PostgreSQL   │
                                   │  mc_* tabele  │
                                   └──────┬───────┘
                                          │
                                   ┌──────┴───────┐
                                   │  clair-hub    │
                                   │  (panel www)  │
                                   └──────────────┘
```

**Przepływ danych:**
1. Plugin MC łączy się po WebSocket do `clair-api` (`/mc-bridge`)
2. Wiadomości uwierzytelniane HMAC-SHA256 (per-server secret z DB)
3. Eventy zapisywane do `mc_event_log` + `mc_notifications` + `pg_notify`
4. Bot odbiera `pg_notify('mc_bridge_event')` i wysyła embed na Discord
5. Fallback scheduler (30s) przetwarza zaległe powiadomienia

---

## Baza danych

**Migracja:** `migrations/186_minecraft_bridge.sql`

| Tabela | Cel |
|--------|-----|
| `mc_servers` | Zarejestrowane serwery MC (server_id, guild_id, secret, status, konfiguracja kanałów, feature toggles, allowed_commands) |
| `mc_links` | Powiązania discord_user_id ↔ mc_uuid per guild |
| `mc_link_codes` | Kody jednorazowe do łączenia kont (6 znaków, TTL 5 min) |
| `mc_event_log` | Historia eventów (player_join/quit/death) |
| `mc_notifications` | Kolejka powiadomień Discord (pending → sent/failed) |

### Kluczowe pola `mc_servers`

| Pole | Typ | Opis |
|------|-----|------|
| `server_id` | VARCHAR(64) | Unikalny ID serwera MC (nadawany przez admina) |
| `guild_id` | VARCHAR(32) | Discord guild |
| `secret` | VARCHAR(256) | HMAC secret (generowany automatycznie) |
| `event_channel_id` | VARCHAR(32) | Kanał Discord na powiadomienia eventów |
| `chat_channel_id` | VARCHAR(32) | Kanał Discord na chat MC (przyszłość) |
| `feature_join_quit` | BOOLEAN | Toggle: powiadomienia join/quit |
| `feature_deaths` | BOOLEAN | Toggle: powiadomienia śmierci |
| `feature_chat` | BOOLEAN | Toggle: bridge chatu (przyszłość) |
| `allowed_commands` | JSONB | Lista dozwolonych komend konsoli |

---

## Pliki źródłowe

### clair-api (Bridge WebSocket + REST)

```
clair-api/src/mc-bridge/
├── McBridgeHub.js              # WebSocket hub (noServer, upgrade routing)
├── index.js                    # Eksport modułu
└── services/
    ├── HmacService.js          # HMAC-SHA256 signing/verification
    ├── ServerRegistry.js       # Cache serwerów (TTL 1 min)
    ├── LinkService.js          # Logika łączenia kont
    ├── EventDispatcher.js      # Routing eventów → pg_notify
    └── CommandService.js       # Wysyłanie komend do MC (Promise + ACK timeout 10s)

clair-api/src/routes/mc-bridge.js   # REST: POST /command, GET /servers
```

**Zmodyfikowane pliki:**
- `clair-api/src/server.js` — inicjalizacja McBridgeHub + route
- `clair-api/src/config/config.js` — sekcja `mcBridge` (parametry timing)

### clair-bot (komendy Discord + powiadomienia)

```
src/commands/minecraft/
├── mc-link.js                  # /mc-link connect|disconnect|status
├── mc-server.js                # /mc-server status|say|kick (admin)
└── mc-profile.js               # /mc-profile [@user]

src/utils/mc-bridge-notification-handler.js     # Embedy Discord
src/schedulers/mc-bridge-notifications-fallback-scheduler.js  # Fallback 30s
```

**Zmodyfikowane pliki:**
- `src/bootstrap/pg-notify-listener.js` — LISTEN `mc_bridge_event`
- `src/bootstrap/schedulers.js` — rejestracja fallback schedulera

### clair-hub (panel webowy)

```
clair-hub/routes/minecraft/index.js    # 8 endpointów REST
```

**Zmodyfikowany:** `clair-hub/app.js` — rejestracja routera

### clair-hub-frontend

```
clair-hub-frontend/src/routes/minecraft/+page.svelte   # Strona panelu (3 zakładki)
```

**Zmodyfikowany:** `clair-hub-frontend/src/lib/hub-api.js` — 8 funkcji API

---

## Komendy bota Discord

### `/mc-link` — łączenie kont (każdy użytkownik)

| Subcommand | Opis |
|------------|------|
| `connect` | Generuje 6-znakowy kod (A-Z0-9). Użytkownik wpisuje `/link KODXYZ` na serwerze MC. Kod ważny 5 min. |
| `disconnect` | Rozłącza konto MC |
| `status` | Pokazuje powiązane konto (nick MC, UUID, data) |

### `/mc-server` — zarządzanie serwerami (admin)

| Subcommand | Opis |
|------------|------|
| `status` | Lista serwerów MC z statusem online/offline, liczbą graczy, wersją |
| `say <server> <message>` | Wysyła wiadomość na chat MC (prefiks `[Discord]`) |
| `kick <server> <player> [reason]` | Wyrzuca gracza z serwera MC |

### `/mc-profile` — profil MC

Pokazuje profil MC powiązanego gracza (avatar z mc-heads.net, nick, UUID, data linkowania). Opcjonalny parametr `@user`.

---

## Panel Hub — strona /minecraft

3 zakładki:

### Serwery
- Lista zarejestrowanych serwerów MC ze statusem online/offline
- Przycisk **Dodaj serwer** — podaj server_id i nazwę, secret generowany automatycznie
- Konfiguracja per serwer: kanał eventów, kanał chatu, feature toggles, dozwolone komendy
- Secret wyświetlany w panelu (do konfiguracji pluginu MC)

### Powiązane konta
- Tabela: Discord user ↔ MC nick/UUID, data powiązania
- Przycisk rozłącz

### Logi eventów
- Paginowana lista z filtrami po typie eventu i serwerze MC
- Typy: player_join, player_quit, player_death, link_success

---

## REST API (clair-api)

### Autentykacja

Header: `Authorization: Bearer <serverId>:<secret>`

Secret pobierany z tabeli `mc_servers` per guild (generowany w panelu Hub).

### Endpointy

| Metoda | Ścieżka | Opis |
|--------|---------|------|
| POST | `/mc-bridge/command` | Wysyła komendę do serwera MC (body: `{ serverId, cmd, payload }`) |
| GET | `/mc-bridge/servers` | Lista połączonych serwerów WS |

### Komendy (`cmd`)

| cmd | payload | Opis |
|-----|---------|------|
| `send_chat` | `{ message }` | Wyślij wiadomość na chat MC |
| `kick` | `{ playerName, reason }` | Wyrzuć gracza |
| `whitelist_add` | `{ playerName }` | Dodaj do whitelist |
| `run_console_command` | `{ command }` | Wykonaj komendę konsoli (sprawdzane z `allowed_commands`) |

---

## REST API (clair-hub)

Wszystkie endpointy wymagają sesji Hub (requireGuildId).

| Metoda | Ścieżka | Opis |
|--------|---------|------|
| GET | `/api/minecraft` | Overview (serwery + liczba powiązań) |
| GET | `/api/minecraft/servers` | Lista serwerów |
| POST | `/api/minecraft/servers` | Rejestracja nowego serwera (generuje secret) |
| PUT | `/api/minecraft/servers/:serverId` | Konfiguracja serwera |
| DELETE | `/api/minecraft/servers/:serverId` | Dezaktywacja serwera |
| GET | `/api/minecraft/links` | Lista powiązań kont |
| DELETE | `/api/minecraft/links/:linkId` | Rozłącz konto |
| GET | `/api/minecraft/events` | Logi eventów (paginacja, filtry: `event_type`, `server_id`) |

---

## Protokół WebSocket (Plugin ↔ Bridge)

Pełna specyfikacja: `DOC/MINECRAFT-INTEGRATION/CLAIRMC-BRIDGE-API.md`

### Podsumowanie

- **Ścieżka WS:** `/mc-bridge` (clair-api, port 4110 zewnętrzny / 4010 wewnętrzny)
- **Auth:** HMAC-SHA256 — canonical JSON (posortowane klucze rekursywnie) + Base64
- **Timestamp:** pole `ts` (Unix epoch seconds), okno ±60s
- **Heartbeat WS:** 30s ping/pong (niezależny od MC server_heartbeat)
- **Auth timeout:** 30s na handshake po połączeniu

### Typy wiadomości

| Typ | Kierunek | Opis |
|-----|----------|------|
| `handshake` | Plugin → Bridge | Pierwszy msg po połączeniu (brand, version, players) |
| `event` | Plugin → Bridge | player_join, player_quit, player_death, server_heartbeat |
| `request` | Plugin → Bridge | claim_link_code, unlink (z reqId, Bridge odpowiada `response`) |
| `response` | Bridge → Plugin | Odpowiedź na request (ok/error) |
| `command` | Bridge → Plugin | send_chat, kick, whitelist_add, run_console_command |
| `ack` | Plugin → Bridge | Potwierdzenie wykonania komendy |

---

## Konfiguracja nowego serwera MC — krok po kroku

1. **Panel Hub** → strona Minecraft → zakładka Serwery → **Dodaj serwer**
2. Podaj `server_id` (np. `survival-1`) i nazwę wyświetlaną
3. Panel generuje `secret` — skopiuj go
4. Skonfiguruj kanał eventów (SmartSelect) i feature toggles
5. W pluginie MC (`config.yml`):
   ```yaml
   bridge:
     url: "ws://ADRES_BRIDGE:4110/mc-bridge"
     serverId: "survival-1"
     secret: "SKOPIOWANY_SECRET"
   ```
6. Uruchom/zrestartuj plugin — powinien wykonać handshake
7. Sprawdź status: `/mc-server status` lub panel Hub

---

## Embedy Discord

| Event | Kolor | Emoji | Treść |
|-------|-------|-------|-------|
| player_join | 🟢 `#2ecc71` | 🟢 | **Nick** dołączył do serwera |
| player_quit | 🔴 `#e74c3c` | 🔴 | **Nick** opuścił serwer |
| player_death | ⚫ `#8b0000` | 💀 | Wiadomość śmierci lub **Nick** zginął |
| link_success | 🟡 `#f1c40f` | 🔗 | **Nick** został powiązany z @User |

---

## Troubleshooting

### Tabele mc_* nie istnieją
Migracja uruchamiana przy starcie bota. Jeśli clair-api wystartuje wcześniej niż bot, pokaże błąd `relation "mc_servers" does not exist`. Rozwiązanie: restart clair-api po starcie bota.

### Plugin nie łączy się (auth timeout)
- Sprawdź `serverId` i `secret` w konfiguracji pluginu
- Upewnij się, że serwer jest zarejestrowany w panelu Hub i ma `active = TRUE`
- Sprawdź timestamp — różnica zegarów > 60s spowoduje odrzucenie

### Brak powiadomień na Discord
- Sprawdź czy `event_channel_id` jest ustawiony w konfiguracji serwera (panel Hub)
- Sprawdź feature toggles (`feature_join_quit`, `feature_deaths`)
- Sprawdź logi bota: `docker compose logs clair-bot | grep McBridge`
- Fallback scheduler (30s) powinien wyłapać zaległe powiadomienia

### Komendy say/kick nie działają
- Serwer MC musi być online (połączony WS)
- Komenda `run_console_command` sprawdza `allowed_commands` — dodaj w panelu Hub
