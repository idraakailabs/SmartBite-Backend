package com.idraak.smartbite.v2.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Logs never contain a raw uid. They carry HMAC-SHA256(uid) truncated to 16 hex characters, keyed by
 * LOG_UID_HASH_KEY. Without a key a random one is generated per process, so hashes still correlate within one run
 * but not across restarts.
 */
public class UidHasher {

    private final byte[] key;

    public UidHasher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = Base64.getDecoder().decode(base64Key.trim());
        }
    }

    public String hash(String uid) {
        if (uid == null) return "-";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(uid.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
