package dev.yuliang.zymbot.core.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts bus lines that leave the process (AES-256-GCM, key derived from the team key), so
 * someone on the same Wi-Fi sees noise instead of bot positions. Signing ({@link Signer}) says
 * *who* sent it; sealing makes it *unreadable* to everyone else. GCM also rejects any tampering.
 * Wire form: {@code zbx <base64url(nonce || ciphertext)>}.
 */
public final class Sealer {
    public static final String PREFIX = "zbx ";
    private static final SecureRandom RNG = new SecureRandom();
    private static final int NONCE = 12, TAG_BITS = 128;
    private final SecretKeySpec key;

    public Sealer(String teamKey) {
        try {
            byte[] k = MessageDigest.getInstance("SHA-256")
                    .digest(("zymbot-seal:" + (teamKey == null ? "" : teamKey)).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String seal(String line) {
        try {
            byte[] nonce = new byte[NONCE];
            RNG.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(line.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE);
            System.arraycopy(ct, 0, out, NONCE, ct.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /** The plain line, or empty if it isn't sealed, was sealed with another key, or was altered. */
    public Optional<String> open(String sealed) {
        if (sealed == null || !sealed.startsWith(PREFIX)) return Optional.empty();
        try {
            byte[] in = Base64.getUrlDecoder().decode(sealed.substring(PREFIX.length()).strip());
            if (in.length <= NONCE) return Optional.empty();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE));
            return Optional.of(new String(c.doFinal(in, NONCE, in.length - NONCE), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
