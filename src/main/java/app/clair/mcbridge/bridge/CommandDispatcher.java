package app.clair.mcbridge.bridge;

import app.clair.mcbridge.ClairMcBridgePlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class CommandDispatcher {
    private final ClairMcBridgePlugin plugin;
    private final BridgeClient bridgeClient;

    public CommandDispatcher(ClairMcBridgePlugin plugin, BridgeClient bridgeClient) {
        this.plugin = plugin;
        this.bridgeClient = bridgeClient;
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
                case "get_player_list" -> {
                    JsonArray players = new JsonArray();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("name", player.getName());
                        entry.addProperty("uuid", player.getUniqueId().toString());
                        entry.addProperty("health", player.getHealth());
                        entry.addProperty("world", player.getWorld().getName());
                        players.add(entry);
                    }

                    JsonObject ackPayload = new JsonObject();
                    ackPayload.add("players", players);
                    ackPayload.addProperty("count", players.size());
                    ackPayload.addProperty("max", Bukkit.getMaxPlayers());
                    bridgeClient.sendAck(id, true, null, ackPayload);
                }
                case "get_tps" -> {
                    double[] tps = Bukkit.getTPS();
                    double currentTps = tps != null && tps.length > 0 ? tps[0] : 0.0;
                    double mspt = Bukkit.getAverageTickTime();

                    JsonObject ackPayload = new JsonObject();
                    ackPayload.addProperty("tps", round2(currentTps));
                    ackPayload.addProperty("mspt", round2(mspt));
                    bridgeClient.sendAck(id, true, null, ackPayload);
                }
                case "send_chat" -> {
                    String message = payload == null ? null : optString(payload, "message");
                    if (message == null) {
                        bridgeClient.sendAck(id, false, "missing payload.message");
                        return;
                    }
                    Bukkit.broadcastMessage(message);
                    bridgeClient.sendAck(id, true, null);
                }
                case "whitelist_add" -> {
                    String playerName = payload == null ? null : optString(payload, "playerName");
                    if (playerName == null) {
                        bridgeClient.sendAck(id, false, "missing payload.playerName");
                        return;
                    }
                    ConsoleCommandSender console = Bukkit.getConsoleSender();
                    boolean ok = Bukkit.dispatchCommand(console, "whitelist add " + playerName);
                    bridgeClient.sendAck(id, ok, ok ? null : "dispatch failed");
                }
                case "kick" -> {
                    String playerName = payload == null ? null : optString(payload, "playerName");
                    String reason = payload == null ? null : optString(payload, "reason");
                    if (playerName == null) {
                        bridgeClient.sendAck(id, false, "missing payload.playerName");
                        return;
                    }
                    Player player = Bukkit.getPlayerExact(playerName);
                    if (player == null) {
                        bridgeClient.sendAck(id, false, "player not online");
                        return;
                    }
                    if (reason != null && !reason.isBlank()) {
                        player.kick(Component.text(reason));
                    } else {
                        player.kick();
                    }
                    bridgeClient.sendAck(id, true, null);
                }
                case "run_console_command" -> {
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

                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), normalized);
                    bridgeClient.sendAck(id, ok, ok ? null : "dispatch failed");
                }
                default -> bridgeClient.sendAck(id, false, "unknown cmd");
            }
        } catch (Exception ex) {
            bridgeClient.sendAck(id, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private boolean isAllowedConsoleCommand(String command) {
        List<String> allowed = plugin.getConfig().getStringList("commands.allowConsoleCommands");
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        return allowed.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
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
