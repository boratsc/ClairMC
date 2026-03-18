package app.clair.mcbridge;

import app.clair.mcbridge.bridge.BridgeClient;
import app.clair.mcbridge.bridge.CommandDispatcher;
import app.clair.mcbridge.commands.LinkCommand;
import app.clair.mcbridge.commands.UnlinkCommand;
import app.clair.mcbridge.listeners.PlayerEvents;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClairMcBridgePlugin extends JavaPlugin {

    private BridgeClient bridgeClient;
    private CommandDispatcher commandDispatcher;
    private int heartbeatTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.bridgeClient = new BridgeClient(this);
        this.commandDispatcher = new CommandDispatcher(this, bridgeClient);

        if (getCommand("link") != null) {
            getCommand("link").setExecutor(new LinkCommand(this));
        }
        if (getCommand("unlink") != null) {
            getCommand("unlink").setExecutor(new UnlinkCommand(this));
        }

        getServer().getPluginManager().registerEvents(new PlayerEvents(this), this);

        bridgeClient.start();
        startHeartbeat();

        getLogger().info("ClairMCBridge enabled");
    }

    @Override
    public void onDisable() {
        stopHeartbeat();
        if (bridgeClient != null) {
            bridgeClient.stop();
        }
        getLogger().info("ClairMCBridge disabled");
    }

    public BridgeClient getBridgeClient() {
        return bridgeClient;
    }

    public String getConfiguredServerId() {
        String id = getConfig().getString("bridge.serverId");
        return id == null ? "" : id.trim();
    }

    public String getBridgeUrl() {
        String url = getConfig().getString("bridge.url", "");
        return url == null ? "" : url.trim();
    }

    public String getBridgeSecret() {
        String secret = getConfig().getString("bridge.secret", "");
        return secret == null ? "" : secret;
    }

    public CommandDispatcher getCommandDispatcher() {
        return commandDispatcher;
    }

    private void startHeartbeat() {
        int seconds = getConfig().getInt("features.heartbeatSeconds", 30);
        if (seconds <= 0) {
            return;
        }

        // Tick-safe: pobieramy status na main thread, wysyłka po WS jest non-blocking.
        heartbeatTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::sendHeartbeat, 40L, seconds * 20L);
    }

    private void stopHeartbeat() {
        if (heartbeatTaskId != -1) {
            Bukkit.getScheduler().cancelTask(heartbeatTaskId);
            heartbeatTaskId = -1;
        }
    }

    private void sendHeartbeat() {
        if (bridgeClient == null) {
            return;
        }
        if (!bridgeClient.isConnected()) {
            getLogger().info("Heartbeat skipped: Bridge WS not connected");
            return;
        }

        JsonObject status = new JsonObject();
        status.addProperty("online", true);
        status.addProperty("playersOnline", Bukkit.getOnlinePlayers().size());
        status.addProperty("playersMax", Bukkit.getMaxPlayers());
        status.addProperty("version", Bukkit.getMinecraftVersion());
        status.addProperty("brand", Bukkit.getName());

        if (getConfig().getBoolean("features.heartbeatTps", false)) {
            double[] tps = Bukkit.getTPS();
            double currentTps = tps != null && tps.length > 0 ? tps[0] : 0.0;
            double mspt = Bukkit.getAverageTickTime();
            status.addProperty("tps", round2(currentTps));
            status.addProperty("mspt", round2(mspt));
        }

        if (getConfig().getBoolean("features.heartbeatPlayersList", false)) {
            com.google.gson.JsonArray players = new com.google.gson.JsonArray();
            Bukkit.getOnlinePlayers().forEach(player -> players.add(player.getName()));
            status.add("players", players);
        }

        JsonObject payload = new JsonObject();
        payload.add("status", status);

        bridgeClient.sendEvent("server_heartbeat", payload);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
