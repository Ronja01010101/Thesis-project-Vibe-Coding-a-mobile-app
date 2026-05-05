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
| P1-FR6 | Display a mobile lock-screen activity for the active commute | P1-FR4 | Android App Widget | Hard |
| P1-FR7 | Lock-screen activity shows vehicle position, status, expected arrival, last update time | P1-FR6 | Android App Widget | Medium |
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

---

## Technical Constraints

> Known constraints that affect implementation — must be understood before building the relevant steps.

| Constraint | Affects | Detail |
|---|---|---|
| GTFS Realtime uses protobuf (binary format) | Step 5 | Not plain JSON — requires a protobuf parsing library (e.g. `google-protobuf` or GTFS-RT Kotlin bindings). Plan for this in Step 5. |
| App Widget minimum refresh = 30 minutes (standard timer only) | Step 8 | The built-in widget auto-refresh timer is capped at 30 minutes. This does NOT apply when a foreground service pushes updates — use a foreground service to push widget updates every 30–60 seconds during active commute. Foreground service requires a persistent notification while running (Android mandatory). |
| INTERNET permission required | Step 2 onwards | Must be declared in `AndroidManifest.xml` before any API calls will work. |

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

Step 4 — Map visualization (P1-FR3)
  Draw selected line and stops on map

Step 5 — Live data fetching (P1-FR4, DS.Req.7–8)
  Connect to GTFS Regional Realtime, fetch only for selected line/direction/window

Step 6 — Live position on map (P1-FR5, DS.Req.5, P1-FR10)
  Show vehicle on map, mark uncertain positions

Step 7 — Disruptions (P1-FR8, P1-FR9, DS.Req.6)
  Connect SL Deviations API, show warnings and cancellations

Step 8 — Lock screen widget (P1-FR6, P1-FR7)
  Android App Widget + foreground service that polls every 30–60s and pushes updates to the widget during active commute window

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
| Step 3 | Not started | — | |
| Step 4 | Not started | — | |
| Step 5 | Not started | — | |
| Step 6 | Not started | — | |
| Step 7 | Not started | — | |
| Step 8 | Not started | — | |
| Step 9 | Not started | — | |
| Step 10 | Not started | — | |
| Phase 2 | Not started | — | After Phase 1 complete |
