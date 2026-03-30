package app.clair.mcbridge.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class NeoForgeConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> BRIDGE_URL;
    public static final ModConfigSpec.ConfigValue<String> BRIDGE_SERVER_ID;
    public static final ModConfigSpec.ConfigValue<String> BRIDGE_SECRET;

    public static final ModConfigSpec.IntValue HEARTBEAT_SECONDS;
    public static final ModConfigSpec.BooleanValue SEND_JOIN_QUIT;
    public static final ModConfigSpec.BooleanValue SEND_DEATHS;
    public static final ModConfigSpec.BooleanValue SEND_CHAT;
    public static final ModConfigSpec.BooleanValue SEND_ADVANCEMENTS;
    public static final ModConfigSpec.BooleanValue HEARTBEAT_PLAYERS_LIST;
    public static final ModConfigSpec.BooleanValue HEARTBEAT_TPS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOW_CONSOLE_COMMANDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("bridge");
        BRIDGE_URL = builder.comment("WebSocket endpoint Bridge API").define("url", "wss://clairbot.app/api/mc-bridge");
        BRIDGE_SERVER_ID = builder.comment("Server ID from Clair panel").define("serverId", "your-server-id");
        BRIDGE_SECRET = builder.comment("HMAC secret from Clair panel").define("secret", "replace-with-your-bridge-secret");
        builder.pop();

        builder.push("features");
        HEARTBEAT_SECONDS = builder.defineInRange("heartbeatSeconds", 30, 0, 3600);
        SEND_JOIN_QUIT = builder.define("sendJoinQuit", true);
        SEND_DEATHS = builder.define("sendDeaths", true);
        SEND_CHAT = builder.define("sendChat", false);
        SEND_ADVANCEMENTS = builder.define("sendAdvancements", false);
        HEARTBEAT_PLAYERS_LIST = builder.define("heartbeatPlayersList", false);
        HEARTBEAT_TPS = builder.define("heartbeatTps", false);
        builder.pop();

        builder.push("commands");
        ALLOW_CONSOLE_COMMANDS = builder.defineListAllowEmpty(
                List.of("allowConsoleCommands"),
                List.of("say", "whitelist add", "kick", "ban"),
                value -> value instanceof String
        );
        builder.pop();

        SPEC = builder.build();
    }

    private NeoForgeConfig() {
    }
}
