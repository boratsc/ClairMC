package app.clair.mcbridge.common;

import com.google.gson.JsonObject;

import java.util.List;

public interface ServerBridgePlatform {
    BridgeLogger logger();

    String getConfiguredServerId();

    String getBridgeUrl();

    String getBridgeSecret();

    String getServerBrand();

    String getMinecraftVersion();

    int getPlayersOnline();

    int getPlayersMax();

    double getAverageTickTimeMillis();

    double getCurrentTps();

    List<? extends PlayerHandle> getOnlinePlayers();

    PlayerHandle findOnlinePlayerExact(String name);

    void broadcastMessage(String message);

    boolean dispatchConsoleCommand(String command);

    void runOnServerThread(Runnable runnable);

    void handleBridgeCommand(JsonObject message);
}
