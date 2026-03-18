package app.clair.mcbridge.commands;

import app.clair.mcbridge.ClairMcBridgePlugin;
import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class UnlinkCommand implements CommandExecutor {
    private final ClairMcBridgePlugin plugin;

    public UnlinkCommand(ClairMcBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda jest tylko dla graczy.");
            return true;
        }

        JsonObject playerObj = new JsonObject();
        playerObj.addProperty("uuid", player.getUniqueId().toString());
        playerObj.addProperty("name", player.getName());

        JsonObject payload = new JsonObject();
        payload.add("player", playerObj);

        player.sendMessage("[Clair] Wysyłam unlink do Bridge...");
        plugin.getBridgeClient().sendRequest("unlink", payload)
                .whenComplete((resp, err) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("[Clair] Błąd unlink: " + err.getMessage());
                        return;
                    }
                    boolean ok = resp.has("ok") && resp.get("ok").getAsBoolean();
                    if (ok) {
                        player.sendMessage("[Clair] Powiązanie zostało usunięte.");
                    } else {
                        String error = resp.has("error") && !resp.get("error").isJsonNull() ? resp.get("error").getAsString() : "unknown";
                        player.sendMessage("[Clair] Unlink nieudany: " + error);
                    }
                }));
        return true;
    }
}
