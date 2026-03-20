package app.clair.mcbridge.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class HeartbeatPayloadFactory {
    private HeartbeatPayloadFactory() {
    }

    public static JsonObject create(ServerBridgePlatform platform, boolean includeTps, boolean includePlayersList) {
        JsonObject status = new JsonObject();
        status.addProperty("online", true);
        status.addProperty("playersOnline", platform.getPlayersOnline());
        status.addProperty("playersMax", platform.getPlayersMax());
        status.addProperty("version", platform.getMinecraftVersion());
        status.addProperty("brand", platform.getServerBrand());

        if (includeTps) {
            status.addProperty("tps", round2(platform.getCurrentTps()));
            status.addProperty("mspt", round2(platform.getAverageTickTimeMillis()));
        }

        if (includePlayersList) {
            JsonArray players = new JsonArray();
            for (PlayerHandle player : platform.getOnlinePlayers()) {
                players.add(player.name());
            }
            status.add("players", players);
        }

        JsonObject payload = new JsonObject();
        payload.add("status", status);
        return payload;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
