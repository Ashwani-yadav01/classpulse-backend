# ClassPulse — Interactive Classroom Backend

ClassPulse is a high-performance, teacher-focused interactive classroom platform inspired by Wooclap. Built with Java 21, Spring Boot 4.1.2, PostgreSQL, Flyway, Spring Data JPA, Spring Security, and WebSocket with STOMP messaging.

The platform allows teachers to create classroom sessions lasting 24 hours by default. Students join frictionlessly via a 6-character joining code or QR code and enter a display name without requiring email authentication. Within a session, teachers run multiple activities sequentially: Multiple Choice Questions (MCQ), Polls, Word Cloud, Open Questions, Random Student Picker, Brainstorming, and Prioritization.

---

## 1. Tech Stack & Architecture
- **Language & Runtime**: Java 21 (JDK 21.0.10)
- **Framework**: Spring Boot 4.1.2
- **Persistence & Migrations**: Spring Data JPA / Hibernate, PostgreSQL, Flyway (V1–V4 migrations)
- **Security**: Spring Security with token-based teacher authorization, SHA-256 hashed participant tokens, IDOR protection, and sanitized error responses
- **Real-Time Communication**: WebSocket with STOMP messaging broker (`/topic`, `/queue`) and channel authorization interceptor
- **QR Code Generation**: ZXing 3.5.3 with dimension clamping
- **Observability**: Spring Boot Actuator (`/actuator/health`, `/actuator/info`)

---

## 2. Supported Activity Types
The activity domain foundation supports all 7 activity types:
1. **Multiple Choice Questions (MCQ)**: Configurable multi- or single-choice options, option text validation, answer keys (`isCorrect`), and student answer privacy.
2. **Polls**: Multi-option opinion polls with option validation.
3. **Word Cloud**: Free-form phrase and word submissions with configurable entry limits.
4. **Open Question**: Free-text responses with configurable max length.
5. **Random Student Picker**: Teacher-triggered student selection rounds.
6. **Brainstorming**: Idea collection with per-participant limits.
7. **Prioritization**: Full option ranking.

---

## 3. Activity Lifecycle & Concurrency Protection

Activities transition through a strict state machine:
- `DRAFT`: Initial state upon creation. Options and configuration can be viewed and verified.
- `ACTIVE`: Accepting responses. Only **one** activity can be active per classroom session at any time.
- `CLOSED`: Terminated activity. Cannot be re-activated (`400 Bad Request`).

### Concurrency Protection
To prevent race conditions where two activities in the same session become active simultaneously:
1. **Application Layer**: Pessimistic write locking on the session row (`SELECT ... FOR UPDATE`) inside the transactional service.
2. **Database Layer**: Unique constraint on `active_session_id` (`CONSTRAINT uq_activities_one_active_per_session UNIQUE (active_session_id)`). When active, `active_session_id = session_id`. When draft or closed, `active_session_id = NULL`. Multiple simultaneous activations result in `409 Conflict`.

---

## 4. REST API Contract (`/api/v1`)

### Session Endpoints
- **Create Session**: `POST /api/v1/sessions` (Header: `X-Teacher-Key`)
- **Get Session Details**: `GET /api/v1/sessions/{id}` (Header: `X-Teacher-Token` or `X-Teacher-Key`)
- **Close Session**: `POST /api/v1/sessions/{id}/close` (Header: `X-Teacher-Token`)
- **Get Public Session Info**: `GET /api/v1/sessions/code/{joinCode}`
- **Get QR Code PNG**: `GET /api/v1/sessions/{id}/qr?width=300&height=300`

### Participant Endpoints
- **Join Session**: `POST /api/v1/sessions/join` or `POST /api/v1/sessions/{joinCode}/join`
- **Get My Participant Profile**: `GET /api/v1/participants/me` (Header: `X-Participant-Token`)
- **List Session Participants (Teacher)**: `GET /api/v1/sessions/{sessionId}/participants` (Header: `X-Teacher-Token`)
- **Revoke Participant (Teacher)**: `DELETE /api/v1/sessions/{sessionId}/participants/{participantId}` (Header: `X-Teacher-Token`)

### Activity Foundation Endpoints
- **Create Activity**: `POST /api/v1/sessions/{sessionId}/activities`
  - Header: `X-Teacher-Token: <token>`
  - Request Body:
    ```json
    {
      "type": "MCQ",
      "title": "Which CPU scheduling algorithm is non-preemptive?",
      "options": [
        {"optionText": "First-Come, First-Served (FCFS)", "isCorrect": true},
        {"optionText": "Round Robin (RR)", "isCorrect": false}
      ]
    }
    ```
  - Response: `201 Created` with status `DRAFT`.
- **List Activities (Teacher)**: `GET /api/v1/sessions/{sessionId}/activities`
  - Header: `X-Teacher-Token: <token>`
- **Get Activity (Teacher)**: `GET /api/v1/sessions/{sessionId}/activities/{activityId}`
  - Header: `X-Teacher-Token: <token>`
- **Get Active Activity (Student)**: `GET /api/v1/sessions/{sessionId}/activities/active`
  - Header: `X-Participant-Token: <token>`
  - Returns `200 OK` with active activity (`isCorrect` stripped from options for privacy) or `204 No Content` if no activity is active.
- **Activate Activity (Teacher)**: `POST /api/v1/sessions/{sessionId}/activities/{activityId}/activate`
  - Header: `X-Teacher-Token: <token>`
  - Returns `200 OK` with status `ACTIVE`. If another activity is already active, returns `409 Conflict`.
- **Close Activity (Teacher)**: `POST /api/v1/sessions/{sessionId}/activities/{activityId}/close`
  - Header: `X-Teacher-Token: <token>`
  - Returns `200 OK` with status `CLOSED`.

### Activity Response Endpoints (All 7 Activities)
- **Submit Response (Participant)**: `POST /api/v1/sessions/{sessionId}/activities/{activityId}/responses`
  - Header: `X-Participant-Token: <token>`
  - Request Body (MCQ / Poll):
    ```json
    { "selectedOptionId": "f04128f6-b184-4824-8fba-22d71ee3ee85" }
    ```
  - Request Body (Word Cloud / Open Question / Brainstorming):
    ```json
    { "textResponse": "Distributed caching with Redis" }
    ```
  - Request Body (Prioritization):
    ```json
    {
      "orderedOptionIds": [
        "f04128f6-b184-4824-8fba-22d71ee3ee85",
        "e36e8b45-e0cb-467f-94ad-7389a9be87a0",
        "a91f3a21-7b12-42da-8971-11823ab91223"
      ]
    }
    ```
  - Response:
    - `201 Created` on first submission (`revised: false`).
    - `200 OK` when revising an existing response (`revised: true`).
    - `409 Conflict` if duplicate submission when revision is disabled.
    - `400 Bad Request` if activity is not active, text is blank or exceeds limits, or prioritization ranking is incomplete.
    - `403 Forbidden` if participant belongs to a different session.
- **Pick Random Student (Teacher)**: `POST /api/v1/sessions/{sessionId}/activities/{activityId}/random-picker/pick`
  - Header: `X-Teacher-Token: <token>`
  - Response: `200 OK` with `RandomPickerResponse`:
    ```json
    {
      "selectionId": "50c40e11-66c5-4ad9-a797-e8ef81977e77",
      "activityId": "d049622c-7b43-41bb-ad68-08573ec6060c",
      "participantId": "38a4ecaa-648b-494b-bf50-fc895f590656",
      "displayName": "Alice",
      "pickedAt": "2026-10-25T14:30:00Z",
      "totalEligible": 25,
      "totalPickedSoFar": 3
    }
    ```
  - Respects `excludePreviouslyPicked: true` configuration, excluding already selected participants and returning `409 Conflict` when the eligible participant pool is exhausted.
- **Update Activity Visibility (Teacher)**: `PATCH /api/v1/sessions/{sessionId}/activities/{activityId}/visibility`
  - Header: `X-Teacher-Token: <token>`
  - Request Body:
    ```json
    {
      "resultsVisible": true,
      "showCorrectAnswer": true
    }
    ```
  - Response: `200 OK` with updated `ActivityResponse`.
- **Get Activity Results**: `GET /api/v1/sessions/{sessionId}/activities/{activityId}/results`
  - Header: `X-Teacher-Token: <token>` OR `X-Participant-Token: <token>`
  - Response: `200 OK` with `ActivityResultResponse` containing activity-specific aggregate payloads:
    - `options`: option counts and percentages for MCQ / Poll; Borda count scores and ranks for Prioritization.
    - `wordCloud`: normalized word frequencies with preserved display casing for Word Cloud.
    - `textAnswers`: text responses for Open Questions (student display names masked for participant viewers; visible to teachers).
    - `ideas`: list of submitted ideas for Brainstorming.
    - `randomPicker`: current selection and selection history for Random Student Picker.
  - Privacy Behavior:
    - If `resultsVisible` is false and activity is ACTIVE, participants receive `403 Forbidden`.
    - If `resultsVisible` is true (or activity is CLOSED), participants can view aggregate results.
    - `isCorrect` remains hidden from participants unless `showCorrectAnswer` is true or activity is CLOSED. Teachers always see `isCorrect`.

---

## 5. Real-Time WebSocket / STOMP Updates

ClassPulse provides real-time updates via WebSocket with STOMP messaging.

### Connection & Handshake
- **Endpoint**: `/ws` (plain WebSocket or SockJS fallback)
- **STOMP CONNECT Headers**:
  - Teacher: `X-Teacher-Token: <token>` or `X-Teacher-Key: <key>`
  - Participant: `X-Participant-Token: <token>`
- Connection is rejected with `AccessDeniedException` if credentials are missing, invalid, or if the participant token has been revoked.

### Destination Conventions & Authorization
- **Participant & Teacher Shared Topic**: `/topic/sessions/{sessionId}/activities/{activityId}`
  - Subscriber must be an authenticated teacher of `{sessionId}` or an authenticated participant belonging to `{sessionId}`.
  - Cross-session subscriptions are strictly forbidden.
- **Teacher-Only Topic**: `/topic/sessions/{sessionId}/teacher`
  - Only authenticated teachers and master teachers can subscribe. Participants attempting to subscribe receive `AccessDeniedException`.
- **Session-Level Topic**: `/topic/sessions/{sessionId}`
  - Receives session lifecycle events (e.g. `ACTIVITY_ACTIVATED`, `ACTIVITY_CLOSED`, `SESSION_CLOSED`).

### Transactional Event Reliability
Events are published **only after the underlying database transaction successfully commits** (`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`). Rolled-back transactions never emit events.

### Event Catalog
- `ACTIVITY_ACTIVATED`: Emitted when teacher activates an activity.
- `ACTIVITY_CLOSED`: Emitted when teacher closes an activity.
- `RESPONSE_SUBMITTED`: Emitted upon new response submission or revision.
- `RESULTS_UPDATED`: Emitted when votes or text answers are committed.
- `WORD_CLOUD_UPDATED`: Emitted when Word Cloud submissions occur.
- `BRAINSTORMING_IDEA_ADDED`: Emitted when a new Brainstorming idea is saved.
- `PRIORITIZATION_UPDATED`: Emitted when a participant ranks items.
- `RANDOM_PICKER_SELECTED`: Emitted when a student is selected by Random Student Picker.
- `RESULT_VISIBILITY_CHANGED`: Emitted when teacher updates results visibility or answer key settings.
- `SESSION_CLOSED`: Emitted when teacher closes the session.

### Reconnect Recovery
If a client experiences network disruption or disconnects:
1. Re-establish STOMP connection with stored token.
2. Re-subscribe to the session and active activity topics.
3. Call the authorized REST endpoint `GET /api/v1/sessions/{sessionId}/activities/active` and `GET .../results` to restore the authoritative committed state.

---

## 6. Environment Variables & Production Configuration

Configure the application via environment variables or `.env`:

| Variable | Description | Default |
|---|---|---|
| `SERVER_PORT` | HTTP server port | `8080` |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://localhost:5432/classpulse` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | `postgres` |
| `TEACHER_MASTER_KEY` | Secret key for session creation and administrative overrides | `dev-teacher-secret-key-change-in-production` |
| `FRONTEND_JOIN_URL` | Base URL used for participant QR code links | `http://localhost:3000/join` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed frontend origins | `*` (set to `https://app.classpulse.com` in prod) |

---

## 7. Running, Testing & Deployment

### Run All Tests
```bash
mvn test
```
All 60 tests execute against an isolated in-memory PostgreSQL-compatible database without requiring external Docker services.

### Build Production Fat JAR
```bash
mvn package
```
Generates self-contained executable JAR at `target/classpulse-backend-0.0.1-SNAPSHOT.jar`.

### Run via Executable JAR
```bash
java -jar target/classpulse-backend-0.0.1-SNAPSHOT.jar
```

### Run via Docker Compose
```bash
# Start PostgreSQL database service
docker compose up -d postgres

# Start full stack (PostgreSQL + ClassPulse Backend)
docker compose up -d --build
```

---

## 8. Health & Observability (Actuator)
- **Health Check**: `GET /actuator/health` -> returns `{"status":"UP"}`
- **Application Info**: `GET /actuator/info`

---

## 9. Frontend Integration Quickstart

### Teacher Workflow:
1. **Create Session**: `POST /api/v1/sessions` with `X-Teacher-Key: <TEACHER_MASTER_KEY>`. Store the returned `id` (`sessionId`) and `teacherToken`.
2. **Create Activity**: `POST /api/v1/sessions/{sessionId}/activities` with `X-Teacher-Token: <teacherToken>`.
3. **Activate Activity**: `POST /api/v1/sessions/{sessionId}/activities/{activityId}/activate` with `X-Teacher-Token: <teacherToken>`.
4. **Listen to Real-Time Updates**:
   - Connect STOMP to `/ws` with header `X-Teacher-Token: <teacherToken>`.
   - Subscribe to `/topic/sessions/{sessionId}` (session events) and `/topic/sessions/{sessionId}/activities/{activityId}` (activity responses).
5. **Manage Activity**:
   - Toggle visibility: `PATCH /api/v1/sessions/{sessionId}/activities/{activityId}/visibility`.
   - Close activity: `POST /api/v1/sessions/{sessionId}/activities/{activityId}/close`.
   - Close session: `POST /api/v1/sessions/{sessionId}/close`.

### Student / Participant Workflow:
1. **Join Session**: `POST /api/v1/sessions/join` with `{"joinCode": "CODE", "displayName": "Display Name"}`. Store `participantToken`.
2. **Check Active Activity**: `GET /api/v1/sessions/{sessionId}/activities/active` with `X-Participant-Token: <participantToken>`.
3. **Listen to Real-Time Updates**:
   - Connect STOMP to `/ws` with header `X-Participant-Token: <participantToken>`.
   - Subscribe to `/topic/sessions/{sessionId}` and `/topic/sessions/{sessionId}/activities/{activityId}`.
4. **Submit Response**: `POST /api/v1/sessions/{sessionId}/activities/{activityId}/responses` with `X-Participant-Token: <participantToken>`.
5. **Fetch Results**: `GET /api/v1/sessions/{sessionId}/activities/{activityId}/results` with `X-Participant-Token: <participantToken>`.
