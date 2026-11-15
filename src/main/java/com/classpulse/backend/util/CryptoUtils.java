package com.classpulse.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Thread-safe cryptographic utility class providing high-entropy random generation
 * and one-way hashing services across the ClassPulse platform.
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Generating cryptographically strong unguessable session tokens (Base64 URL-safe).</li>
 *   <li>Generating SHA-256 hex digest hashes for secure zero-knowledge storage in database columns.</li>
 *   <li>Generating user-friendly 6-character alphanumeric classroom join codes excluding ambiguous glyphs.</li>
 * </ul>
 */
public final class CryptoUtils {

    /**
     * Cryptographically secure pseudo-random number generator (CSPRNG) instance.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 32-character unambiguous alphabet for classroom join codes.
     * Visually confusing characters like '0' (zero), 'O' (capital letter O), '1' (one),
     * and 'I' (capital letter I) are intentionally excluded to minimize human transcription mistakes.
     */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /**
     * Private constructor preventing direct instantiation of this utility class.
     */
    private CryptoUtils() {}

    /**
     * Generates a cryptographically strong, URL-safe random token of specified byte entropy.
     *
     * @param byteLength the number of raw random bytes to sample (e.g. 32 bytes for 256 bits of entropy)
     * @return a Base64 URL-encoded string without padding characters
     */
    public static String generateSecureToken(int byteLength) {
        // Allocate byte array buffer for random entropy
        byte[] bytes = new byte[byteLength];
        // Populate the buffer with cryptographically secure random bytes
        SECURE_RANDOM.nextBytes(bytes);
        // Encode the bytes using URL-safe Base64 without trailing '=' padding
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the SHA-256 one-way cryptographic hash of a given string.
     *
     * @param token the plain-text token or secret to hash
     * @return 64-character lowercase hex string representation of the hash, or null if input is null
     */
    public static String hashToken(String token) {
        // Return null immediately if input is null
        if (token == null) {
            return null;
        }
        try {
            // Obtain a SHA-256 MessageDigest instance
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Compute the 32-byte digest of the UTF-8 encoded token
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            // Convert binary hash into a standard lowercase hexadecimal string
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by standard Java SE specifications; rethrow as unrecoverable
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    }

    /**
     * Generates a random classroom join code of the requested length using the unambiguous alphabet.
     *
     * @param length the desired length of the code (standard is 6 characters)
     * @return a string consisting of randomly selected uppercase characters and digits
     */
    public static String generateJoinCode(int length) {
        // Pre-allocate StringBuilder with exact target capacity
        StringBuilder sb = new StringBuilder(length);
        // Randomly select indices from the unambiguous alphabet
        for (int i = 0; i < length; i++) {
            int index = SECURE_RANDOM.nextInt(CODE_ALPHABET.length());
            sb.append(CODE_ALPHABET.charAt(index));
        }
        // Return constructed code
        return sb.toString();
    }
}
