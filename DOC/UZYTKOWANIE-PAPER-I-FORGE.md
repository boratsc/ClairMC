# Uzytkowanie ClairMC Bridge: Paper i Forge

Ten dokument opisuje obie wersje integracji:

- `paper-plugin` - plugin dla Paper/Purpur 1.21.x
- `forge-server-mod` - serwerowy mod Forge dla Minecraft 1.20.1

Instrukcja jest rozdzielona na dwie perspektywy:

- administrator serwera
- uzytkownik / gracz

## 1. Szybkie rozroznienie

### Paper

- artefakt: `paper-plugin/build/libs/clair-mc-bridge-paper-<wersja>.jar`
- miejsce instalacji: `plugins/`
- konfiguracja runtime: `plugins/ClairMCBridge/config.yml`
- wymagana platforma: Paper/Purpur 1.21.x
- wymagana Java: 21

### Forge

- artefakt: `forge-server-mod/build/libs/clair-mc-bridge-forge-<wersja>.jar`
- miejsce instalacji: `mods/`
- konfiguracja runtime: `config/clairmcbridge-common.toml`
- wymagana platforma: Forge 1.20.1 z linii 47.x
- wymagana Java: zgodna z serwerem Forge 1.20.1

## 2. Administrator: Paper

### Instalacja

1. Zbuduj projekt albo wez gotowy plik JAR.
2. Skopiuj plugin do katalogu `plugins/`.
3. Uruchom serwer raz.
4. Plugin utworzy katalog i plik konfiguracyjny:
   `plugins/ClairMCBridge/config.yml`
5. Zatrzymaj serwer.
6. Uzupelnij konfiguracje.
7. Uruchom serwer ponownie.

### Minimalna konfiguracja

Przyklad:

```yml
bridge:
  url: "wss://clairbot.app/api/mc-bridge"
  serverId: "twoj-server-id"
  secret: "twoj-sekret-bridge"

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

### Co oznaczaja opcje

- `bridge.url` - endpoint WebSocket Bridge
- `bridge.serverId` - identyfikator serwera z panelu Clair
- `bridge.secret` - sekret HMAC dla tego serwera
- `heartbeatSeconds` - co ile sekund wysylac heartbeat
- `sendJoinQuit` - raportowanie wejsc i wyjsc graczy
- `sendDeaths` - raportowanie smierci graczy
- `sendChat` - raportowanie zwyklego czatu Minecraft
- `sendAdvancements` - raportowanie advancementow
- `heartbeatPlayersList` - dolaczanie listy graczy do heartbeat
- `heartbeatTps` - dolaczanie TPS i MSPT do heartbeat
- `allowConsoleCommands` - dozwolone prefiksy komend, ktore Clair moze wykonac zdalnie

### Jak sprawdzic, czy dziala

Po starcie serwera szukaj w logu:

- `Connecting to Bridge WS`
- `Bridge WS connected`
- `Bridge handshake sent`

To oznacza, ze plugin polaczyl sie z Bridge.

### Co plugin obsluguje

- `/link <kod>`
- `/unlink`
- eventy `player_join`, `player_quit`, `player_death`
- heartbeat serwera
- opcjonalnie `mc_chat` i `advancement`
- zdalne komendy: `send_chat`, `kick`, `whitelist_add`, `run_console_command`

### Typowe problemy

- `Authentication timeout`
  - zwykle nieprawidlowy `serverId` albo `secret`
- brak polaczenia
  - sprawdz `bridge.url`
  - sprawdz dostep serwera do internetu
- brak reakcji na zdalna komende
  - sprawdz `commands.allowConsoleCommands`

## 3. Administrator: Forge

### Instalacja

1. Zbuduj projekt albo wez gotowy plik JAR.
2. Skopiuj mod do katalogu `mods/`.
3. Uruchom serwer raz.
4. Mod utworzy plik:
   `config/clairmcbridge-common.toml`
5. Zatrzymaj serwer.
6. Uzupelnij konfiguracje.
7. Uruchom serwer ponownie.

### Minimalna konfiguracja

Przyklad:

```toml
[bridge]
url = "wss://clairbot.app/api/mc-bridge"
serverId = "twoj-server-id"
secret = "twoj-sekret-bridge"

[features]
heartbeatSeconds = 30
sendJoinQuit = true
sendDeaths = true
sendChat = false
sendAdvancements = false
heartbeatPlayersList = false
heartbeatTps = false

[commands]
allowConsoleCommands = ["say", "whitelist add", "kick", "ban"]
```

### Co oznaczaja opcje

Pola maja ten sam sens co w wersji Paper:

- `bridge.url`
- `bridge.serverId`
- `bridge.secret`
- `features.*`
- `commands.allowConsoleCommands`

### Jak sprawdzic, czy dziala

Po starcie serwera szukaj w logu:

- `Connecting to Bridge WS`
- `Bridge WS connected`
- `Bridge handshake sent`

### Co mod obsluguje

- `/link <kod>`
- `/unlink`
- eventy `player_join`, `player_quit`, `player_death`
- heartbeat serwera
- opcjonalnie `mc_chat` i `advancement`
- zdalne komendy: `send_chat`, `kick`, `whitelist_add`, `run_console_command`

### Typowe problemy

- config nadpisuje sie po restarcie
  - edytuj `config/clairmcbridge-common.toml`, nie `world/serverconfig/...`
- `Authentication timeout`
  - sprawdz `serverId` i `secret`
- mod sie laduje, ale nie wysyla handshake
  - sprawdz log pod katem bledow WebSocket i ruch wychodzacy z hosta

### Uwagi specyficzne dla Forge

- to jest mod tylko serwerowy, gracze nie potrzebuja go po stronie klienta
- TPS w Forge sa liczone przyblizeniowo na podstawie `mspt`

## 4. Uzytkownik / gracz

Z perspektywy gracza Paper i Forge dzialaja tak samo.

### Polaczenie konta

Jesli masz kod z Clair lub z bota, wpisz:

```text
/link <kod>
```

Przyklad:

```text
/link ABC123
```

Po sukcesie gracz dostanie komunikat:

```text
[Clair] Konto zostalo zlinkowane.
```

### Odlaczenie konta

Jesli chcesz usunac powiazanie:

```text
/unlink
```

Po sukcesie gracz dostanie:

```text
[Clair] Powiazanie zostalo usuniete.
```

### O czym gracz powinien wiedziec

- `/link` dziala tylko dla gracza, nie z konsoli
- trzeba podac dokladnie jeden kod
- jesli Bridge nie odpowiada, pojawi sie komunikat o bledzie lub timeout
- jesli administrator wlaczyl odpowiednie opcje, Clair moze widziec:
  - wejscie i wyjscie z serwera
  - smierc
  - zwykly czat
  - advancementy

## 5. Bezpieczenstwo i dobre praktyki

- nie commituj prawdziwego `secret`
- nie zostawiaj przykladowych placeholderow na produkcji
- ogranicz `allowConsoleCommands` do naprawde potrzebnych prefiksow
- po zmianie `serverId` lub `secret` wykonaj restart serwera
- po aktualizacji JAR-a sprawdz log i handshake po starcie

## 6. Szybki skrot

### Administrator

- Paper: wrzuc do `plugins/`, skonfiguruj `plugins/ClairMCBridge/config.yml`
- Forge: wrzuc do `mods/`, skonfiguruj `config/clairmcbridge-common.toml`
- w obu przypadkach ustaw:
  - `bridge.url`
  - `bridge.serverId`
  - `bridge.secret`

### Gracz

- linkowanie: `/link <kod>`
- odpiecie: `/unlink`
