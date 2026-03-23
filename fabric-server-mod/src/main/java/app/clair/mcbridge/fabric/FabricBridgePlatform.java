package app.clair.mcbridge.fabric;

import app.clair.mcbridge.bridge.CommandDispatcher;
import app.clair.mcbridge.common.BridgeLogger;
import app.clair.mcbridge.common.PlayerHandle;
import app.clair.mcbridge.common.ServerBridgePlatform;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class FabricBridgePlatform implements ServerBridgePlatform {
    private final Logger logger;
    private final BridgeLogger bridgeLogger;
    private volatile MinecraftServer server;
    private volatile CommandDispatcher commandDispatcher;

    public FabricBridgePlatform(Logger logger) {
        this.logger = logger;
        this.bridgeLogger = new BridgeLogger() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }

            @Override
            public void warn(String message, Throwable error) {
                logger.warn(message, error);
            }

            @Override
            public void error(String message) {
                logger.error(message);
            }

            @Override
            public void error(String message, Throwable error) {
                logger.error(message, error);
            }
        };
    }

    public void attachServer(MinecraftServer server) {
        this.server = server;
    }

    public void detachServer() {
        this.server = null;
    }

    public void setCommandDispatcher(CommandDispatcher commandDispatcher) {
        this.commandDispatcher = commandDispatcher;
    }

    @Override
    public BridgeLogger logger() {
        return bridgeLogger;
    }

    @Override
    public String getConfiguredServerId() {
        return FabricConfig.bridgeServerId();
    }

    @Override
    public String getBridgeUrl() {
        return FabricConfig.bridgeUrl();
    }

    @Override
    public String getBridgeSecret() {
        return FabricConfig.bridgeSecret();
    }

    @Override
    public String getServerBrand() {
        return "Fabric";
    }

    @Override
    public String getMinecraftVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    @Override
    public int getPlayersOnline() {
        return server == null ? 0 : server.getPlayerCount();
    }

    @Override
    public int getPlayersMax() {
        return server == null ? 0 : server.getMaxPlayers();
    }

    @Override
    public double getAverageTickTimeMillis() {
        return server == null ? 0.0D : server.getAverageTickTime();
    }

    @Override
    public double getCurrentTps() {
        double mspt = getAverageTickTimeMillis();
        if (mspt <= 0.0D) {
            return 20.0D;
        }
        return Math.min(20.0D, 1000.0D / mspt);
    }

    @Override
    public List<? extends PlayerHandle> getOnlinePlayers() {
        if (server == null) {
            return Collections.emptyList();
        }
        return server.getPlayerList().getPlayers().stream().map(FabricPlayerHandle::new).toList();
    }

    @Override
    public PlayerHandle findOnlinePlayerExact(String name) {
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> player.getGameProfile().getName().equalsIgnoreCase(name))
                .findFirst()
                .map(FabricPlayerHandle::new)
                .orElse(null);
    }

    @Override
    public void broadcastMessage(String message) {
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
        }
    }

    @Override
    public boolean dispatchConsoleCommand(String command) {
        if (server == null) {
            return false;
        }

        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        try {
            CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
            server.getCommands().performPrefixedCommand(source, normalized);
            return true;
        } catch (Exception ex) {
            logger.warn("Console command failed: {}", normalized, ex);
            return false;
        }
    }

    @Override
    public void runOnServerThread(Runnable runnable) {
        MinecraftServer current = this.server;
        if (current == null) {
            return;
        }
        if (current.isSameThread()) {
            runnable.run();
        } else {
            current.execute(runnable);
        }
    }

    @Override
    public void handleBridgeCommand(JsonObject message) {
        CommandDispatcher current = this.commandDispatcher;
        if (current != null) {
            current.handle(message);
        }
    }

    public static JsonObject playerPayload(ServerPlayer player) {
        JsonObject out = new JsonObject();
        out.addProperty("uuid", player.getUUID().toString());
        out.addProperty("name", player.getGameProfile().getName());
        return out;
    }

    private static final class FabricPlayerHandle implements PlayerHandle {
        private final ServerPlayer player;

        private FabricPlayerHandle(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public UUID uuid() {
            return player.getUUID();
        }

        @Override
        public String name() {
            return player.getGameProfile().getName();
        }

        @Override
        public double health() {
            return player.getHealth();
        }

        @Override
        public String worldName() {
            return player.serverLevel().dimension().location().toString().toLowerCase(Locale.ROOT);
        }

        @Override
        public void sendMessage(String message) {
            player.sendSystemMessage(Component.literal(message));
        }

        @Override
        public void kick(String reason) {
            String text = reason == null || reason.isBlank() ? "Kicked by bridge" : reason;
            player.connection.disconnect(Component.literal(text));
        }
    }
}
