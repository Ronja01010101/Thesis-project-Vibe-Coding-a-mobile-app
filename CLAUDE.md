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
- **At the start of every session, read all four planning files in this order to understand current project state:**
  1. **PLAN.md** — overall plan + change log (so you understand what's been decided and why)
  2. **REQUIREMENTS.md** — status tracker for each step (so you know which step is next)
  3. **BUGS.md** — deferred / known bugs (so you don't accidentally re-fix or duplicate)
  4. **USAGE_LOG.md** — read at least the last 5–10 entries (so you know where we left off)
- **Update USAGE_LOG.md after every prompt — never batch.** Each user prompt → my response = one new entry, written into USAGE_LOG.md as part of that turn. Do not defer log updates to "the end of the step" — entries written in bulk lose context and the thesis loses traceability of decision paths. The only exception is purely confirmational responses with no new information ("OK", "thanks").
- **At every step transition, prompt the user to run `/cost` so the cost snapshot for the just-finished step can be logged.** Add the snapshot to USAGE_LOG.md's Token Checkpoints section, attribute it to the step that just finished, and only then mark the step Done in REQUIREMENTS.md.
- **For every manual / runtime test the user performs, log the outcome in the relevant USAGE_LOG entry** with one of: (a) "worked first time", (b) "needed N iterations to fix" — and link to the bugs / commits the iterations produced. This is thesis-relevant data on AI-assisted-build first-pass quality.
- **When a decision involves a blocker, a disagreement, or a user-judgement call** (e.g. "do X now or punt"), log the full decision path in USAGE_LOG.md: what options were considered, the tradeoffs surfaced, what the user picked, and *why*. The thesis reviewer needs to be able to trace WHY each non-obvious decision was made, not just WHAT was done. PLAN.md change log gets a one-line summary; USAGE_LOG carries the detail.
- **Keep BUGS.md as a permanent record. Never delete fixed bugs** — mark them "Fixed YYYY-MM-DD" or "Fixed pending runtime test" so the cumulative bug count and fix history are preserved for the thesis.
- **Update PLAN.md and REQUIREMENTS.md whenever a meaningful change happens** — step-state transitions, requirement additions/changes, new technical constraints, design reversals. Don't wait until end of step.
- **When dealing with live-data APIs (Trafiklab SL Transport, GTFS Realtime, GTFS Static, SL Deviations, SL Journey Planner), always consult the official documentation before guessing about field semantics, value ranges, ID schemes, or behaviour.** Use WebFetch on `https://www.trafiklab.se/api/our-apis/sl/...` or the OpenAPI specs in `github.com/trafiklab/openApi-docs` and `github.com/trafiklab/trafiklab.se`. If the docs are silent on a specific question, say so explicitly to the user before applying a heuristic — don't paper over the gap. Heuristics are acceptable as fallbacks only when the docs confirm "we don't guarantee X". Reason: BUG-009's three-version fix arc (v1 assumed direction_code = GTFS direction_id, v2 added a code-1 mapping heuristic, v3 only became principled after the user told me to actually read the docs — at which point Trafiklab's own staff had already published the correct guidance: reconcile via trip_id and stop_times, not direction).
- **Never write personal information** (email addresses, full real names, work email domains, phone numbers, home/work addresses, local Windows usernames, machine paths beyond the project root, auth tokens, API keys, passwords, or anything that uniquely identifies a person beyond what's already public in the GitHub repo URL) into ANY committed project file — including USAGE_LOG.md, PLAN.md, BUGS.md, REQUIREMENTS.md, CLAUDE.md, code comments, or commit messages. The repo is public. If the user mentions any of these in conversation, summarise without quoting and use generic terms like "user", "collaborator", "their work email" instead.

## Project files
- PLAN.md — living project plan with change log
- REQUIREMENTS.md — requirements list + per-step status tracker
- BUGS.md — non-blocker bugs and follow-ups (open AND fixed); permanent record
- USAGE_LOG.md — full exchange log for thesis analysis (entry per prompt + Token Checkpoints + manual-test outcomes)
