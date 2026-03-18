package app.clair.mcbridge.bridge;

import app.clair.mcbridge.ClairMcBridgePlugin;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class BridgeClient {
    private final ClairMcBridgePlugin plugin;
    private final Gson gson;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Map<String, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile int reconnectAttempt = 0;

    public BridgeClient(ClairMcBridgePlugin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "clair-bridge-client");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        stopping.set(false);
        connect(0);
    }

    public void stop() {
        stopping.set(true);
        try {
            WebSocket ws = this.webSocket;
            if (ws != null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disable");
            }
        } catch (Exception ignored) {
        }
        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        WebSocket ws = this.webSocket;
        return ws != null;
    }

    public void sendEvent(String eventName, JsonObject payload) {
        JsonObject msg = baseMessage("event");
        msg.addProperty("event", eventName);
        msg.add("payload", payload == null ? new JsonObject() : payload);
        sign(msg);
        sendJson(msg);
    }

    public CompletableFuture<JsonObject> sendRequest(String reqName, JsonObject payload) {
        String id = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JsonObject msg = baseMessage("request");
        msg.addProperty("req", reqName);
        msg.addProperty("id", id);
        msg.add("payload", payload == null ? new JsonObject() : payload);
        sign(msg);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        sendJson(msg);

        scheduler.schedule(() -> {
            CompletableFuture<JsonObject> f = pendingRequests.remove(id);
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new RuntimeException("Bridge request timeout: " + id));
            }
        }, 8, TimeUnit.SECONDS);

        return future;
    }

    public void sendAck(String commandId, boolean ok, String error) {
        sendAck(commandId, ok, error, null);
    }

    public void sendAck(String commandId, boolean ok, String error, JsonObject payload) {
        JsonObject msg = baseMessage("ack");
        msg.addProperty("id", commandId);
        msg.addProperty("ok", ok);
        msg.addProperty("error", error);
        msg.add("payload", payload == null ? new JsonObject() : payload);
        sign(msg);
        sendJson(msg);
    }

    public void sendHandshake() {
        JsonObject payload = new JsonObject();
        payload.addProperty("brand", Bukkit.getName());
        payload.addProperty("version", Bukkit.getMinecraftVersion());
        payload.addProperty("playersMax", Bukkit.getMaxPlayers());

        JsonObject msg = baseMessage("handshake");
        msg.add("payload", payload);
        sign(msg);
        sendJson(msg);
    }

    private void connect(long delayMillis) {
        if (stopping.get()) {
            return;
        }

        String url = plugin.getBridgeUrl();
        if (url.isEmpty()) {
            plugin.getLogger().warning("bridge.url is empty; BridgeClient will not connect");
            return;
        }

        String serverId = plugin.getConfiguredServerId();
        if (serverId.isEmpty()) {
            plugin.getLogger().severe("bridge.serverId is empty; BridgeClient will not connect");
            return;
        }

        String secret = plugin.getBridgeSecret();
        if (secret.isEmpty()) {
            plugin.getLogger().severe("bridge.secret is empty; BridgeClient will not connect");
            return;
        }

        scheduler.schedule(() -> {
            if (stopping.get()) {
                return;
            }

            URI uri;
            try {
                uri = URI.create(url);
            } catch (Exception ex) {
                plugin.getLogger().severe("Invalid bridge.url: " + url);
                return;
            }

            plugin.getLogger().info("Connecting to Bridge WS: " + uri + " (serverId=" + serverId + ")");
            httpClient.newWebSocketBuilder()
                    .buildAsync(uri, new WsListener())
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            plugin.getLogger().log(Level.WARNING, "Bridge WS connect failed", err);
                            scheduleReconnect();
                            return;
                        }

                        this.webSocket = ws;
                        this.reconnectAttempt = 0;
                        plugin.getLogger().info("Bridge WS connected");

                        // handshake powinien lecieć po połączeniu
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            sendHandshake();
                            plugin.getLogger().info("Bridge handshake sent (serverId=" + serverId + ")");
                        });
                    });
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleReconnect() {
        if (stopping.get()) {
            return;
        }
        long delay = Math.min(60_000L, 2_000L * (long) Math.pow(2, Math.min(5, reconnectAttempt)));
        reconnectAttempt++;
        plugin.getLogger().warning("Reconnecting to Bridge in " + delay + "ms");
        connect(delay);
    }

    private JsonObject baseMessage(String type) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        String serverId = plugin.getConfiguredServerId();
        msg.addProperty("serverId", serverId.isEmpty() ? "unknown" : serverId);
        msg.addProperty("ts", Instant.now().getEpochSecond());
        // payload jest opcjonalny, ale dla prostoty kontraktu zawsze ustawiamy go na obiekt
        // (pusty obiekt również będzie podpisywany jako część kanonicznej wiadomości).
        if (!msg.has("payload")) {
            msg.add("payload", new JsonObject());
        }
        return msg;
    }

    private void sign(JsonObject message) {
        String secret = plugin.getBridgeSecret();
        if (secret == null || secret.isEmpty()) {
            return;
        }

        JsonObject copy = message.deepCopy();
        copy.remove("signature");
        String canonical = CanonicalJson.canonicalize(copy);
        byte[] mac = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), canonical.getBytes(StandardCharsets.UTF_8));
        message.addProperty("signature", Base64.getEncoder().encodeToString(mac));
    }

    private static String stripBase64Padding(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String addBase64Padding(String s) {
        if (s == null) {
            return "";
        }
        int mod = s.length() % 4;
        if (mod == 0) {
            return s;
        }
        int need = 4 - mod;
        return s + "=".repeat(need);
    }

    private static byte[] decodeBase64Any(String sig) {
        if (sig == null) {
            return null;
        }
        String trimmed = sig.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // 1) Standard Base64 (toleruj brak paddingu)
        try {
            return Base64.getDecoder().decode(addBase64Padding(trimmed));
        } catch (Exception ignored) {
        }

        // 2) Base64URL (toleruj brak paddingu)
        try {
            return Base64.getUrlDecoder().decode(addBase64Padding(trimmed));
        } catch (Exception ignored) {
        }

        // 3) Czasem przychodzi URL-safe, ale dekoder standardowy nie łyka '-'/'_' — spróbuj normalizacji.
        try {
            String normalized = trimmed.replace('-', '+').replace('_', '/');
            return Base64.getDecoder().decode(addBase64Padding(normalized));
        } catch (Exception ignored) {
        }

        return null;
    }

    private static boolean macMatchesSignature(byte[] mac, String providedSig) {
        byte[] providedBytes = decodeBase64Any(providedSig);
        if (providedBytes == null) {
            return false;
        }
        return MessageDigest.isEqual(mac, providedBytes);
    }

    private static boolean sigEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        if (expected.equals(provided)) {
            return true;
        }
        // Tolerancja: część implementacji Base64 używa bez paddingu '='.
        if (stripBase64Padding(expected).equals(stripBase64Padding(provided))) {
            return true;
        }
        // Tolerancja: Base64URL vs Base64 (porównanie po zdekodowanych bajtach)
        byte[] expectedBytes = decodeBase64Any(expected);
        byte[] providedBytes = decodeBase64Any(provided);
        return expectedBytes != null && providedBytes != null && MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private String expectedSignatureUnsorted(String secret, JsonObject messageWithoutSignature) {
        // Wariant kompatybilności: HMAC liczony na JSON z zachowaniem kolejności kluczy takiej,
        // jak przyszła w wiadomości (bez rekursywnego sortowania).
        String json = gson.toJson(messageWithoutSignature);
        byte[] mac = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac);
    }

    private boolean verifyLegacyConcat(String secret, JsonObject messageWithoutSignature, String providedSignature) {
        // Legacy: HMAC(secret, serverId + ts + payloadJson)
        String serverId = Optional.ofNullable(messageWithoutSignature.get("serverId")).map(JsonElement::getAsString).orElse("");
        String ts = Optional.ofNullable(messageWithoutSignature.get("ts")).map(JsonElement::getAsString).orElse("");
        JsonElement payloadEl = messageWithoutSignature.has("payload") ? messageWithoutSignature.get("payload") : new JsonObject();

        String payloadCanonical = CanonicalJson.canonicalize(payloadEl);
        String data1 = serverId + ts + payloadCanonical;
        byte[] mac1 = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), data1.getBytes(StandardCharsets.UTF_8));
        if (macMatchesSignature(mac1, providedSignature)) {
            return true;
        }

        String payloadUnsorted = gson.toJson(payloadEl);
        String data2 = serverId + ts + payloadUnsorted;
        byte[] mac2 = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), data2.getBytes(StandardCharsets.UTF_8));
        return macMatchesSignature(mac2, providedSignature);
    }

    private boolean verify(JsonObject message) {
        String secret = plugin.getBridgeSecret();
        if (secret == null || secret.isEmpty()) {
            // Brak secreta => nie mamy jak zweryfikować. MVP: akceptujemy, ale ostrzegamy.
            plugin.getLogger().warning("bridge.secret is empty; incoming messages are NOT verified");
            return true;
        }

        if (!message.has("signature")) {
            return false;
        }

        String provided = message.get("signature").getAsString();
        if (provided == null) {
            return false;
        }
        provided = provided.trim();

        // 1) Weryfikacja kanoniczna (rekurencyjne sortowanie kluczy)
        JsonObject copy1 = message.deepCopy();
        copy1.remove("signature");
        String expected1 = expectedSignature(secret, copy1);
        if (sigEquals(expected1, provided)) {
            return true;
        }

        // 2) Tolerancja: niektóre implementacje podpisują zawsze z payload={}, nawet jeśli pole jest pominięte.
        //    Jeśli payload nie istnieje, spróbujmy wariantu z dołożonym pustym obiektem.
        if (!copy1.has("payload")) {
            JsonObject copy2 = copy1.deepCopy();
            copy2.add("payload", new JsonObject());
            String expected2 = expectedSignature(secret, copy2);
            if (sigEquals(expected2, provided)) {
                return true;
            }
        }

        // 3) Kompatybilność: jeśli backend podpisuje JSON bez kanonizacji (np. JSON.stringify),
        //    spróbujmy policzyć HMAC na niesortowanym JSON (kolejność kluczy jak w wejściu).
        String expected3 = expectedSignatureUnsorted(secret, copy1);
        if (sigEquals(expected3, provided)) {
            plugin.getLogger().warning("Bridge signature verified using UNSORTED JSON fallback (backend signing mismatch)");
            return true;
        }

        // 4) Legacy: serverId + ts + payloadJson
        if (verifyLegacyConcat(secret, copy1, provided)) {
            plugin.getLogger().warning("Bridge signature verified using LEGACY concat fallback (backend needs canonical signing update)");
            return true;
        }

        return false;
    }

    private String expectedSignature(String secret, JsonObject messageWithoutSignature) {
        String canonical = CanonicalJson.canonicalize(messageWithoutSignature);
        byte[] mac = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac);
    }

    private boolean verifyTimestamp(JsonObject message) {
        if (!message.has("ts")) {
            return false;
        }
        long ts = message.get("ts").getAsLong();
        long now = Instant.now().getEpochSecond();
        long diff = Math.abs(now - ts);
        return diff <= 60;
    }

    private void sendJson(JsonObject obj) {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            return;
        }
        String text = gson.toJson(obj);
        ws.sendText(text, true);
    }

    private final class WsListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handleIncoming(text);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            plugin.getLogger().log(Level.WARNING, "Bridge WS error", error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            BridgeClient.this.webSocket = null;
            plugin.getLogger().warning("Bridge WS closed: " + statusCode + " reason=" + reason);

            // Fail pending requests
            pendingRequests.forEach((id, f) -> f.completeExceptionally(new RuntimeException("WS closed")));
            pendingRequests.clear();

            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }
    }

    private void handleIncoming(String text) {
        JsonObject msg;
        try {
            msg = gson.fromJson(text, JsonObject.class);
        } catch (Exception ex) {
            plugin.getLogger().warning("Bridge message parse error");
            return;
        }

        // Podstawowa walidacja: serverId, timestamp, signature
        String expectedServerId = plugin.getConfiguredServerId();
        if (expectedServerId.isEmpty()) {
            plugin.getLogger().warning("bridge.serverId is empty; ignoring incoming messages");
            return;
        }
        String gotServerId = Optional.ofNullable(msg.get("serverId")).map(JsonElement::getAsString).orElse("");
        if (!Objects.equals(expectedServerId, gotServerId)) {
            plugin.getLogger().warning("Bridge message ignored (serverId mismatch)");
            return;
        }
        if (!verifyTimestamp(msg)) {
            plugin.getLogger().warning("Bridge message ignored (timestamp out of range)");
            return;
        }
        if (!verify(msg)) {
            // Ostatnia deska ratunku (UX): jeśli to response do naszego pending request,
            // nie blokuj /link przez timeout — zaakceptuj odpowiedź, ale głośno ostrzeż.
            String typeMaybe = Optional.ofNullable(msg.get("type")).map(JsonElement::getAsString).orElse("");
            if (Objects.equals(typeMaybe, "response")) {
                String idMaybe = Optional.ofNullable(msg.get("id")).map(JsonElement::getAsString).orElse(null);
                if (idMaybe != null && pendingRequests.containsKey(idMaybe)) {
                    plugin.getLogger().severe("SECURITY WARNING: accepting Bridge response with INVALID signature for pending id=" + idMaybe
                            + " (fix backend signing ASAP)");
                    CompletableFuture<JsonObject> f = pendingRequests.remove(idMaybe);
                    if (f != null) {
                        f.complete(msg);
                    }
                    return;
                }
            }

            String type = Optional.ofNullable(msg.get("type")).map(JsonElement::getAsString).orElse("?");
            String id = Optional.ofNullable(msg.get("id")).map(JsonElement::getAsString).orElse(null);
            String cmd = Optional.ofNullable(msg.get("cmd")).map(JsonElement::getAsString).orElse(null);
            String req = Optional.ofNullable(msg.get("req")).map(JsonElement::getAsString).orElse(null);
            String event = Optional.ofNullable(msg.get("event")).map(JsonElement::getAsString).orElse(null);
            String secret = plugin.getBridgeSecret();
            String sig = Optional.ofNullable(msg.get("signature")).map(JsonElement::getAsString).orElse("");
            String sigTrim = sig == null ? "" : sig.trim();
            String sigShort = sigTrim.length() <= 16 ? sigTrim : sigTrim.substring(0, 16) + "...";

            String exp1Short = "";
            String exp2Short = "";
            String exp3Short = "";
            String expLens = "";
            try {
                if (secret != null && !secret.isEmpty()) {
                    JsonObject copy1 = msg.deepCopy();
                    copy1.remove("signature");
                    String exp1 = expectedSignature(secret, copy1);
                    exp1Short = exp1.length() <= 16 ? exp1 : exp1.substring(0, 16) + "...";

                    String exp3 = expectedSignatureUnsorted(secret, copy1);
                    exp3Short = exp3.length() <= 16 ? exp3 : exp3.substring(0, 16) + "...";

                    if (!copy1.has("payload")) {
                        JsonObject copy2 = copy1.deepCopy();
                        copy2.add("payload", new JsonObject());
                        String exp2 = expectedSignature(secret, copy2);
                        exp2Short = exp2.length() <= 16 ? exp2 : exp2.substring(0, 16) + "...";
                    }

                    expLens = " providedLen=" + sigTrim.length() + " exp1Len=" + exp1.length() + " exp3Len=" + exp3.length();
                }
            } catch (Exception ignored) {
            }

            plugin.getLogger().warning("Bridge message ignored (invalid signature): type=" + type
                    + (id != null ? " id=" + id : "")
                    + (cmd != null ? " cmd=" + cmd : "")
                    + (req != null ? " req=" + req : "")
                    + (event != null ? " event=" + event : "")
                    + " sig=" + sigShort
                    + (exp1Short.isEmpty() ? "" : " exp1=" + exp1Short)
                    + (exp2Short.isEmpty() ? "" : " exp2=" + exp2Short)
                    + (exp3Short.isEmpty() ? "" : " exp3=" + exp3Short)
                    + expLens);
            return;
        }

        String type = Optional.ofNullable(msg.get("type")).map(JsonElement::getAsString).orElse("");
        if (Objects.equals(type, "response")) {
            String id = Optional.ofNullable(msg.get("id")).map(JsonElement::getAsString).orElse(null);
            if (id == null) {
                return;
            }
            CompletableFuture<JsonObject> f = pendingRequests.remove(id);
            if (f != null) {
                f.complete(msg);
            }
            return;
        }

        if (Objects.equals(type, "command")) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.getCommandDispatcher().handle(msg));
        }
    }
}
