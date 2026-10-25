package com.classpulse.backend.security;

import com.classpulse.backend.common.CryptoUtils;
import com.classpulse.backend.common.exception.ForbiddenException;
import com.classpulse.backend.common.exception.UnauthorizedException;
import com.classpulse.backend.session.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TeacherSecurityService {

    private final String teacherMasterKey;

    public TeacherSecurityService(@Value("${classpulse.security.teacher-master-key:}") String teacherMasterKey) {
        this.teacherMasterKey = teacherMasterKey;
    }

    public boolean isMasterKeyValid(String teacherKey) {
        return teacherMasterKey != null && !teacherMasterKey.isBlank() && teacherMasterKey.trim().equals(teacherKey != null ? teacherKey.trim() : null);
    }

    public void verifyTeacherSessionAccess(Session session, String teacherToken, String teacherKey) {
        if (teacherKey != null && !teacherKey.isBlank() && teacherKey.equals(teacherMasterKey)) {
            return;
        }

        if (teacherToken == null || teacherToken.isBlank()) {
            throw new UnauthorizedException("Teacher authentication required. Provide X-Teacher-Token or X-Teacher-Key header.");
        }

        // Clean bearer prefix if present
        String cleanToken = teacherToken.startsWith("Bearer ") ? teacherToken.substring(7).trim() : teacherToken.trim();
        String hash = CryptoUtils.hashToken(cleanToken);

        if (!hash.equalsIgnoreCase(session.getTeacherTokenHash())) {
            throw new ForbiddenException("Invalid teacher credentials for session " + session.getId());
        }
    }

    public void verifyTeacherCreationAccess(String teacherKey) {
        // If master key is configured, verify it
        if (teacherMasterKey != null && !teacherMasterKey.isBlank()) {
            if (teacherKey == null || !teacherKey.trim().equals(teacherMasterKey.trim())) {
                throw new UnauthorizedException("Valid teacher key required to create sessions. Provide X-Teacher-Key header.");
            }
        }
    }
}
