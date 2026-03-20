package app.clair.mcbridge.common;

import java.util.UUID;

public interface PlayerHandle {
    UUID uuid();

    String name();

    double health();

    String worldName();

    void sendMessage(String message);

    void kick(String reason);
}
