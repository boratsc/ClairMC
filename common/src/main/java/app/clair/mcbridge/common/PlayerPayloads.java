package app.clair.mcbridge.common;

import com.google.gson.JsonObject;

public final class PlayerPayloads {
    private PlayerPayloads() {
    }

    public static JsonObject player(PlayerHandle player) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", player.uuid().toString());
        payload.addProperty("name", player.name());
        return payload;
    }
}
