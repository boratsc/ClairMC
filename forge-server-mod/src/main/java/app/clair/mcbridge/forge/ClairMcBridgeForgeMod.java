package app.clair.mcbridge.forge;

import app.clair.mcbridge.bridge.BridgeClient;
import app.clair.mcbridge.bridge.CommandDispatcher;
import app.clair.mcbridge.common.HeartbeatPayloadFactory;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Locale;

@Mod(ClairMcBridgeForgeMod.MOD_ID)
public final class ClairMcBridgeForgeMod {
    public static final String MOD_ID = "clairmcbridge";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ForgeBridgePlatform platform;
    private final BridgeClient bridgeClient;
    private long tickCounter = 0L;

    public ClairMcBridgeForgeMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC, "clairmcbridge-common.toml");

        this.platform = new ForgeBridgePlatform(LOGGER);
        this.bridgeClient = new BridgeClient(platform);
        this.platform.setCommandDispatcher(new CommandDispatcher(platform, bridgeClient, ForgeConfig.ALLOW_CONSOLE_COMMANDS.get().stream().map(Object::toString).toList()));

        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        platform.attachServer(event.getServer());
        bridgeClient.start();
        LOGGER.info("ClairMCBridge Forge server mod enabled");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        bridgeClient.stop();
        platform.detachServer();
        LOGGER.info("ClairMCBridge Forge server mod disabled");
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
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        int seconds = ForgeConfig.HEARTBEAT_SECONDS.get();
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
                        ForgeConfig.HEARTBEAT_TPS.get(),
                        ForgeConfig.HEARTBEAT_PLAYERS_LIST.get()
                )
        );
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ForgeConfig.SEND_JOIN_QUIT.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", ForgeBridgePlatform.playerPayload(player));
        bridgeClient.sendEvent("player_join", payload);
    }

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!ForgeConfig.SEND_JOIN_QUIT.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", ForgeBridgePlatform.playerPayload(player));
        bridgeClient.sendEvent("player_quit", payload);
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!ForgeConfig.SEND_DEATHS.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", ForgeBridgePlatform.playerPayload(player));
        if (event.getSource().getLocalizedDeathMessage(player) != null) {
            payload.addProperty("message", event.getSource().getLocalizedDeathMessage(player).getString());
        }
        bridgeClient.sendEvent("player_death", payload);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (!ForgeConfig.SEND_CHAT.get()) {
            return;
        }

        String message = event.getRawText();
        if (message == null || message.isBlank() || message.startsWith("/")) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("player", ForgeBridgePlatform.playerPayload(event.getPlayer()));
        payload.addProperty("message", message);
        bridgeClient.sendEvent("mc_chat", payload);
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!ForgeConfig.SEND_ADVANCEMENTS.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        Advancement advancement = event.getAdvancement();
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
        payload.add("player", ForgeBridgePlatform.playerPayload(player));
        payload.add("advancement", advancementPayload);
        bridgeClient.sendEvent("advancement", payload);
    }

    private void sendLinkRequest(ServerPlayer player, String code) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.add("player", ForgeBridgePlatform.playerPayload(player));

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
        payload.add("player", ForgeBridgePlatform.playerPayload(player));

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
