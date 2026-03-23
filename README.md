# ClairMC Bridge

Repo zawiera teraz trzy warianty integracji z Clair Bridge API:

- `paper-plugin` - plugin dla Paper/Purpur 1.21.x, Java 21
- `forge-server-mod` - serwerowy mod Forge dla Minecraft 1.20.1, Java 17
- `fabric-server-mod` - serwerowy mod Fabric dla Minecraft 1.20.1, Loader 0.17.2, Java 17
- `common` - wspólny rdzeń: WebSocket, HMAC, canonical JSON i dispatch komend

## Build

Budowa obu artefaktów:

```bash
./gradlew buildAll
```

Tylko plugin Paper:

```bash
./gradlew :paper-plugin:build
```

Tylko mod Forge:

```bash
./gradlew :forge-server-mod:build
```

Tylko mod Fabric:

```bash
./gradlew :fabric-server-mod:build
```

Narzędzie do testu podpisu:

```bash
./gradlew :common:probeSig -PprobeSecret=...
```

## Wyniki builda

- plugin Paper: `paper-plugin/build/libs/clair-mc-bridge-paper-<version>.jar`
- mod Forge: `forge-server-mod/build/libs/clair-mc-bridge-forge-<version>.jar`
- mod Fabric: `fabric-server-mod/build/libs/clair-mc-bridge-fabric-<version>.jar`

## Konfiguracja

Paper używa `paper-plugin/src/main/resources/config.yml`.

Forge używa stałego pliku konfiguracyjnego instancji serwera `config/clairmcbridge-common.toml`.

Fabric używa tego samego pliku instancji serwera: `config/clairmcbridge-common.toml`.

We wszystkich wariantach kluczowe pola pozostają takie same:

```text
bridge.url
bridge.serverId
bridge.secret
```

Domyślny endpoint to `wss://clairbot.app/api/mc-bridge`.
Domyślne `serverId` i `secret` są placeholderami i trzeba je podmienić przed uruchomieniem na produkcji.

## Zakres funkcji

Wszystkie warianty wspierają:

- heartbeat serwera
- eventy `player_join`, `player_quit`, `player_death`
- `/link <kod>` i `/unlink`
- zdalne komendy Bridge, w tym `send_chat`, `kick`, `whitelist_add`, `run_console_command`
- weryfikację HMAC, `serverId` i `ts`

## Uwagi

Forge i Fabric 1.20.1 nie udostępniają tego samego API TPS co Paper, więc w obu modułach TPS jest liczone z `mspt` jako przybliżenie `min(20, 1000 / mspt)`.

Pelna instrukcja administratora i gracza dla wszystkich wariantow jest w `DOC/UZYTKOWANIE-PAPER-I-FORGE.md`.
