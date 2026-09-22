package dev.yuliang.zymbot.core.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over the message body with the team key, truncated to 96 bits. Without it, anyone
 * on the network (or a public relay) could send a fake blood-moon reading and make every bot hide.
 * An empty key still signs — with a well-known key — so the format never changes; it just isn't secret.
 */
public final class Signer {
    private final SecretKeySpec key;

    public Signer(String teamKey) {
        byte[] k = ("zymbot:" + (teamKey == null ? "" : teamKey)).getBytes(StandardCharsets.UTF_8);
        this.key = new SecretKeySpec(k, "HmacSHA256");
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] full = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] cut = java.util.Arrays.copyOf(full, 12);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(cut);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public boolean verify(String body, String sig) {
        return MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII),
                sig.getBytes(StandardCharsets.US_ASCII));
    }
}
