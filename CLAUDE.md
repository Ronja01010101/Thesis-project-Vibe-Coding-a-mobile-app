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
- **Never write personal information** (email addresses, full real names, work email domains, phone numbers, home/work addresses, local Windows usernames, machine paths beyond the project root, auth tokens, API keys, passwords, or anything that uniquely identifies a person beyond what's already public in the GitHub repo URL) into ANY committed project file — including USAGE_LOG.md, PLAN.md, BUGS.md, REQUIREMENTS.md, CLAUDE.md, code comments, or commit messages. The repo is public. If the user mentions any of these in conversation, summarise without quoting and use generic terms like "user", "collaborator", "their work email" instead.

## Project files
- PLAN.md — living project plan with change log
- USAGE_LOG.md — full exchange log for thesis analysis
- REQUIREMENTS.md — requirements list (added in Phase 1)
