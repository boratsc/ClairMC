package app.clair.mcbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.TreeMap;

public final class CanonicalJson {
    private static final Gson GSON = new Gson();

    private CanonicalJson() {
    }

    public static String canonicalize(JsonElement element) {
        JsonElement normalized = normalize(element);
        return GSON.toJson(normalized);
    }

    public static JsonElement normalize(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            return primitive;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                out.add(normalize(child));
            }
            return out;
        }
        if (element.isJsonObject()) {
            JsonObject out = new JsonObject();
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                out.add(entry.getKey(), normalize(entry.getValue()));
            }
            return out;
        }
        return JsonNull.INSTANCE;
    }
}
