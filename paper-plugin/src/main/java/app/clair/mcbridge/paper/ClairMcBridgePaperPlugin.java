package app.clair.mcbridge.paper;

import app.clair.mcbridge.bridge.BridgeClient;
import app.clair.mcbridge.bridge.CommandDispatcher;
import app.clair.mcbridge.common.HeartbeatPayloadFactory;
import app.clair.mcbridge.paper.commands.LinkCommand;
import app.clair.mcbridge.paper.commands.UnlinkCommand;
import app.clair.mcbridge.paper.listeners.PlayerEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClairMcBridgePaperPlugin extends JavaPlugin {
    private BridgeClient bridgeClient;
    private PaperBridgePlatform platform;
    private int heartbeatTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.platform = new PaperBridgePlatform(this);
        this.bridgeClient = new BridgeClient(platform);
        CommandDispatcher commandDispatcher = new CommandDispatcher(
                platform,
                bridgeClient,
                getConfig().getStringList("commands.allowConsoleCommands")
        );
        platform.setCommandDispatcher(commandDispatcher);

        if (getCommand("link") != null) {
            getCommand("link").setExecutor(new LinkCommand(this));
        }
        if (getCommand("unlink") != null) {
            getCommand("unlink").setExecutor(new UnlinkCommand(this));
        }

        getServer().getPluginManager().registerEvents(new PlayerEvents(this), this);

        bridgeClient.start();
        startHeartbeat();
        getLogger().info("ClairMCBridge Paper plugin enabled");
    }

    @Override
    public void onDisable() {
        stopHeartbeat();
        if (bridgeClient != null) {
            bridgeClient.stop();
        }
        getLogger().info("ClairMCBridge Paper plugin disabled");
    }

    public BridgeClient getBridgeClient() {
        return bridgeClient;
    }

    private void startHeartbeat() {
        int seconds = getConfig().getInt("features.heartbeatSeconds", 30);
        if (seconds <= 0) {
            return;
        }
        heartbeatTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::sendHeartbeat, 40L, seconds * 20L);
    }

    private void stopHeartbeat() {
        if (heartbeatTaskId != -1) {
            Bukkit.getScheduler().cancelTask(heartbeatTaskId);
            heartbeatTaskId = -1;
        }
    }

    private void sendHeartbeat() {
        if (bridgeClient == null || !bridgeClient.isConnected()) {
            return;
        }

        bridgeClient.sendEvent(
                "server_heartbeat",
                HeartbeatPayloadFactory.create(
                        platform,
                        getConfig().getBoolean("features.heartbeatTps", false),
                        getConfig().getBoolean("features.heartbeatPlayersList", false)
                )
        );
    }
}
