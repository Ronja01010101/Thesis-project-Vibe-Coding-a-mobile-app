# Project Plan

**Project:** SL Transit Android App
**Thesis context:** Artifact for DSR study on AI-assisted development by a non-programmer
**Article goal:** Document the full Claude-assisted build process
**Started:** 2026-05-04
**Collaborators:** 2 people (both on Windows)

---

## Current Tech Stack

| Decision | Choice | Reason |
|---|---|---|
| Platform | Android native | Lock screen widget required; iOS blocked on Windows |
| Language | Kotlin | Modern Android standard |
| IDE | Android Studio | Free, Windows, includes SDK + emulator |
| Lock screen feature | Android App Widget | Native widget, updates live, works on lock screen |
| Live data | Trafiklab API (SL) | Free, open, covers Stockholm public transport |
| APIs needed | SL Transport + SL Deviations + SL Journey Planner + GTFS Regional Realtime | Departures/delays, deviations, journey planning, live vehicle positions |
| Map library | OSMDroid (OpenStreetMap) | Free, no API key, native Android library |
| Version control | Git + GitHub | Backed up, collaborative, good for article |
| Repo visibility | Public | Good for thesis/article |

---

## Phases

### Phase 0 — Setup
> Goal: everything in place before writing a single line of app code

- [x] Both: install Android Studio (studio.android.com)
- [x] Both: register at trafiklab.se — APIs used:
  - SL Transport, SL Deviations, SL Journey Planner (no key needed)
  - GTFS Regional Realtime (key obtained)
- [x] Person A: create GitHub repo
  - https://github.com/Ronja01010101/Thesis-project-Vibe-Coding-a-mobile-app
- [x] Person A: add Person B as collaborator (Settings → Collaborators)
- [x] Both: clone the repo locally
- [x] Create new Android project in Android Studio (Kotlin, Empty Activity)
- [x] Verify .gitignore is present (Android Studio generates this)
- [x] Add API keys to local.properties — GTFS Realtime key added; SL APIs need no key
- [x] Create CLAUDE.md in repo root
- [x] Move USAGE_LOG.md and PLAN.md into repo
- [x] First commit + push: "project setup"

### Phase 1 — Requirements
> Goal: a clear, prioritized build list before any coding starts

- [x] Share requirements list
- [x] Structure and prioritize requirements together
- [x] Create REQUIREMENTS.md in repo
- [x] Break requirements into buildable chunks with order

### Phase 2 — Build
> Goal: build one requirement at a time, fully logged

- [ ] One requirement at a time
- [ ] Feature branch per requirement
- [ ] Merge to main when requirement is done and tested
- [ ] Usage log updated every exchange

### Phase 3 — Wrap Up
> Goal: reflection and article material

- [ ] Run `/cost` for final token summary
- [ ] Analyze USAGE_LOG.md — patterns, difficult requirements, prompt counts
- [ ] Reflect on process for thesis/article

---

## Collaboration Rules

- One person drives Claude sessions per feature — no parallel editing
- Always `git pull` before starting work
- Branch per requirement → merge to main when done
- Decide who is lead developer per session before starting

---

## Change Log
> All plan changes recorded here so nothing is lost

| Date | Change | Reason |
|---|---|---|
| 2026-05-04 | Initial plan created | First planning session |
| 2026-05-04 | Tech stack changed from React + Vite (web) → React Native | Lock screen widget requirement surfaced |
| 2026-05-04 | Tech stack changed from React Native → Android native (Kotlin) | True lock screen widget requires native; iOS blocked on Windows; Android-only accepted |
| 2026-05-05 | Map library changed from Google Maps SDK → OSMDroid (OpenStreetMap) | Free, no billing setup, no API key needed, native Android library |
| 2026-05-05 | API set finalised: SL Transport + SL Deviations + SL Journey Planner + GTFS Regional Realtime | SL Transport covers departures; GTFS Regional Realtime covers live vehicle positions; Deviations and Journey Planner cover Phase 2 reqs |
| 2026-05-06 | Step 3 (P1-FR2) implemented on branch `step-3-commute-config`, merged to `main` after build verification | Marker tap or stop search opens a bottom sheet; user picks line+direction (loaded from SL departures), start/end time; saved to SharedPreferences with overlap rejection. Runtime test pending — collaborator to execute the smoke-test plan. |
| 2026-05-06 | **Emergency fix:** new step "Step 3.5 — Limit map data" inserted into build order; new NFR8 added | Both collaborators' machines pegged at 100% CPU/RAM and could not start the emulator. Suspected cause: ~14k stop markers being rendered in OSMDroid simultaneously. Fix is viewport-bound rendering with a hard cap, rebuilt on map idle. Branch `step-3-5-limit-map-data`. |
| 2026-05-06 | Step 3.5 implementation finalised and merged to main | Initial bbox-based filter wasn't reliable on a slow emulator (boundingBox returned a degenerate box pre-layout). Replaced with "render 400 stops nearest to map center" — same hard cap, same pan-rebuild behaviour, but no dependence on MapView projection state. Confirmed working in emulator after a network/VPN issue was resolved on the host. Two cosmetic follow-ups parked in BUGS.md (BUG-002 cluster-at-center, BUG-003 oversized marker icon). |
| 2026-05-07 | Step 4 split into 4a + 4b. Step 4a redesigned **before any code was written** from "download GTFS zip on the device + parse with OpenCSV + cache per-route as JSON" to "**pre-process GTFS zip on a developer machine at build time, ship a compact JSON inside `app/src/main/assets/`, app does no GTFS download or parsing**". Two new NFRs added (NFR9 tile-provider policy, NFR10 polling cadence). New Technical Constraint added for GTFS Static dataset size. New BUG-004 logged for the eventual tile-provider switch. | Reasoning chain (recorded in detail in USAGE_LOG entries 041–046 for thesis review): (1) The user committed to real route geometry (not straight stop-to-stop lines) because Step 5 ETA accuracy depends on it. (2) First research pass on Trafiklab GTFS Static recommended OpenCSV + SQLite/Room. I disagreed and proposed a simpler stream-parse + per-route JSON cache. (3) I asked the user three programmer-level design questions; the user (a non-programmer) couldn't evaluate them and asked me to research instead, providing five reference URLs (stranne SL.se spec, sl-map.gunnar.se, trafiklab.se, github.com/trafiklab/trafiklab.se, github.com/fltman/stockholms-puls). (4) Second research pass discovered that `sl-map.gunnar.se` ingests GTFS at build time, not on the device — a clean precedent that eliminates the on-device download (49 MB), the on-device CSV parse (30–90 s on slow hardware), and the SQLite/Room complexity. (5) Two additional findings from the research: raw OSM tile servers are forbidden for distributed apps regardless of commercial intent (NFR9), and Trafiklab Bronze realtime quota requires 10–15 s polling (NFR10). User accepted the redesign and explicitly asked for the decision-change to be logged in detail so a thesis reviewer can trace what triggered the plan change — flagged this as the first significant design reversal since the original tech-stack pivots. |
| 2026-05-07 | Step 4a implemented and merged to main on branch `step-4a-gtfs-extraction`. `extractGtfs` Gradle task working end-to-end. Output: `app/src/main/assets/sl-lines.json` (15 MB, 623 lines, 1166 directions, 21823 stops, 1165 polylines, lat/lon rounded to 5 decimals). | Implementation took four debug iterations (logged in USAGE_LOG entry 047): (a) Maven dependency download failed with SSL cert chain — fixed by adding `-Djavax.net.ssl.trustStoreType=Windows-ROOT` to gradle.properties, parallel to the earlier `git config http.sslBackend schannel` fix. (b) Kotlin .kts compiler crashed on locally-declared data classes inside the task lambda — fixed by hoisting `GtfsRoute` and `GtfsRepTrip` to top-level. (c) Trafiklab returned HTTP 406 — their endpoint requires `Accept-Encoding: gzip` even though the zip body is sent identity-encoded; added the header and a defensive gzip/deflate decoder. (d) First successful run produced a 58.8 MB JSON; reduced to 15 MB by dropping pretty-printing (each polyline coord was on its own indented line) and rounding coordinates to 5 decimals (~1 m accuracy, plenty for transit visualisation). One BUG-005 logged: empty `trip_headsign` on some lines means direction labels will display as empty strings — needs a fallback (e.g. final-stop name) before Step 4b ships. |
| 2026-05-07 | Step 4b implemented and merged to main on branch `step-4b-render-lines`. App now reads `sl-lines.json` at startup and draws each saved commute as a coloured polyline + filled-circle stop dots. Build verified clean; runtime test still pending the collaborator's emulator. | New `SlLine.kt` model + `SlLineRepository.kt` with async load and headsign-based direction matching. CommuteConfig extended with `lineDesignation` + `transportMode` (nullable for backward compat). Bottom sheet broadcasts `commute_saved` via `setFragmentResult` so MainActivity redraws overlays without waiting for onResume. Five-colour palette cycle distinguishes multiple commutes. Step 4b implementation was clean — no debug iterations beyond the initial write. Direction matching falls back through: exact headsign → contains either way → first direction (partial mitigation for BUG-005, full fix still on the BUGS.md backlog). |
| 2026-05-07 | Step 4b runtime-tested on emulator (user testing on own machine now, no longer collaborator-blocked). Initial run hit OOM-related instability — heap climbed to 135 MB peak during JSON parse, sustained 90–119 MB. Hotfix committed (`bac2809`): refactored `SlLineRepository` to stream-parse with Gson's `JsonReader`, keeping only entries matching saved `CommuteConfig.lineDesignation`. With 0 saved configs the asset isn't opened at all. Sustained heap dropped from ~10–15 MB → ~hundreds of KB; emulator-confirmed stable run after fix (heap stable at 5–17 MB). Step 4 (P1-FR3) is fully complete. UI jank during sheet interactions logged as BUG-006 (low priority). | **Methodological observation for thesis:** the build-verify pass alone (Step 4b first commit) wouldn't have caught the OOM — runtime testing on the target hardware was essential. The hotfix iteration cost ~$2–3 of token spend, small relative to the value of catching a feature that would have crashed on real devices. CLAUDE.md updated to expand the session-start reading rule from "USAGE_LOG only" to "PLAN.md, REQUIREMENTS.md, BUGS.md, USAGE_LOG.md (last 5–10 entries)" so future sessions pick up full context, not just the last few exchanges. |
