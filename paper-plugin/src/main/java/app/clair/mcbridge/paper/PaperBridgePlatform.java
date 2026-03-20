package app.clair.mcbridge.paper;

import app.clair.mcbridge.bridge.CommandDispatcher;
import app.clair.mcbridge.common.BridgeLogger;
import app.clair.mcbridge.common.PlayerHandle;
import app.clair.mcbridge.common.ServerBridgePlatform;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class PaperBridgePlatform implements ServerBridgePlatform {
    private final ClairMcBridgePaperPlugin plugin;
    private CommandDispatcher commandDispatcher;

    public PaperBridgePlatform(ClairMcBridgePaperPlugin plugin) {
        this.plugin = plugin;
    }

    public void setCommandDispatcher(CommandDispatcher commandDispatcher) {
        this.commandDispatcher = commandDispatcher;
    }

    @Override
    public BridgeLogger logger() {
        return new BridgeLogger() {
            @Override
            public void info(String message) {
                plugin.getLogger().info(message);
            }

            @Override
            public void warn(String message) {
                plugin.getLogger().warning(message);
            }

            @Override
            public void warn(String message, Throwable error) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, message, error);
            }

            @Override
            public void error(String message) {
                plugin.getLogger().severe(message);
            }

            @Override
            public void error(String message, Throwable error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, message, error);
            }
        };
    }

    @Override
    public String getConfiguredServerId() {
        String value = plugin.getConfig().getString("bridge.serverId");
        return value == null ? "" : value.trim();
    }

    @Override
    public String getBridgeUrl() {
        String value = plugin.getConfig().getString("bridge.url", "");
        return value == null ? "" : value.trim();
    }

    @Override
    public String getBridgeSecret() {
        String value = plugin.getConfig().getString("bridge.secret", "");
        return value == null ? "" : value;
    }

    @Override
    public String getServerBrand() {
        return Bukkit.getName();
    }

    @Override
    public String getMinecraftVersion() {
        return Bukkit.getMinecraftVersion();
    }

    @Override
    public int getPlayersOnline() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public int getPlayersMax() {
        return Bukkit.getMaxPlayers();
    }

    @Override
    public double getAverageTickTimeMillis() {
        return Bukkit.getAverageTickTime();
    }

    @Override
    public double getCurrentTps() {
        double[] tps = Bukkit.getTPS();
        return tps != null && tps.length > 0 ? tps[0] : 0.0D;
    }

    @Override
    public List<? extends PlayerHandle> getOnlinePlayers() {
        return Bukkit.getOnlinePlayers().stream().map(PaperPlayerHandle::new).toList();
    }

    @Override
    public PlayerHandle findOnlinePlayerExact(String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player == null ? null : new PaperPlayerHandle(player);
    }

    @Override
    public void broadcastMessage(String message) {
        Bukkit.broadcastMessage(message);
    }

    @Override
    public boolean dispatchConsoleCommand(String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public void runOnServerThread(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public void handleBridgeCommand(JsonObject message) {
        if (commandDispatcher != null) {
            commandDispatcher.handle(message);
        }
    }

    private static final class PaperPlayerHandle implements PlayerHandle {
        private final Player player;

        private PaperPlayerHandle(Player player) {
            this.player = player;
        }

        @Override
        public java.util.UUID uuid() {
            return player.getUniqueId();
        }

        @Override
        public String name() {
            return player.getName();
        }

        @Override
        public double health() {
            return player.getHealth();
        }

        @Override
        public String worldName() {
            return player.getWorld().getName();
        }

        @Override
        public void sendMessage(String message) {
            player.sendMessage(message);
        }

        @Override
        public void kick(String reason) {
            if (reason != null && !reason.isBlank()) {
                player.kick(Component.text(reason));
            } else {
                player.kick();
            }
        }
    }
}
