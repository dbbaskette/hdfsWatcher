## HDFS Watcher API-Only Transition Plan

Owner: hdfsWatcher
Status: Active (to be updated as steps complete)

Goals
- Remove embedded web UI; expose API-only service
- Provide consistent REST APIs to control processing and inspect files
- Add health and minimal metrics via Actuator for external monitoring

Non-Goals
- Persist processed-state across restarts (explicitly not required)
- Add authentication/authorization at this time

High-level Breaking Changes
- Remove Thymeleaf UI and all browser pages
- Remove file download endpoint
- Consolidate and standardize API responses and paths

API Surface (target state)
- Processing control
  - POST `/api/processing/start` → enable processing and immediately process pending files
  - POST `/api/processing/stop` → disable processing
  - POST `/api/processing/toggle` → toggle processing state (kept for convenience)
  - GET `/api/processing-state` → current state { enabled|disabled }
- File management
  - GET `/api/files` → list files with required metadata
    - Item fields: `name` (string), `size` (long), `type` (string: file|dir|other), `state` (string: processed|pending), `url` (string; processing URL)
  - POST `/api/files/upload` (multipart/form-data; field: `file`) → upload a file (pseudoop or WebHDFS)
  - POST `/api/reprocess-all` → stop processing, clear all processed flags, return counts
  - POST `/api/reprocess` → reprocess selected by hash (kept)
  - POST `/api/clear` → clear processed flags (will be superseded by `/api/reprocess-all` semantics)
- Status
  - GET `/api/status` → detailed app status (kept for now)
- Health & Metrics via Actuator
  - `/actuator/health` → includes HDFS and Rabbit health (see Phase 3)
  - `/actuator/metrics` → includes processing state and last poll timestamp

Phases and Steps

Phase 0 — Baseline and alignment [Planned]
- Review versions (see `versions.txt`) and confirm Java/Spring Boot alignment
- Define final endpoint names and response schema (captured above)

Phase 1 — Remove Web UI and reshape endpoints [Planned]
1. Remove UI dependencies and assets
   - Remove `spring-boot-starter-thymeleaf` from `pom.xml`
   - Delete `src/main/resources/templates/` and `src/main/resources/static/`
2. Migrate upload endpoint to API path
   - Add `POST /api/files/upload` (multipart) in controller
   - Remove `GET "/"`, `POST "/"` (UI page and upload), and `GET "/files/{filename}"` (download)
3. Ensure existing processing endpoints remain reachable under `/api/processing/*`
4. Update `README.md` to reflect API-only usage; remove UI references

Acceptance
- Build succeeds; app starts without Thymeleaf
- `POST /api/files/upload` works in pseudoop and HDFS modes
- No UI routes are served

Phase 2 — API consistency and reprocess-all [Completed]
1. Normalize file listing payload across modes
   - Implement `GET /api/files` to return items with: `name`, `size`, `type`, `state`, `url`
   - Pseudoop mode: compute actual `size`, derive `type`, build `url` as processing URL
   - HDFS mode: use `WebHdfsService` details; `state` from processed-hash set
2. Implement `POST /api/reprocess-all`
   - Behavior: stop processing, clear all processed flags; return cleared count and state
   - Note: supersedes `/api/clear`; keep `/api/clear` for now but document `reprocess-all` as preferred
3. Ensure consistent response envelopes: `{ status, message?, timestamp, ... }`

Acceptance
- `GET /api/files` returns unified schema in both modes
- `POST /api/reprocess-all` stops processing and clears flags atomically

Phase 3 — Actuator health and minimal metrics [Completed]
1. Add Actuator
   - Add `spring-boot-starter-actuator`
   - Expose endpoints: `management.endpoints.web.exposure.include=health,info,metrics`
2. Health indicators
   - HDFS: If not pseudoop, perform a lightweight WebHDFS LISTSTATUS to confirm connectivity; otherwise report UP with `mode=pseudoop`
   - Rabbit (cloud mode): Prefer binder health from Spring Cloud Stream; if unavailable, provide a custom indicator that validates binder presence and reports status
3. Metrics (Micrometer)
   - `hdfswatcher.processing.enabled` (gauge: 0/1)
   - `hdfswatcher.last.poll.timestamp` (gauge: epoch millis)

Acceptance
- `/actuator/health` shows `webHdfsService` and `hdfsWatcherOutput` health details
- `/actuator/metrics/hdfswatcher.processing.enabled` and `/actuator/metrics/hdfswatcher.last.poll.timestamp` available

Phase 4 — Hardening and docs [Planned]
1. Documentation updates per hierarchy
   - `implementation_details.md`: API-only changes; endpoint shapes
   - `quick_reference.md`: curl examples for each endpoint
   - `gotchas.md`: pseudoop URL semantics; no persistence; breaking changes
   - `mental_model.md`: architecture shift to headless API + external management UI
2. Optional: Add basic tests around controller responses

Acceptance
- Docs reflect final behavior; examples verified

Notes & Impacts
- Breaking changes: UI removed; root routes removed; downloads removed
- No persistence of processed-state by design; restarts reset state
- Upload remains available via API only
- Reprocess-all implements: stop-processing-before-clear > clear all flags > return success

Work Log (update as completed)
- [ ] Phase 0 completed
- [x] Phase 1 completed
- [x] Phase 2 completed
- [x] Phase 3 completed
- [ ] Phase 4 completed


