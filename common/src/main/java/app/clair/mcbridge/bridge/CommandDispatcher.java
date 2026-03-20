package app.clair.mcbridge.bridge;

import app.clair.mcbridge.common.PlayerHandle;
import app.clair.mcbridge.common.ServerBridgePlatform;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class CommandDispatcher {
    private final ServerBridgePlatform platform;
    private final BridgeClient bridgeClient;
    private final List<String> allowedConsoleCommands;

    public CommandDispatcher(ServerBridgePlatform platform, BridgeClient bridgeClient, List<String> allowedConsoleCommands) {
        this.platform = platform;
        this.bridgeClient = bridgeClient;
        this.allowedConsoleCommands = allowedConsoleCommands;
    }

    public void handle(JsonObject msg) {
        String id = optString(msg, "id");
        String cmd = optString(msg, "cmd");
        JsonObject payload = optObj(msg, "payload");

        if (id == null || cmd == null) {
            return;
        }

        try {
            switch (cmd) {
                case "get_player_list" -> handleGetPlayerList(id);
                case "get_tps" -> handleGetTps(id);
                case "send_chat" -> handleSendChat(id, payload);
                case "whitelist_add" -> handleWhitelistAdd(id, payload);
                case "kick" -> handleKick(id, payload);
                case "run_console_command" -> handleRunConsoleCommand(id, payload);
                default -> bridgeClient.sendAck(id, false, "unknown cmd");
            }
        } catch (Exception ex) {
            bridgeClient.sendAck(id, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void handleGetPlayerList(String id) {
        JsonArray players = new JsonArray();
        for (PlayerHandle player : platform.getOnlinePlayers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", player.name());
            entry.addProperty("uuid", player.uuid().toString());
            entry.addProperty("health", player.health());
            entry.addProperty("world", player.worldName());
            players.add(entry);
        }

        JsonObject ackPayload = new JsonObject();
        ackPayload.add("players", players);
        ackPayload.addProperty("count", players.size());
        ackPayload.addProperty("max", platform.getPlayersMax());
        bridgeClient.sendAck(id, true, null, ackPayload);
    }

    private void handleGetTps(String id) {
        JsonObject ackPayload = new JsonObject();
        ackPayload.addProperty("tps", round2(platform.getCurrentTps()));
        ackPayload.addProperty("mspt", round2(platform.getAverageTickTimeMillis()));
        bridgeClient.sendAck(id, true, null, ackPayload);
    }

    private void handleSendChat(String id, JsonObject payload) {
        String message = payload == null ? null : optString(payload, "message");
        if (message == null) {
            bridgeClient.sendAck(id, false, "missing payload.message");
            return;
        }
        platform.broadcastMessage(message);
        bridgeClient.sendAck(id, true, null);
    }

    private void handleWhitelistAdd(String id, JsonObject payload) {
        String playerName = payload == null ? null : optString(payload, "playerName");
        if (playerName == null) {
            bridgeClient.sendAck(id, false, "missing payload.playerName");
            return;
        }
        boolean ok = platform.dispatchConsoleCommand("whitelist add " + playerName);
        bridgeClient.sendAck(id, ok, ok ? null : "dispatch failed");
    }

    private void handleKick(String id, JsonObject payload) {
        String playerName = payload == null ? null : optString(payload, "playerName");
        String reason = payload == null ? null : optString(payload, "reason");
        if (playerName == null) {
            bridgeClient.sendAck(id, false, "missing payload.playerName");
            return;
        }

        PlayerHandle player = platform.findOnlinePlayerExact(playerName);
        if (player == null) {
            bridgeClient.sendAck(id, false, "player not online");
            return;
        }

        player.kick(reason);
        bridgeClient.sendAck(id, true, null);
    }

    private void handleRunConsoleCommand(String id, JsonObject payload) {
        String command = payload == null ? null : optString(payload, "command");
        if (command == null) {
            bridgeClient.sendAck(id, false, "missing payload.command");
            return;
        }

        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (!isAllowedConsoleCommand(normalized)) {
            bridgeClient.sendAck(id, false, "command not allowed");
            return;
        }

        boolean ok = platform.dispatchConsoleCommand(normalized);
        bridgeClient.sendAck(id, ok, ok ? null : "dispatch failed");
    }

    private boolean isAllowedConsoleCommand(String command) {
        if (allowedConsoleCommands == null || allowedConsoleCommands.isEmpty()) {
            return false;
        }

        String lower = command.toLowerCase(Locale.ROOT);
        return allowedConsoleCommands.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(lower::startsWith);
    }

    private static String optString(JsonObject obj, String key) {
        return Optional.ofNullable(obj.get(key)).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString).orElse(null);
    }

    private static JsonObject optObj(JsonObject obj, String key) {
        return Optional.ofNullable(obj.get(key)).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
