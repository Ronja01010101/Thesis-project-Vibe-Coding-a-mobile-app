# Requirements

**Scope:** Personal commute Digital Shadow for SL public transport in Stockholm.
**Purpose:** Support a user during a selected daily commute — not display the entire transport system.

---

## APIs Required

| API | Used for | Key needed |
|---|---|---|
| SL Transport | Stops, lines, departures, ETA | No |
| SL Deviations | Disruptions, cancellations | No |
| SL Journey Planner | Route planning (Phase 2) | No |
| GTFS Regional Realtime | Live vehicle GPS positions (protobuf format) | Yes (Trafiklab) |
| GTFS Regional Static | Route geometry (polylines), stop sequences per line, schedule data | Yes (Trafiklab) — used at build time only, not by the device |

## Map Library

| Library | Purpose | Key needed |
|---|---|---|
| OSMDroid (OpenStreetMap) | Map display, route geometry | No |

> Note: Google Maps SDK was considered and rejected in favour of OSMDroid — free, no API key, native Android library.

---

## Phase 1 — Base Requirements

### Functional Requirements

| ID | Requirement | Depends on | API needed | Complexity |
|---|---|---|---|---|
| P1-FR1 | Display a map where the user can view and select public transport stops | — | OSMDroid, SL Transport | Medium |
| P1-FR2 | Allow user to select departure stop, line, direction, and daily commute time window | P1-FR1 | SL Transport | Medium |
| P1-FR3 | Display selected line and relevant stops on the map | P1-FR1, P1-FR2 | OSMDroid, SL Transport | Medium |
| P1-FR4 | Fetch live vehicle-position data only for selected line, direction, and time window | P1-FR2 | GTFS Regional Realtime | Hard |
| P1-FR5 | Display live position of selected vehicle relative to selected stop and route | P1-FR3, P1-FR4 | OSMDroid, GTFS | Hard |
| P1-FR6 | Display an Android AppWidget for the active commute (visible on home screen; on lock screen where the OS provides that surface — Android 16 QPR1+ phones via opt-out, Samsung One UI lockscreen-widgets panel, etc.) | P1-FR4 | Android App Widget | Hard |
| P1-FR7 | AppWidget shows vehicle position on route, ETA to user's stop, on-time/early/late state vs. schedule, deviations | P1-FR6 | Android App Widget, SL Transport, SL Deviations | Medium |
| P1-FR8 | Notify/warn user when vehicle is delayed, cancelled, missing, or has uncertain data | P1-FR4, P1-FR10 | SL Deviations | Medium |
| P1-FR9 | Display disruption or cancellation information from the traffic disruption API | P1-FR2 | SL Deviations | Medium |
| P1-FR10 | Clearly mark live data as uncertain when position is missing, stale, inconsistent, or off-route | P1-FR4 | Logic only | Medium |
| P1-FR11 | User can open the application from the lock-screen activity | P1-FR6 | Android deep link | Easy |

---

## Phase 2 — Journey Planning Extension

| ID | Requirement | Depends on | Notes |
|---|---|---|---|
| P2-FR1 | Select a destination stop in addition to start stop | P1-FR2 | Phase 2 only |
| P2-FR2 | Generate a journey plan from start to destination | P2-FR1 | Phase 2 only |
| P2-FR3 | Evaluate selected journey against live delay, cancellation and disruption data | P2-FR2, P1-FR8 | Phase 2 only |
| P2-FR4 | Suggest an alternative route when journey is delayed, cancelled, or unavailable | P2-FR3 | Phase 2 only |
| P2-FR5 | Compare original and alternative route by departure time, arrival time, transfers, disruption status | P2-FR4 | Phase 2 only |
| P2-FR6 | Proactively notify user when alternative route becomes more suitable | P2-FR5 | Phase 2 only |
| P2-FR7 | Explain why alternative route is suggested | P2-FR4 | Phase 2 only |

---

## Data & Digital Shadow Requirements

> These are architectural requirements that apply throughout the build — not built one at a time, but respected from the first line of code.

| ID | Requirement |
|---|---|
| DS.Req.1 | Define transport object schemas before live data is processed |
| DS.Req.2 | Transport object includes: line ID, direction, stop ID, route geometry, vehicle position, timestamp, data source, disruption status |
| DS.Req.3 | All live-data outputs connected to a timestamp, source, and user context |
| DS.Req.4 | Normalise data from static, live-position, disruption, and map APIs into a shared internal format |
| DS.Req.5 | Flag vehicle position as uncertain if old, missing, outside expected route, or inconsistent with selected commute |
| DS.Req.6 | Validate whether disruption data applies to selected line, stop, direction, or journey before showing to user |
| DS.Req.7 | Only fetch and process data relevant to the active user selection |
| DS.Req.8 | Cache static/frequently used data (stops, lines, route geometry) to reduce API calls |
| DS.Req.9 | Do not present cached live-position data as current live data |
| DS.Req.10 | Support both time-based and event-based updates |

---

## Non-Functional Requirements

| ID | Requirement |
|---|---|
| NFR1 | Provide clear loading, error, or uncertainty state when live data cannot be retrieved |
| NFR2 | Avoid misleading the user when live data is incomplete, outdated, or uncertain |
| NFR3 | Handle API errors, timeouts, missing responses, and rate-limit issues without crashing |
| NFR4 | Separate user configuration, map display, live transport status and disruption info in the interface |
| NFR5 | Store only user settings needed for the commute-monitoring function |
| NFR6 | Log API response status, timestamp, validation result, and update errors during development |
| NFR7 | Modular structure — live-data, map, user settings, disruption, and notification functions developed and tested separately |
| NFR8 | Limit rendered map markers to the current viewport (with a hard cap on count) so the app stays responsive on low-spec hardware and emulators |
| NFR9 | Map tile provider must comply with the OpenStreetMap Foundation's tile usage policy. Default `tile.openstreetmap.org` (used by `TileSourceFactory.MAPNIK`) is for development only — switch to a third-party provider (CartoDB / MapTiler / Mapbox / similar) before any wider distribution. The OSM policy is volume-based, not commercial-status-based, so this applies even to free / non-commercial distribution. |
| NFR10 | Live-data polling cadence must respect Trafiklab API quotas. On the Bronze tier (50 calls/min, 30 000/month for GTFS Realtime), poll at **20-second baseline intervals** during the active commute window. May dial down to 10–15 seconds later if quota and UX both allow. Polling must be gated to (active-commute window) AND (app foregrounded OR widget-tracking active in Step 8) — never run continuously. |

---

## Technical Constraints

> Known constraints that affect implementation — must be understood before building the relevant steps.

| Constraint | Affects | Detail |
|---|---|---|
| GTFS Realtime uses protobuf (binary format) | Step 5 | Not plain JSON — requires a protobuf parsing library (e.g. `google-protobuf` or GTFS-RT Kotlin bindings). Plan for this in Step 5. |
| App Widget minimum refresh = 30 minutes (standard timer only); WorkManager minimum periodic interval = 15 minutes | Step 8a | The built-in widget auto-refresh timer is capped at 30 minutes. WorkManager `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is 900_000 (15 minutes) — enforced, cannot be bypassed. Sub-minute widget refresh is therefore impossible from deferrable background work. Use a **foreground service** that runs the existing `LivePositionTracker` polling loop and calls `AppWidgetManager.updateAppWidget(...)` on each tick. Persistent notification required while service runs (Android mandatory). Gated to active-commute window per NFR10. |
| INTERNET permission required | Step 2 onwards | Must be declared in `AndroidManifest.xml` before any API calls will work. |
| GTFS Regional Static dataset is large (~49 MB compressed, ~300–500 MB unzipped; `stop_times.txt` is 5–15 million rows) | Step 4a | Too large to download and parse on a low-spec emulator. Decision: pre-process on a developer machine (Gradle task or standalone script) that downloads `sl.zip`, extracts only the needed columns from `routes.txt` / `trips.txt` / `shapes.txt` / `stops.txt` / `stop_times.txt`, and writes a compact JSON to `app/src/main/assets/`. The app only reads the small JSON at runtime. Refresh = re-run the extractor and ship a new app version. |
| Widget route line + time-scale gauge must be Canvas-rendered bitmaps | Step 8b | RemoteViews has no polyline-drawing primitive (only basic widget views: TextView, ImageView, Button, ProgressBar, ListView, etc.). Glance has no `Canvas` composable for the same underlying reason. Pattern: render to `android.graphics.Canvas` → `Bitmap` → `RemoteViews.setImageViewBitmap()` per widget refresh. |

---

## Recommended Build Order (Phase 1)

> Build in this sequence to always have something working at each step.

```
Step 1 — Data foundation (DS.Req.1–4, NFR7)
  Define transport object schema and internal data models before any UI

Step 2 — Map + stops (P1-FR1)
  OSMDroid setup, display stops from SL Transport API

Step 3 — User selection (P1-FR2)
  UI: pick stop, line, direction, time window

Step 3.5 — Limit map data (NFR8) — emergency fix
  Viewport-bound marker rendering with a hard cap; rebuilt on map idle.
  Inserted after Step 3 because rendering all ~14k SL stops at once
  killed the emulator on both collaborators' machines.

Step 4 — Map visualization (P1-FR3)
  Split into two sub-steps because route geometry isn't exposed by SL Transport
  and the GTFS Regional Static feed is too large to handle on-device:

  Step 4a — Build-time GTFS extraction
    Gradle task / standalone script that downloads sl.zip from Trafiklab using
    GTFS_STATIC_KEY, extracts the per-line polylines and ordered stop sequences,
    writes a compact JSON file to app/src/main/assets/. Re-run rarely (weekly or
    less). The app does NOT download or parse GTFS itself.

  Step 4b — Render from assets
    App reads the bundled JSON at startup. For each saved commute config, draw
    a coloured polyline + highlighted stop markers on the existing map. All
    saved commutes drawn together; "active" config (lockscreen tracking) handled
    later in Step 8.

Step 5 — Live data fetching (P1-FR4, DS.Req.7–8)
  Connect to GTFS Regional Realtime, fetch only for selected line/direction/window

Step 6 — Live position on map (P1-FR5, DS.Req.5, P1-FR10)
  Show vehicle on map, mark uncertain positions

Step 7 — Disruptions (P1-FR8, P1-FR9, DS.Req.6)
  Connect SL Deviations API, show warnings and cancellations

Step 8a — Foreground service + widget state derivation (P1-FR6, P1-FR7)
  Background service runs LivePositionTracker outside the activity lifecycle during the
  active commute window. Persistent notification (Android-mandatory). New
  WidgetCommuteState derived from (TrackingState.Polling.vehicles + SL Departures
  predictions + Deviations + matched-direction stop sequence). Includes single-vehicle
  selection ("vehicle lock" pattern from the design handoff — pin one trip_id at window
  start so the widget marker doesn't jump as buses cycle through) and busIndex
  interpolation along the polyline. Widget config piggybacks the existing CommuteConfig
  store — no parallel DataStore. One widget instance per saved CommuteConfig (multiple
  widgets for users with multiple commutes).

Step 8b — AppWidget surface (P1-FR6, P1-FR7)
  Classic RemoteViews + XML layout, AppWidgetProvider, manifest declaration. Canvas-
  rendered bitmaps for the route line and the time-scale (delta-vs-schedule) gauge
  per the design handoff. Seven phase states per the handoff state table:
  OnTime / Late / Early / LeaveNow / Deviation / Passed / Dormant. Tech stack chosen
  over Jetpack Glance for lower scaffolding cost (project has no Compose surface today)
  and better-Googled failure modes; aesthetic loss is zero because both frameworks
  bitmap-render the route geometry.

Step 9 — Lock screen → app link (P1-FR11)
  Tap widget opens the app on the map view

Step 10 — Polish (NFR1–6)
  Error states, loading states, uncertainty warnings, logging
```

---

## Status Tracker

| ID | Status | Branch | Notes |
|---|---|---|---|
| Step 1 | Done | step-1-data-models | 7 model files, build verified |
| Step 2 | Done | step-2-map-stops | OSMDroid map + SL stop markers, build verified |
| Step 3 | Done | step-3-commute-config | Bottom sheet for commute config, stop search, overlap check, build verified (runtime test pending — collaborator) |
| Step 3.5 | Done | step-3-5-limit-map-data | Emergency perf fix (NFR8). Render the 400 stops nearest the map center; rebuilt on map idle. Runtime-tested in emulator (2026-05-06). Two follow-ups in BUGS.md: BUG-002 (zoom-out cluster looks weird), BUG-003 (default marker icon too large). |
| Step 4a | Done | step-4a-gtfs-extraction | `extractGtfs` Gradle task downloads sl.zip (~71 MB), extracts 623 lines / 1166 directions / 21823 stops / 1165 polylines, writes 15 MB compact JSON to `app/src/main/assets/sl-lines.json`. App build verified clean. Empty `headsign` on some lines noted as BUG-005. |
| Step 4b | Done (runtime tested 2026-05-07) | step-4b-render-lines | MainActivity stream-reads `sl-lines.json` async, draws a coloured polyline + filled-circle stop markers per saved commute, cycles through 5 colours. CommuteConfig extended with `lineDesignation` + `transportMode` (nullable for back-compat). Bottom sheet broadcasts `commute_saved` via FragmentResultListener so overlays redraw immediately. Direction matching: exact headsign → contains match → first direction (partial mitigation for BUG-005). Hotfix `bac2809` after first runtime test: switched from full-catalog Gson parse (~119 MB sustained heap) to `JsonReader` streaming filtered to saved designations (~5–17 MB sustained, 10× reduction). UI jank during sheet interactions filed as BUG-006. |
| Step 5 | Done (runtime tested 2026-05-07) | step-5-live-data | Live GTFS-RT polling for the active commute. Smoke test, then full implementation: `GtfsRealtimeRepository` (OkHttp + `org.mobilitydata:gtfs-realtime-bindings`), `LivePositionTracker` (active-commute selector + 20s polling coroutine, gated to active window AND foreground), `TrackingState` sealed class, MainActivity wiring with bottom-screen verification overlay + 1s age ticker. Asset regenerated once for tripIds extension, once for BUG-005 final-stop headsign fallback. SlLineRepository changed to `Map<String, List<SlLineEntry>>` for multi-route designation handling, with transport-mode + stop-sequence-aware direction matching (the latter being the documentation-aligned approach Trafiklab recommends — discovered after BUG-009's three-version fix arc). Commute removal UI added (tap "Commutes" button). Two bugs fixed (BUG-005, BUG-009), one deferred (BUG-008 polyline overlap). Runtime confirmed: line 57 → Sofia from Tullgårdsparken correctly tracks 3 buses going direction_id=1 (Tengdahlsgatan-bound, Sofia is intermediate). |
| Step 6 | Done (runtime tested 2026-05-07) | step-6-live-vehicles-on-map | Live vehicles drawn on the map: filled coloured dot per vehicle (matches commute polyline colour), greyed out when `quality=UNCERTAIN`, directional notch + rotation when GTFS-RT bearing is reported. Auto-fit camera fires once per active commute session, fitting the polyline + visible vehicles. `bearing: Float?` added to `VehiclePosition`; `GtfsRealtimeRepository` reads `position.bearing` when present. Polish bundle on the same branch fixed 4 UI bugs: BUG-002 (zoom-out cluster, fixed via `MIN_STOP_ZOOM = 14.0`), BUG-003 (default OSMDroid pin too large, fixed via 6 dp blue-grey dot), BUG-010 NEW (vehicle InfoWindow couldn't close, fixed via `MapEventsOverlay`), BUG-011 NEW (osmdroid built-in zoom controls overlapped live_status, fixed via custom always-visible buttons + 72 dp bottom margin). One observation deferred as BUG-012 (vehicle markers often render `UNCERTAIN` even on fresh polls — likely SL feed timestamp lag vs our 60 s threshold). Runtime confirmed on two distinct commutes (line 57 → Sofia from Tullgårdsparken, then line 57 → Hjorthagen from Mjärdgränd). Three commits on branch: `9d70326` core, `874239a` polish bundle, `93adedd` runtime-confirmation status. |
| Step 7 | Done (runtime tested 2026-05-07) | step-7-deviations | SL Deviations API integrated. New `Deviation` model + `MessageVariant`. New `SlDeviationsRepository` (OkHttp + Gson, ETag-aware conditional GET → 304 = NotModified, 200 = Modified). Server-side filter: `?line=<id>&future=true` (site param dropped after runtime test — see PLAN.md change log entry below). Client-side filter: drop deviations whose `publish.upto` is in the past or whose `publish.from` is after the active commute's next end-time (handles cross-midnight windows). Polling: every 60s (every 3rd vehicle tick at 20s baseline) inside `LivePositionTracker.pollOnce`, gated to active-commute-window AND foreground (NFR10), within docs cap of 1/min. `TrackingState.Polling` extended with `deviations: List<Deviation>` (default empty for back-compat). UI: top warning bar (`deviation_card`, MaterialCardView, amber-yellow background `#FFB300` with deep-orange `(!)` accent `#E64A19`) under the search bar — hidden when none, shows the highest-importance header (Swedish), `+N` count badge if >1, tap-to-expand details (concatenated header+details for all matching deviations). Vehicle markers also get a small `(!)` badge inside the existing 26 dp dot at bottom-right (rotates with the icon when bearing reported — acceptable since the badge meaning doesn't depend on orientation). Build verified clean. **Cross-system ID sanity check: PASSED** — first runtime test confirmed line=57 / line=17 returned 200 OK (no 4xx/5xx), and an unfiltered API check confirmed both lines have entries with `id=57 designation="57"` and `id=17 designation="17"`. SL Transport `line.id` and Deviations `scope.lines[].id` share the SL integer namespace as inferred. |
| Step 8a | Implemented, runtime test pending | step-8a-foreground-service | Two sub-steps. (1) Data path: `DepartureDto` extended with `scheduled` / `expected` / `state` per Trafiklab OpenAPI; `StopRepository.getNextDeparture` (with anti-BUG-009 docstring noting SL `journey.id` ≠ GTFS-RT `trip_id`); `TrackingState.Polling` carries `nextDeparture` + `matchedDirection`; `LivePositionTracker` fetches departures every 60s alongside deviations. New `util.GeoMath` (haversine + project-onto-segment), new `widget.WidgetCommuteState` + `Phase` enum + `WidgetDeviationSummary`, new `widget.WidgetStateDeriver` (pure object — busIndex via consecutive-stops projection, vehicle-lock heuristic, 7-state Phase derivation). MainActivity logs the derived widget state per tick. (2) Foreground service: `service.CommuteTrackingService` runs its own `LivePositionTracker` instance off-Activity; persistent notification (IMPORTANCE_MIN, low priority); writes derived state into singleton `widget.WidgetStateHolder`; self-stops on `NoActiveCommute`. New permissions: FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS. New vector drawable for notification icon. New string resources for channel + notification text. MainActivity starts the service in onResume when there's an active commute window, lets it self-stop on window close. Build verified clean (20s, zero debug iterations). |
| Step 8b | Not started | — | |
| Step 9 | Not started | — | |
| Step 10 | Not started | — | |
| Phase 2 | Not started | — | After Phase 1 complete |
