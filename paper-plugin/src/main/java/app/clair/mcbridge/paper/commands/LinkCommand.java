package app.clair.mcbridge.paper.commands;

import app.clair.mcbridge.paper.ClairMcBridgePaperPlugin;
import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class LinkCommand implements CommandExecutor {
    private final ClairMcBridgePaperPlugin plugin;

    public LinkCommand(ClairMcBridgePaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda jest tylko dla graczy.");
            return true;
        }

        if (args.length != 1 || args[0].trim().isEmpty()) {
            player.sendMessage("Użycie: /link <kod>");
            return true;
        }

        JsonObject playerObj = new JsonObject();
        playerObj.addProperty("uuid", player.getUniqueId().toString());
        playerObj.addProperty("name", player.getName());

        JsonObject payload = new JsonObject();
        payload.addProperty("code", args[0].trim());
        payload.add("player", playerObj);

        player.sendMessage("[Clair] Wysyłam kod linkowania do Bridge...");
        plugin.getBridgeClient().sendRequest("claim_link_code", payload)
                .whenComplete((resp, err) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("[Clair] Błąd linkowania: " + err.getMessage());
                        return;
                    }

                    boolean ok = resp.has("ok") && resp.get("ok").getAsBoolean();
                    if (ok) {
                        player.sendMessage("[Clair] Konto zostało zlinkowane.");
                    } else {
                        String error = resp.has("error") && !resp.get("error").isJsonNull()
                                ? resp.get("error").getAsString()
                                : "unknown";
                        player.sendMessage("[Clair] Linkowanie nieudane: " + error);
                    }
                }));
        return true;
    }
}
