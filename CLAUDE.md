# CLAUDE.md — Project Instructions

## What this project is
A native Android app (Kotlin) that shows live SL bus/transit positions and estimated arrival times to help Stockholm commuters decide whether to wait, leave later, or take another route.

This is a DSR thesis artifact. The development process is being documented for an article about AI-assisted development by non-programmers.

## Tech stack
- Language: Kotlin
- Platform: Android (native)
- IDE: Android Studio
- Live data: Trafiklab API (SL Departures v4 + SL Vehicle Positions)
- Lock screen feature: Android App Widget
- Version control: Git + GitHub

## Collaborators
- Two people, both on Windows
- One person drives Claude per feature — no parallel editing
- Branch per requirement, merge to main when done

## Rules for Claude
- Always use Kotlin, never Java
- Keep code beginner-readable — we are non-programmers
- One requirement at a time
- API keys are stored in local.properties — never hardcode them
- When starting a session, read USAGE_LOG.md to understand where we left off
- Update USAGE_LOG.md after every response

## Project files
- PLAN.md — living project plan with change log
- USAGE_LOG.md — full exchange log for thesis analysis
- REQUIREMENTS.md — requirements list (added in Phase 1)
