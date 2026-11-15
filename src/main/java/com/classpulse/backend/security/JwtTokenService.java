package com.classpulse.backend.security;

import com.classpulse.backend.model.User;
import com.classpulse.backend.model.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for signing, issuing, parsing, and cryptographically validating
 * JSON Web Tokens (JWT) using HMAC-SHA256 (HS256).
 * <p>
 * Implemented natively without third-party heavy dependencies to ensure zero-overhead,
 * predictable constant-time signature validation, and complete control over token serialization.
 */
@Slf4j
@Service
public class JwtTokenService {

    /** Secret symmetric key used for HMAC-SHA256 signing and verification. */
    private final String secretKey;

    /** System clock provider for deterministic temporal evaluation during tests. */
    private final Clock clock;

    /** JCE standard algorithm name for HMAC with SHA-256. */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** Default token lifespan duration: 7 days in seconds. */
    private static final long EXPIRATION_SECONDS = 7 * 24 * 3600;

    /** Static standard JWT header specifying algorithm HS256 and type JWT. */
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    /**
     * Constructs the JWT service injecting secret key and time clock.
     *
     * @param secretKey symmetric signing key from configuration or environment
     * @param clock     application clock
     */
    public JwtTokenService(
            @Value("${classpulse.security.jwt-secret:default-secret-jwt-key-classpulse-platform-2026-secure-32chars}") String secretKey,
            Clock clock) {
        this.secretKey = secretKey;
        this.clock = clock;
    }

    /**
     * Issues a new signed compact JWT token for an authenticated user.
     *
     * @param user the authenticated user entity
     * @return signed JWT string in format header.payload.signature
     */
    public String generateToken(User user) {
        try {
            // Step 1: Calculate current epoch seconds and expiration boundary
            long nowSec = clock.instant().getEpochSecond();
            long expSec = nowSec + EXPIRATION_SECONDS;

            // Step 2: Assemble JSON claims payload manually with JSON-escaped string fields
            String payloadJson = "{"
                    + "\"sub\":\"" + escapeJson(user.getId().toString()) + "\","
                    + "\"email\":\"" + escapeJson(user.getEmail()) + "\","
                    + "\"role\":\"" + escapeJson(user.getRole().name()) + "\","
                    + "\"fullName\":\"" + escapeJson(user.getFullName()) + "\","
                    + "\"iat\":" + nowSec + ","
                    + "\"exp\":" + expSec
                    + "}";

            // Step 3: Base64-URL encode header and payload JSON representations
            String encodedHeader = base64UrlEncode(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

            // Step 4: Concatenate header and payload with period delimiter
            String dataToSign = encodedHeader + "." + encodedPayload;

            // Step 5: Compute HMAC-SHA256 signature
            String signature = sign(dataToSign);

            // Step 6: Return complete compact 3-part token
            return dataToSign + "." + signature;
        } catch (Exception e) {
            log.error("Failed to generate JWT token for user {}", user.getId(), e);
            throw new IllegalStateException("Failed to generate authentication token", e);
        }
    }

    /**
     * Cryptographically verifies and extracts claims from a raw JWT token string.
     * Handles optional "Bearer " prefix and performs constant-time signature comparison.
     *
     * @param token candidate token string
     * @return Optional containing {@link JwtClaims} if valid, unexpired, and authentic; empty Optional otherwise
     */
    public Optional<JwtClaims> validateAndParseToken(String token) {
        // Step 1: Guard against null or blank inputs
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        // Step 2: Strip optional 'Bearer ' prefix and whitespace
        String cleanToken = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();

        // Step 3: Ensure token conforms to standard 3-segment structure (header.payload.signature)
        String[] parts = cleanToken.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            // Step 4: Recompute expected HMAC signature from incoming header and payload segments
            String dataToSign = parts[0] + "." + parts[1];
            String expectedSignature = sign(dataToSign);

            // Step 5: Constant-time comparison to guard against side-channel timing attacks
            if (!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.UTF_8), expectedSignature.getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }

            // Step 6: Decode payload segment from Base64-URL
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

            // Step 7: Validate expiration timestamp
            long exp = extractJsonLong(payloadJson, "exp");
            long nowSec = clock.instant().getEpochSecond();
            if (exp > 0 && nowSec > exp) {
                log.debug("JWT token expired: exp={}, now={}", exp, nowSec);
                return Optional.empty();
            }

            // Step 8: Extract core claim fields
            String subStr = extractJsonString(payloadJson, "sub");
            String email = extractJsonString(payloadJson, "email");
            String fullName = extractJsonString(payloadJson, "fullName");
            String roleStr = extractJsonString(payloadJson, "role");

            // Step 9: Verify mandatory subject, email, and role claims exist
            if (subStr == null || email == null || roleStr == null) {
                return Optional.empty();
            }

            // Step 10: Parse UUID and Role enum
            UUID userId = UUID.fromString(subStr);
            UserRole role = UserRole.valueOf(roleStr);

            // Step 11: Return validated claims
            return Optional.of(JwtClaims.builder()
                    .userId(userId)
                    .email(email)
                    .fullName(fullName != null ? fullName : "")
                    .role(role)
                    .exp(exp)
                    .build());
        } catch (Exception e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Computes the HMAC-SHA256 signature for the given input data using the configured secret key.
     *
     * @param data the "header.payload" string
     * @return Base64-URL encoded signature string
     * @throws Exception if cryptographic provider setup fails
     */
    private String sign(String data) throws Exception {
        // Initialize Mac instance with HMAC-SHA256 algorithm
        Mac mac = Mac.getInstance(HMAC_SHA256);
        // Create secret key specification from secret key bytes
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(keySpec);
        // Compute cryptographic signature
        byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        // Base64-URL encode without padding
        return base64UrlEncode(signatureBytes);
    }

    /**
     * Encodes byte array into a URL-safe Base64 string without trailing padding '=' characters.
     *
     * @param bytes byte array to encode
     * @return unpadded URL-safe Base64 string
     */
    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Escapes control characters and quotes for safe JSON serialization.
     *
     * @param s input string
     * @return sanitized JSON-safe string
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Unescapes control characters and quotes from JSON string literal.
     *
     * @param s escaped JSON string
     * @return plain unescaped string
     */
    private String unescapeJson(String s) {
        if (s == null) return null;
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\b", "\b")
                .replace("\\f", "\f")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    /**
     * Fast regex extractor for a string property from simple JSON structures.
     *
     * @param json  raw JSON string
     * @param field property key name
     * @return extracted unescaped string value or null if not found
     */
    private String extractJsonString(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        return null;
    }

    /**
     * Fast regex extractor for a numeric long property from simple JSON structures.
     *
     * @param json  raw JSON string
     * @param field property key name
     * @return extracted long value or 0 if absent/malformed
     */
    private long extractJsonLong(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
