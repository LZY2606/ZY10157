package gsb.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable SHA-256 fingerprints over canonical, order-independent key/value streams. */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256Hex16(String canonical) {
        return sha256Hex(canonical).substring(0, 16);
    }

    public static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Event fingerprint: SHA-256 of the pipe-joined, sorted member segment ids. */
    public static String eventFingerprint(java.util.Collection<String> segmentIds) {
        return sha256Hex16(String.join("|",
                segmentIds.stream().sorted().toList()));
    }
}
