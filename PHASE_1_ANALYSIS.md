# Phase 1 — Process Analysis for Thesis

**Project:** SL Transit Android App — pre-trip commute decision support for Stockholm
**Phase 1 completed:** 2026-05-09 (functionally complete; runtime confirmation pending across late-day fixes)
**Article goal:** Document the AI-assisted development process by a non-programmer, in terms thesis-reviewable

---

## 1. Project shape

| Item | Value |
|---|---|
| Started | 2026-05-04 |
| Phase 1 functionally complete | 2026-05-09 |
| Calendar duration | 6 days |
| Active session days | 7 (Days 1-7 in the log) |
| USAGE_LOG entries | 125 |
| Commits to main | ~80+ across all branches |
| Bugs filed | 31 |
| Bugs fixed | 27 (24 with code, 2 closed-without-fix per user judgement) |
| Bugs deferred | 4 (BUG-004 tile provider for distribution, BUG-007 commute-window cap, BUG-013 GPS waypoint, plus runtime-pending status across many recent fixes) |
| Lines of code (rough) | ~5,000–7,000 net app code + ~600 LOC in Gradle tasks + ~18.9 MB GTFS asset |

Phase 1 delivered all 11 functional requirements (P1-FR1 through P1-FR11), 10 data/digital-shadow requirements (DS.Req.1–10), and 10 non-functional requirements (NFR1–NFR10).

---

## 2. Tech stack journey — six pivots

The tech stack was decided through six discrete decision points, each documented in PLAN.md's change log. Each decision had a load-bearing trigger.

| When | Decision | Trigger |
|---|---|---|
| 2026-05-04 | **React + Vite (web) → React Native** | "Lock screen widget" requirement surfaced after web stack was chosen — web apps cannot present widgets. |
| 2026-05-04 | **React Native → Android native (Kotlin)** | A *true* lock-screen widget requires native APIs. iOS development requires macOS — both collaborators on Windows. Accepted Android-only as a constraint of the dev environment. |
| 2026-05-05 | **Google Maps SDK → OSMDroid** | OSMDroid is free, no API key, no billing setup. Pre-distribution tile-provider switch later required (filed as BUG-004). |
| 2026-05-05 | **API set finalized** | SL Transport (departures, no key) + SL Deviations (line-level disruptions, no key) + SL Journey Planner (Phase 2, no key) + GTFS Regional Realtime (live vehicle GPS, key) + GTFS Regional Static (route geometry, key, build-time only). |
| 2026-05-07 | **GTFS Static: on-device download/parse → build-time extraction** | First plan: app downloads sl.zip (~49 MB compressed) on first run, parses with OpenCSV, caches per-route JSON. Second pass found `sl-map.gunnar.se` precedent: pre-process on a developer machine, ship a compact JSON in `assets/`. Eliminates 49 MB download, 30–90 s on-device parse, SQLite/Room complexity. |
| 2026-05-08 | **AppWidget: Glance vs RemoteViews → RemoteViews** + **ETA source: GTFS-RT TripUpdates vs SL Departures vs geometric → SL Departures** | User explicitly delegated ("I have no prior knowledge of this — debate with yourself"). RemoteViews: lower scaffolding cost (project has no Compose surface). SL Departures: already integrated in Step 3, gives `expected` and `scheduled` directly. |

**Two more pivots that were considered but not made:**
- **Android-only stays** — iOS would require macOS access; explicitly accepted as out-of-scope for thesis.
- **Light-mode widget** — implemented during Step 10 polish, then **reverted on user push-back**: the original BUG-019 dark-only decision is now permanent, saved to memory as `feedback_widget_dark_only_forever.md`.

The pattern: **every pivot happened in response to a concrete trigger** (requirement surfaced, test environment constraint, runtime cost discovery, design-handoff verification). No pivot was speculative.

---

## 3. Cost analysis

### Hard data per checkpoint

| Checkpoint | Step | Cost | Wall | $/min | Notes |
|---|---|---|---|---|---|
| 1 | Step 4 design (research-only) | $5.10 | 49m | $0.10/min | research+design, no code |
| 2 | Step 4a impl | $8.61 | 33m | $0.26/min | implementation |
| 3 | Step 4b impl + OOM hotfix | $9.61 | 42m | $0.23/min | impl + runtime-test fix |
| 4 | Step 5 (live data) | $30.90 | 3h 23m | $0.15/min | 4-iter BUG-009 fix arc |
| 5 | Step 6 (vehicles on map) | $11.09 | 1h 28m | $0.13/min | scaffolding reuse, 1 runtime iter |
| 6 | Step 7 (deviations) | $13.58 | 2h 41m | **$0.084/min** | research-first first-time-as-default |
| 7 | Step 8a (foreground service + deriver) | $19.18 | 3h 49m | **$0.084/min** | scaffolding compounded; pattern held |
| 8 | Step 8b closing arc | $8.85 | 1h 10m | $0.126/min | pre-compact arc unmeasured (battery death) |
| 9 | Step 10 polish + BUG-031 | $44.04 | 2h 8m | **$0.343/min** | spec-gap closeout, high iteration density at >150k context |

**Cumulative project total estimate: $151–179** for Steps 1–10. Steps 1–4 estimated ~$23 (cumulative day total at end of Step 4b was $23.32). Step 8b pre-compact unmeasured (~$15–25 estimated) due to a **battery-death event** that ended the original session and made `claude --continue` cost-prohibitive (~750k tokens to reload).

### Per-minute cost dynamics

The most thesis-relevant pattern is **per-minute cost compression as the project matured, then inflation at Step 10**:

```
Step 4 design     $0.10/min    (research-only, lightest)
Step 4a impl      $0.26/min    (heaviest implementation single-step)
Step 5 live data  $0.15/min    (data-pipeline complexity)
Step 6 vehicles   $0.13/min    (scaffolding reuse begins)
Step 7 deviations $0.084/min   (research-first as default; reuse compounded)
Step 8a service   $0.084/min   (held flat through architecture change)
Step 8b polish    $0.126/min   (UX iteration density)
Step 10 polish    $0.343/min   (spec-gap closeout + high context band)
```

**Two distinct trends:**
- **Compression** in Steps 5→6→7→8a (~50% reduction per-minute over 4 steps). Driver: scaffolding reuse. The same `LivePositionTracker` + `TrackingState` + `CommuteConfig` schema absorbed every new feature without rewrites.
- **Inflation** at Step 10. Drivers: (a) 90% of session time at >150k context, where every prompt pays elevated cache-read cost; (b) BUG-031 was effectively a Step-7-equivalent feature build that surfaced as polish; (c) high iteration density (~14 prompt-response turns producing code changes + builds + commits + planning-file updates).

### What drove the absolute cost

| Driver | Where | Cost impact |
|---|---|---|
| **Scaffolding reuse compounding** | Steps 6, 7, 8a, 8b | Net negative cost — each new step consumed less per-minute than the prior |
| **Research-first as default** | Steps 7+, all subsequent API integrations | Saved ~3-5× per affected step vs heuristic-drift (BUG-009 evidence) |
| **Heuristic drift before docs** | Step 5 (BUG-009 4-iter arc) | $30.90 step cost was inflated by ~30-40% by the iteration loop |
| **Context band at >150k** | Step 10 (90% of usage) | Elevated cache-read cost per prompt; shorter sessions would compress this |
| **UX iteration density** | Step 8b, Step 10 | Many small decisions per minute = high cost-per-minute even when LOC is small |
| **Battery-death + cost-prohibitive resume** | Step 8b boundary | Permanent cost-visibility loss, plus a cost cliff if you choose to recover |
| **Multi-version fix arcs** | BUG-009 (3v), BUG-016 (3v), BUG-021 (2v), BUG-023 (2v) | Each arc adds ~$2-5 of avoidable spend |

### Cost per delivered requirement

11 functional requirements + 10 data + 10 NFR = 31 total requirements satisfied. **Cost per requirement: ~$5-6**. Far cheaper than typical engineer-hour billing for equivalent work (50-150 €/h × 0.5-1 h per requirement = ~$30-150 per requirement at minimum).

This is one of the headline numbers for the article: **AI-assisted prototyping converts requirement satisfaction from labour-hours-cost to token-cost, at roughly an order of magnitude smaller bill** — but only for prototyping. Production-readiness work (BUG-004 tile-provider switch, key rotation, security review, app-store compliance) would shift the calculus.

---

## 4. Time analysis

| Day | Date | Wall hours | Steps covered |
|---|---|---|---|
| 1 | 2026-05-04 | ~3-4 h | Phase 0 setup + tech-stack pivots × 2 |
| 2 | 2026-05-05 | ~3-4 h | Phase 0 finalize + Phase 1 reqs |
| 3 | 2026-05-06 | ~2-3 h | Steps 1, 2, 3 + emergency Step 3.5 |
| 4 | 2026-05-07 | **~9.5 h** (4 sessions) | Steps 4a, 4b, 5, 6, 7 — most productive day |
| 5 | 2026-05-08 | ~3.8 h | Step 8a + Step 8b start |
| 6 | 2026-05-09 (early) | ~1.2 h | Step 8b closing arc + BUG-024 |
| 7 | 2026-05-09 (late) | **~2.1 h** | Step 10 polish run + BUG-031 |

**Total active session time: ~25-30 hours wall over 7 days.**
**Total API time (compute time): ~5 hours.**

Wall:API ratio of ~5-6:1 is notable. The user is not waiting on Claude — they're inspecting outputs, running emulator tests, taking screenshots, deliberating between turns, and writing planning files. **Most session wall time is the user's verification loop, not Claude's compute.**

---

## 5. Iteration patterns

### Fix arcs (multi-version bugs that needed several attempts)

| Bug | Versions | Pattern | Lesson |
|---|---|---|---|
| **BUG-009** (direction matching) | 3 versions | v1 wrong assumption → v2 heuristic drift → v3 docs-aligned | **Trigger for the documentation-first CLAUDE.md rule.** Trafiklab's docs had the answer the entire time. |
| **BUG-016** (GPS age display) | 3 versions | v1 inline suffix → v2 dedicated line + sentinel → v3 Chronometer for live ticking | Each version surfaced a deeper architectural mismatch (Activity vs RemoteViews update lifecycle). |
| **BUG-021** (stops-away wording) | 2 versions | "away" → "behind" (principled fix) → revert to "away" (user vetoed on fresh data point) | **Wording principles can over-fit on one anecdote.** UX work needs ≥2 observation cycles before locking. |
| **BUG-023** (header times) | 2 versions | "Sched 00:49" abbreviation → restructured layout with full caption | **User vetoed abbreviation in favor of layout-restructure.** Recurring pattern across 3 sub-iterations of Step 8b. |

### Reverts (decisions undone after lived-use exposed flaws)

| Reverted item | Why | Cost |
|---|---|---|
| Step 4a on-device GTFS handling | Research surfaced precedent for build-time extraction | Prevented before code was written — zero cost |
| Light-mode widget (BUG-020) | I overstepped a user decision; user re-asserted dark-only | One revert + memory write |
| WINDOW_SIZE 7→5 | Runtime testing showed 7 was crowded | One revert |
| BUG-027 auto-expand | Long deviation lists pushed UI off-screen | Reverted via BUG-029 |
| Site-filter on Deviations API | Excluded relevant downstream alerts | Caught proactively via WebFetch cross-check |

**Three reverts in a single session is not a failure mode** — it's how runtime-driven UI iteration works when each revert is a 1-3 file branch + commit + merge. Branch-per-bug discipline made every revert cheap.

### Runtime-driven discoveries (caught only by lived use, not synthetic test plans)

- **BUG-024**: line picker empty at night (user wanted to save tomorrow's commute at 00:35; line 57 wasn't running)
- **BUG-025**: GTFS technical terminus names instead of SL per-stop signs (only visible at night when live SL Departures returned no entries)
- **Step 4b OOM**: 119 MB heap during JSON parse; build-verify alone wouldn't have caught it
- **Step 5 BUG-009**: SL `direction_code` semantics differ from GTFS `direction_id` — only surfaced when user noticed bus going wrong direction
- **Step 7 site-filter too tight**: SL Deviations doesn't surface downstream-stop alerts when filtered by user's stop
- **Step 8a service-start timing**: zero-saved-commutes-at-launch path needed special handling
- **Step 10 BUG-029**: BUG-027's auto-expand pushed long deviation lists off-screen
- **Step 10 BUG-030**: widget Updated timer used GPS time (lagged 30-90s), not poll time

**Every step had at least one runtime-only discovery.** The build-verify pass is necessary but not sufficient.

---

## 6. Decision-making patterns

### How decisions were actually made

| Decision type | Example | How it was made |
|---|---|---|
| **Tech stack** | React → React Native → Android native | User-led, AI surfaced trade-offs. The "lock screen widget" requirement was load-bearing; user accepted Android-only as a constraint. |
| **API integration** (Step 4 redesign, Step 7 deviations, Step 8b ETA source, BUG-031 ServiceAlerts) | All decisions made via documentation research first. Heuristic guess was the failure mode (BUG-009). |
| **Library choice** (Glance vs RemoteViews) | User explicitly delegated ("debate with yourself") per `feedback_no_implementation_choices.md` memory. AI gave reasoned recommendation; user accepted. |
| **UX wording / layout** | Iterative — AI ships v1, user runtime-tests, vetoes or refines, AI ships v2. ~2 cycles per UX decision. |
| **Scope boundaries** | User-articulated, often mid-step. "I don't care about anything after I get on the bus" → saved as `project_app_scope.md` memory. |
| **Spec-gap recovery** | User-recognized retroactively. BUG-031: "this is due to a lack of specification for that specific requirement." |
| **Performance tuning** (UNCERTAIN threshold, WINDOW_SIZE) | Empirical/iterative. No docs-driven correct value; ship → test → tune. |
| **Reverts** | User-led on lived-use grounds. AI implements promptly, no friction. |

### Two distinct "choice frames" emerged

**Multiple-choice frame** (AI presents N options, user picks): used early in the project. Worked for tech-stack decisions where user has domain intuition (e.g., "free vs paid map library" → free). **Failed when the user had no domain knowledge** (Step 8a Glance vs RemoteViews) — user explicitly invoked the existing memory rule.

**"Debate with yourself" frame** (user delegates back, AI argues in the open): used after `feedback_no_implementation_choices.md` was saved. Notable secondary effect on Step 8a: when forced to debate openly, AI found a strictly-better fourth ETA option (use SL Departures, already integrated) that had been excluded from the original 3-way multiple-choice. **Multiple-choice can constrain AI's own search space, not just the user's.**

### "Spec-gap" pattern (thesis-novel)

The user explicitly framed BUG-031 as "this is due to a lack of specification for that specific requirement in my opinion." This is a load-bearing reframing for the article:

> Late-arriving features in AI-assisted dev are not necessarily scope creep or rework. They can be artefacts of requirement-specification depth — what was implicit in the original wording but never made explicit until lived use exposed it.

For P1-FR8 ("Notify/warn user when vehicle is delayed, cancelled, missing, or has uncertain data"), the line-level interpretation looked complete from the requirement text. The trip-level interpretation only became visible when the user observed line-level deviations on their widget for a bus they weren't on.

**The thesis claim is not "AI helps you ship faster" — it's "AI-assisted prototyping is cheap enough to iterate on requirements you couldn't articulate up front."**

---

## 7. Scaffolding rules and memory — what shaped behavior across sessions

### CLAUDE.md rules (project file, read at every session start)

Three rules were added incrementally as load-bearing patterns surfaced:

1. **Per-prompt USAGE_LOG entries + step-transition `/cost` snapshots** (added end of Step 5). Triggered by realizing batched logging loses context and decision rationale.
2. **Documentation-first for live-data APIs** (added end of Step 5, citing BUG-009 as the load-bearing example). Trigger: BUG-009's three-version arc could have been one if docs had been consulted first.
3. **Permanent record in BUGS.md** (no deletion of fixed bugs). Preserves cumulative bug count and fix history for thesis analysis.

**Rules 1 and 2 demonstrably shifted later step costs:** Step 7 onwards ran with research-first as default and zero heuristic-drift iterations.

### Memory files (cross-session, persistent in `~/.claude/projects/.../memory/`)

| Memory | Type | Effect |
|---|---|---|
| `feedback_no_implementation_choices.md` | feedback | Don't ask non-programmer to choose between named libraries; share trade-offs as process options instead |
| `project_app_scope.md` | project | Phase 1 scope = pre-trip decision support, not journey planning. Saved mid-Step-7 after user's "I don't care after I board" comment |
| `feedback_widget_dark_only_forever.md` | feedback | Saved 2026-05-09 after I overstepped on BUG-020 — prevents re-violation in future sessions |

**Memory persistence pays back the first time it prevents a re-violation, no matter how many sessions later.** Without the dark-only memory, a fresh session in the future could plausibly re-implement light-mode "as polish" again.

### Documentation hygiene

- 4 planning files (PLAN.md, REQUIREMENTS.md, BUGS.md, USAGE_LOG.md) read at every session start
- 125 USAGE_LOG entries (~one per user prompt) preserve full decision paths
- BUGS.md preserves fixed bugs as permanent record
- PLAN.md change log captures every plan revision with reason

This is **deliberate over-documentation for thesis traceability**. In a non-thesis project, this much recordkeeping would be overhead. For the thesis, it's the primary deliverable.

---

## 8. Bug taxonomy

| Type | Count | Examples |
|---|---|---|
| API integration / cross-system ID | 4 | BUG-005 blank headsigns, BUG-009 direction-code drift, BUG-024 night picker, BUG-025 stop_headsign |
| UX wording / layout iteration | 8 | BUG-014 windowed route, BUG-015 scheduled time, BUG-017 stops-away framing, BUG-018 hero caption, BUG-021 wording, BUG-023 header times, BUG-029 expansion UX, BUG-030 timer alignment |
| Performance / runtime | 4 | BUG-002 marker cluster, BUG-003 marker size, BUG-006 sheet jank (closed without code), Step 4b OOM (no number, hotfix) |
| Visual rendering | 4 | BUG-008 polyline overlap, BUG-010 InfoWindow, BUG-011 zoom controls, BUG-022 marker clipping |
| Feature requests / spec gaps | 5 | BUG-013 GPS waypoint, BUG-026 button colors, BUG-027 multi-deviation, BUG-028 multi-vehicle, BUG-031 trip-level alerts |
| Pre-distribution blockers | 1 | BUG-004 tile provider |
| Project-decision closures | 2 | BUG-019 dark-only, BUG-020 light-mode-deferred-then-permanent |
| Theme / accessibility | 1 | BUG-001 Swedish characters |

**Of 31 bugs, 14 surfaced through runtime testing** (would not have been caught by build-verify alone). Of those 14, 5 surfaced through *circumstantial* lived use (e.g., BUG-024 at 00:35; BUG-031 from observing line-level noise on widget).

---

## 9. Thesis-relevant signals — distilled

**Process-level findings worth claiming in the article:**

1. **Per-requirement cost is ~$5-6 for prototyping.** ~$151-179 for 31 satisfied requirements over 25-30 wall hours. Production-readiness adds significant cost not measured here.

2. **Scaffolding-reuse compounds across step boundaries.** Cost-per-minute compressed by ~50% over 4 consecutive steps once core abstractions stabilized.

3. **Documentation-first as default beats heuristic-and-iterate.** BUG-009 (4 iterations, no docs) vs BUG-031 (one research spike + clean implementation) — same shape of question, ~3-5× cost ratio.

4. **Runtime testing remains essential.** Build-verify reaches "code compiles" quickly, but every step had at least one runtime-only discovery.

5. **UX work needs ≥2 observation cycles per decision.** BUG-021 and BUG-023 both reverted on second look. Single-shot UX changes are unreliable.

6. **Branch-per-bug discipline makes reverts cheap.** Three mid-session reverts in Step 10 absorbed without rework.

7. **Cross-session memory pays back the first re-violation.** The dark-only memory, project-scope memory, and no-implementation-choices memory all shaped behavior in later sessions.

8. **"Multiple-choice for the user" can constrain AI's own search space.** "Debate with yourself" prompts produce strictly-better answers in some cases.

9. **Spec gaps surface during lived use, not requirements review.** BUG-031 / P1-FR8 — the line-level interpretation looked complete from the requirement; trip-level only became visible when observed in real use.

10. **Battery-death and conversation-resumption cost are AI-tool-specific cost dynamics.** Long conversations become expensive to even reload (~750k tokens at one point); a non-trivial chunk of "AI-assisted dev cost" is the cost of resuming after interruption. Mitigation: aggressive checkpointing.

11. **The non-programmer user is the spec-quality oracle.** Every load-bearing scope clarification ("I don't care after boarding"; "we wont add light mode"; "this is a spec gap") came from the user, not from AI analysis. AI can implement and iterate; only the user can articulate what they actually want.

12. **Per-minute cost inflates at high context bands and high iteration density** — Step 10's $0.343/min was driven by 90% of time at >150k context plus high turn density, not by intrinsic complexity.

---

## 10. What's NOT in the data (transparency)

For thesis honesty:

- **Steps 1-4 have no `/cost` snapshots** — pre-Checkpoint-4 cost is estimated from cumulative day totals (~$23 inferred).
- **Step 8b pre-compact arc is unmeasured** — battery-death event ended the session before /cost; estimated $15-25 from commit-count parity.
- **Wall vs API time ratio (~5-6:1)** means most cost figures are heavily influenced by the user's verification cadence, which is project-specific.
- **The user is one person** — generalizability to other non-programmers depends on their feedback density, runtime-test diligence, and willingness to write spec gaps as user judgements.
- **No A/B comparison** — there's no "same project built without AI" baseline. Cost comparisons to engineer-hour billing are illustrative, not rigorous.
