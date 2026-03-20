package app.clair.mcbridge.bridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Hmac {
    private Hmac() {
    }

    public static byte[] hmacSha256(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
