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
| APIs needed | SL Departures v4 + SL Vehicle Positions | Departures for timing, Vehicle Positions for live location |
| Version control | Git + GitHub | Backed up, collaborative, good for article |
| Repo visibility | Public | Good for thesis/article |

---

## Phases

### Phase 0 — Setup
> Goal: everything in place before writing a single line of app code

- [x] Both: install Android Studio (studio.android.com)
- [x] Both: register at trafiklab.se, apply for:
  - SL Departures v4 *(pending repo creation)*
  - SL Vehicle Positions *(pending repo creation)*
- [x] Person A: create GitHub repo
  - https://github.com/Ronja01010101/Thesis-project-Vibe-Coding-a-mobile-app
- [ ] Person A: add Person B as collaborator (Settings → Collaborators)
- [x] Both: clone the repo locally
  - Local path: C:\Users\datan\RonjAs\Thesis-project-Vibe-Coding-a-mobile-app
- [x] Create new Android project in Android Studio (Kotlin, Empty Activity)
- [x] Verify .gitignore is present (Android Studio generates this)
- [ ] Add API keys to local.properties *(waiting for Trafiklab approval)*
- [x] Create CLAUDE.md in repo root
- [x] Move USAGE_LOG.md and PLAN.md into repo
- [ ] First commit + push: "project setup"

### Phase 1 — Requirements
> Goal: a clear, prioritized build list before any coding starts

- [ ] Share requirements list
- [ ] Structure and prioritize requirements together
- [ ] Create REQUIREMENTS.md in repo
- [ ] Break requirements into buildable chunks with order

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
