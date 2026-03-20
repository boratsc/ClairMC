package app.clair.mcbridge.common;

public interface BridgeLogger {
    void info(String message);

    void warn(String message);

    void warn(String message, Throwable error);

    void error(String message);

    void error(String message, Throwable error);
}
