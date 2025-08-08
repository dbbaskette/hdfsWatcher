# Presence and Monitoring Spec for Stream Apps

This document defines how hdfsWatcher, textProc, and embedProc self-register with RagMon and stay visible as they scale.

## Message Schema (INIT/HEARTBEAT/EVENT)

All messages are JSON. The following fields are relevant to presence. Additional fields are allowed.

```
{
  "instanceId": "uuid-or-stable-id",          // REQUIRED, stable per instance lifespan
  "timestamp": "2025-08-08T12:34:56.789Z",    // REQUIRED, ISO-8601
  "event": "INIT" | "HEARTBEAT" | "FILE_PROCESSED", // Optional but recommended
  "status": "PROCESSING" | "DISABLED" | "IDLE" | "ERROR", // REQUIRED
  "hostname": "host[:port]",                  // Recommended
  "publicHostname": "host[:port]",            // Recommended (external)
  "url": "http(s)://host[:port]",            // Optional; if missing, UI derives from hostnames + default port
  "bootEpoch": 1723100000000,                  // RECOMMENDED millis when process started
  "version": "5.0.1+abcd123",                 // RECOMMENDED
  "meta": {                                    // REQUIRED
    "service": "hdfsWatcher" | "textProc" | "embedProc",
    "inputMode": "standalone" | "scdf",
    "bindingState": "running" | "stopped" | "unknown",
    "tags": { "zone": "us-east-1a" }
  },

  // Work metrics (optional, recommended)
  "pendingMessages": 0,
  "processingRate": 123.4,
  "filesProcessed": 10,
  "filename": null
}
```

## Liveness Model

- lastHeartbeatAt is updated on INIT/HEARTBEAT.
- lastActivityAt is updated on ANY message from that instance.
- Classification:
  - Alive: now - lastActivityAt ≤ 30s
  - Degraded: 30s < now - lastActivityAt ≤ 120s
  - Offline: > 120s
  - “HB stale” flag if now - lastHeartbeatAt > 20s

## App Responsibilities

1) On start, send an INIT message with full metadata. Include `instanceId`, `meta.service`, `status`, `hostname`/`publicHostname`, and `bootEpoch`.
2) Send HEARTBEAT every 5–10 seconds (low payload). Continue to send even when idle.
3) All other pipeline messages should include `instanceId` and `meta.service`. These will count as activity for liveness.
4) Optionally send a SHUTDOWN message on graceful exit (same schema with `event: "SHUTDOWN"`).
5) If the app exposes HTTP controls or Actuator, set `url` or ensure `publicHostname` includes host:port.

## RagMon Backend

- Maintains an Instance Registry keyed by (service, instanceId).
- Updates lastActivityAt on any message and lastHeartbeatAt on INIT/HEARTBEAT.
- Derives `url` from `url` or `publicHostname`/`hostname` plus default port (8080) if missing.
- Exposes:
  - GET `/api/instances` → list of instances with status, lastSeen, url, etc.
  - SSE `/instances/stream` → presence updates.
- Proxies control calls if needed: `/api/proxy/{app}/**`.

## Versioning

Include `version` in INIT. Changes to message shape should bump minor/patch; add `schemaVersion` if desired for future migrations.


