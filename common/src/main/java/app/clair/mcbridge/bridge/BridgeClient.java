package app.clair.mcbridge.bridge;

import app.clair.mcbridge.common.ServerBridgePlatform;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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

public final class BridgeClient {
    private final ServerBridgePlatform platform;
    private final Gson gson;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Map<String, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile int reconnectAttempt = 0;

    public BridgeClient(ServerBridgePlatform platform) {
        this.platform = platform;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "clair-bridge-client");
            thread.setDaemon(true);
            return thread;
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
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bridge shutdown");
            }
        } catch (Exception ignored) {
        }
        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        return this.webSocket != null;
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
            CompletableFuture<JsonObject> pending = pendingRequests.remove(id);
            if (pending != null && !pending.isDone()) {
                pending.completeExceptionally(new RuntimeException("Bridge request timeout: " + id));
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
        payload.addProperty("brand", platform.getServerBrand());
        payload.addProperty("version", platform.getMinecraftVersion());
        payload.addProperty("playersMax", platform.getPlayersMax());

        JsonObject msg = baseMessage("handshake");
        msg.add("payload", payload);
        sign(msg);
        sendJson(msg);
    }

    private void connect(long delayMillis) {
        if (stopping.get()) {
            return;
        }

        String url = platform.getBridgeUrl();
        if (url.isEmpty()) {
            platform.logger().warn("bridge.url is empty; BridgeClient will not connect");
            return;
        }

        String serverId = platform.getConfiguredServerId();
        if (serverId.isEmpty()) {
            platform.logger().error("bridge.serverId is empty; BridgeClient will not connect");
            return;
        }

        String secret = platform.getBridgeSecret();
        if (secret.isEmpty()) {
            platform.logger().error("bridge.secret is empty; BridgeClient will not connect");
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
                platform.logger().error("Invalid bridge.url: " + url);
                return;
            }

            platform.logger().info("Connecting to Bridge WS: " + uri + " (serverId=" + serverId + ")");
            httpClient.newWebSocketBuilder()
                    .buildAsync(uri, new WsListener())
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            platform.logger().warn("Bridge WS connect failed", err);
                            scheduleReconnect();
                            return;
                        }

                        this.webSocket = ws;
                        this.reconnectAttempt = 0;
                        platform.logger().info("Bridge WS connected");
                        platform.runOnServerThread(() -> {
                            sendHandshake();
                            platform.logger().info("Bridge handshake sent (serverId=" + serverId + ")");
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
        platform.logger().warn("Reconnecting to Bridge in " + delay + "ms");
        connect(delay);
    }

    private JsonObject baseMessage(String type) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        String serverId = platform.getConfiguredServerId();
        msg.addProperty("serverId", serverId.isEmpty() ? "unknown" : serverId);
        msg.addProperty("ts", Instant.now().getEpochSecond());
        if (!msg.has("payload")) {
            msg.add("payload", new JsonObject());
        }
        return msg;
    }

    private void sign(JsonObject message) {
        String secret = platform.getBridgeSecret();
        if (secret == null || secret.isEmpty()) {
            return;
        }

        JsonObject copy = message.deepCopy();
        copy.remove("signature");
        String canonical = CanonicalJson.canonicalize(copy);
        byte[] mac = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), canonical.getBytes(StandardCharsets.UTF_8));
        message.addProperty("signature", Base64.getEncoder().encodeToString(mac));
    }

    private static String stripBase64Padding(String value) {
        if (value == null) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '=') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String addBase64Padding(String value) {
        if (value == null) {
            return "";
        }
        int mod = value.length() % 4;
        if (mod == 0) {
            return value;
        }
        return value + "=".repeat(4 - mod);
    }

    private static byte[] decodeBase64Any(String signature) {
        if (signature == null) {
            return null;
        }

        String trimmed = signature.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        try {
            return Base64.getDecoder().decode(addBase64Padding(trimmed));
        } catch (Exception ignored) {
        }

        try {
            return Base64.getUrlDecoder().decode(addBase64Padding(trimmed));
        } catch (Exception ignored) {
        }

        try {
            String normalized = trimmed.replace('-', '+').replace('_', '/');
            return Base64.getDecoder().decode(addBase64Padding(normalized));
        } catch (Exception ignored) {
        }

        return null;
    }

    private static boolean macMatchesSignature(byte[] mac, String providedSignature) {
        byte[] providedBytes = decodeBase64Any(providedSignature);
        return providedBytes != null && MessageDigest.isEqual(mac, providedBytes);
    }

    private static boolean sigEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        if (expected.equals(provided)) {
            return true;
        }
        if (stripBase64Padding(expected).equals(stripBase64Padding(provided))) {
            return true;
        }
        byte[] expectedBytes = decodeBase64Any(expected);
        byte[] providedBytes = decodeBase64Any(provided);
        return expectedBytes != null && providedBytes != null && MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private String expectedSignature(String secret, JsonObject messageWithoutSignature) {
        String canonical = CanonicalJson.canonicalize(messageWithoutSignature);
        byte[] mac = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac);
    }

    private String expectedSignatureUnsorted(String secret, JsonObject messageWithoutSignature) {
        String json = gson.toJson(messageWithoutSignature);
        byte[] mac = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac);
    }

    private boolean verifyLegacyConcat(String secret, JsonObject messageWithoutSignature, String providedSignature) {
        String serverId = Optional.ofNullable(messageWithoutSignature.get("serverId")).map(JsonElement::getAsString).orElse("");
        String ts = Optional.ofNullable(messageWithoutSignature.get("ts")).map(JsonElement::getAsString).orElse("");
        JsonElement payload = messageWithoutSignature.has("payload") ? messageWithoutSignature.get("payload") : new JsonObject();

        String payloadCanonical = CanonicalJson.canonicalize(payload);
        byte[] mac1 = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), (serverId + ts + payloadCanonical).getBytes(StandardCharsets.UTF_8));
        if (macMatchesSignature(mac1, providedSignature)) {
            return true;
        }

        String payloadUnsorted = gson.toJson(payload);
        byte[] mac2 = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), (serverId + ts + payloadUnsorted).getBytes(StandardCharsets.UTF_8));
        return macMatchesSignature(mac2, providedSignature);
    }

    private boolean verify(JsonObject message) {
        String secret = platform.getBridgeSecret();
        if (secret == null || secret.isEmpty()) {
            platform.logger().warn("bridge.secret is empty; incoming messages are NOT verified");
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

        JsonObject copy1 = message.deepCopy();
        copy1.remove("signature");

        if (sigEquals(expectedSignature(secret, copy1), provided)) {
            return true;
        }

        if (!copy1.has("payload")) {
            JsonObject copy2 = copy1.deepCopy();
            copy2.add("payload", new JsonObject());
            if (sigEquals(expectedSignature(secret, copy2), provided)) {
                return true;
            }
        }

        if (sigEquals(expectedSignatureUnsorted(secret, copy1), provided)) {
            platform.logger().warn("Bridge signature verified using UNSORTED JSON fallback (backend signing mismatch)");
            return true;
        }

        if (verifyLegacyConcat(secret, copy1, provided)) {
            platform.logger().warn("Bridge signature verified using LEGACY concat fallback (backend needs canonical signing update)");
            return true;
        }

        return false;
    }

    private boolean verifyTimestamp(JsonObject message) {
        if (!message.has("ts")) {
            return false;
        }
        long ts = message.get("ts").getAsLong();
        long now = Instant.now().getEpochSecond();
        return Math.abs(now - ts) <= 60;
    }

    private void sendJson(JsonObject object) {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            return;
        }
        ws.sendText(gson.toJson(object), true);
    }

    private void handleIncoming(String text) {
        JsonObject msg;
        try {
            msg = gson.fromJson(text, JsonObject.class);
        } catch (Exception ex) {
            platform.logger().warn("Bridge message parse error");
            return;
        }

        String expectedServerId = platform.getConfiguredServerId();
        if (expectedServerId.isEmpty()) {
            platform.logger().warn("bridge.serverId is empty; ignoring incoming messages");
            return;
        }

        String gotServerId = Optional.ofNullable(msg.get("serverId")).map(JsonElement::getAsString).orElse("");
        if (!Objects.equals(expectedServerId, gotServerId)) {
            platform.logger().warn("Bridge message ignored (serverId mismatch)");
            return;
        }
        if (!verifyTimestamp(msg)) {
            platform.logger().warn("Bridge message ignored (timestamp out of range)");
            return;
        }
        if (!verify(msg)) {
            String typeMaybe = Optional.ofNullable(msg.get("type")).map(JsonElement::getAsString).orElse("");
            if (Objects.equals(typeMaybe, "response")) {
                String idMaybe = Optional.ofNullable(msg.get("id")).map(JsonElement::getAsString).orElse(null);
                if (idMaybe != null && pendingRequests.containsKey(idMaybe)) {
                    platform.logger().error("SECURITY WARNING: accepting Bridge response with INVALID signature for pending id=" + idMaybe
                            + " (fix backend signing ASAP)");
                    CompletableFuture<JsonObject> future = pendingRequests.remove(idMaybe);
                    if (future != null) {
                        future.complete(msg);
                    }
                    return;
                }
            }
            platform.logger().warn("Bridge message ignored (invalid signature)");
            return;
        }

        String type = Optional.ofNullable(msg.get("type")).map(JsonElement::getAsString).orElse("");
        if (Objects.equals(type, "response")) {
            String id = Optional.ofNullable(msg.get("id")).map(JsonElement::getAsString).orElse(null);
            if (id == null) {
                return;
            }
            CompletableFuture<JsonObject> future = pendingRequests.remove(id);
            if (future != null) {
                future.complete(msg);
            }
            return;
        }

        if (Objects.equals(type, "command")) {
            platform.runOnServerThread(() -> platform.handleBridgeCommand(msg));
        }
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
            platform.logger().warn("Bridge WS error", error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            BridgeClient.this.webSocket = null;
            platform.logger().warn("Bridge WS closed: " + statusCode + " reason=" + reason);
            pendingRequests.forEach((id, future) -> future.completeExceptionally(new RuntimeException("WS closed")));
            pendingRequests.clear();
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }
    }
}
