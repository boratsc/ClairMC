# Clair Game Bridge ↔ Minecraft Plugin — Protokół (MVP)

Ten dokument opisuje oczekiwany kontrakt komunikacji pomiędzy pluginem Paper/Purpur (`clair-mc-bridge`) a Bridge (`clair-game-bridge`).

Zakres MVP:
- WebSocket (jedno stałe połączenie serwera MC → Bridge)
- Auth: HMAC + timestamp anti-replay
- Eventy: `server_heartbeat`, `player_join`, `player_quit`, `player_death`, `advancement` (opcjonalnie)
- Requesty: `claim_link_code`, `unlink`
- Komendy: `send_chat`, `whitelist_add`, `kick`, `run_console_command`, `get_player_list`, `get_tps` + ACK

---

## 1. Transport

- Protokół: WebSocket
- Kierunek połączenia: plugin łączy się do Bridge
- Ścieżka WS: `/mc-bridge`
- Przykładowy URL: `wss://dev.clairbot.app/api/mc-bridge` (DEV)
- Bridge powinien utrzymywać routing po `serverId`.

### 1.1. Zasady połączenia

- Po zestawieniu WS plugin wysyła wiadomość `handshake`.
- Bridge może odsyłać komendy w dowolnym momencie po handshake.
- Rekonekt: plugin ponawia połączenie z backoffem.

---

## 2. Autoryzacja i bezpieczeństwo

### 2.1. Konfiguracja

Plugin posiada:
- `bridge.serverId` — identyfikator serwera (string)
- `bridge.secret` — sekret współdzielony (string)

### 2.2. Timestamp i okno czasowe

- Każda wiadomość musi zawierać `ts` (Unix epoch seconds).
- Bridge odrzuca wiadomości, których `ts` jest poza oknem (np. > 60s różnicy względem czasu Bridge).

### 2.3. Podpis HMAC

Każda wiadomość (w obie strony) musi zawierać `signature`:

- `signature = Base64(HMAC_SHA256(secret, canonical_message_json))`
- `canonical_message_json` to kanoniczny JSON całej wiadomości **bez pola** `signature`.

#### Kanonizacja JSON

Żeby podpis był deterministyczny:
- obiekty JSON są serializowane z kluczami posortowanymi leksykograficznie (rekurencyjnie)
- tablice zachowują kolejność
- JSON jest zminimalizowany (bez białych znaków)

Weryfikacja:
- jeśli `signature` jest niepoprawne → wiadomość jest ignorowana i logowana jako ostrzeżenie
- jeśli `bridge.secret` jest puste, integracja nie powinna być uznana za bezpieczną (MVP może logować ostrzeżenie).

---

## 3. Wspólny format wiadomości

Wszystkie wiadomości mają co najmniej:

```json
{
  "type": "...",
  "serverId": "pogranicze-1",
  "ts": 1738976400,
  "signature": "..."
}
```

Pole `payload` jest opcjonalne (gdy brak, traktujemy jak `{}` w kanonizacji).

---

## 4. Typy wiadomości

### 4.1. `handshake` (Plugin → Bridge)

```json
{
  "type": "handshake",
  "serverId": "pogranicze-1",
  "ts": 1738976400,
  "payload": {
    "brand": "Paper",
    "version": "1.21.10",
    "playersMax": 60
  },
  "signature": "..."
}
```

### 4.2. `event` (Plugin → Bridge)

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

### 4.3. `request` (Plugin → Bridge)

Plugin wysyła request z `id`, Bridge odsyła `response` o tym samym `id`.

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

### 4.4. `response` (Bridge → Plugin)

```json
{
  "type": "response",
  "id": "req_9f2a1b2c3d4e",
  "serverId": "pogranicze-1",
  "ts": 1738976401,
  "ok": true,
  "payload": {},
  "signature": "..."
}
```

Konwencja pól:
- `ok`: boolean
- `error`: string (gdy `ok=false`); gdy brak błędu pole powinno być pominięte
- `payload`: obiekt zależny od requestu (może być pusty)

### 4.5. `command` (Bridge → Plugin)

```json
{
  "type": "command",
  "cmd": "send_chat",
  "id": "cmd_9f2a",
  "serverId": "pogranicze-1",
  "ts": 1738976500,
  "payload": {
    "message": "[Clair] Witajcie na evencie!"
  },
  "signature": "..."
}
```

### 4.6. `ack` (Plugin → Bridge)

ACK zawsze odnosi się do `command.id`.
`payload` w ACK może zawierać dane zwrotne (np. dla komend typu `get_*`).

```json
{
  "type": "ack",
  "id": "cmd_9f2a",
  "serverId": "pogranicze-1",
  "ts": 1738976501,
  "ok": true,
  "error": null,
  "payload": {},
  "signature": "..."
}
```

---

## 5. Eventy (MVP)

### 5.1. `server_heartbeat`

`payload.status`:
- `online` boolean
- `playersOnline` number
- `playersMax` number
- `version` string
- `brand` string
- `players` array (opcjonalnie, lista nicków online)
- `tps` number (opcjonalnie, TPS)
- `mspt` number (opcjonalnie, MSPT)

### 5.2. `player_join` / `player_quit`

`payload.player`:
- `uuid` string
- `name` string

### 5.3. `player_death`

`payload.player` jak wyżej oraz opcjonalnie:
- `message` string

### 5.4. `advancement`

`payload.player` jak wyżej oraz:
- `advancement.key` string (np. `minecraft:story/mine_diamond`)
- `advancement.title` string (plain text)
- `advancement.description` string (plain text)
- `advancement.frame` string (`task` | `goal` | `challenge`)

---

## 6. Requesty (MVP)

### 6.1. `claim_link_code`

Wejście (`payload`):
- `code` (string)
- `player.uuid` (string)
- `player.name` (string)

Wyjście (`response`):
- `ok=true` jeśli kod poprawny i zapisano mapowanie
- `ok=false` + `error` jeśli kod nie istnieje / wygasł / już użyty

### 6.2. `unlink`

Wejście (`payload`):
- `player.uuid` (string)
- `player.name` (string)

Wyjście (`response`):
- `ok=true` jeśli usunięto powiązanie (lub było już puste — decyzja Bridge)
- `ok=false` + `error` jeśli np. brak uprawnień

---

## 7. Komendy (MVP)

### 7.1. `send_chat`

Wejście (`payload`):
- `message` (string) — tekst do broadcastu w grze

### 7.2. `whitelist_add`

Wejście (`payload`):
- `playerName` (string)

### 7.3. `kick`

Wejście (`payload`):
- `playerName` (string)
- `reason` (string, opcjonalne)

### 7.4. `run_console_command`

Wejście (`payload`):
- `command` (string)

### 7.5. `get_player_list`

Wejście (`payload`):
- `{}`

ACK `payload`:
- `players` array obiektów `{ name, uuid, health, world }`
- `count` number
- `max` number

### 7.6. `get_tps`

Wejście (`payload`):
- `{}`

ACK `payload`:
- `tps` number
- `mspt` number

Bridge powinien wysyłać tylko komendy z allowlisty skonfigurowanej na serwerze MC.

---

## 8. Kody błędów (proponowane)

`error` jako string (do logów i UI):
- `invalid_signature`
- `timestamp_out_of_range`
- `unknown_server`
- `command_not_allowed`
- `player_not_online`
- `link_code_not_found`
- `link_code_expired`
- `link_code_already_used`

---

## 9. Pseudokod Node.js (Bridge)

Poniżej jest pseudokod pokazujący:
- kanonizację JSON (sortowanie kluczy rekurencyjnie)
- liczenie podpisu HMAC i weryfikację (`timingSafeEqual`)
- walidację `ts` (okno 60s)
- szkic obsługi WebSocket z routingiem po `serverId`

### 9.1. Kanonizacja + HMAC

```js
// Node.js pseudo-code
import crypto from 'node:crypto'

function normalizeJson(value) {
  if (value === null || value === undefined) return null
  if (Array.isArray(value)) return value.map(normalizeJson)
  if (typeof value === 'object') {
    const out = {}
    for (const key of Object.keys(value).sort()) {
      out[key] = normalizeJson(value[key])
    }
    return out
  }
  // string | number | boolean
  return value
}

function canonicalStringify(messageWithoutSignature) {
  // JSON.stringify bez spacji daje minimalny JSON
  // a normalizeJson gwarantuje stabilne sortowanie kluczy
  return JSON.stringify(normalizeJson(messageWithoutSignature))
}

function signMessage(secret, messageObj) {
  const copy = { ...messageObj }
  delete copy.signature
  if (copy.payload === undefined) copy.payload = {}

  const canonical = canonicalStringify(copy)
  const sig = crypto
    .createHmac('sha256', Buffer.from(secret, 'utf8'))
    .update(Buffer.from(canonical, 'utf8'))
    .digest('base64')

  return sig
}

function verifySignature(secret, messageObj) {
  if (!messageObj || typeof messageObj !== 'object') return false
  if (!messageObj.signature) return false

  const expected = signMessage(secret, messageObj)
  const a = Buffer.from(expected, 'utf8')
  const b = Buffer.from(String(messageObj.signature), 'utf8')

  // timingSafeEqual wymaga równej długości
  if (a.length !== b.length) return false
  return crypto.timingSafeEqual(a, b)
}

function verifyTimestamp(messageObj, windowSeconds = 60) {
  const ts = Number(messageObj?.ts)
  if (!Number.isFinite(ts)) return false
  const now = Math.floor(Date.now() / 1000)
  return Math.abs(now - ts) <= windowSeconds
}
```

### 9.2. WebSocket serwer (routing po serverId)

Założenia:
- trzymasz mapę `serverId -> { secret, ws }`
- `secret` jest znany Bridge (np. w DB)
- każda wiadomość jest walidowana: `serverId`, `ts`, `signature`

```js
// Node.js pseudo-code (np. biblioteka 'ws')
import { WebSocketServer } from 'ws'

const wss = new WebSocketServer({ port: 8080 })

// Example registry; docelowo: DB + cache
const servers = new Map()
// servers.set('pogranicze-1', { secret: 'SUPER_TAJNY_TOKEN_SERWERA', ws: null, lastSeen: 0 })

function getServer(serverId) {
  return servers.get(serverId) || null
}

function send(ws, obj) {
  ws.send(JSON.stringify(obj))
}

function sendResponse(ws, serverId, secret, reqId, ok, error, payload = {}) {
  const msg = {
    type: 'response',
    id: reqId,
    serverId,
    ts: Math.floor(Date.now() / 1000),
    ok,
    error: error ?? null,
    payload: payload ?? {},
  }
  msg.signature = signMessage(secret, msg)
  send(ws, msg)
}

function sendCommand(ws, serverId, secret, cmd, id, payload) {
  const msg = {
    type: 'command',
    cmd,
    id,
    serverId,
    ts: Math.floor(Date.now() / 1000),
    payload: payload ?? {},
  }
  msg.signature = signMessage(secret, msg)
  send(ws, msg)
}

wss.on('connection', (ws) => {
  ws.on('message', (raw) => {
    let msg
    try {
      msg = JSON.parse(String(raw))
    } catch {
      return
    }

    const serverId = String(msg?.serverId || '')
    const server = getServer(serverId)
    if (!server) return

    if (!verifyTimestamp(msg, 60)) return
    if (!verifySignature(server.secret, msg)) return

    server.lastSeen = Math.floor(Date.now() / 1000)

    // (opcjonalnie) przypięcie połączenia do serverId
    server.ws = ws

    switch (msg.type) {
      case 'handshake': {
        // np. zapisz brand/version/playersMax w DB
        // a potem możesz odesłać response/ack jeśli chcesz (nie wymagane)
        break
      }
      case 'event': {
        // event routing do bota/panelu
        // msg.event + msg.payload
        break
      }
      case 'request': {
        const req = String(msg.req || '')
        const id = String(msg.id || '')
        const payload = msg.payload || {}

        if (!id) return

        if (req === 'claim_link_code') {
          // TODO: sprawdź kod, zapisz mapowanie
          // sendResponse(ws, serverId, server.secret, id, true, null, {})
        } else if (req === 'unlink') {
          // TODO: usuń mapowanie
          // sendResponse(ws, serverId, server.secret, id, true, null, {})
        } else {
          sendResponse(ws, serverId, server.secret, id, false, 'unknown_request', {})
        }
        break
      }
      case 'ack': {
        // potwierdzenie komendy: msg.id, msg.ok, msg.error
        break
      }
      default:
        break
    }
  })
})
```

### 9.3. Uwagi praktyczne

- Podpisuj i weryfikuj **całą** wiadomość (bez `signature`), nie tylko `payload`.
- Zawsze dokładaj `payload: {}` jeśli payload jest pusty/brak — upraszcza kanonizację.
- Dla bezpieczeństwa porównuj podpis przez `crypto.timingSafeEqual`.
- Jeśli będziesz robił load-balancing Bridge, potrzebujesz współdzielonego storage dla `lastSeen`/pending requestów albo sticky sessions.

---

## 10. Backend — co dokładnie poprawić (żeby nie było `invalid signature`)

Objaw, który widzimy w integracji:
- `clair-api` poprawnie **weryfikuje requesty** z pluginu (bo linkowanie faktycznie zachodzi),
- ale plugin odrzuca `type=response` z powodu **niezgodnego podpisu**.

To prawie zawsze oznacza, że backend:
- **podpisuje inną treść niż wysyła** (mutacja pola po podpisaniu), albo
- podpisuje **inny zestaw pól** (np. tylko `payload`, albo przypadkowo wlicza `signature`), albo
- używa innej kanonizacji (np. `JSON.stringify` bez sortowania) / innego formatu `ts`.

Poniżej jest dokładna rozpiska zmian po stronie backendu (`clair-api/src/mc-bridge/...`).

### 10.1. Zasada #1: jedna implementacja signing/verification dla wszystkich wiadomości

Zrób jeden serwis (np. `HmacService.js`) i używaj go:
- w **verify** dla wiadomości od pluginu (`handshake/event/request/ack`)
- w **sign** dla wiadomości do pluginu (`response/command`)

NIE duplikuj logiki w kilku miejscach.

### 10.2. Zasada #2: podpisuj dokładnie to, co wysyłasz (zero mutacji po podpisaniu)

W praktyce:
- Zbuduj obiekt `msg` z kompletem pól (`type/serverId/ts/...`) **w finalnej postaci**
- Dopiero na końcu: `msg.signature = sign(msg, secret)`
- Po ustawieniu `signature` nic w `msg` nie może się już zmienić

Najczęstszy bug: backend robi `signature = sign(msg)` a potem dopiero ustawia `ts`, `ok`, `error`, `payload`, `id` albo “czyści” payload.

### 10.3. Format wejścia do HMAC (MUSI być identyczny jak w pluginie)

Backend ma liczyć HMAC-SHA256 po:
- kanonicznym JSON całej wiadomości
- **bez pola** `signature`
- z regułą: jeśli `payload` nie istnieje → traktuj jak `{}`

Uwaga (ważne dla kompatybilności):
- Po stronie Java/Gson w pluginie właściwości o wartości `null` mogą zostać pominięte w serializacji `JsonElement`.
- Żeby nie rozjeżdżać podpisów, backend powinien **pomijać pole `error`**, jeśli nie ma błędu, zamiast wysyłać `"error": null`.

Kanonizacja musi być deterministyczna:
- obiekty: klucze sortowane leksykograficznie rekurencyjnie
- tablice: kolejność zachowana
- `null` zostaje `null`
- `undefined` nie może „pływać”: albo zamień na `null`, albo usuń — ale konsekwentnie (zalecane: `undefined -> null` w normalizacji)

### 10.4. Timestamp (`ts`) — sekundy, nie milisekundy

Plugin używa `Instant.now().getEpochSecond()`.
Backend musi używać:
- `Math.floor(Date.now() / 1000)`

Jeśli podpisujesz `ts` w milisekundach, a wysyłasz w sekundach (albo odwrotnie), podpis nigdy nie będzie pasował.

### 10.5. Base64

Ustal jeden format na backendzie i trzymaj się go wszędzie:
- `digest('base64')` (standard Base64)

Weryfikację rób przez porównanie bajtów, nie stringów:
- zdekoduj `provided` z Base64 do bajtów
- policz `mac` i porównaj `timingSafeEqual(mac, providedBytes)`

To eliminuje problemy typu Base64URL/padding.

### 10.6. Referencyjna implementacja (wklej 1:1 do `HmacService.js` i używaj wszędzie)

```js
import crypto from 'node:crypto'

function normalizeJson(value) {
  if (value === undefined || value === null) return null
  if (Array.isArray(value)) return value.map(normalizeJson)
  if (typeof value === 'object') {
    const out = {}
    for (const key of Object.keys(value).sort()) {
      out[key] = normalizeJson(value[key])
    }
    return out
  }
  return value // string | number | boolean
}

export function canonicalStringify(messageWithoutSignature) {
  return JSON.stringify(normalizeJson(messageWithoutSignature))
}

export function signMessage(secret, messageObj) {
  // ZAWSZE podpisuj kopię bez signature
  const copy = { ...messageObj }
  delete copy.signature
  if (copy.payload === undefined) copy.payload = {}

  const canonical = canonicalStringify(copy)
  return crypto
    .createHmac('sha256', Buffer.from(secret, 'utf8'))
    .update(Buffer.from(canonical, 'utf8'))
    .digest('base64')
}

export function verifyMessage(secret, messageObj) {
  if (!messageObj?.signature) return false
  const expectedSig = signMessage(secret, messageObj)

  // Porównuj po bajtach HMAC, nie po stringach
  const providedBytes = Buffer.from(String(messageObj.signature).trim(), 'base64')
  const expectedBytes = Buffer.from(expectedSig, 'base64')
  if (providedBytes.length !== expectedBytes.length) return false
  return crypto.timingSafeEqual(providedBytes, expectedBytes)
}
```

### 10.7. Gdzie to podpiąć (konkretnie)

1) `McBridgeHub` (obsługa WS):
- po `JSON.parse` → `verifyMessage(server.secret, msg)`

2) `sendResponse(...)` / `sendCommand(...)`:
- zbuduj obiekt
- ustaw `payload` (nawet `{}`)
- ustaw `error: null` jeśli brak
- ustaw `ts = Math.floor(Date.now()/1000)`
- na końcu: `msg.signature = signMessage(secret, msg)`
- od razu `ws.send(JSON.stringify(msg))`

### 10.8. Test, który ma przechodzić (najważniejszy)

Dodaj test jednostkowy:
- tworzysz `msg` (np. response)
- robisz `msg.signature = signMessage(secret, msg)`
- asercja: `verifyMessage(secret, msg) === true`

Jeśli ten test nie przechodzi, to znaczy że:
- kanonizacja albo `payload` default różnią się między sign i verify, albo
- obiekt jest mutowany pomiędzy sign a verify.

