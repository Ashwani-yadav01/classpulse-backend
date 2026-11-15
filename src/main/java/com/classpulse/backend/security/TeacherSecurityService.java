package com.classpulse.backend.security;

import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.exception.UnauthorizedException;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.UserRole;
import com.classpulse.backend.util.CryptoUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Security service verifying teacher credentials and authorization rights for classroom sessions.
 * <p>
 * Supports multiple authorization modes:
 * <ol>
 *   <li>Stateless JWT authentication verifying the TEACHER role and session ownership.</li>
 *   <li>Configured environment Master Key ({@code X-Teacher-Key}) for administrative or automated operations.</li>
 *   <li>Per-session opaque teacher tokens hashed with SHA-256 ({@code X-Teacher-Token}).</li>
 * </ol>
 */
@Service
public class TeacherSecurityService {

    /** Master admin key configured in application properties. */
    private final String teacherMasterKey;

    /** JWT parsing and verification service. */
    private final JwtTokenService jwtTokenService;

    /**
     * Fallback constructor used in test environments without full JWT service injection.
     *
     * @param teacherMasterKey master key secret
     */
    public TeacherSecurityService(@Value("${classpulse.security.teacher-master-key:}") String teacherMasterKey) {
        this(teacherMasterKey, null);
    }

    /**
     * Primary constructor injecting master key configuration and JWT token service.
     *
     * @param teacherMasterKey master key secret
     * @param jwtTokenService  JWT verification service
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TeacherSecurityService(
            @Value("${classpulse.security.teacher-master-key:}") String teacherMasterKey,
            JwtTokenService jwtTokenService) {
        this.teacherMasterKey = teacherMasterKey;
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * Validates whether a provided teacher key matches the configured administrative master key.
     *
     * @param teacherKey the candidate key passed in the {@code X-Teacher-Key} header
     * @return true if master key is configured and matches candidate; false otherwise
     */
    public boolean isMasterKeyValid(String teacherKey) {
        return teacherMasterKey != null
                && !teacherMasterKey.isBlank()
                && teacherMasterKey.trim().equals(teacherKey != null ? teacherKey.trim() : null);
    }

    /**
     * Verifies that the client possesses valid teacher credentials to manage the given session.
     * <p>
     * Order of evaluation:
     * <ol>
     *   <li>If teacherToken is missing, verify if master key was supplied.</li>
     *   <li>Check if teacherToken is a valid JWT issued to a TEACHER and verifies session ownership.</li>
     *   <li>Check if teacherKey matches configured master key.</li>
     *   <li>Check if teacherToken matches the session's SHA-256 teacherTokenHash.</li>
     * </ol>
     *
     * @param session      the target Session entity to be managed
     * @param teacherToken raw token string (Bearer JWT or opaque session token)
     * @param teacherKey   optional master key string
     * @throws UnauthorizedException if credentials are missing
     * @throws ForbiddenException    if credentials fail validation or lack authorization
     */
    public void verifyTeacherSessionAccess(Session session, String teacherToken, String teacherKey) {
        // Step 1: Check for absent teacher token
        if (teacherToken == null || teacherToken.isBlank()) {
            // Allow access if a valid administrative master key was provided
            if (teacherKey != null && !teacherKey.isBlank() && isMasterKeyValid(teacherKey)) {
                return;
            }
            throw new UnauthorizedException("Teacher authentication required. Provide X-Teacher-Token, Authorization, or X-Teacher-Key header.");
        }

        // Clean token of any Bearer prefix
        String cleanToken = teacherToken.startsWith("Bearer ") ? teacherToken.substring(7).trim() : teacherToken.trim();

        // Step 2: Attempt JWT token verification
        if (jwtTokenService != null) {
            Optional<JwtClaims> claimsOpt = jwtTokenService.validateAndParseToken(cleanToken);
            if (claimsOpt.isPresent()) {
                JwtClaims claims = claimsOpt.get();
                // Ensure token belongs to a user with TEACHER role
                if (claims.getRole() == UserRole.TEACHER) {
                    // If session is owned by a specific teacher, ensure user IDs match
                    if (session.getTeacher() != null) {
                        if (session.getTeacher().getId().equals(claims.getUserId())) {
                            return; // Authorized: User owns this session
                        }
                        throw new ForbiddenException("Teacher does not have permission for session " + session.getId());
                    }
                    // If session has no explicit owner (legacy session), allow access
                    if (teacherKey != null && !teacherKey.isBlank() && isMasterKeyValid(teacherKey)) {
                        return;
                    }
                    return;
                }
                throw new ForbiddenException("Teacher role required to manage session " + session.getId());
            }
        }

        // Step 3: Check master key if provided alongside non-JWT token
        if (teacherKey != null && !teacherKey.isBlank() && isMasterKeyValid(teacherKey)) {
            return;
        }

        // Step 4: Check per-session opaque teacher token hash
        String hash = CryptoUtils.hashToken(cleanToken);
        if (hash.equalsIgnoreCase(session.getTeacherTokenHash())) {
            return; // Authorized: Opaque token hash matched
        }

        // Neither JWT, master key, nor opaque session token matched
        throw new ForbiddenException("Invalid teacher credentials for session " + session.getId());
    }

    /**
     * Verifies that the client is authorized to create new classroom sessions using master key only.
     *
     * @param teacherKey master key string
     */
    public void verifyTeacherCreationAccess(String teacherKey) {
        verifyTeacherCreationAccess(teacherKey, null);
    }

    /**
     * Verifies that the client is authorized to create new sessions via JWT or master key.
     *
     * @param teacherKey optional master key string
     * @param authHeader optional Authorization header containing Bearer JWT
     * @throws UnauthorizedException if credentials are missing or invalid
     * @throws ForbiddenException    if user does not hold the TEACHER role
     */
    public void verifyTeacherCreationAccess(String teacherKey, String authHeader) {
        // Step 1: If Authorization header provided, validate JWT teacher role
        if (authHeader != null && !authHeader.isBlank()) {
            if (jwtTokenService != null) {
                Optional<JwtClaims> claimsOpt = jwtTokenService.validateAndParseToken(authHeader);
                if (claimsOpt.isPresent()) {
                    if (claimsOpt.get().getRole() == UserRole.TEACHER) {
                        return; // Authorized teacher account
                    }
                    throw new ForbiddenException("Teacher role required to create sessions");
                }
            }
        }

        // Step 2: Check if administrative master key is valid
        if (teacherKey != null && !teacherKey.isBlank() && isMasterKeyValid(teacherKey)) {
            return; // Authorized via master key
        }

        // Neither valid JWT nor master key was provided
        throw new UnauthorizedException("Valid teacher key or teacher account required to create sessions. Provide X-Teacher-Key or Authorization header.");
    }
}
