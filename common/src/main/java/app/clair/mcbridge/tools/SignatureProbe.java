package app.clair.mcbridge.tools;

import app.clair.mcbridge.bridge.CanonicalJson;
import app.clair.mcbridge.bridge.Hmac;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SignatureProbe {
    private SignatureProbe() {
    }

    public static void main(String[] args) {
        String secret = args.length > 0 ? args[0] : "test_secret";

        JsonObject payload = new JsonObject();
        payload.addProperty("discordUserId", "1324396184411832370");

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "response");
        msg.addProperty("id", "req_example");
        msg.addProperty("serverId", "serwer-test");
        msg.addProperty("ts", 1738976401L);
        msg.addProperty("ok", true);
        msg.add("payload", payload);

        JsonObject copy = msg.deepCopy();
        copy.remove("signature");

        String canonical = CanonicalJson.canonicalize(copy);
        byte[] mac = Hmac.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), canonical.getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(mac);

        System.out.println("canonical=" + canonical);
        System.out.println("signature=" + sig);
    }
}
