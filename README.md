# ClairMC Bridge Plugin

Plugin Minecraft dla Paper/Purpur 1.21.x, który łączy serwer z ekosystemem Clair przez Bridge API. Projekt nie komunikuje się bezpośrednio z Discordem; utrzymuje jedno połączenie WebSocket do Bridge i wymienia podpisane wiadomości JSON.

## Zakres

- heartbeat serwera i podstawowe statystyki
- eventy `player_join`, `player_quit`, `player_death`
- komendy `/link <kod>` i `/unlink`
- komendy zdalne z Bridge, m.in. `send_chat`, `kick`, `whitelist_add`
- weryfikacja HMAC i kontrola `serverId` oraz `ts`

## Wymagania

- Java 21
- Paper lub Purpur 1.21.1+
- dostęp do Bridge API przez WebSocket

## Build

```bash
./gradlew clean build
```

Gotowy plik JAR trafia do `build/libs/clair-mc-bridge-<version>.jar`.

## Konfiguracja

Najważniejsze ustawienia znajdują się w `src/main/resources/config.yml`:

```yml
bridge:
  url: "wss://dev.clairbot.app/api/mc-bridge"
  serverId: "pogranicze-1"
  secret: "SUPER_TAJNY_TOKEN_SERWERA"
```

Nie commituj prawdziwych sekretów ani produkcyjnych identyfikatorów serwerów.

## Uruchomienie lokalne

1. Zbuduj plugin przez `./gradlew build`.
2. Skopiuj JAR do katalogu `plugins/` serwera Paper/Purpur.
3. Uzupełnij `plugins/ClairMCBridge/config.yml`.
4. Uruchom serwer i sprawdź log połączenia WS oraz handshake.

## Rozwój

Kod źródłowy znajduje się w `src/main/java/app/clair/mcbridge`:

- `bridge/` obsługuje WS, podpisy i dispatch wiadomości
- `commands/` zawiera komendy gracza
- `listeners/` zbiera eventy Bukkit
- `tools/` zawiera narzędzia pomocnicze, np. `SignatureProbe`

Dodatkowe notatki projektowe i opis protokołu są w katalogu `DOC/`.
