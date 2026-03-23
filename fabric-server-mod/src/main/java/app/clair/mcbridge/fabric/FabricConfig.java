package app.clair.mcbridge.fabric;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class FabricConfig {
    private static final String FILE_NAME = "clairmcbridge-common.toml";
    private static final String DEFAULT_BRIDGE_URL = "wss://clairbot.app/api/mc-bridge";
    private static final String DEFAULT_BRIDGE_SERVER_ID = "your-server-id";
    private static final String DEFAULT_BRIDGE_SECRET = "replace-with-your-bridge-secret";

    private static volatile State current = State.defaults();

    private FabricConfig() {
    }

    public static synchronized void load(Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                Files.writeString(path, defaultToml());
                logger.info("Created default ClairMC Bridge config at {}", path);
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                TomlParseResult result = Toml.parse(reader);
                if (result.hasErrors()) {
                    throw new IllegalStateException("Invalid TOML in " + path + ": " + formatErrors(result.errors()));
                }
                current = State.from(result);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load Fabric config from " + path, ex);
        }
    }

    public static String bridgeUrl() {
        return current.bridgeUrl();
    }

    public static String bridgeServerId() {
        return current.bridgeServerId();
    }

    public static String bridgeSecret() {
        return current.bridgeSecret();
    }

    public static int heartbeatSeconds() {
        return current.heartbeatSeconds();
    }

    public static boolean sendJoinQuit() {
        return current.sendJoinQuit();
    }

    public static boolean sendDeaths() {
        return current.sendDeaths();
    }

    public static boolean sendChat() {
        return current.sendChat();
    }

    public static boolean sendAdvancements() {
        return current.sendAdvancements();
    }

    public static boolean heartbeatPlayersList() {
        return current.heartbeatPlayersList();
    }

    public static boolean heartbeatTps() {
        return current.heartbeatTps();
    }

    public static List<String> allowConsoleCommands() {
        return current.allowConsoleCommands();
    }

    private static String requireString(TomlParseResult result, String key) {
        Object value = result.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalStateException("Missing or invalid string config key: " + key);
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("Config key must not be blank: " + key);
        }
        return trimmed;
    }

    private static int requireInt(TomlParseResult result, String key, int min, int max) {
        Object value = result.get(key);
        if (!(value instanceof Long number)) {
            throw new IllegalStateException("Missing or invalid integer config key: " + key);
        }
        if (number < min || number > max) {
            throw new IllegalStateException("Config key out of range (" + min + "-" + max + "): " + key);
        }
        return number.intValue();
    }

    private static boolean requireBoolean(TomlParseResult result, String key) {
        Object value = result.get(key);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalStateException("Missing or invalid boolean config key: " + key);
        }
        return bool;
    }

    private static List<String> requireStringList(TomlParseResult result, String key) {
        Object value = result.get(key);
        if (!(value instanceof TomlArray array)) {
            throw new IllegalStateException("Missing or invalid string-list config key: " + key);
        }

        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object entry = array.get(i);
            if (!(entry instanceof String text)) {
                throw new IllegalStateException("Invalid list entry in " + key + " at index " + i);
            }
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalStateException("Blank list entry in " + key + " at index " + i);
            }
            out.add(trimmed);
        }
        return List.copyOf(out);
    }

    private static String formatErrors(List<TomlParseError> errors) {
        return errors.stream()
                .map(error -> error.position().line() + ":" + error.position().column() + " " + error.getMessage())
                .collect(Collectors.joining("; "));
    }

    private static String defaultToml() {
        return """
                [bridge]
                url = "wss://clairbot.app/api/mc-bridge"
                serverId = "your-server-id"
                secret = "replace-with-your-bridge-secret"

                [features]
                heartbeatSeconds = 30
                sendJoinQuit = true
                sendDeaths = true
                sendChat = false
                sendAdvancements = false
                heartbeatPlayersList = false
                heartbeatTps = false

                [commands]
                allowConsoleCommands = ["say", "whitelist add", "kick", "ban"]
                """;
    }

    private record State(
            String bridgeUrl,
            String bridgeServerId,
            String bridgeSecret,
            int heartbeatSeconds,
            boolean sendJoinQuit,
            boolean sendDeaths,
            boolean sendChat,
            boolean sendAdvancements,
            boolean heartbeatPlayersList,
            boolean heartbeatTps,
            List<String> allowConsoleCommands
    ) {
        private static State defaults() {
            return new State(
                    DEFAULT_BRIDGE_URL,
                    DEFAULT_BRIDGE_SERVER_ID,
                    DEFAULT_BRIDGE_SECRET,
                    30,
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    List.of("say", "whitelist add", "kick", "ban")
            );
        }

        private static State from(TomlParseResult result) {
            return new State(
                    requireString(result, "bridge.url"),
                    requireString(result, "bridge.serverId"),
                    requireString(result, "bridge.secret"),
                    requireInt(result, "features.heartbeatSeconds", 0, 3600),
                    requireBoolean(result, "features.sendJoinQuit"),
                    requireBoolean(result, "features.sendDeaths"),
                    requireBoolean(result, "features.sendChat"),
                    requireBoolean(result, "features.sendAdvancements"),
                    requireBoolean(result, "features.heartbeatPlayersList"),
                    requireBoolean(result, "features.heartbeatTps"),
                    requireStringList(result, "commands.allowConsoleCommands")
            );
        }
    }
}
