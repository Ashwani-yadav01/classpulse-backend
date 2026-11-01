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

@Slf4j
@Service
public class JwtTokenService {

    private final String secretKey;
    private final Clock clock;
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long EXPIRATION_SECONDS = 7 * 24 * 3600; // 7 days

    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    public JwtTokenService(
            @Value("${classpulse.security.jwt-secret:default-secret-jwt-key-classpulse-platform-2026-secure-32chars}") String secretKey,
            Clock clock) {
        this.secretKey = secretKey;
        this.clock = clock;
    }

    public String generateToken(User user) {
        try {
            long nowSec = clock.instant().getEpochSecond();
            long expSec = nowSec + EXPIRATION_SECONDS;

            String payloadJson = "{"
                    + "\"sub\":\"" + escapeJson(user.getId().toString()) + "\","
                    + "\"email\":\"" + escapeJson(user.getEmail()) + "\","
                    + "\"role\":\"" + escapeJson(user.getRole().name()) + "\","
                    + "\"fullName\":\"" + escapeJson(user.getFullName()) + "\","
                    + "\"iat\":" + nowSec + ","
                    + "\"exp\":" + expSec
                    + "}";

            String encodedHeader = base64UrlEncode(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

            String dataToSign = encodedHeader + "." + encodedPayload;
            String signature = sign(dataToSign);

            return dataToSign + "." + signature;
        } catch (Exception e) {
            log.error("Failed to generate JWT token for user {}", user.getId(), e);
            throw new IllegalStateException("Failed to generate authentication token", e);
        }
    }

    public Optional<JwtClaims> validateAndParseToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String cleanToken = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
        String[] parts = cleanToken.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            String dataToSign = parts[0] + "." + parts[1];
            String expectedSignature = sign(dataToSign);

            // Constant-time comparison to prevent timing attacks
            if (!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.UTF_8), expectedSignature.getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

            long exp = extractJsonLong(payloadJson, "exp");
            long nowSec = clock.instant().getEpochSecond();
            if (exp > 0 && nowSec > exp) {
                log.debug("JWT token expired: exp={}, now={}", exp, nowSec);
                return Optional.empty();
            }

            String subStr = extractJsonString(payloadJson, "sub");
            String email = extractJsonString(payloadJson, "email");
            String fullName = extractJsonString(payloadJson, "fullName");
            String roleStr = extractJsonString(payloadJson, "role");

            if (subStr == null || email == null || roleStr == null) {
                return Optional.empty();
            }

            UUID userId = UUID.fromString(subStr);
            UserRole role = UserRole.valueOf(roleStr);

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

    private String sign(String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(keySpec);
        byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(signatureBytes);
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

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

    private String extractJsonString(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        return null;
    }

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
