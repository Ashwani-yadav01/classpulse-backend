package com.classpulse.backend.security;

import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.exception.UnauthorizedException;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.UserRole;
import com.classpulse.backend.util.CryptoUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TeacherSecurityService {

    private final String teacherMasterKey;
    private final JwtTokenService jwtTokenService;

    public TeacherSecurityService(@Value("${classpulse.security.teacher-master-key:}") String teacherMasterKey) {
        this(teacherMasterKey, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TeacherSecurityService(
            @Value("${classpulse.security.teacher-master-key:}") String teacherMasterKey,
            JwtTokenService jwtTokenService) {
        this.teacherMasterKey = teacherMasterKey;
        this.jwtTokenService = jwtTokenService;
    }

    public boolean isMasterKeyValid(String teacherKey) {
        return teacherMasterKey != null && !teacherMasterKey.isBlank() && teacherMasterKey.trim().equals(teacherKey != null ? teacherKey.trim() : null);
    }

    public void verifyTeacherSessionAccess(Session session, String teacherToken, String teacherKey) {
        if (teacherToken == null || teacherToken.isBlank()) {
            if (teacherKey != null && !teacherKey.isBlank() && isMasterKeyValid(teacherKey)) {
                return;
            }
            throw new UnauthorizedException("Teacher authentication required. Provide X-Teacher-Token, Authorization, or X-Teacher-Key header.");
        }

        String cleanToken = teacherToken.startsWith("Bearer ") ? teacherToken.substring(7).trim() : teacherToken.trim();

        // 1. Check if token is a valid JWT for a teacher
        if (jwtTokenService != null) {
            Optional<JwtClaims> claimsOpt = jwtTokenService.validateAndParseToken(cleanToken);
            if (claimsOpt.isPresent()) {
                JwtClaims claims = claimsOpt.get();
                if (claims.getRole() == UserRole.TEACHER) {
                    // If session is owned by a teacher, verify match
                    if (session.getTeacher() != null) {
                        if (session.getTeacher().getId().equals(claims.getUserId())) {
                            return;
                        }
                        throw new ForbiddenException("Teacher does not have permission for session " + session.getId());
                    }
                    // If session has no explicit teacher owner, allow master key or fallback
                    if (teacherKey != null && !teacherKey.isBlank() && isMasterKeyValid(teacherKey)) {
                        return;
                    }
                    return;
                }
                throw new ForbiddenException("Teacher role required to manage session " + session.getId());
            }
        }

        // 2. Check master key if provided without valid JWT
        if (teacherKey != null && !teacherKey.isBlank() && isMasterKeyValid(teacherKey)) {
            return;
        }

        // 3. Check per-session opaque teacher token hash
        String hash = CryptoUtils.hashToken(cleanToken);
        if (hash.equalsIgnoreCase(session.getTeacherTokenHash())) {
            return;
        }

        throw new ForbiddenException("Invalid teacher credentials for session " + session.getId());
    }

    public void verifyTeacherCreationAccess(String teacherKey) {
        verifyTeacherCreationAccess(teacherKey, null);
    }

    public void verifyTeacherCreationAccess(String teacherKey, String authHeader) {
        // If JWT header provided, check if it's a teacher
        if (authHeader != null && !authHeader.isBlank()) {
            if (jwtTokenService != null) {
                Optional<JwtClaims> claimsOpt = jwtTokenService.validateAndParseToken(authHeader);
                if (claimsOpt.isPresent()) {
                    if (claimsOpt.get().getRole() == UserRole.TEACHER) {
                        return;
                    }
                    throw new ForbiddenException("Teacher role required to create sessions");
                }
            }
        }

        // If master key is configured and valid, allow creation
        if (teacherKey != null && !teacherKey.isBlank() && isMasterKeyValid(teacherKey)) {
            return;
        }

        throw new UnauthorizedException("Valid teacher key or teacher account required to create sessions. Provide X-Teacher-Key or Authorization header.");
    }
}
