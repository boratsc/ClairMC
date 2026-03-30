package app.clair.mcbridge.neoforge;

import app.clair.mcbridge.bridge.BridgeClient;
import app.clair.mcbridge.bridge.CommandDispatcher;
import app.clair.mcbridge.common.HeartbeatPayloadFactory;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;

@Mod(ClairMcBridgeNeoForgeMod.MOD_ID)
public final class ClairMcBridgeNeoForgeMod {
    public static final String MOD_ID = "clairmcbridge";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final NeoForgeBridgePlatform platform;
    private final BridgeClient bridgeClient;
    private long tickCounter = 0L;

    public ClairMcBridgeNeoForgeMod(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, NeoForgeConfig.SPEC, "clairmcbridge-common.toml");

        this.platform = new NeoForgeBridgePlatform(LOGGER);
        this.bridgeClient = new BridgeClient(platform);
        this.platform.setCommandDispatcher(new CommandDispatcher(platform, bridgeClient, NeoForgeConfig.ALLOW_CONSOLE_COMMANDS.get().stream().map(Object::toString).toList()));

        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        platform.attachServer(event.getServer());
        bridgeClient.start();
        LOGGER.info("ClairMCBridge NeoForge server mod enabled");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        bridgeClient.stop();
        platform.detachServer();
        LOGGER.info("ClairMCBridge NeoForge server mod disabled");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("link")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    String code = StringArgumentType.getString(context, "code");
                                    sendLinkRequest(player, code);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("unlink")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            sendUnlinkRequest(player);
                            return 1;
                        })
        );
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        int seconds = NeoForgeConfig.HEARTBEAT_SECONDS.get();
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
                        NeoForgeConfig.HEARTBEAT_TPS.get(),
                        NeoForgeConfig.HEARTBEAT_PLAYERS_LIST.get()
                )
        );
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!NeoForgeConfig.SEND_JOIN_QUIT.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", NeoForgeBridgePlatform.playerPayload(player));
        bridgeClient.sendEvent("player_join", payload);
    }

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!NeoForgeConfig.SEND_JOIN_QUIT.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", NeoForgeBridgePlatform.playerPayload(player));
        bridgeClient.sendEvent("player_quit", payload);
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!NeoForgeConfig.SEND_DEATHS.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", NeoForgeBridgePlatform.playerPayload(player));
        if (event.getSource().getLocalizedDeathMessage(player) != null) {
            payload.addProperty("message", event.getSource().getLocalizedDeathMessage(player).getString());
        }
        bridgeClient.sendEvent("player_death", payload);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (!NeoForgeConfig.SEND_CHAT.get()) {
            return;
        }

        String message = event.getRawText();
        if (message == null || message.isBlank() || message.startsWith("/")) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", NeoForgeBridgePlatform.playerPayload(event.getPlayer()));
        payload.addProperty("message", message);
        bridgeClient.sendEvent("mc_chat", payload);
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!NeoForgeConfig.SEND_ADVANCEMENTS.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        AdvancementHolder holder = event.getAdvancement();
        Optional<DisplayInfo> displayOpt = holder.value().display();
        if (displayOpt.isEmpty() || holder.id().getPath().startsWith("recipes/")) {
            return;
        }

        DisplayInfo display = displayOpt.get();

        JsonObject advancementPayload = new JsonObject();
        advancementPayload.addProperty("key", holder.id().toString());
        advancementPayload.addProperty("title", display.getTitle().getString());
        advancementPayload.addProperty("description", display.getDescription().getString());
        advancementPayload.addProperty("frame", readDisplayFrame(display));

        JsonObject payload = new JsonObject();
        payload.add("player", NeoForgeBridgePlatform.playerPayload(player));
        payload.add("advancement", advancementPayload);
        bridgeClient.sendEvent("advancement", payload);
    }

    private void sendLinkRequest(ServerPlayer player, String code) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.add("player", NeoForgeBridgePlatform.playerPayload(player));

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
        payload.add("player", NeoForgeBridgePlatform.playerPayload(player));

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
            value = invokeDisplayMethod(display, "frame");
        }
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
