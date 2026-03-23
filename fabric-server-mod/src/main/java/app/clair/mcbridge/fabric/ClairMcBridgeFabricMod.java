package app.clair.mcbridge.fabric;

import app.clair.mcbridge.bridge.BridgeClient;
import app.clair.mcbridge.bridge.CommandDispatcher;
import app.clair.mcbridge.common.HeartbeatPayloadFactory;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Locale;

public final class ClairMcBridgeFabricMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "clairmcbridge";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final FabricBridgePlatform platform = new FabricBridgePlatform(LOGGER);
    private final BridgeClient bridgeClient = new BridgeClient(platform);
    private long tickCounter = 0L;

    @Override
    public void onInitializeServer() {
        FabricConfig.load(LOGGER);
        platform.setCommandDispatcher(new CommandDispatcher(platform, bridgeClient, FabricConfig.allowConsoleCommands()));

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("link")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayerOrException();
                                        String code = StringArgumentType.getString(context, "code");
                                        sendLinkRequest(player, code);
                                        return 1;
                                    }))
            );

            dispatcher.register(
                    Commands.literal("unlink")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                sendUnlinkRequest(player);
                                return 1;
                            })
            );
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!FabricConfig.sendJoinQuit()) {
                return;
            }

            JsonObject payload = new JsonObject();
            payload.add("player", FabricBridgePlatform.playerPayload(handler.player));
            bridgeClient.sendEvent("player_join", payload);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!FabricConfig.sendJoinQuit()) {
                return;
            }

            JsonObject payload = new JsonObject();
            payload.add("player", FabricBridgePlatform.playerPayload(handler.player));
            bridgeClient.sendEvent("player_quit", payload);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!FabricConfig.sendDeaths() || !(entity instanceof ServerPlayer player)) {
                return;
            }

            JsonObject payload = new JsonObject();
            payload.add("player", FabricBridgePlatform.playerPayload(player));
            if (damageSource.getLocalizedDeathMessage(player) != null) {
                payload.addProperty("message", damageSource.getLocalizedDeathMessage(player).getString());
            }
            bridgeClient.sendEvent("player_death", payload);
        });

        ServerMessageEvents.CHAT_MESSAGE.register(this::onChatMessage);
        FabricAdvancementEvents.AWARDED.register(this::onAdvancementAwarded);
    }

    private void onServerStarted(MinecraftServer server) {
        platform.attachServer(server);
        bridgeClient.start();
        LOGGER.info("ClairMCBridge Fabric server mod enabled");
    }

    private void onServerStopping(MinecraftServer server) {
        bridgeClient.stop();
        platform.detachServer();
        tickCounter = 0L;
        LOGGER.info("ClairMCBridge Fabric server mod disabled");
    }

    private void onServerTick(MinecraftServer server) {
        int seconds = FabricConfig.heartbeatSeconds();
        if (seconds <= 0) {
            return;
        }

        tickCounter++;
        if (tickCounter % (seconds * 20L) != 0L) {
            return;
        }

        if (!bridgeClient.isConnected()) {
            return;
        }

        bridgeClient.sendEvent(
                "server_heartbeat",
                HeartbeatPayloadFactory.create(
                        platform,
                        FabricConfig.heartbeatTps(),
                        FabricConfig.heartbeatPlayersList()
                )
        );
    }

    private void onChatMessage(PlayerChatMessage message, ServerPlayer sender, net.minecraft.network.chat.ChatType.Bound params) {
        if (!FabricConfig.sendChat()) {
            return;
        }

        String text = message.decoratedContent().getString();
        if (text.isBlank()) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", FabricBridgePlatform.playerPayload(sender));
        payload.addProperty("message", text);
        bridgeClient.sendEvent("mc_chat", payload);
    }

    private void onAdvancementAwarded(ServerPlayer player, Advancement advancement) {
        if (!FabricConfig.sendAdvancements()) {
            return;
        }

        DisplayInfo display = advancement.getDisplay();
        if (display == null || advancement.getId().getPath().startsWith("recipes/")) {
            return;
        }

        JsonObject advancementPayload = new JsonObject();
        advancementPayload.addProperty("key", advancement.getId().toString());
        advancementPayload.addProperty("title", display.getTitle().getString());
        advancementPayload.addProperty("description", display.getDescription().getString());
        advancementPayload.addProperty("frame", readDisplayFrame(display));

        JsonObject payload = new JsonObject();
        payload.add("player", FabricBridgePlatform.playerPayload(player));
        payload.add("advancement", advancementPayload);
        bridgeClient.sendEvent("advancement", payload);
    }

    private void sendLinkRequest(ServerPlayer player, String code) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.add("player", FabricBridgePlatform.playerPayload(player));

        player.sendSystemMessage(Component.literal("[Clair] Wysyłam kod linkowania do Bridge..."));
        bridgeClient.sendRequest("claim_link_code", payload).whenComplete((resp, err) ->
                platform.runOnServerThread(() -> {
                    if (err != null) {
                        player.sendSystemMessage(Component.literal("[Clair] Błąd linkowania: " + err.getMessage()));
                        return;
                    }

                    boolean ok = resp.has("ok") && resp.get("ok").getAsBoolean();
                    if (ok) {
                        player.sendSystemMessage(Component.literal("[Clair] Konto zostało zlinkowane."));
                    } else {
                        String error = resp.has("error") && !resp.get("error").isJsonNull()
                                ? resp.get("error").getAsString()
                                : "unknown";
                        player.sendSystemMessage(Component.literal("[Clair] Linkowanie nieudane: " + error));
                    }
                })
        );
    }

    private void sendUnlinkRequest(ServerPlayer player) {
        JsonObject payload = new JsonObject();
        payload.add("player", FabricBridgePlatform.playerPayload(player));

        player.sendSystemMessage(Component.literal("[Clair] Wysyłam unlink do Bridge..."));
        bridgeClient.sendRequest("unlink", payload).whenComplete((resp, err) ->
                platform.runOnServerThread(() -> {
                    if (err != null) {
                        player.sendSystemMessage(Component.literal("[Clair] Błąd unlink: " + err.getMessage()));
                        return;
                    }

                    boolean ok = resp.has("ok") && resp.get("ok").getAsBoolean();
                    if (ok) {
                        player.sendSystemMessage(Component.literal("[Clair] Powiązanie zostało usunięte."));
                    } else {
                        String error = resp.has("error") && !resp.get("error").isJsonNull()
                                ? resp.get("error").getAsString()
                                : "unknown";
                        player.sendSystemMessage(Component.literal("[Clair] Unlink nieudany: " + error));
                    }
                })
        );
    }

    private static String readDisplayFrame(DisplayInfo display) {
        Object value = invokeDisplayMethod(display, "getFrame");
        if (value == null) {
            value = invokeDisplayMethod(display, "getType");
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name().toLowerCase(Locale.ROOT);
        }
        return value == null ? "task" : value.toString().toLowerCase(Locale.ROOT);
    }

    private static Object invokeDisplayMethod(DisplayInfo display, String methodName) {
        try {
            Method method = display.getClass().getMethod(methodName);
            return method.invoke(display);
        } catch (Exception ignored) {
            return null;
        }
    }
}
