package app.clair.mcbridge.paper.listeners;

import app.clair.mcbridge.paper.ClairMcBridgePaperPlugin;
import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.advancement.Advancement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Method;
import java.util.Locale;

public final class PlayerEvents implements Listener {
    private final ClairMcBridgePaperPlugin plugin;

    public PlayerEvents(ClairMcBridgePaperPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("features.sendJoinQuit", true)) {
            return;
        }

        JsonObject player = new JsonObject();
        player.addProperty("uuid", event.getPlayer().getUniqueId().toString());
        player.addProperty("name", event.getPlayer().getName());

        JsonObject payload = new JsonObject();
        payload.add("player", player);
        plugin.getBridgeClient().sendEvent("player_join", payload);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("features.sendJoinQuit", true)) {
            return;
        }

        JsonObject player = new JsonObject();
        player.addProperty("uuid", event.getPlayer().getUniqueId().toString());
        player.addProperty("name", event.getPlayer().getName());

        JsonObject payload = new JsonObject();
        payload.add("player", player);
        plugin.getBridgeClient().sendEvent("player_quit", payload);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("features.sendDeaths", true)) {
            return;
        }

        JsonObject player = new JsonObject();
        player.addProperty("uuid", event.getPlayer().getUniqueId().toString());
        player.addProperty("name", event.getPlayer().getName());

        JsonObject payload = new JsonObject();
        payload.add("player", player);
        if (event.getDeathMessage() != null) {
            payload.addProperty("message", event.getDeathMessage());
        }

        plugin.getBridgeClient().sendEvent("player_death", payload);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("features.sendChat", false)) {
            return;
        }

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (message == null || message.isBlank() || message.startsWith("/")) {
            return;
        }

        JsonObject player = new JsonObject();
        player.addProperty("uuid", event.getPlayer().getUniqueId().toString());
        player.addProperty("name", event.getPlayer().getName());

        JsonObject payload = new JsonObject();
        payload.add("player", player);
        payload.addProperty("message", message);
        plugin.getBridgeClient().sendEvent("mc_chat", payload);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.getConfig().getBoolean("features.sendAdvancements", false)) {
            return;
        }

        Advancement advancement = event.getAdvancement();
        if (advancement == null || advancement.getKey().getKey().startsWith("recipes/")) {
            return;
        }

        Object display = advancement.getDisplay();
        if (display == null) {
            return;
        }

        JsonObject player = new JsonObject();
        player.addProperty("uuid", event.getPlayer().getUniqueId().toString());
        player.addProperty("name", event.getPlayer().getName());

        JsonObject advancementPayload = new JsonObject();
        advancementPayload.addProperty("key", advancement.getKey().toString());

        String title = readDisplayText(display, "title", "getTitle");
        String description = readDisplayText(display, "description", "getDescription");
        String frame = readDisplayFrame(display);

        if (title != null && !title.isBlank()) {
            advancementPayload.addProperty("title", title);
        }
        if (description != null && !description.isBlank()) {
            advancementPayload.addProperty("description", description);
        }
        advancementPayload.addProperty("frame", frame);

        JsonObject payload = new JsonObject();
        payload.add("player", player);
        payload.add("advancement", advancementPayload);
        plugin.getBridgeClient().sendEvent("advancement", payload);
    }

    private static String readDisplayText(Object display, String modernMethod, String legacyMethod) {
        Object value = invokeDisplayMethod(display, modernMethod);
        if (value == null) {
            value = invokeDisplayMethod(display, legacyMethod);
        }

        if (value instanceof Component component) {
            return PlainTextComponentSerializer.plainText().serialize(component);
        }
        return value == null ? null : value.toString();
    }

    private static Object invokeDisplayMethod(Object display, String methodName) {
        try {
            Method method = display.getClass().getMethod(methodName);
            return method.invoke(display);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readDisplayFrame(Object display) {
        Object value = invokeDisplayMethod(display, "frame");
        if (value == null) {
            value = invokeDisplayMethod(display, "getFrame");
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name().toLowerCase(Locale.ROOT);
        }
        return value == null ? "task" : value.toString().toLowerCase(Locale.ROOT);
    }
}
