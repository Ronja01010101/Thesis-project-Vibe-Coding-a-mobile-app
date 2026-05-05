# Claude Usage Log

**Project:** SL Transit Android App (artifact for thesis)
**Article goal:** Document Claude usage through a real project build
**Started:** 2026-05-04
**Model:** Claude Opus 4.7 / Sonnet 4.6
**Collaborators:** 2 people

**How to read this log:**
- Each entry = one exchange (prompt → my response)
- Agreement: did the user accept my suggestion as-is?
- Plan revised: did the plan change after agreement?
- Satisfaction: captured from `[[...]]` at start of next prompt, scale 1–5

---

## Summary Stats (updated periodically)

| Metric | Value |
|--------|-------|
| Total entries | 10 |
| Avg satisfaction | — |
| Sessions | 1 |
| Phases logged | Pre-project setup, Deciding tech stack |
| Token checkpoint | run `/cost` to check |

---

## Phase: Pre-project Setup

---

### Entry 001
- **Date:** 2026-05-04
- **Prompt summary:** How to set effort level and model to auto in Claude Code?
- **My response summary:** Explained `/model` interactive picker for default model, and that `/effort` is a custom command with no built-in "auto" — user would need to stop invoking it or edit the command.
- **Agreement:** Partial — user tried commands themselves, no explicit agreement
- **Plan revised after:** No
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

### Entry 002
- **Date:** 2026-05-04
- **Prompt summary:** Key points a rookie software engineering student needs to know about using Claude Code for a first project?
- **My response summary:** Covered setup steps, key commands (/help, /model, /clear), model selection (Sonnet for daily work, Opus for complex tasks), CLAUDE.md project instructions, and practical beginner tips (be specific, one thing at a time, read what Claude does).
- **Agreement:** Yes — user moved forward with this as foundation
- **Plan revised after:** No
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

### Entry 003
- **Date:** 2026-05-04
- **Prompt summary:** User wants to build an app with live API data, document Claude usage (prompts, satisfaction, tokens per requirement), and have a tracking sheet for analysis and article-writing later.
- **My response summary:** Proposed a two-file system: REQUIREMENTS.md and a session log. Explained the `[[...]]` feedback convention.
- **Agreement:** Modified — user wanted one rolling log file, wanted me to keep it completely (not shared maintenance), and simplified the `[[...]]` format to freeform text
- **Plan revised after:** Yes — switched to single USAGE_LOG.md, I maintain it fully
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

### Entry 004
- **Date:** 2026-05-04
- **Prompt summary:** Should Git setup be step one of the plan? Are there other decisions to make now?
- **My response summary:** Confirmed Git first. Identified three decisions needed upfront: app type (web/desktop), tech stack (Python/JS), and GitHub remote. Drafted a 3-phase plan (Setup → Planning → Build → Wrap up) and flagged .gitignore + .env as critical from day one.
- **Agreement:** Yes — user answered all three questions in next message
- **Plan revised after:** No — plan held, answers came in next prompt
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

## Phase: Deciding Tech Stack

---

### Entry 005
- **Date:** 2026-05-04
- **Prompt summary:** User shared full research background (thematic analysis of SL reddit complaints, erosion of trust theme, misinformation and delays as core problem). Described the artifact: a focused transit-tracking app using live SL data to show bus position and estimated arrival to help commuters decide whether to wait or take another route. Confirmed beginner level, no tech preference, will use GitHub with two collaborators.
- **My response summary:** Confirmed understanding of the DSR thesis context. Recommended React + Vite (web app, mobile-responsive) as beginner-friendly. Identified Trafiklab as the API source (SL Departures v4 + SL Vehicle Positions). Drafted full project plan including GitHub collaboration setup rules. Asked if I should draft CLAUDE.md.
- **Agreement:** Partially — user agreed on GitHub and two-collaborator setup, but tech stack was not finalized (lock screen requirement surfaced next)
- **Plan revised after:** Yes — tech stack changed in subsequent entries
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

### Entry 006
- **Date:** 2026-05-04
- **Prompt summary:** Core feature of the app is to automatically display live bus information on the mobile lock screen — is this possible for a web app, or do we need a native mobile app?
- **My response summary:** Explained the difference between push notifications (web can do) and a true live-updating lock screen widget (native only). Gave a comparison table of PWA vs React Native vs native. Recommended PWA + push notifications as the easiest path, or React Native as a middle ground. Offered Option A (PWA + alerts), B (React Native), C (Mac needed).
- **Agreement:** No — user pushed back, wanted to understand more before deciding
- **Plan revised after:** No — decision deferred
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

### Entry 007
- **Date:** 2026-05-04
- **Prompt summary:** Why not just go with a native app?
- **My response summary:** Identified the hard blocker: user is on Windows, which cannot build native iOS apps (no Xcode). Explained that "native" would therefore mean Android-only. Listed softer reasons (harder languages, Android Studio complexity, distribution costs). Recommended React Native as the realistic middle ground — one codebase, both platforms, buildable on Windows.
- **Agreement:** No — user asked a follow-up about what "alerts" actually means
- **Plan revised after:** No — decision still pending
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

### Entry 008
- **Date:** 2026-05-04
- **Prompt summary:** What does "alerts" mean exactly? The intended feature is a widget showing live bus location on the lock screen, not just a notification. Is that achievable?
- **My response summary:** Clarified that true live-updating lock screen widgets are called Live Activities on iOS (requires Swift + Mac, blocked) and App Widgets on Android (requires Kotlin, buildable on Windows). Confirmed that React Native cannot do iOS Live Activities. Presented three options: A (scope down to push notifications), B (Android-only native), C (get a Mac). Noted this constraint is valid thesis material.
- **Agreement:** Yes — user chose Option B (Android-only native app)
- **Plan revised after:** Yes — full pivot from web/React Native to Android native (Kotlin)
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

### Entry 009
- **Date:** 2026-05-04
- **Prompt summary:** Confirmed: going with Option B, Android-only is acceptable.
- **My response summary:** Updated the full plan to Android native with Kotlin, Android Studio, and Android App Widget for lock screen. Outlined Phase 0 setup steps, noted that API keys go in local.properties (git-ignored by default in Android). Gave immediate next steps: install Android Studio, register on Trafiklab.
- **Agreement:** Yes — plan accepted
- **Plan revised after:** No
- **User feedback:** *(not provided)*
- **Satisfaction:** —

---

### Entry 010
- **Date:** 2026-05-04
- **Prompt summary:** Request to log the full conversation under the label "deciding tech stack", with format showing prompt summary, response summary, agreement status, and whether plan was revised.
- **My response summary:** Backfilled all 10 entries into USAGE_LOG.md with the requested format, grouped into phases (Pre-project Setup, Deciding Tech Stack).
- **Agreement:** *(current entry — pending)*
- **Plan revised after:** —
- **User feedback:** *(pending — start next prompt with [[...]])*
- **Satisfaction:** —

---

*New entries appended below as the project progresses.*
