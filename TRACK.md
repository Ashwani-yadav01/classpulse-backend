# ClassPulse Backend — Implementation & Progress Tracking

## 1. Initial Project Inspection & State
- **Date**: 2026-10-25
- **Spring Boot Version**: 4.1.2
- **Java Version**: 21 (JDK 21.0.10, Compiler release 21)
- **Maven Version**: 3.9.10
- **Base Package**: `com.classpulse.backend`
- **Main Application Class**: `com.classpulse.backend.ClasspulseBackendApplication`
- **Dependencies Configured**:
  - `spring-boot-starter-actuator`
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-flyway`
  - `spring-boot-starter-security`
  - `spring-boot-starter-validation`
  - `spring-boot-starter-webmvc`
  - `flyway-database-postgresql`
  - `postgresql` (runtime)
  - `com.google.zxing:core:3.5.3` & `com.google.zxing:javase:3.5.3` (QR code generation)
  - `com.h2database:h2` (test scope, `MODE=PostgreSQL`)
  - Starter test libraries
- **Initial Build Status**:
  - `mvn clean compile`: SUCCESS
- **Environment Status & Decisions**:
  - Docker Desktop: Installed (v28.3.0), but Docker daemon returned HTTP 500 error on local pipe.
  - PostgreSQL: Local PostgreSQL daemon on port 5432 was inactive.
  - Strategy: Configured PostgreSQL profile with environment variables for production/docker/local deployment. Added `com.h2database:h2` with PostgreSQL compatibility mode (`MODE=PostgreSQL`) in test scope so all tests run deterministically, fast, and independently of external container daemons. Added `docker-compose.yml` for standing up PostgreSQL and backend.

---

## 2. Architectural Decisions
1. **Modular Monolith**:
   - `common`: Base exceptions, `CryptoUtils`, `ClockConfig`, `GlobalExceptionHandler`.
   - `security`: `TeacherSecurityService`, `ParticipantSecurityService`, `SecurityConfig`.
   - `session`: `Session` entity, `SessionRepository`, `SessionService`, `QrCodeService`, `SessionController`.
   - `participant`: `Participant` entity, `ParticipantRepository`, `ParticipantService`, `ParticipantController`.
   - `activity`: `Activity` entity, `ActivityOption` entity, `ActivityType`, `ActivityStatus`, `ActivityRepository`, `ActivityOptionRepository`, `ActivityConfigValidator`, `ActivityService`, `ActivityController`.

2. **Security Model**:
   - **Teacher Authorization**:
     - Master key (`TEACHER_MASTER_KEY` / `X-Teacher-Key`) for admin operations and session creation.
     - Per-session cryptographically secure teacher token (`X-Teacher-Token`): Generated upon session creation (32-byte Base64URL string). Stored hashed (SHA-256). Returned ONLY ONCE in the session creation response. Knowing a session UUID or student 6-character joining code does NOT grant teacher permissions.
     - Session ownership verified on every teacher operation, including activity creation, retrieval, activation, and closure.
   - **Participant Identity**:
     - No email required. Students join with join code + display name.
     - Unguessable 32-byte secure token issued. Stored as SHA-256 hash in database.
     - Token sent in `X-Participant-Token` header.
     - Reconnection handled via token lookup; token revocation supported.
     - Duplicate display name in the same session is prevented (returns HTTP 409 Conflict).
     - Distinct sessions can have identical display names.
     - Students can fetch the active activity for their session (`/activities/active`), with sensitive keys (e.g. `isCorrect` on MCQ options) hidden.

3. **Database & Migrations**:
   - `V1__init_schema.sql`: Establishes `sessions` and `participants` tables.
   - `V2__activity_foundation.sql`: Establishes `activities` and `activity_options` tables with foreign keys (`ON DELETE CASCADE`), indexes, and unique concurrency constraint `uq_activities_one_active_per_session`.

4. **Single Active Activity Concurrency Protection**:
   - Dual-layer protection:
     1. Database unique constraint on `active_session_id` (`CONSTRAINT uq_activities_one_active_per_session UNIQUE (active_session_id)`). When active, `active_session_id = session_id`. When draft or closed, `active_session_id = NULL`. Multiple NULLs allowed by standard SQL.
     2. Pessimistic write locking on the session row (`SELECT ... FOR UPDATE` via `sessionRepository.findByIdForUpdate(sessionId)`) in the transactional service.

---

## 3. Implementation Phases & Actual Verification

- [x] **Phase 1: Generated-Project Validation, Configuration, and Tracking**
  - Project inspected, `pom.xml` verified (Java 21, Spring Boot 4.1.2).
  - Added ZXing (`core` and `javase` 3.5.3) for QR code generation.
  - Added H2 test dependency with PostgreSQL mode.
  - Configured `application.properties` and `.env.example`.
  - Initial tests verified.

- [x] **Phase 2: PostgreSQL, Flyway, Teacher Authorization, Session Creation, Joining Codes, Participant Identity, Expiry, and QR Generation**
  - Created `V1__init_schema.sql` for sessions and participants.
  - Implemented `Session` and `Participant` domain models and repositories.
  - Implemented cryptographic token generation and SHA-256 hashing in `CryptoUtils`.
  - Implemented minimal, secure Teacher authorization (`X-Teacher-Token` and `X-Teacher-Key`) and Participant token security (`X-Participant-Token`).
  - Implemented dynamic 24-hour expiry check with injectable `Clock`.
  - Implemented QR Code service using ZXing and configurable `FRONTEND_JOIN_URL`.
  - Implemented standardized REST exceptions, `GlobalExceptionHandler`, and DTOs.
  - Implemented session creation, management, closing, public info, and QR endpoints.
  - Implemented participant joining, `/participants/me` reconnection, and teacher revocation.
  - Verified 15 unit and integration tests: ALL PASSED.

- [x] **Phase 3: Activity Model, Typed Configuration Validation, Lifecycle Transitions, and Single-Active-Activity Concurrency Protection**
  - Created Flyway migration `V2__activity_foundation.sql` (adding `activities` and `activity_options`).
  - Implemented `ActivityType` enum covering all 7 types: `MCQ`, `POLL`, `WORD_CLOUD`, `OPEN_QUESTION`, `RANDOM_PICKER`, `BRAINSTORMING`, `PRIORITIZATION`.
  - Implemented `ActivityStatus` lifecycle enum: `DRAFT`, `ACTIVE`, `CLOSED`.
  - Implemented `Activity` and `ActivityOption` entities and JPA repositories (`ActivityRepository`, `ActivityOptionRepository`).
  - Implemented `ActivityConfigValidator` enforcing typed configuration rules for all 7 activity types (minimum option counts, non-blank option texts, title length).
  - Implemented `ActivityService` and `ActivityController` with:
    - Draft activity creation with teacher ownership validation.
    - Activity listing and retrieval for session owners.
    - Active activity retrieval for authenticated participants with answer key privacy.
    - Transition from DRAFT to ACTIVE with pessimistic session row locking and database unique constraint fallback.
    - Rejection of second active activity with HTTP 409 Conflict.
    - Transition from ACTIVE to CLOSED.
    - Rejection of closed activity re-activation with HTTP 400 Bad Request.
  - Added `ActivityServiceTest` (8 tests) and `ActivityApiIntegrationTest` (2 tests).
  - Verified all 25 unit and integration tests across the entire application: ALL PASSED.

- [x] **Phase 4: MCQ and Poll End-to-End Implementation**
  - Created Flyway migration `V3__mcq_poll_responses.sql`:
    - `responses` table with foreign keys to `activities`, `participants`, and `activity_options` (`ON DELETE CASCADE`).
    - Database unique constraint `CONSTRAINT uq_response_activity_participant UNIQUE (activity_id, participant_id)` ensuring at most one response per participant per activity.
    - Foreign key indexes (`idx_responses_activity`, `idx_responses_participant`) for performance.
  - Implemented `Response` entity and `ResponseRepository`.
  - Implemented typed `ActivityConfig` supporting:
    - MCQ: `showCorrectAnswer`, `allowRevision`, `resultsVisible`.
    - Poll: `allowVoteRevision` (defaults to true), `resultsVisible`.
  - Implemented `ResultAggregationService`:
    - Aggregates vote counts and percentages for options.
    - Result privacy: participants forbidden from viewing until `resultsVisible: true` or activity is `CLOSED`.
    - Answer key privacy: `isCorrect` hidden from participants unless `showCorrectAnswer: true` or activity is `CLOSED`. Teachers always view answer keys.
  - Implemented `ResponseService` and `ResponseController`:
    - `POST /api/v1/sessions/{sessionId}/activities/{activityId}/responses` (`X-Participant-Token`):
      - Rejects if session inactive or expired.
      - Rejects if activity not active or not in session.
      - Rejects if option does not belong to activity.
      - Rejects cross-session participants.
      - Rejects duplicate vote on MCQ with HTTP 409 Conflict when revision is disabled.
      - Updates existing vote on Poll / revision-enabled MCQ (200 OK with `revised: true`), preventing duplicate vote inflation.
    - `PATCH /api/v1/sessions/{sessionId}/activities/{activityId}/visibility` (`X-Teacher-Token`):
      - Allows teacher to dynamically update `showCorrectAnswer` and `resultsVisible`.
    - `GET /api/v1/sessions/{sessionId}/activities/{activityId}/results`:
      - Allows teachers (`X-Teacher-Token` or `X-Teacher-Key`) and participants (`X-Participant-Token`) to retrieve results with privacy checks enforced.
  - Tests added:
    - `ResponseServiceTest` (9 unit tests): MCQ submission, Poll vote revision, duplicate prevention, visibility toggling, aggregation calculations, and participant privacy.
    - `McqAndPollApiIntegrationTest` (2 comprehensive integration tests): End-to-end multi-student MCQ lifecycle with options & privacy toggles, and Poll lifecycle with vote revisions and accurate percentages.
  - Verified test suite: 36 tests run across all test classes, 0 failures, 0 errors.

- [x] **Phase 5: Word Cloud, Open Questions, Brainstorming, Prioritization, and Random Student Picker**
  - Created Flyway migration `V4__unified_activities_responses.sql`:
    - Nullable `selected_option_id`, added `text_response` and `normalized_text` to `responses`.
    - Created `prioritization_ranks` table with unique constraints `(response_id, option_id)` and `(response_id, rank_order)`.
    - Created `random_picker_selections` table with foreign keys and query indexes.
  - Implemented domain entities and repositories:
    - `PrioritizationRank` and `PrioritizationRankRepository`
    - `RandomPickerSelection` and `RandomPickerSelectionRepository`
  - Implemented end-to-end functionality for all 5 remaining activities:
    - **Word Cloud**: Normalized lowercase frequency aggregation, original casing display preservation, length validation.
    - **Open Question**: Nonblank text answers, length limits, teacher-only display name visibility for student anonymity.
    - **Brainstorming**: Idea text submissions, revision or conflict enforcement, real-time idea broadcasting.
    - **Prioritization**: Strict full-permutation ranking validation, Borda count aggregation ($n$ down to 1 points), tie handling.
    - **Random Student Picker**: Teacher-initiated server-side random selection via `POST .../random-picker/pick`, non-revoked session participant eligibility, optional repeat exclusion with candidate exhaustion handling.

- [x] **Phase 6: Response Consistency, Result Aggregation, Privacy, and Revision Rules**
  - Unified revision policies across all 7 activity types:
    - `allowRevision: false` strictly rejects subsequent submissions with `409 Conflict`.
    - `allowRevision: true` (or `allowVoteRevision: true` for Polls) performs an in-place update of the existing response row, updating `updated_at` without vote or submission count inflation.
  - Unified result aggregation engine in `ResultAggregationService`:
    - Handles all 7 activity types deterministically with zero-division protection and rounding.
    - Enforces result visibility: participants receive `403 Forbidden` if `resultsVisible: false` while activity is active.
    - Enforces answer key privacy: `isCorrect` hidden from participants unless `showCorrectAnswer: true` or activity is `CLOSED`.
    - Open question privacy: Participant display names hidden from fellow students in result DTOs.
    - IDOR and cross-session protection on every operation.

- [x] **Phase 7: WebSocket Authorization, Event Delivery, and Reconnect Recovery**
  - STOMP WebSocket broker configured (`/ws` endpoint with SockJS fallback, `/topic` broker, `/app` application prefix).
  - Secure STOMP `CONNECT` frame authentication via `WebSocketAuthChannelInterceptor`:
    - Validates `X-Teacher-Key`, `X-Teacher-Token`, and `X-Participant-Token`.
    - Creates typed `WebSocketUserPrincipal` and sets principal on session accessor.
    - Rejects unauthenticated or revoked connections with `AccessDeniedException`.
  - Subscription Authorization:
    - Prevents cross-session subscription attempts (`/topic/sessions/{sessionId}/...` checked against authenticated principal's session).
    - Prevents participants from subscribing to teacher-only topic `/topic/sessions/{sessionId}/teacher`.
    - Blocks unauthorized client sends to broker topics.
  - Transaction-Committed Event Delivery:
    - Implemented `WebSocketEventPublisher` and `TransactionalWebSocketEventListener` using Spring's `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)`.
    - Events emitted ONLY after successful database commits. Rolled-back transactions emit zero events.
    - Stable event types: `ACTIVITY_ACTIVATED`, `ACTIVITY_CLOSED`, `RESPONSE_SUBMITTED`, `RESULTS_UPDATED`, `WORD_CLOUD_UPDATED`, `BRAINSTORMING_IDEA_ADDED`, `PRIORITIZATION_UPDATED`, `RANDOM_PICKER_SELECTED`, `RESULT_VISIBILITY_CHANGED`, `SESSION_CLOSED`.
    - Event payloads contain privacy-safe identifiers and state notifications; raw tokens, hashes, and hidden answer keys are never broadcast.

- [x] **Phase 8: Integration Tests, Regression Testing, Documentation, Security Audit, and Final Readiness**
  - **Comprehensive Security & Architecture Audit**:
    - Performed audit across all 7 activity types (`MCQ`, `POLL`, `WORD_CLOUD`, `OPEN_QUESTION`, `RANDOM_PICKER`, `BRAINSTORMING`, `PRIORITIZATION`).
    - Verified cross-session boundaries (participants and teachers strictly isolated to authorized sessions).
    - Hardened `GlobalExceptionHandler`:
      - Added dedicated handlers for `AccessDeniedException` (HTTP 403 Forbidden).
      - Added dedicated handlers for `MissingRequestHeaderException` and `HttpMessageNotReadableException` (HTTP 400 Bad Request).
      - Sanitized fallback `Exception` handler to prevent leaking internal database schema, SQL errors, or stack traces in HTTP 500 responses; logs securely via SLF4J.
    - Hardened QR code generation in `QrCodeService` by clamping dimensions between 50 and 2000 pixels, preventing memory exhaustion (DoS) attacks.
    - Added `SESSION_CLOSED` real-time WebSocket event emission in `SessionService.closeSession` notifying connected participants immediately.
    - Enforced session status checks in `ActivityService.getActiveActivityForParticipant` preventing inactive/expired sessions from serving active activities to participants.
    - Configured production-grade CORS support with configurable `classpulse.cors.allowed-origins` (`${CORS_ALLOWED_ORIGINS:*}`) across both HTTP security filter chain and WebSocket STOMP endpoints.
    - Enabled Spring Boot Actuator `/actuator/health` and `/actuator/info` endpoints for observability and deployment health checks.
  - **Regression & Security Test Suite**:
    - Created `Phase8SecurityAndAuditTest` with 8 comprehensive security and audit tests:
      1. `/actuator/health` endpoint accessibility and UP status.
      2. Malformed JSON payload sanitization and HTTP 400 response format.
      3. Cross-session participant attack rejection (HTTP 403 Forbidden).
      4. Closed session participant rejection and active activity lockdown (HTTP 403 Forbidden).
      5. Dynamically expired session rejection (HTTP 410 Gone) and automatic transition to `EXPIRED`.
      6. Teacher-only authorization enforcement for Random Student Picker (HTTP 401 unauthenticated, HTTP 403 participant token).
      7. QR code dimension clamping resilience against absurd sizes (5 and 100,000 pixels).
      8. IDOR cross-teacher session manipulation prevention (HTTP 403 Forbidden).
  - **Verification Results**:
    - Total tests run: **60 tests** across 10 test suites.
    - Failures: **0**, Errors: **0**, Skipped: **0**.
    - Executable fat JAR packaged successfully: `target/classpulse-backend-0.0.1-SNAPSHOT.jar` (62.5 MB).

---

## 4. Known Limitations & MVP Security Notes
- **Teacher Authentication**: The MVP uses a session-specific token (`X-Teacher-Token`) generated on session creation, with an optional master teacher key (`TEACHER_MASTER_KEY`). For full enterprise production, this can be integrated with OAuth2 / OIDC / Keycloak or email-based teacher accounts.
- **Participant Tokens**: Secure random tokens stored with SHA-256 hashes allow frictionless student joining without email credentials. If a student clears browser storage, they can rejoin under a new display name or use a reconnect token if stored.
- **WebSocket Broker**: Uses Spring's in-memory SimpleBroker (`/topic`, `/queue`). Suitable for single-instance deployments and small-to-medium clusters. For high-throughput multi-node horizontal scaling, configure an external STOMP broker relay (e.g., RabbitMQ or ActiveMQ).
