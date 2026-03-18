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
            JsonPrimitive p = element.getAsJsonPrimitive();
            return p;
        }
        if (element.isJsonArray()) {
            JsonArray arr = new JsonArray();
            for (JsonElement el : element.getAsJsonArray()) {
                arr.add(normalize(el));
            }
            return arr;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject out = new JsonObject();

            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                sorted.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, JsonElement> e : sorted.entrySet()) {
                out.add(e.getKey(), normalize(e.getValue()));
            }
            return out;
        }

        // Fallback (nie powinno się zdarzyć)
        return JsonNull.INSTANCE;
    }
}
