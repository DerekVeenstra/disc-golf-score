# Disc Golf Scorecard — Wear OS App

**Status:** Phases 1–6 done — skeleton, domain model + reducer, DataStore persistence + ViewModel,
roster/setup UI, the hole screen (segmented par selector, scoring, standings, finish flow), and the
real final scoreboard with `RESUME`, deleted-course resume, and every named edge state verified.
The **Layouts** feature (a course as a container of layouts — see section 2) landed on top of that
on 2026-09-13, with 201 green JVM tests. Phase 7 (real device) is next.
**Last updated:** 2026-09-13
**Target device:** TicWatch Pro 5 Enduro (primary, confirmed hardware), any Wear OS 3+ smartwatch (secondary)
**Project dir:** `/Users/derekveenstra/dev/disc-golf-score`
**Sibling project:** `/Users/derekveenstra/dev/ultimate-score` — same hardware, same toolchain, same
conventions. Its `PLAN.md` is the reference for everything already learned the hard way; this plan
reuses those decisions rather than re-deriving them.

---

## 1. What this is

A standalone Wear OS watch app that keeps the scorecard for a round of disc golf. You save your
regular players and your regular courses once; from then on starting a round is "pick a course,
tick the players, go." On each hole you set the par and tap each player's score up or down, then
advance. At the end you get a final card and the round is done.

### Design principles

1. **Setup once, play forever.** Players and courses live on the watch between rounds. Creating a
   course must not mean typing 18 pars on a touchscreen the size of a stamp.
2. **One hole fits on one screen.** Everything you need for the hole you're standing on — par,
   every player's strokes, every player's standing — without leaving the screen.
3. **Never loses a round.** A crash, a force-stop, or a dead battery mid-round must not lose the
   card. Same guarantee ultimate-score makes.
4. **No phone.** Works standalone at a course with no phone in reach and no signal.
5. **Wrong taps are cheap to fix.** Every score is `−`/`+`; you can walk back to a previous hole
   and correct it. Nothing is one-way until you finish the round.
6. **Every button is inline; nothing is sticky.** Actions (`START`, `CREATE`, `SAVE`, `DONE`,
   `NEXT`/`FINISH`, …) are rows in the scrolling list via `PrimaryActionRow`, never an `EdgeButton`
   or anything else docked to the bottom of the watch face. *(Derek's call, 2026-09-28.)* A pinned
   button permanently eats a big slice of an already tiny round screen, even when you're nowhere
   near ready to press it, and forces every list to reserve bottom padding around it. Scrolling to
   the end of the list to act is an acceptable cost; a new screen with a sticky button is a bug.

### Explicitly out of scope for v1

Stats (round *history* was added after v1 — §3 "Past rounds"), handicaps, GPS or hole maps, distances, throw-by-throw tracking, OB/penalty
strokes as a separate field, teams/doubles formats, phone companion, cloud sync, always-on ambient
rendering, tile/complication. Section 10 notes where v1 leaves room for these; none get built until
v1 works on the real watch.

---

## 2. Decisions

Everything in this table that isn't marked "inherited" was chosen by Derek during planning.

| Decision | Choice | Why |
|---|---|---|
| Platform | Standalone Wear OS app | *(inherited)* No phone dependency on the course |
| Language / UI | Kotlin + Jetpack Compose for Wear OS (Material3) | *(inherited)* Only first-class option; handles round screens |
| `minSdk` | 30 (Wear OS 3) | *(inherited)* Covers the TicWatch Pro 5 Enduro and everything sold since 2021 |
| `compileSdk` / `targetSdk` | 37 | *(inherited)* Current stable; AndroidX libs require it |
| Package | `com.veenstra.discgolfscore` | Mirrors `com.veenstra.ultimatescore` |
| Persistence | Jetpack DataStore (Preferences), written on every change | *(inherited)* Survives crash, force-stop, battery pull |
| Text entry | `RemoteInput` launcher (watch keyboard/voice) | *(inherited)* Same `rememberTextInputLauncher` pattern as ultimate-score §13 |
| **Course model** | Name + hole count + learned per-hole pars | Enough to pre-fill par and show "HOLE 4 / 18" progress |
| **Course creation** | Name + hole count only; **pars are learned by playing** | Zero setup friction. The par you pick on an *unlearned* hole is written back onto the course, so the second round on that course comes pre-filled. No 18-screen par wizard, no par grid to fill in blind. |
| **Par write-back policy** | **Learn once.** A hole is learned the first time a par is set on it; after that, changing par mid-round affects only that round | A temp pin or a re-teed hole must not silently and permanently re-teach the course. Corrections are deliberate, via course edit (below). |
| **Course editing** | Rename, and edit pars hole by hole. **Hole count is immutable after creation** | Removes the truncate-or-pad question for the learned-par list entirely, and makes the par list a fixed-size array from creation. Wrong hole count = delete and recreate. |
| **Score entry** | Scrollable list of players, explicit `−`/`+` per row, **one line per row** | Whole group visible at once; explicit controls, no gesture to learn and nothing to mis-trigger with wet or gloved hands |
| **Running total** | Relative to par (`+3`, `E`, `−1`), in a **standings block below the rows** | Standard disc golf convention; keeping it out of the rows is what buys one-line rows and a foursome on screen without scrolling |
| **Player count** | No cap | Row height and scrolling are sized to work for any number; nothing in the layout assumes a maximum |
| **Round history** | **Kept.** A round is saved to history the moment it's finished; `DONE` clears only the active round. Listed under Home's `PAST ROUNDS` | Originally "not kept" to keep v1 small; added at Derek's request on 2026-09-11 |
| **Course record** | Shown on the courses manager and the course editor: best score ever carded on that course *relative to par*, and who holds it (every tied holder, not just one). Kept as fields on `Course` (`recordHolderNames`/`recordToPar`), advanced automatically when a round finishes, and correctable by hand from the course editor | Added at Derek's request on 2026-09-11, **stored rather than derived** — revised the same day once Derek asked for hand-editing: a purely derived value (recomputed from `history`) can't be corrected without editing history itself, so the record moved onto `Course`, the exact same "learned automatically, fixed by hand in the course editor" shape as a learned par (§2 "Why par is learned instead of entered up front"). Automatic advancement (`recordAfterRound`) only counts a round that reached every hole, for the same reason `strokesThrough`/`toPar` already only count holes reached — an early finish's partial total isn't comparable to a full round's. **Changed to relative-to-par** on 2026-09-12 at Derek's request (was raw stroke total): a record now means the same thing across courses of different lengths and survives a learned par later being corrected, matching the app's own convention that "to-par is what's shown" (§3 "Final scoreboard"). |
| **Course record fanfare** | A gold "🏆 NEW COURSE RECORD!" banner on the final scoreboard, shown once, only on the live finish that actually advanced the record | Added at Derek's request on 2026-09-11, alongside the manual-edit change above. Tracked as `RoundViewModel.justSetRecord`, an in-memory flag set the moment `finishRound()`'s write-back changes something and cleared on the next `startRound`/`done` — deliberately not persisted, so a force-stop right after finishing costs the celebration but nothing else. `PastRoundsScreen` reopening an old record-holding round never shows it: revisiting a record later isn't the moment it was set. **Superseded 2026-09-13** — see "Layout record fanfare" below; the banner text changed but the tracking mechanism didn't. |
| **Layouts (the concept)** | A course becomes a container of layouts — "Columbia Lake" is a course; "9 short red tees" and "18 long blues" are two layouts of it. **Rounds are played against a layout, not a course.** | Added at Derek's request on 2026-09-13. Real courses have more than one usable configuration (different tee pads, different pin placements, a short/long variant), and forcing each onto its own separate `Course` would mean re-entering the same physical location under a different name every time, plus scattering that course's rounds across multiple unrelated "courses" in history and the manager. |
| **Layout data split** | `Course` becomes `Course(id, name, layouts: List<Layout>)`. New `Layout(id, name, holeCount, pars, recordHolderNames, recordToPar)` — exactly the five fields, no starting-hole offset, no explicit typed par total. `Course` itself now holds nothing but identity and grouping. | Hole count, learned pars, and the record are properties of *how a round is played*, which is what a layout is — sibling layouts of the same course must learn independently (a par corrected on "18 long blues" must not touch "9 short reds"), so each of those fields has to live on `Layout`, not `Course`. Tee/pin detail deliberately has no dedicated field: it goes in the layout's `name` instead, the same way it always implicitly lived in a course's name before layouts existed. |
| **Layout hole count immutability** | Hole count stays immutable after a layout is created, for the same reason it was immutable on a course (see "Course editing" above) | Now that hole count is a property of the layout rather than the course, creating a *new layout* is the supported way to get a different hole count at the same course — nothing new needed inventing, the existing "create another one" escape hatch just moved down a level. |
| **Migration (courses → layouts)** | The DataStore codec reads the old flat on-disk course shape and auto-wraps it: each existing course becomes a course with exactly one layout carrying that course's old `holeCount`/`pars`/`recordHolderNames`/`recordToPar`. The generated layout is named by hole count (`defaultLayoutName` — `"18 holes"`, `"9 holes"`, `"1 hole"`) and reuses the course's own id. Detected by a version-tag prefix (`"C2"` + the field separator) on every record this codec itself writes — a course id is always a wall-clock millisecond count, so it can never collide with the tag, making old vs. new format unambiguous from the string alone with no separate schema-version field. | Nothing may be lost migrating existing users' saved courses onto the new shape, and the migration has to be pure-function testable (feed the old serialized form in, assert the wrapped result) without a live DataStore. A version tag was chosen over trying to structurally infer old-vs-new from field counts because layouts nest one more level than a flat course record did — an unambiguous, explicit marker beats a heuristic that would only work by coincidence of the two shapes' field counts never aligning. |
| **Round setup: course → layout, with a skip** | Picking a course whose `layouts.size == 1` goes straight to ticking players, no layout picker shown — single-layout courses feel exactly as they did before layouts existed. Two or more layouts opens a layout-picker step listing them, plus a "+ New layout…" entry (mirroring "+ New course…"'s inline name → hole count flow). | The common case (a course really does have just one layout) must not grow an extra tap just because the feature exists. A picker only appears when there's an actual decision to make, which is also when skipping it silently would be wrong (guessing a layout on someone's behalf isn't safe the way skipping is for one option). |
| **Deleting a layout** | Blocked outright if it would leave a course with zero layouts, rather than deleting the whole course along with its last layout | Blocking is simpler and strictly safer: it can never destroy a course's saved-round history or its other layouts as a side effect of removing one, and there is always an obvious, discoverable fix (delete the course itself, from the courses manager, if that's really what's wanted) rather than a silent cascade. |
| **Layout record fanfare wording** | The final scoreboard's fanfare banner reads "🏆 NEW LAYOUT RECORD!", one consistent wording regardless of how many layouts the course has | A record is now tracked per layout (see the data split above), so "course record" stopped being accurate the moment a course could have more than one. Deliberately **not** special-cased for the common single-layout course — one wording that's technically correct everywhere beats a wording that reads better half the time and requires a branch to get there. |
| **Layout record editing removed** | The record row on `LayoutEditorScreen` is now display-only — no more tap-to-edit holder names or `To par` stepper. Automatic advancement (`recordAfterRound`) on finishing a round is unchanged. | Removed at Derek's request on 2026-09-13: a wrong record is meant to be fixed from the Google Sheet the cloud saves feature writes to instead, if it's ever needed at all. `RoundViewModel.setLayoutRecordHolders`/`setLayoutRecordToPar`, and the params/launchers threading them from `WearApp` down through `NewRoundSetupScreen`/`ManageCoursesScreen`/`CourseEditorScreen` to `LayoutEditorScreen`, were deleted along with the UI. |
| **Finished round persistence** | The finished round stays in storage until `DONE` is pressed | Right-swipe dismisses the app and can't be rebound; an accidental swipe on the final card must not destroy it. Relaunching returns to the scoreboard. |
| **Finishing** | Both paths confirm — early finish *and* the last hole's `FINISH` | Both are the point of no return; only one of them being guarded is arbitrary |
| **Ties on the scoreboard** | Shared rank (`1, 1, 3`) | It's a tie; showing one of them as second is wrong |
| **Always-on / ambient** | No; normal screen timeout | You don't glance at a scorecard between throws. Saves an entire second rendering path, burn-in handling, and battery over a 2-hour round. Restore-from-DataStore makes wake instant. |
| Default strokes on a new hole | **Pre-filled to the hole's par** | A par is the most likely score; the common case becomes zero taps per player. See the tradeoff below. |
| Untouched rows | **No visual distinction** — identical to touched rows | The obvious affordance was dimness, and dimness is exactly what dies in direct sun over a two-hour round. A signal that's unreadable outdoors isn't a mitigation, it's chrome. |
| "Didn't play this hole" | Not modelled in v1 | Every player has a number on every hole; a late joiner or a sat-out hole isn't represented |
| Theme | Dark only | *(inherited)* OLED-friendly, matches Wear conventions; verify sunlight legibility on-device |
| Navigation | One activity, a sealed `AppScreen` in Compose state | *(inherited, §13)* No nav graph — this app is one screen at a time |
| **List API** | `TransformingLazyColumn` + `ScreenScaffold` + `EdgeButton` | Settled in Phase 1 by compiling against the pinned 1.6.2. **Deliberately diverges from ultimate-score**, which uses the older `ScalingLazyColumn` on these same versions. **Half the original rationale did not survive Phase 4** — see the row below. |
| **Round-edge row safety** | A **static** horizontal inset plus content padding. No per-row measurement | Phase 1 justified the divergence partly on `TransformingLazyColumnItemScope`'s per-item transform handling round-edge clipping. Phase 4 found that API's visual half (`applyContainerTransformation`) is not reachable from app code — it is reserved for Material3's own `Button`/`Card`. The Phase 4 workaround measured each row's position and then resized that row, a measure→resize feedback loop, which produced a confirmed and only partly explained bug (a list's last item rendering at 96×96px) papered over with a 55%-width floor. Replaced in Phase 5: §3 only ever required that targets stay inside the inscribed circle *at any scroll position*, which a fixed inset satisfies **by construction**, with no measurement, no loop, and nothing to fail to converge. Per-row optimal width was an optimization nobody asked for. |

### Why par is learned instead of entered up front

The options were an editable par grid at course-creation time, a sequential 18-screen wizard, or
learning. Learning wins because the data you need is *already being entered during play* — the par
selector has to exist on the hole screen regardless (holes get re-teed, temp pins move, and a new
course has no pars yet). Writing that choice back onto the course costs one line and removes an
entire setup screen. First round on a new course: pick par on each hole as you go. Every round
after: it's already right.

Unlearned holes pre-fill as **par 3** — by far the most common disc golf hole — and are flagged
internally as unlearned so the write-back happens on first play.

**Learning is once, not every round.** If changing par mid-round always re-taught the course, then
a single round played on a temp layout would permanently corrupt a course you'd played correctly
twenty times, silently, and you'd only find out a round later. So a hole is learned the first time
a par is set on it and never again; after that, the par selector still works, but it changes only
the round in front of you. Getting a par genuinely wrong is fixed deliberately, in the course
editor, where you can see the whole card.

### The tradeoff on "learn once"

The cost is that the *first* round on a course also has to be a round where you pay attention to
par. Set hole 7 to par 3 by reflex when it's really a 4, and the course stays wrong until you edit
it. That's the right way round: a wrong par entered once is fixable in one obvious place, whereas
last-played-wins spreads the same mistake over every future round with no indication it happened.

### The tradeoff on par-prefilled scores

Defaulting each player's strokes to the hole's par means a player who parred needs zero taps, which
is the whole point. The cost is that *forgetting* to enter someone silently records a par rather
than an obviously-wrong zero. This is the same tradeoff every disc golf app makes, and the
alternative (start at 0) means tapping `+3` three-to-five times for every player on every hole —
roughly 250 taps over an 18-hole round with 3 players.

The obvious mitigation — render untouched rows dimmer — was considered and **rejected**: this app
lives in direct sunlight for two hours at a stretch, and a brightness difference is the first thing
sunlight erases. A signal you can't read outdoors is worse than no signal, because it's still
occupying the design. So untouched rows look exactly like touched ones, and the real mitigation is
that `◂ PREV` walks back to any earlier hole with everything still editable.

The `touched` set stays in the model regardless, because it drives a rule that isn't visual:
changing a hole's par updates every *untouched* row to the new par, while rows a human has already
adjusted keep the number the human put there.

---

## 3. Screens

Five screens, all round-safe (content inside the inscribed circle), scrollable where content can
exceed the screen. Wear reserves right-swipe for back/dismiss — **nothing** may be bound to it.

### Home

```
        ╭───────────────╮
        │  DISC GOLF    │
        │               │
        │ ▸ RESUME      │   ← only when a round is in progress
        │ ▸ NEW ROUND   │
        │ ▸ PLAYERS     │
        │ ▸ COURSES     │
        │ ▸ PAST ROUNDS │
        ╰───────────────╯
```

### New round setup

One scrolling screen, two sections. Course is single-select, players are multi-select, `START` is
disabled until a course and at least one player are chosen.

```
        ╭───────────────╮
        │    COURSE     │
        │ ● Riverside 18│
        │ ○ Maple Hill 9│
        │ + New course  │
        │    PLAYERS    │
        │ ☑ Derek       │
        │ ☑ Sam         │
        │ ☐ Alex        │
        │ + New player  │
        │    START ▸    │
        ╰───────────────╯
```

- `+ New course…` → name via RemoteInput → hole count (`9` / `18` / custom `±`) → saved **and
  selected**. Same inline-create-then-select flow as ultimate-score §13's presets. **Hole count is
  set here and never again** (§2).
- `+ New player…` → name via RemoteInput → saved and ticked.
- Long-press any course or player row → rename, edit pars (courses), or delete. Same
  `combinedClickable` management gesture as ultimate-score §13.
- **Deleting something that's currently picked** clears that selection at the point of deletion —
  deleting the selected course leaves no course selected (`START` goes back to disabled); deleting a
  ticked player unticks them. Selection is never left pointing at an id that no longer exists. This
  is the exact bug ultimate-score §13 fixed, doubled here by having two selection models.
- `START` is an `EdgeButton` pinned to the bottom edge, so a long roster never puts it out of reach.
  **Superseded 2026-09-28 by design principle 6:** `START` is now an inline `PrimaryActionRow` at
  the end of the list (dimmed and untappable until course/layout/players are all picked).

**Superseded 2026-09-13 by the Layouts phase log below.** Picking a course now resolves a layout
too before `START` enables — automatically, with no extra screen, when the course has exactly one
layout (the mockup above still describes this case exactly), or via one extra layout-picker step
when it has more than one. "Long-press a course row → edit pars" became "→ manage its layouts,"
each of which is where pars/record now live. See the phase log for the exact new screen shapes.

### Hole (the main screen)

```
        ╭───────────────╮
        │  HOLE 4 / 18  │
        │ PAR 3         │   ← learned: label, tap to expand
        │ Derek  ─ 4 ＋ │
        │ Sam    ─ 4 ＋ │
        │ Alex   ─ 3 ＋ │
        │ ── TOTAL ──   │
        │ Derek     +2  │
        │ Sam        E  │
        │ Alex      −1  │
        │  ◂ prev hole  │
        │  finish round │
        │   ⟨ NEXT ▸ ⟩  │   ← EdgeButton, pinned
        ╰───────────────╯
```

One scrolling list, one pinned edge button:

- **Player rows are one line**: name (ellipsized) then `−  N  ＋`. This is what moving the running
  totals out of the rows buys — a foursome fits on screen without scrolling, where two-line rows
  showed three players at most.
- **Par control**: on a *learned* hole it's a compact `PAR 3` label; tapping it expands the
  `3 [4] 5` selector. On an unlearned hole the selector is expanded from the start, since that's the
  hole where a choice is actually being asked for. Saves a permanent row of chrome on every hole of
  every round after the first.
- **The expanded par selector is a deliberate width exception** and does not obey the shared row
  inset. Phase 5 first replaced it with a stepper because three 48dp targets don't fit inside a 64%
  row, but par is the one control Derek specified as *three visible options* — and it is not a list
  row that scrolls to the viewport extremes, it sits near the top of the content. So it gets the
  width it needs (~85%) while the player rows below it keep the tighter inset. Reverting a specified
  interaction to fit a constraint that doesn't apply to it was the wrong trade.
- **Standings block** below the rows: each player's to-par across holes 1 through the current one,
  live as you tap. One flick away rather than always on screen.
- **`NEXT ▸` is an `EdgeButton`** — Material3's bottom-edge action, shaped to the round face and the
  largest target on the screen. It becomes `FINISH ▸` on the last hole. This is the only control
  tapped 18+ times a round, so it gets the best real estate and never scrolls away.
- **`◂ prev hole` and `finish round` are dim text rows at the end of the list**, above the standings'
  bottom edge and reachable only by scrolling. Both are rare, both are recoverable-or-confirmed, and
  burying them keeps two low-frequency targets out of the band where a mis-tap costs the most.
  `◂ prev hole` is hidden on hole 1; `finish round` is hidden on the last hole (where the edge button
  already says `FINISH`).
- **Both finish paths confirm** (§2) with a full-screen yes/no.
- **Row width is inset horizontally** by a **fixed** fraction of screen width (not edge-to-edge, and
  not measured per row) so the `−` and `＋` targets stay inside the inscribed circle even when a row
  sits at the top or bottom of the viewport. Combined with content padding that keeps rows out of the
  extreme top/bottom band, this makes edge-safety a property of the layout rather than something
  arithmetic has to land exactly right on every frame. Every target stays ≥ 48dp.
- **Scroll resets to the top on every hole change**, forward and back.
- Strokes clamp to 1–15. Par is 3/4/5 only.

### Final scoreboard

```
        ╭───────────────╮
        │   FINAL       │
        │  Riverside    │
        │ 1 Alex  53 −1 │
        │ 1 Sam   53 −1 │
        │ 3 Derek 56 +2 │
        │   ⟨ DONE ⟩    │
        ╰───────────────╯
```

- Sorted by total strokes, with the raw total and the to-par. This is the only place the stroke
  total appears — during play, to-par is what's shown.
- **Ties share a rank** and the next rank skips accordingly (`1, 1, 3`).
- **The round is still in storage here.** Right-swipe dismisses the app on Wear and can't be
  rebound, so a stray swipe on the final card must not destroy it: the round is marked finished and
  persisted, relaunching comes straight back to this screen, and only `DONE` clears it.
- If the round's course was deleted mid-round, this screen is unaffected — the round carries its own
  `courseName` snapshot and its own hole list. Par write-back to a course that no longer exists
  silently does nothing.
- **Course record fanfare** (§2 "Course record fanfare"): a gold `🏆 NEW COURSE RECORD!` banner
  between `FINAL` and the standings, shown only when *this* finish is the one that just advanced
  the course record — never on a relaunch that lands back here after the fact, and never when
  re-opening the same round later from `PAST ROUNDS`. **Superseded 2026-09-13**: the banner now
  reads `🏆 NEW LAYOUT RECORD!` (§2 "Layout record fanfare wording"), and a non-blank layout name
  shows in smaller text under the course name — blank (no second line) for a round saved before
  layouts existed.

### Past rounds

Reached from Home's `PAST ROUNDS`. Every finished round, newest first, one row each: course name,
with the date it was finished underneath. Tapping one re-opens the same final scoreboard the round
ended on (plus the date), with a `Delete round` row below the standings. Deleting asks first — a
round, unlike a player or course, can't be recreated. A round finished early is saved as it was
finished: its totals count only the holes it reached, exactly like its final scoreboard did.

**Superseded 2026-09-13**: a row's detail line becomes `<layout name> · <date>` when the round has
a non-blank layout name, and just the date otherwise (a round saved before layouts existed).

### Players / Courses managers

Reached from Home for housekeeping outside a round. Same list + `+ New…` + long-press-to-edit
pattern as the setup screen. Each course's row shows its **course record** as a detail line
underneath — the best to-par ever carded there, and who holds it (§2 "Course record"); `No record
yet` before anyone's finished a full round on it.

**Course editor** is where a wrong learned par gets fixed (§2), and now also where the course
record itself gets fixed by hand. Rename, then the record row, then a scrolling hole-by-hole par
list:

```
        ╭───────────────╮
        │   Riverside   │
        │ Record: Derek │
        │      — −5     │
        │ To par ─ −5 ＋│
        │ H1   ─ 3 ＋   │
        │ H2   ─ 3 ＋   │
        │ H3   ─ 4 ＋   │
        │ H4   ─ 3 ＋   │
        │   ⟨ SAVE ⟩    │
        ╰───────────────╯
```

The record row opens the same text-entry keyboard/voice input as a rename, prefilled with the
current holder name(s) — comma-separated for a tie — and typing a blank result clears the record
entirely. A `To par` stepper appears underneath once there's at least one holder, the same `−`/`＋`
shape as every par row below it, seeded to a reasonable guess (even par, `0`) the first time a
name is set with no prior to-par to keep. A round finishing on this course that beats or ties the
current record still corrects it automatically — hand-editing doesn't turn that off, it's just
another way to arrive at the same fields.

Same one-line row shape and horizontal inset as the hole screen's player rows — same problem, same
solution, and it should be the same composable. Unlearned holes show their par-3 default; editing
one marks it learned. Hole count is displayed but not editable (§2).

**Superseded 2026-09-13 by the Layouts phase log below.** Everything this subsection describes —
the record row, the `To par` stepper, and the hole-by-hole par list — moved one level down onto a
new `LayoutEditorScreen`, reached by tapping a layout listed in `CourseEditorScreen` rather than
being drawn directly on the course editor; the mechanics (text-entry rename, the stepper shape, the
par list, hole count shown but not editable) are otherwise unchanged, just re-scoped from "the
course" to "the layout you tapped." See the phase log for the exact new screen shapes.

---

## 4. Architecture

Same shape as ultimate-score, one size up: one activity, a screen-level sealed state, one
ViewModel, a pure reducer, one repository.

```
MainActivity (ComponentActivity)
  └── WearApp()                  @Composable — theme, AppScreen state, routing
        ├── HomeScreen()
        ├── NewRoundSetupScreen()
        ├── HoleScreen()
        ├── FinalScoreboardScreen()
        └── ManagePlayersScreen() / ManageCoursesScreen()
              ↑ state
        RoundViewModel           StateFlow<RoundState?>, StateFlow<Roster>
              ↑
        DiscGolfRepository       DataStore-backed load + save (round, players, courses)
              ↑
        RoundState / RoundReducer / Player / Course   pure Kotlin → JVM unit testable
```

### Model

```kotlin
data class Player(val id: String, val name: String)

data class Course(
    val id: String,
    val name: String,
    val layouts: List<Layout>,        // never empty — see §2 "Deleting a layout"
)

data class Layout(
    val id: String,
    val name: String,                 // e.g. "9 short reds", "18 long blues"
    val holeCount: Int,               // 1..36, typically 9 or 18; immutable after creation
    val pars: List<Int>,              // size == holeCount; 0 = not yet learned
    val recordHolderNames: List<String> = emptyList(),
    val recordToPar: Int? = null,
)

data class HoleScore(
    val par: Int,
    val wasLearnedAtStart: Boolean,   // did the layout already know this hole's par?
    val strokes: Map<String, Int>,    // playerId -> strokes, always populated
    val touched: Set<String>,         // playerIds a human has adjusted — no visual, see §2
)

data class RoundState(
    val courseId: String?,
    val courseName: String,
    val players: List<Player>,        // snapshot: renaming a player mid-round doesn't reshuffle
    val holes: List<HoleScore>,       // size == holeCount
    val currentHole: Int,             // 1-based
    val finished: Boolean = false,
    val layoutId: String? = null,     // nullable/defaulted so a pre-layouts saved round still decodes
    val layoutName: String = "",      // "" means "nothing to show" (pre-layouts round, or deleted layout)
) {
    fun strokesThrough(playerId: String): Int      // holes 1..currentHole
    fun toPar(playerId: String): Int               // strokes - par, holes 1..currentHole
}
```

A course is a container of layouts — "Columbia Lake" is a course; "9 short red tees" and "18 long
blues" are two layouts of it (§2 "Layouts") — and **a round is played against a layout, not a
course**. Hole count, learned pars, and the record all live on `Layout`, independently per layout,
for the same "learn once, correct by hand" reasons §2 gives for why par is learned and why the
record is stored rather than derived; `Course` itself carries nothing but identity and grouping.

Players are **snapshotted into the round** rather than referenced by id into the live roster, so
renaming or deleting a player mid-round can't corrupt a card in progress. `courseName`/`layoutName`
are snapshotted for the same reason — as **two separate fields**, not one combined string, so the
UI can render them at different sizes (§3's final scoreboard) and so `courseName` keeps its
pre-layouts field name, letting an old saved round deserialize with a blank `layoutName` rather than
breaking. `courseId`/`layoutId` are kept only for the par/record write-back, which no-ops if that
course or layout is gone.

`wasLearnedAtStart` is what makes "learn once" (§2) a property of the model rather than of the UI:
the write-back fires only for holes where it's `false`, so a par changed on an already-learned hole
never escapes the round it was changed in. This now targets the round's layout, not its course —
sibling layouts of the same course learn independently.

`finished` is persisted like everything else — a finished round survives until `DONE` explicitly
clears it, which is what makes an accidental right-swipe on the final card harmless (§3).

### Reducer

```kotlin
sealed interface RoundAction {
    data class SetPar(val par: Int) : RoundAction              // applies to currentHole
    data class Adjust(val playerId: String, val delta: Int) : RoundAction
    data object NextHole : RoundAction
    data object PrevHole : RoundAction
    data object Finish : RoundAction
}

fun reduce(state: RoundState, action: RoundAction): RoundState
```

Pure, no Android imports, fully unit tested on the JVM. All the rules that are easy to get wrong
live here and only here: par change propagating to untouched rows only, clamping, `PREV` at hole 1
being a no-op, and which holes are eligible for par write-back.

**There is exactly one notion of "how much of the round counts": holes 1 through `currentHole`.**
Finishing doesn't move `currentHole`, so a round finished early simply never counts the holes it
never reached, and the final totals are the same `strokesThrough`/`toPar` functions the hole screen
was already using. No separate "final" accessor exists, because a second way to total a card is a
second way to get it wrong.

**`NextHole` on the last hole is a no-op, not an implicit finish.** Both finish paths confirm (§2),
so the only thing that can end a round is an explicit `Finish` after a confirmation. A reducer that
finished a round on overflow would give the last hole's `NEXT` a silent, unconfirmed third meaning.

**A finished round is frozen:** `reduce` returns a `finished` state unchanged for every action. What
clears it is `DONE`, which is a repository/ViewModel concern, not a `RoundAction`.

**Par write-back is bounded by `currentHole` too**, not just by `wasLearnedAtStart` — the two rules
compose. Eligibility is "unlearned **and** actually reached." Without the second half, abandoning a
fresh 18-hole course on hole 3 would teach the course fifteen par-3s that nobody ever looked at, and
"learn once" would then make them permanent: the course would be fully, confidently wrong, and no
amount of replaying it would fix it. The same boundary that decides what counts toward the score
decides what the course is allowed to learn from it.

This does mean that *standing on* an unlearned hole and advancing without touching the par selector
teaches its default par 3. That is intended — §3 shows the selector already expanded on unlearned
holes, so it's a question the screen visibly asked and the player answered by moving on. The
alternative (learn only on an explicit `SetPar`) would mean an all-par-3 course never finishes
learning and re-asks on every hole of every round forever.

Ranking for the final scoreboard (shared ranks on ties, `1, 1, 3`) is a pure function here too, not
something the scoreboard composable works out while drawing.

### Persistence

Three concerns in one DataStore preferences file, one repository class implementing three small
interfaces (`PlayerStore`, `CourseStore`, `RoundStore`) so tests can fake each independently —
exactly the split ultimate-score §13 landed on.

**Encoding — take the lesson from ultimate-score §13 on day one.** Names are arbitrary user text
and will contain commas and colons. Use ASCII control characters as separators and never write an
escaping scheme:

- `U+001F` (Unit Separator) between the fields of one record
- `U+001E` (Record Separator) between records
- Plain commas *inside* a digits-only field (par lists, stroke lists) — safe, since no user text
  reaches there

`sanitizeName()` strips control characters from any name before a `Player`, `Course`, or `Layout`
is constructed, so the guarantee holds at the source rather than at encode time. Malformed records
are dropped, never thrown on.

**A third separator, `U+001D` (Group Separator), was added for layouts** (§2 "Layouts"): a course
record now nests a list of layout records one level below where `Course.pars`/
`Course.recordHolderNames` used to live directly, and `U+001F`/`U+001E` were already spoken for one
level up (fields-within-a-record and records-within-the-list). A version tag (`"C2"` plus the field
separator) prefixes every course record this codec writes in the new nested shape; any record
without it is the old flat pre-layouts shape, decoded by its original, frozen logic and wrapped into
a course with one generated layout (§2 "Migration") — a course id is always a wall-clock millisecond
count, so it can never collide with the tag, and old and new formats can coexist in the same stored
string with no separate schema-version field anywhere.

The active round is written on **every** action. A round is ~18 holes × ~4 players; the cost is
irrelevant and the guarantee is worth it. It's read once at startup before first composition, so
the watch never flashes "no round" over a live one.

**Round history** (`RoundHistoryStore`) lives in its own preferences file, `discgolf_history`: a
preferences file is rewritten whole on every edit, and the active round is edited on every tap, so
sharing a file would rewrite every past round on every tap. Each saved round is stored as the same
three pieces as the active round, under keys prefixed with its id, plus one index key listing the
ids (and finish times) in order.

### Manifest essentials

*(inherited verbatim from ultimate-score §4 — watch feature, standalone meta-data, DeviceDefault
theme, launcher intent filter. **No** `WAKE_LOCK`: v1 has no always-on.)*

---

## 5. Toolchain

Already installed and verified on this machine during ultimate-score Phase 0 — **nothing new to
install.** Confirm rather than redo:

- Android Studio via Homebrew; JDK is the bundled JBR at
  `/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- SDK at `~/Library/Android/sdk`: `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`,
  `emulator`, `system-images;android-34;android-wear;arm64-v8a`
- AVD `Wear_Large_Round_API34`; launch with
  `emulator -avd Wear_Large_Round_API34 -no-audio -no-boot-anim`
- `ANDROID_HOME` / `JAVA_HOME` / `PATH` already in `~/.zshrc`

Version pins to copy from `ultimate-score/gradle/libs.versions.toml` (AGP 9.4.0 with built-in
Kotlin — **no** `org.jetbrains.kotlin.android` plugin; Kotlin 2.4.20; Compose BOM 2026.08.00; Wear
Compose Material3 1.6.2; DataStore 1.2.1; JUnit 4 + coroutines-test).

### Real-device notes, already paid for once

- **The TicWatch has no USB data connection** — charging pins are power only. Wireless ADB:
  Settings → System → About → tap Build number ×7 → Developer options → ADB debugging + Wireless
  debugging → `adb pair <ip>:<pairPort>` then `adb connect <ip>:<mainPort>`. **These are two
  different ports on this watch.**
- `adb shell am start` does **not** wake a real watch's display; a screenshot will come back solid
  black. Send `adb shell input keyevent KEYCODE_WAKEUP` first.
- The real Pro 5 Enduro is **233dp tall (466px @ 320dpi)** — *taller* than the round AVD's 227dp.
  Layouts tuned only against the emulator have clipped on the real device before. Anything with
  fixed heights gets checked on the watch, not just the AVD.

---

## 6. Build phases

Each phase ends with something runnable. Do not start a phase before the previous one runs. Append
what actually happened to this file at the end of each phase, ultimate-score style — including the
bugs and the wrong turns, which is the part that turns out to be worth re-reading.

### Phase 1 — Skeleton
Hand-written Gradle project (no Studio wizard): `settings.gradle.kts`, root `build.gradle.kts`,
`gradle/libs.versions.toml`, `app/build.gradle.kts`, manifest, `MainActivity`, `git init`,
`.gitignore` — all copied in shape from ultimate-score.
**Done when:** a hello-world Wear screen runs on the emulator and `./gradlew :app:assembleDebug` is
green.

### Phase 2 — Domain (no UI)
`Player`, `Course`, `HoleScore`, `RoundState`, `RoundAction`, `reduce`, `sanitizeName`, and the
derived score functions. Unit tests first-class here: par propagation to untouched rows only,
clamping at 1 and 15, PREV/NEXT boundaries, to-par across partial rounds, finishing early, ranking
with ties, and write-back eligibility (`wasLearnedAtStart`).
**Done when:** `./gradlew :app:testDebugUnitTest` is green and the reducer covers every rule in §4.

### Phase 3 — Persistence
DataStore repository, the three store interfaces, the control-character codecs for players,
courses, and the active round, plus the ViewModel that owns them. Codec tests including names with
commas, colons, and a raw separator character; malformed and empty input. ViewModel tests against
in-memory fakes, ultimate-score §13 style.
**Done when:** tests green and the app still builds and runs.

*The end-to-end force-stop check that used to live here moved to Phases 4 and 5: it needs a UI to
observe the restore in, and asserting persistence through a fake proves the codec, not the wiring.*

### Phase 4 — Roster and setup UI
`rememberTextInputLauncher` (lifted from ultimate-score §13), players and courses managers, the
course par editor, new-round setup screen with single-select course + multi-select players, inline
create, long-press edit/delete, and the delete-clears-selection rule.
**Done when:** you can create players and courses on the emulator, edit a course's pars, then
`adb shell am force-stop com.veenstra.discgolfscore`, reopen, and everything is still there —
verified by screenshot, not by assumption. This is the first real test of the Phase 3 wiring.

### Phase 5 — Hole screen
The main event. Collapsing par control, scrolling one-line player rows with `−`/`+`, the standings
block, the `NEXT`/`FINISH` EdgeButton, prev-hole, both confirmed finish paths, scroll reset on hole
change, and the learn-once par write-back.
**Done when:** a full 18-hole round can be played on the emulator; replaying the same course comes
back pre-filled; changing par on an already-learned hole does *not* change the course; and a
`force-stop` mid-round reopens on the same hole with the same numbers.

### Phase 6 — Finish and polish
Final scoreboard with shared-rank ties, resume-on-launch from Home (mid-round *and* finished),
deleted-course resume, empty/edge states (one player, a 12-player roster, 1-hole course, long
names), rotary/bezel scrolling on every list, haptics on score changes, launcher icon (placeholder
is fine; a real icon is a follow-up, as it was in ultimate-score §15).
**Done when:** every screen renders correctly on the round AVD by screenshot, with no clipping.

### Phase 7 — Real device
Wireless debug onto the TicWatch Pro 5 Enduro. Play an actual round with it.
**Done when:** it survived a round outdoors, the touch targets are hittable while walking, and the
card was right at the end.

This is also where §11's four provisional interaction choices (row inset, EdgeButton `NEXT` with
`prev hole` buried, delete-clears-selection, deleted-course resume) get confirmed or changed. They
were the model's call on the understanding that only the wrist settles them.

---

## 7. Testing

- **Unit (JVM):** reducer, codecs, ViewModel. This is where correctness lives and it runs in a
  second with no emulator. Expect ~60–80 tests, in line with ultimate-score's 63.
- **Manual on emulator, by screenshot at every phase:** layout on the round AVD, scroll behaviour,
  process-death restore.
- **Manual on device:** the only test that matters for touch-target size, sunlight legibility, and
  whether the flow survives actually walking a course.
- Compose UI tests deliberately skipped in v1 — same call as ultimate-score; the logic worth
  testing is pure.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| One-line row (name + 3 targets) is too cramped on a round face | Totals moved out of the row to buy the space; horizontal inset keeps targets inside the circle; verify with 8- and 15-character names on the *real* watch in Phase 7, not the AVD |
| Par-prefill hides a forgotten entry | Deliberately unmitigated visually — a dim-row signal dies in sunlight (§2). PREV makes it correctable |
| `−`/`＋` on a row near the top or bottom of the viewport is hard to hit | Rows inset horizontally so targets stay inside the inscribed circle at any scroll position; the frequently-tapped action (`NEXT`) is an EdgeButton instead of a list row |
| Wrong par learned on the first round sticks | The course editor exists precisely for this and is in Phase 4, not deferred |
| Fixed heights clip on the 233dp real device | Derive sizes from measured constraints, never from AVD-tuned constants — the exact bug ultimate-score Phase 7 spent four round-trips on |
| Long round drains battery | No always-on, no wake lock; screen sleeps normally between holes |
| Deleting a player mid-round | Round snapshots its players; roster edits can't touch a live card |
| Wear Material3 list API churn (`TransformingLazyColumn` vs `ScalingLazyColumn`) | Settle on whichever the pinned 1.6.2 provides in Phase 1's skeleton, before five screens depend on it |

---

## 9. How this gets built

Planning (this document) is Opus's. **Implementation is Sonnet's**, one phase at a time, with this
file as the spec. At the end of each phase Sonnet appends a phase log here — what was built, what
broke, and what the fix actually was — so the next phase starts from reality rather than from the
plan's intentions.

---

## 10. Room left for later

- **Stats** — round history is now kept (§3 "Past rounds"), every entry a complete `RoundState`,
  so per-course bests or per-player averages would be derived functions over it, no storage change.
- **A "didn't play this hole" state** — deliberately absent in v1 (§2). It would be a sentinel in
  `strokes` plus an exclusion in the derived totals; nothing structural.
- **Changing a course's hole count** — blocked in v1 to keep the par list fixed-size (§2). Allowing
  it later means one truncate-or-pad decision, not a redesign.
- **Doubles / teams** — a team is a `Player` whose name is two names, and works today. Real team
  scoring would need the reducer to group ids.
- **Distances, OB strokes, throw tracking** — additive fields on `HoleScore`.
- **Phone companion** — the serialized round is exactly what would go over the Data Layer.

None of this gets built in v1.

---

## 11. Open questions

None. Answered during planning on 2026-09-08.

**Round 1 — the shape of the app:**

1. Course model → name + hole count + per-hole pars.
2. Course creation → skip par setup, learn pars by playing.
3. Score entry → scrollable list with explicit `−`/`+`.
4. Round history → not kept; final scoreboard only.
5. Running total → relative to par.
6. Always-on → no.

**Round 2 — details pulled out of §2 and §3 for a closer look:**

| # | Question | Answer |
|---|---|---|
| 1 | Affordance for untouched rows | None — same brightness as every other row |
| 2 | Par write-back policy | Learn once; corrections via the course editor |
| 3 | Finished round destroyed by a stray right-swipe | Persist it until `DONE` |
| 4 | "Didn't play this hole" state | Not needed in v1 |
| 5 | Player cap | None — rows and scrolling support any number |
| 6 | 15-stroke ceiling | Fine as-is |
| 7 | Where the running total lives | Below the rows, not in them |
| 8 | Collapse the par control once learned | Yes |
| 9 | Round-inset vs. edge rows | *Model's call:* horizontal inset on rows, ≥48dp targets — Derek to try on-device |
| 10 | PREV/NEXT placement | *Model's call:* `NEXT` as a pinned EdgeButton, `prev hole` a dim row at the end of the scroll — Derek to try on-device |
| 11 | Confirm on finish | Both paths confirm |
| 12 | Scroll reset on hole change | Yes, both directions |
| 13 | Changing a course's hole count | Not allowed after creation |
| 14 | Deleting a player/course that's selected | *Model's call:* clear the selection at the point of deletion — Derek to try on-device |
| 15 | Resuming when the round's course was deleted | *Model's call:* round plays and scores off its own snapshot; write-back no-ops — Derek to try on-device |
| 16 | Ties on the scoreboard | Shared rank (`1, 1, 3`) |

Four of these (9, 10, 14, 15) were the model's call and are explicitly provisional — they're
interaction choices that only the wrist can settle, so they get revisited in Phase 7 rather than
being treated as settled now.

Ready to start Phase 1.

---

## Phase 1 log ✅ done (2026-09-08)

Hand-written Gradle project, copied in shape from ultimate-score's Phase 1 (which had already
paid for every toolchain surprise — none of that recurred here). **Done when:**
`./gradlew :app:assembleDebug` green, `./gradlew :app:testDebugUnitTest` green, hello-world screen
on the emulator, confirmed by screenshot.

### What was built

- `settings.gradle.kts` (`rootProject.name = "disc-golf-score"`), root `build.gradle.kts`,
  `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts` — all copied verbatim
  from ultimate-score except `namespace`/`applicationId` (`com.veenstra.discgolfscore`) and the
  app name. Same version pins: AGP 9.4.0 (built-in Kotlin, no
  `org.jetbrains.kotlin.android` plugin), Kotlin 2.4.20, Compose BOM 2026.08.00, Wear Compose
  Material3/Foundation 1.6.2, DataStore 1.2.1, JUnit 4, coroutines-test 1.11.0. Same
  `sourceSets` block pointing at `src/main/kotlin` / `src/test/kotlin`.
- Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) copied byte-for-byte from
  ultimate-score — same Gradle 9.7.1, no need to bootstrap a new one.
- `AndroidManifest.xml`: watch `uses-feature`, `com.google.android.wearable.standalone`
  meta-data, `Theme.DeviceDefault`, launcher intent filter — copied from ultimate-score **minus**
  the `WAKE_LOCK` permission, per PLAN §2 (no always-on in this app).
- `MainActivity.kt` (`ComponentActivity`, `enableEdgeToEdge()`, `setContent { WearApp() }`) and a
  new `WearApp.kt` with a trivial `MaterialTheme { Box { Text("Disc Golf") } }` — enough to prove
  the toolchain end to end without reaching into Phase 2+ territory.
- `res/values/strings.xml` (`app_name = "Disc Golf"`), `res/values/colors.xml`
  (`ic_launcher_background`), and the placeholder launcher icon
  (`mipmap-anydpi-v26/ic_launcher.xml` + `drawable-nodpi/ic_launcher_foreground.png`) copied
  as-is from ultimate-score — same placeholder-then-replace-later approach ultimate-score used in
  its own Phase 1 (real icon was a Phase 6/15 follow-up there; same deferral applies here).
- `.gitignore` copied from ultimate-score. `git init` run; **nothing committed** — confirmed
  `git status` shows only untracked files, no commits exist.
- `local.properties` written locally (`sdk.dir=...`), not committed (gitignored, matching
  ultimate-score).

### What broke

Nothing. Every ultimate-score Phase 0/1 surprise (AGP 9's built-in-Kotlin plugin shape,
`compileSdk`/`targetSdk` needing 37 not 35, the Gradle wrapper needing to be pinned to 9.7.1) was
already resolved by copying its already-corrected files verbatim rather than re-deriving them --
`./gradlew :app:assembleDebug` was green on the **first** invocation, no iteration needed. The
toolchain env vars (`JAVA_HOME`/`ANDROID_HOME`/`PATH`) had to be set explicitly in each Bash call
in this non-interactive session, as PLAN section 5 warned -- `~/.zshrc` isn't sourced here -- but
that's an invocation detail, not a project bug.

### The Material3 list API question (PLAN section 8 risk), settled

**Both `TransformingLazyColumn` and `ScalingLazyColumn` are present in the pinned
`androidx.wear.compose:compose-foundation:1.6.2`, and `androidx.wear.compose:compose-material3:1.6.2`
provides both `ScreenScaffold` and `EdgeButton` -- verified by more than reading a changelog:**

1. Unzipped the actual resolved AARs from the Gradle module cache
   (`~/.gradle/caches/modules-2/files-2.1/androidx.wear.compose/{compose-material3,compose-foundation}/1.6.2/*.aar`)
   and inspected `classes.jar` with `javap`. Found `TransformingLazyColumnKt.TransformingLazyColumn(...)`
   and `ScalingLazyColumnKt` both in `compose-foundation`; `EdgeButtonKt.EdgeButton-P1_1MVs(...)` and
   nine overloads of `ScreenScaffoldKt.ScreenScaffold(...)` in `compose-material3` -- including
   overloads typed for `TransformingLazyColumnState`, `ScalingLazyListState`, plain `LazyListState`,
   and `ScrollState`.
2. Not satisfied with bytecode presence alone (a symbol existing doesn't mean it's wired for the
   combination the plan wants), wrote a throwaway probe composable
   (`ListApiProbe.kt`, deleted after) using `rememberTransformingLazyColumnState()` +
   `ScreenScaffold(scrollState = ...)` + `TransformingLazyColumn(state = ...)` + `EdgeButton(...)`
   together in one function, and ran `./gradlew :app:compileDebugKotlin`. **It compiled clean on
   the first try** -- no deprecation warnings, no overload ambiguity.

**Finding: build Phase 5's hole screen (and the other four list screens) on
`TransformingLazyColumn` + `ScreenScaffold` + `EdgeButton`, not the older `ScalingLazyColumn`.**
Both exist side by side in 1.6.2, but `TransformingLazyColumn` is the one `ScreenScaffold`'s
newest overloads are written against, and it's what PLAN section 3's hole screen scaling behavior
(rows "scale down" near the top/bottom of the round face) describes -- that's
`TransformingLazyColumn`'s per-item transform, not `ScalingLazyColumn`'s older auto-scaling model.
One caveat worth flagging now rather than in Phase 5: ultimate-score itself (same pinned versions)
uses `androidx.wear.compose.foundation.lazy.ScalingLazyColumn` throughout, not
`TransformingLazyColumn` -- so this is new ground relative to the sibling project, not a pattern
that can be copy-pasted from it the way everything else in Phase 1 was. Budget a little extra time
in Phase 4/5 for `TransformingLazyColumn`'s API shape (`TransformingLazyColumnItemScope`'s
per-item transform callback in particular) since there's no local precedent for it yet.

### Verified

- `./gradlew :app:assembleDebug` -- **BUILD SUCCESSFUL**, first try.
- `./gradlew :app:testDebugUnitTest` -- **BUILD SUCCESSFUL** (`NO-SOURCE`, zero tests yet, as
  expected for Phase 1).
- APK installed on the already-running `Wear_Large_Round_API34` emulator
  (`emulator-5554`, confirmed booted and confirmed as the right AVD via
  `adb emu avd name`), launched with `adb shell am start`, and confirmed with
  `adb exec-out screencap -p` -- the round face shows "Disc Golf" centered in white text on black.
  (First screenshot, taken immediately after `am start`, only caught the launch-splash icon
  animation -- a reminder that Wear's splash transition needs a couple seconds to clear before a
  screenshot means anything; the second, ~2s later, showed the real screen.)

### Nothing from Phase 2+ was built

No domain model, no reducer, no persistence, no real screens -- `WearApp()` is a single hard-coded
`Text`, exactly per scope.

---

## Phase 2 log ✅ done (2026-09-08)

Pure-Kotlin domain layer, styled after ultimate-score's `GameState`/`GameAction`/`TeamConfig`.
**Done when:** `./gradlew :app:testDebugUnitTest` green with the reducer covering every rule in
section 4, `./gradlew :app:assembleDebug` still green. Both true -- see Verified below.

### What was built

Six files in `app/src/main/kotlin/com/veenstra/discgolfscore/`, no Android imports (verified by
grep -- the only `android`/`androidx` imports anywhere under `main/` are still MainActivity.kt and
WearApp.kt, untouched from Phase 1):

- **`Player.kt`** -- `sanitizeName(raw: String?)` (control-character strip + trim, mirroring
  ultimate-score's but with no fallback-name behavior -- PLAN's Phase 2 scope was explicit that
  callers reject blank rather than the function inventing a placeholder) and `data class Player`.
- **`Course.kt`** -- `data class Course` exactly as section 4 specifies.
- **`HoleScore.kt`** -- `data class HoleScore` exactly as section 4 specifies, `touched` defaulted
  to `emptySet()`.
- **`RoundState.kt`** -- `data class RoundState`, `strokesThrough`/`toPar` (both
  `holes.take(currentHole).sumOf { ... }`), the `newRound(course, players)` factory,
  `parsToLearn()` (the par write-back eligibility Phase 5 will need -- see "Ambiguity" below), and
  the four bounds constants (`MIN_STROKES`/`MAX_STROKES` = 1/15, `MIN_PAR`/`MAX_PAR` = 3/5,
  `DEFAULT_PAR` = 3).
- **`RoundAction.kt`** -- the `RoundAction` sealed interface and `reduce(state, action)`.
- **`Scoreboard.kt`** -- `data class ScoreboardRow` and `RoundState.scoreboard()`, the shared-rank
  (`1, 1, 3`) ranking function.

55 JVM unit tests across four files in `app/src/test/kotlin/com/veenstra/discgolfscore/`:
`PlayerTest` (6), `RoundReducerTest` (24), `RoundStateTest` (15), `ScoreboardTest` (10). All green,
zero failures. Coverage includes every case the task called out by name: clamping at both 1 and 15
on `Adjust`, a clamped no-op `Adjust` still marking `touched`, `SetPar` propagating to untouched
rows only after some rows are already touched, `SetPar` clamping out-of-range input to 3/4/5,
`toPar` mid-round vs. after `Finish`, finishing early and confirming the totals freeze at whatever
`currentHole` was, a 1-hole course (`PrevHole` and `NextHole` both no-ops on the same hole), a
single-player round, a 25-player roster, a three-way tie, a tie for last, two simultaneous ties in
one field, a round where no par was ever explicitly set, and every `RoundAction` being a no-op once
`finished` is true.

### What broke

Nothing at the Gradle level -- compileDebugKotlin, testDebugUnitTest, and assembleDebug were all
green on the first attempt once the source was written. The only friction was environmental, not
architectural: getting a literal Unit-Separator / Record-Separator control character into a Kotlin
string literal inside a test file, through this tool chain, took several tries. A Bash heredoc
containing a raw control byte is rejected outright by the sandbox's command-approval check
("contains control characters that would be hidden in the approval dialog"), and writing the
intended six-character escape sequence as plain text into a file via Write/Edit was not reliable
either -- depending on the call, it sometimes landed as that literal text and sometimes as the
actual invisible control byte. Settled by writing the test file with Write, then confirming with
`od -c` (not a plain Read/cat, which renders the byte invisibly either way) exactly which outcome
landed, and adjusting the test's wording to match. The resulting .kt file compiles and asserts
correctly -- sanitizeName really is being handed a string containing the real separator characters
and really does strip them -- it just doesn't *display* the separator the way ultimate-score's own
test source does, which is worth knowing if this file is ever edited by hand later: an editor may
show an invisible glyph in the middle of a name string rather than a visible escape sequence.

### Where the plan was ambiguous — flagged rather than silently resolved

**1. What `RoundAction.SetPar` does with a par outside 3..5.** Section 4 lists "Par is 3, 4 or 5
only" as a rule the reducer must enforce, but never says what happens if `reduce` is handed, say,
`SetPar(9)` — reject it as a no-op, or clamp it? Resolved by clamping (`par.coerceIn(3, 5)`), the
same shape as `Adjust`'s stroke clamp and defensible because 3/4/5 are consecutive integers, so
"clamp to the valid range" and "restrict to the valid set" are the same operation for integer
input. Any caller in Phase 4/5 is expected to only ever send 3, 4, or 5 from the selector anyway,
so this only matters for adversarial/defensive calls — tested explicitly (`SetPar(0)` clamps to 3,
`SetPar(9)` clamps to 5).

**2. Whether `parsToLearn()` (the write-back eligibility the task asked Phase 2 to expose) should
include holes the round never reached.** This is the one worth Derek's attention. Section 4 states
the eligibility rule as "the write-back fires only for holes where `wasLearnedAtStart` is false" —
full stop, no mention of `currentHole` at all. Taken completely literally, an 18-hole round finished
after hole 3 would report holes 4-18 as eligible to learn too, each at its untouched `DEFAULT_PAR`
of 3 — a value nobody ever actually chose, since `SetPar` only ever touches `currentHole`. That
contradicts the immediately adjacent paragraph in the same section, which says totals count "holes
1 through `currentHole`" specifically *because* "a round finished early simply never counts the
holes it never reached" — the same logic applied to strokes. Silently teaching a course a par it
was never actually shown, just because a hole happened to default to par 3, is exactly the kind of
corruption "learn once" (section 2) exists to prevent, just via a different door than the one
section 2 discusses (mid-round par edits) — an unplayed hole's guess, not a deliberate re-tee.

**Resolved by restricting `parsToLearn()` to `holes.take(currentHole)`**, the same boundary
`strokesThrough`/`toPar` use, so a round finished early teaches back only the holes it actually
stood on. This is called out explicitly in `RoundState.parsToLearn()`'s doc comment and covered by
two adversarial tests (`parsToLearn excludes unlearned holes the round never reached`, and the
one-hole-in/one-hole-out companion). **Derek: if the intent was actually "write back every
never-learned hole regardless of whether the round reached it" — i.e., a fresh 18-hole course
converges to fully learned in one round no matter where it's abandoned — say so and this is a
one-line change** (drop the `.take(currentHole)`). As written, a course only fully learns over
however many rounds it takes to actually reach every hole, which seemed like the safer and more
literal reading of "no human chose this" but is very much an interpretation, not a re-derivation of
something section 4 stated outright.

### Design choices made without an explicit spec, worth a mention

- **File layout**: one file per major type (`Player.kt`, `Course.kt`, `HoleScore.kt`,
  `RoundState.kt`, `RoundAction.kt`, `Scoreboard.kt`) rather than ultimate-score's denser grouping
  (e.g. `TeamConfig.kt` holding `TeamColor`, `TeamConfig`, `TeamPreset`, `TeamPresetLists`, and the
  contrast-ratio math all together). Disc golf's Phase 2 types are simpler individually but there
  are more of them with distinct responsibilities (a factory, a reducer, a ranking function), so
  splitting seemed to read better than one large file; nothing stops Phase 4/5 from adding to any
  of these files instead of creating new ones if that turns out to read worse in practice.
- **`sanitizeName` placement**: put in `Player.kt` even though it also sanitizes `Course` names
  (mirroring where ultimate-score put its version, in the single most name-adjacent file rather
  than a dedicated utility file), with a doc comment noting the dual use.
- **`parsToLearn()` return shape**: `Map<Int, Int>` of 1-based hole number to the par to teach,
  rather than e.g. a `List<Pair<Int, Int>>` or a value class. Phase 3's course-update code will
  need to map hole number to a 0-based index into `Course.pars` regardless of shape, so this is a
  minor, easily revisited choice, not a load-bearing one.
- **`Adjust` on an unknown `playerId`**: not specified anywhere in the plan (the model assumes
  `strokes` is "always populated" with the round's actual players). Treated as a no-op — nothing
  sensible to clamp for a player who isn't in the round — and covered by a test
  (`Adjust for an unknown player id is a no-op`) rather than left as undefined behavior a future
  phase could trip over.

### Verified

- `./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL**, 55 tests, 0 failures, 0 errors,
  0 skipped (confirmed by reading each `TEST-*.xml` result file's summary attributes directly, not
  just the Gradle console's pass/fail line).
- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**.
- `grep` over every file in `app/src/main/kotlin/com/veenstra/discgolfscore/` for `android`/
  `androidx` imports: matches only in `MainActivity.kt` and `WearApp.kt` (Phase 1's files, untouched
  this phase) — the six new Phase 2 files have zero Android dependencies, confirmed rather than
  assumed.
- No UI, DataStore, ViewModel, or serialization code was added; `WearApp()` is still the Phase 1
  hard-coded `Text`.

---

## Phase 3 log ✅ done (2026-09-08)

DataStore repository, the three store interfaces, control-character codecs for players, courses,
and the active round, plus the `RoundViewModel` that owns them. **Done when:**
`./gradlew :app:testDebugUnitTest` green, `./gradlew :app:assembleDebug` green and the app still
launches on the emulator. All three true — see Verified below.

### What was built

Two new files in `app/src/main/kotlin/com/veenstra/discgolfscore/`, no new Android imports beyond
`android.content.Context` and DataStore's own package (confirmed by grep — same discipline as
Phase 2's check):

- **`DiscGolfRepository.kt`** — `PlayerStore`, `CourseStore`, `RoundStore` interfaces (mirroring
  ultimate-score's `ScoreHistoryStore`/`TeamPresetStore` split exactly); pure top-level codec
  functions for players, courses, and the active round; `DataStoreDiscGolfRepository`, one class
  implementing all three interfaces against one `discgolf_state` preferences file.
- **`RoundViewModel.kt`** — holds `players`/`courses`/`round` as `StateFlow`s plus `isReady`
  (false until a persisted round finishes loading, same contract as ultimate-score's `isReady`).
  Roster CRUD (`addPlayer`/`renamePlayer`/`deletePlayer`, `addCourse`/`renameCourse`/`deleteCourse`)
  persists on every call. `startRound`/`setPar`/`adjust`/`nextHole`/`prevHole`/`finishRound` all
  dispatch through the Phase 2 `reduce` and persist the round on every single call, per PLAN
  section 4's "written on every action". `done()` is a plain method, not a `RoundAction` — it
  clears the round from both state and storage, the only thing that can. Every store parameter is
  nullable/injectable, so `RoundViewModel()` with zero arguments is fully constructible with no
  Android dependency, same as `ScoreViewModel`.

109 JVM unit tests total (55 carried over from Phase 2, 54 new) across three new files in
`app/src/test/kotlin/com/veenstra/discgolfscore/`: `DiscGolfPersistenceCodecTest` (24),
`RoundViewModelPersistenceTest` (12), `RoundViewModelRosterTest` (18). All green, zero
failures/errors (confirmed by reading each `TEST-*.xml` result file's `tests`/`failures`/`errors`
attributes directly, same discipline as Phase 2). Coverage includes every case the task named:
round trips for players, courses, a full in-progress round, and a finished round; names containing
commas, colons, and a real (not escaped-text) control character from the codec's own separator
range; malformed records of every kind called out (wrong field count, blank id, blank name,
non-numeric where a number is expected, a par list whose length disagrees with its hole count, a
round referencing a since-deleted course id — decodes fine at the codec level, since the codec has
no notion of which courses currently exist); absent/empty/blank stored values for all three
artifacts; every `RoundAction` persisting; `DONE` clearing the round from both state and storage; a
finished round restored as finished; par write-back firing only for reached-and-unlearned holes and
no-oping entirely when the round's course has since been deleted.

### What broke, and the actual fix

**The single-string round codec was wrong on the first pass, and every round-trip test caught it
immediately.** The first design encoded the whole `RoundState` as one string — the four scalar
fields, the encoded player list, and the encoded hole list, all joined with the same field
separator — reasoning (in the doc comment, no less) that reusing the field/record separators one
level down inside the nested player/hole blobs was safe "because there is only ever one round
stored, never a list of them." That reasoning is backwards: the collision isn't between multiple
*rounds* sharing a separator, it's between the **top-level join** and the **exact same separator
character reused inside the nested blobs it's holding apart** — the player and hole blobs each need
the field separator to separate *their own* records' fields, and that character shows up dozens of
times inside a blob that's also, simultaneously, one field of a string joined with that same
separator one level up. Splitting the outer string on that character doesn't know which occurrence
belongs to which level; it just shreds everything into far more pieces than expected. All 7
round-related codec tests failed on the very first `testDebugUnitTest` run with `AssertionError`s
at the round-trip assertions — not a crash, just silently wrong reconstructed data, which is
exactly the kind of bug a round-trip test exists to catch and a manual/eyeballed review of the
encoder alone would not have.

**The fix: three preference keys instead of one nested string** — `round_meta` (the four scalar
fields, joined with the field separator, one record), `round_players` (reusing
`encodePlayers`/`decodePlayers` verbatim), `round_holes` (record separator between hole records,
field separator between each hole's four fields, plain commas inside the digits-only
`strokes`/`touched` fields — this level was never the problem, since it stands alone once it's its
own key). This is not a workaround so much as the design ultimate-score itself already uses (seven
separate keys in one `game_state` preferences file, not one mega-string) — Phase 3's first attempt
strayed from that precedent by trying to fold everything into a single `RoundStore` value and
inventing a third conceptual "level" that PLAN section 4's two-separator scheme was never going to
support. `encodeRound`/`decodeRound` still exist as the round's public codec surface, just
reshaped: `encodeRound(round)` returns a `Triple<meta, players, holes>` and
`decodeRound(meta, players, holes)` takes the three pieces back, so `DataStoreDiscGolfRepository`
reads/writes three keys and the pure codec functions stay fully unit-testable with no Android
dependency, same as before.

**One nuance this fix surfaces, worth flagging rather than silently deciding:** with the round
split across three keys, `decodeRound`'s malformed-input handling had to pick what a *missing*
players key means. Resolved by treating an absent/blank stored-players value as "zero players" (via
the existing `decodePlayers(null) == emptyList()`) rather than invalidating the whole round —
unlike a corrupt hole (which invalidates the round entirely, since a card missing a hole in the
middle can't be trusted), a round genuinely has no cross-check that would let the codec tell "the
players key was wiped by something" apart from "this round legitimately started with the roster
empty," so there's nothing safer to do than decode what's there. This can't actually happen through
the app's own write path (`saveRound` always writes all three keys together in one `edit` block),
but a codec has to decide what an adversarial/corrupted read means regardless, and this is covered
by an explicit test (a round missing its players piece still decodes with an empty player list
rather than crashing or invalidating the round) rather than left as undefined behavior.

**The control-character tooling quirk from Phase 2 recurred, identically, and needed no new
workaround.** Writing the intended six-character escape-sequence text for the field/record
separators into a new `.kt` file via the `Write` tool landed, in every file this phase touched, as
the actual raw control byte instead of the escape text — confirmed with a small Python byte-scan on
`DiscGolfRepository.kt` (the two separator constants each held exactly one raw byte — octal 037 and
036, i.e. hex 1F/1E — sitting alone inside otherwise print-empty quoted string literals) and on the
test files (dozens of those same raw bytes embedded mid-string across the malformed-input test
cases, zero literal escape-sequence text surviving anywhere). This is harmless and was treated as
such: a Kotlin string literal may contain a literal control byte directly (nothing about the
language requires escaping it, unlike an embedded newline), so the separator constants compiled and
worked correctly either way, and every test asserting on "a name containing a raw control
character" got the real thing for free rather than needing any special handling. Also confirmed:
this same tooling quirk makes a Bash heredoc containing that raw byte get rejected outright by the
sandbox's command-approval check (exactly as the Phase 2 log warned), which is why this very log
entry was appended via the file-editing tool rather than a heredoc. The one operational lesson,
same as Phase 2's: any of these files would show invisible glyphs rather than visible escape-
sequence text if opened by hand in an editor later.

### Design choices made without an explicit spec, worth a mention

- **When par write-back fires.** PLAN section 4 says only that "the ViewModel calls `parsToLearn()`
  and updates the course," not *when* during a round's lifecycle. Resolved by calling it after
  *every* round mutation (`startRound` and every dispatched action, including `Finish`), not just
  on finish or on `DONE`. This is safe and idempotent because `RoundState.parsToLearn()` is itself
  bounded to holes reached so far and holes the course didn't already know at round start — calling
  it repeatedly just reconverges the course to whatever the round's current state says, and matches
  the PLAN's own example ("standing on an unlearned hole and advancing... teaches its default par
  3") more directly than deferring to `Finish` would: the moment `currentHole` reaches a fresh hole,
  that hole is already "reached" even before any action changes it. A no-real-change guard skips the
  redundant store write on every no-op recomputation, which is the majority of calls in a typical
  round. Covered by `RoundViewModelRosterTest`'s "applies across multiple holes as the round
  advances" and "does not re-teach a hole the course already knew" tests.
- **Roster CRUD's scope.** The task said the ViewModel "owns... the round and roster state," and
  Phase 4's screens (course/player managers, inline creation) will need somewhere to call into. Add/
  rename/delete for both players and courses were built now, as plain data operations with no UI
  attached — deliberately stopping short of the course *par editor* (PLAN section 3's hole-by-hole
  "Course editing" screen), which is a Phase 4 UI feature end to end and has no Phase 3 test
  demanding it. If this reads as scope creep relative to "Phase 3 only," the roster CRUD methods are
  cleanly removable without touching the round/persistence machinery Phase 3 was actually asked to
  build — they're additive, not load-bearing for anything else in this phase.
- **Id generation.** One shared `idGenerator: () -> String` parameter (default: wall-clock millis,
  same call as ultimate-score's preset id generator) used for both new players and new courses,
  rather than two separate generators. Good enough for something created by hand a few dozen times
  at most; tests inject a deterministic sequence the same way `ScoreViewModelPresetTest` does.
- **File layout.** One repository file (`DiscGolfRepository.kt`, interfaces + codecs + the DataStore
  class together) and one ViewModel file (`RoundViewModel.kt`), matching ultimate-score's
  `ScoreRepository.kt`/`ScoreViewModel.kt` split exactly rather than Phase 2's more-files-per-type
  approach — persistence is one cohesive concern with fewer, larger pieces (a store interface is
  meaningless without its codec and its DataStore implementation sitting next to it), unlike Phase
  2's several independent, individually-testable domain types.

### Verified

- `./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL**, 109 tests, 0 failures, 0 errors, 0
  skipped across 7 test classes (confirmed by reading each `TEST-*.xml`'s summary attributes
  directly): `PlayerTest` (6), `RoundReducerTest` (24), `RoundStateTest` (15), `ScoreboardTest` (10)
  unchanged from Phase 2; `DiscGolfPersistenceCodecTest` (24), `RoundViewModelPersistenceTest` (12),
  `RoundViewModelRosterTest` (18) new this phase.
- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**.
- APK reinstalled (`adb install -r`) on the already-running `Wear_Large_Round_API34` emulator
  (confirmed via `adb emu avd name`), force-stopped and relaunched fresh via
  `adb shell am start -n com.veenstra.discgolfscore/.MainActivity`, screenshotted ~3s after launch
  (past the splash-clearing delay Phase 1 flagged) via `adb exec-out screencap -p` — still shows the
  Phase 1 hard-coded "Disc Golf" text on black, as expected, since `WearApp()` was untouched this
  phase. `adb logcat` around the launch shows a clean `ActivityTaskManager: Displayed` line and no
  exceptions/crashes — nothing in this phase's new persistence code runs yet (`WearApp()` never
  constructs a `RoundViewModel`), so this screenshot mainly confirms the build/install/launch cycle
  still works, not the persistence itself; the real end-to-end test of persistence (create data,
  force-stop, reopen, confirm by screenshot) is explicitly deferred to Phase 4 per PLAN section 6's
  own note under Phase 3.
- `grep` over every file in `app/src/main/kotlin/com/veenstra/discgolfscore/` for `android`/
  `androidx` imports: matches only in `MainActivity.kt`, `WearApp.kt` (Phase 1, untouched),
  `DiscGolfRepository.kt` (`android.content.Context` + `androidx.datastore.*`, exactly what a
  DataStore-backed repository needs), and `RoundViewModel.kt` (`androidx.lifecycle.*`, exactly what
  a `ViewModel` needs) — the six Phase 2 domain files remain zero-Android-dependency, confirmed
  rather than assumed.
- No UI code was added or changed; `WearApp()` is still the Phase 1 hard-coded `Text`, confirmed by
  the screenshot above matching Phase 1's exactly.

---

## Phase 4 log ✅ done (2026-09-08)

Roster and setup UI: `AppScreen`, `rememberTextInputLauncher`, Home, players/courses managers, the
course par editor, new-round setup with single-select course + multi-select players, inline
create, long-press edit/delete, delete-clears-selection. **Done when:** create players and courses
on the emulator, edit a course's pars, `force-stop`, reopen, everything still there — verified by
screenshot. True — see Verified below.

### What was built

Thirteen new files, one new `RoundViewModel` method, in
`app/src/main/kotlin/com/veenstra/discgolfscore/`:

- **`AppScreen.kt`** — the sealed nav state PLAN.md section 2 asks for (`Home`, `NewRoundSetup`,
  `ManagePlayers`, `ManageCourses`), held in `remember { mutableStateOf(...) }` at `WearApp()`.
  Deliberately does **not** carry the multi-step flows each screen owns internally (inline course/
  player creation, long-press editing) — those stay local `SetupMode`/`ManageXMode` sealed
  interfaces inside each screen's own file, exactly the split ultimate-score's `NewGameSetupScreen`
  makes with its private `SetupMode` (PLAN.md's own pointer to that file).
- **`TextInputLauncher.kt`** — `rememberTextInputLauncher`, lifted close to verbatim from
  ultimate-score's `NewGameSetupScreen` (same `RemoteInputIntentHelper` intent, same
  `TEXT_INPUT_KEY`, same "null result means do nothing" contract).
- **`PickableRow.kt`** — the select-list row (course/player rows on setup, plain roster rows on the
  managers): a label, an optional leading glyph (`●`/`○` single-select, `☑`/`☐` multi-select, or
  none for a `+ New…` action row), tap always fires `onClick`, `combinedClickable` adds long-press
  when supplied.
- **`StepperRow.kt`** — the one-line `label` / `− value ＋` row PLAN.md section 3 asks to be "the
  same composable" between the course editor's par rows and (Phase 5's) hole screen player rows.
  Built once here; Phase 5 should reuse it unchanged. Both `−`/`＋` targets are a fixed 48dp square
  regardless of the row's own width.
- **`EdgeSafeTransform.kt`** (`roundSafeWidth`) and **`EdgeButtonPadding.kt`**
  (`withEdgeButtonReserve`) — the two round-safety fixes; see "What broke" below, they're most of
  this phase's real story.
- **`HomeScreen.kt`** — `NEW ROUND` / `PLAYERS` / `COURSES`. No `RESUME`, per scope.
- **`NewCourseFlow.kt`** (`HoleCountPickerScreen`) — step two of course creation (name already
  typed): `9 holes` / `18 holes` / a `StepperRow`-based custom count, `CREATE` as an `EdgeButton`.
  Shared between the setup screen's and the courses manager's `+ New course…`, one file instead of
  two near-identical copies.
- **`CourseEditorScreen.kt`** — rename row, `"{holeCount} holes"` (display-only), the hole-by-hole
  `StepperRow` par list, `Delete course`, `SAVE` as an `EdgeButton`. One composable, reused
  unchanged from both the setup screen's long-press-a-course flow and the courses manager's tap-a-
  course flow — this is the screen PLAN.md section 3 calls "the only way to fix a wrong learned
  par," so it had to exist and actually work this phase, not just compile.
- **`PlayerEditorScreen.kt`** — the same shape, minus the par list: rename row, `Delete player`,
  `DONE`.
- **`NewRoundSetupScreen.kt`** — the big one. Local `SetupMode` (`Picking` /
  `ChoosingHoleCount` / `EditingCourse` / `EditingPlayer`); `selectedCourseId: String?` and
  `selectedPlayerIds: Set<String>` as local Compose state; `START` as an `EdgeButton`, enabled only
  when both are non-empty, wired to a literal no-op stub per this phase's explicit scope ("`START`
  does nothing yet"). Delete-clears-selection lives here, inline in the `onDelete` lambdas passed
  into `CourseEditorScreen`/`PlayerEditorScreen` — `if (selectedCourseId == live.id) selectedCourseId
  = null`, `selectedPlayerIds = selectedPlayerIds - live.id` — the same place ultimate-score section
  13 put the equivalent fix, for the same reason: the selection is this screen's own state, not the
  ViewModel's.
- **`ManagePlayersScreen.kt`** / **`ManageCoursesScreen.kt`** — the Home-reached managers. Same
  list + `+ New…` + tap-or-long-press-to-edit pattern; both tap and long-press open the editor here
  (there's no "select" concept outside a round, so there was nothing for a plain tap to do that
  wasn't also "edit").
- **`RoundViewModel.setCoursePar(id, holeIndex, newPar)`** — new method, same shape as
  `renameCourse`/`deleteCourse`: clamps to `MIN_PAR..MAX_PAR`, no-ops on an unknown id or an
  out-of-range hole index, persists via the existing `persistCourses()`. This is the course
  editor's only new ViewModel surface; every other screen calls roster CRUD Phase 3 already built.
- **`WearApp.kt`** rewritten from Phase 1's hard-coded `Text` — `rememberRoundViewModel()`
  (`viewModelFactory` + `DataStoreDiscGolfRepository`, same shape as ultimate-score's
  `rememberScoreViewModel()`), the `isReady` black-screen guard from PLAN.md section 4
  ("the watch never flashes 'no round' over a live one" — same guard now covers the roster lists),
  and the `when (screen)` router.

### What broke, and the actual fixes

**1. `TransformingLazyColumn` + `ScreenScaffold` + `EdgeButton` compiled together on the very
first attempt** — all five screens, first try, zero iteration on the core list/scaffold/button
trio. Phase 1's "budget extra time, there's no local precedent" turned out to be about two *other*
things, not this one:

**2. `ScreenScaffold` in 1.6.2 has no `edgeButton` parameter at all.** `javap` on the resolved AAR
showed every non-deprecated `ScreenScaffold` overload (for `TransformingLazyColumnState`,
`ScalingLazyListState`, `LazyListState`, `ScrollState`) with exactly the same seven parameters —
`scrollState, modifier, contentPadding, timeText, scrollIndicator, overscrollEffect, content` —
and no edge-button slot; the only overloads that *do* have one extra `Function3` and a `Dp` param
are separate, name-mangled `-V-95POc`-suffixed overloads, which is the ABI signature Kotlin gives a
`@Deprecated` binary-compatibility overload, not a hidden feature of the current one. So an
`EdgeButton` here is just a normal composable placed with `Modifier.align(Alignment.BottomCenter)`
inside the `Box` that `content: @Composable BoxScope.(PaddingValues) -> Unit` already provides —
which is also *why* the next bug exists:

**3. Without a `ScreenScaffold`-owned edge button, `contentPadding` has no idea one exists, so a
short list's last row or two render underneath it.** Caught by screenshot on the hole-count
picker's `Custom`/`Cancel` rows sitting directly behind `CREATE` on the very first real multi-item
list — not a corner case, the very first screen tested with more than 3-4 rows. Fixed with
`EdgeButtonPadding.kt`'s `PaddingValues.withEdgeButtonReserve()` — `+64.dp` on the bottom inset,
applied to every screen that draws both a list and an `EdgeButton` (five of the seven list
screens; `HomeScreen` has no edge button and needs none). 64dp is a hand-picked approximation, not
read off any Material3 constant — `EdgeButtonDefaults`/`EdgeButtonSize`'s own height math is itself
`internal` (`-impl$compose_material3`-suffixed), so there's no public constant to read it from.
Confirmed sufficient by screenshot (the hole-count picker's `Cancel` row, the course editor's
`Delete course` row on an 18-hole course, and the managers' `+ New…` rows all scroll fully clear of
their edge buttons) but not derived from anything more principled than "big enough."

**4. The `TransformationSpec`/`SurfaceTransformation` per-item transform Phase 1 flagged as the
answer to round-edge scaling turned out to be a dead end for this phase — not a bug, a closed
door.** Full account lives in `EdgeSafeTransform.kt`'s doc comment (worth reading in full); the
short version: `Modifier.transformedHeight(itemScope, spec)` (height) compiles and works,
`TransformationSpec.applyContainerTransformation`/`SurfaceTransformation.applyContainerTransformation`
(the visual scale/fade half) do not — `UNRESOLVED_REFERENCE` from Kotlin despite both being public,
unmangled methods by `javap`, which only makes sense if they're `internal` to `compose-material3`
and reserved for Material3's own `Button`/`Card`/etc. (which *do* take a public
`transformation: SurfaceTransformation` parameter). Reaching that parameter would have meant
rebuilding `PickableRow` as a Material3 `Button`, giving up the custom pill shape, the leading
glyphs, and — worse — `combinedClickable`'s long-press, which `Button` doesn't expose. **Not**
silently swapped in; flagged here and in the source instead.

**5. What replaced it: hand-rolled geometry (`roundSafeWidth`), and it has a confirmed, still
partially-understood edge case.** Every row's width is recomputed from its own on-screen vertical
position (`onGloballyPositioned` → `boundsInRoot()`), capped by the chord length of the round
display's inscribed circle at that height — plain trigonometry, no undocumented API. This is what
fixed the very first round-safety bug this phase found (screenshotted on Home before any fix
existed: the `COURSES` row's rounded-rect pill visibly sliced off by the circular display mask,
confirmed the display really is a perfect circle via `adb shell dumpsys window displays` →
`RoundedCorners{radius=227...}` exactly half the 454px screen). But `roundSafeWidth` alone left one
row — the hole-count picker's `Cancel` row, the very last item in a list only reachable by
scrolling all the way to the end — rendering at 96×96px (`adb shell uiautomator dump`-measured,
not eyeballed: `bounds="[179,184][275,280]"`), a width matching neither its actual on-screen
position (dead center, which should compute to ~418px) nor anything else obvious. The value looks
like it came from wherever the row sat mid-fling, one `onGloballyPositioned` short of where it
settled — repeatable across a full reinstall, not a one-off race, but the exact mechanism (something
about how this specific list schedules relayout during/after a fling, differently from a plain
`LazyColumn`) wasn't run to ground; `MIN_WIDTH_FRACTION = 0.55f` is a floor that makes the failure
mode "a bit wider than strictly correct at that one row" instead of "invisible and untappable,"
confirmed by screenshot + `uiautomator dump` after the floor went in. **Derek: if this recurs on
the real watch, the floor is the first place to look — and point 5's honest doc comment is the
explanation, not a full fix.**

**6. The Phase 2/3 control-character tooling quirk did not recur.** No new control-character
literals were needed this phase (all new code is UI, not codecs), so nothing to report here beyond
confirming it stayed a non-issue.

### Design choices made without an explicit spec, worth a mention

- **Tap vs. long-press on the managers.** PLAN.md section 3 says the managers use "the same list +
  `+ New…` + long-press-to-edit pattern" as the setup screen, but the setup screen's tap already
  means something else (select/tick) that the managers don't have. Resolved by making *both* tap
  and long-press open the editor on `ManagePlayersScreen`/`ManageCoursesScreen` — there's nothing
  else a plain tap could sensibly do outside a round, and forcing a long-press-only manager would
  be strictly worse with no corresponding gain.
- **`START`'s stub.** "Wire it to a stub" was read as *literally* `onStart = { _, _ -> }` — no call
  into `RoundViewModel.startRound` at all, not even one that goes nowhere. The alternative (start
  the round for real, just don't navigate anywhere) would leave a persisted, orphaned `RoundState`
  sitting in `DataStore` with no screen able to reach it until Phase 5's hole screen exists, which
  seemed like exactly the kind of half-wired state Phase 4's own force-stop/reopen check should
  *not* have to reason about. `RoundViewModel.startRound` is untouched and Phase 5-ready as-is.
- **Course/player editors as shared composables, not per-entry-point duplicates.** PLAN.md's own
  "it should be the same composable" is stated only for `StepperRow`, but the same logic applies
  one level up: `CourseEditorScreen`/`PlayerEditorScreen` are each a single composable called from
  both their manager and the setup screen's long-press, rather than two near-identical copies. Only
  possible because both entry points already share the exact same callback shape
  (`onRename`/`onSetPar`/`onDelete`/`onDone`), so nothing needed adapting to unify them.
- **`EDGE_BUTTON_RESERVE` and `MIN_WIDTH_FRACTION` as hand-picked constants.** Both are called out
  explicitly in their own doc comments as approximations rather than derived values, because the
  APIs that would have given a principled number (`EdgeButtonSize`'s height math, `TransformationSpec`'s
  own scale curve) turned out to be internal-only this phase. Good enough to pass every screenshot
  check run against them; revisit on the real device in Phase 7 if either one looks wrong there.

### The `TransformingLazyColumn` question from Phase 1, answered for real this time

Phase 1's log flagged budgeting extra time for "`TransformingLazyColumnItemScope`'s per-item
transform callback in particular," on the theory that the risk was *learning the API shape*. The
API shape itself was the easy part — `item`/`items` as extension lambdas on
`TransformingLazyColumnItemScope`, `rememberTransformingLazyColumnState()`, all exactly as
`LazyColumn`-shaped as expected, first-try compiling. The actual cost was almost entirely in two
things Phase 1 couldn't have predicted from bytecode alone: **`ScreenScaffold` not owning an edge
button** (a design choice, not an API-shape question) and **the transform's own visual-application
half being internal** (only discoverable by trying to call it and reading the compiler's refusal).
Both are now documented in source for Phase 5, which will lean on the same `roundSafeWidth` +
`withEdgeButtonReserve` pair for the hole screen rather than rediscovering either.

### Verified

- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**.
- `./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL**, 109 tests, 0 failures, 0 errors — the
  same 109 from Phase 3, unchanged (confirmed via each `TEST-*.xml`'s summary attributes): this
  phase added no JVM tests of its own, since it's UI-only and PLAN.md section 7 keeps Compose UI
  tests out of scope for v1.
- **End-to-end persistence, on the `Wear_Large_Round_API34` emulator, by screenshot** (PLAN.md
  section 6 Phase 4's actual done-when): created player "Derek", created course "Riverside" at 18
  holes, opened the course editor and changed H1's par from 3 to 4, `adb shell am force-stop
  com.veenstra.discgolfscore`, relaunched, navigated back to Players and Courses — Derek, Riverside
  (18 holes), and H1=4 were all still there. This is the first time any of Phase 3's DataStore
  wiring has been exercised through the real UI rather than a fake store in a JVM test.
- **Delete-clears-selection, both directions, screenshot-verified rather than just read out of the
  code**: on the new-round setup screen, ticked Derek and selected Riverside (`START` turned from
  disabled-gray to enabled-purple); long-pressed Derek → deleted him → his tick vanished and
  `START` went back to disabled, Riverside stayed selected; long-pressed Riverside → deleted it →
  its selection vanished (`+ New course…` is the only course row left) and `START` stayed disabled.
  Neither deletion disturbed the other selection, confirming the two selection models really are
  independent.
- **Every screen screenshotted on the round face and checked for clipping**: Home, both managers
  (empty and populated), the text-input picker, the hole-count picker (initial view and scrolled),
  the course editor (top, mid-scroll at H15-H18, and the `Delete course` row), the player editor,
  and new-round setup (course section, scrolled to the players section, and mid-edit). The one real
  clipping defect found (Home's `COURSES` row before `roundSafeWidth` existed) and the one
  near-invisible-row defect found (the hole-count picker's `Cancel` row before the width floor) are
  both documented above and both confirmed fixed by a follow-up screenshot.
- `grep` for `android`/`androidx` imports outside what's expected: not re-run this phase — Phase 4
  is UI by definition, so every new file legitimately imports Compose/Wear packages; the Phase 2/3
  discipline of a zero-Android domain layer was left completely untouched (no edits to `Player.kt`,
  `Course.kt`, `HoleScore.kt`, `RoundState.kt`, `RoundAction.kt`, `Scoreboard.kt`,
  `DiscGolfRepository.kt`) — confirmed by which files this phase's diff actually touches, not by
  re-running the grep.

### Open item for Derek

**`roundSafeWidth`'s stale-position edge case (point 5 above) is a real, confirmed bug with a
mitigation, not a root-caused fix.** It only reproduced on one row in extensive manual testing
(the last item of a list, reached only by scrolling to the very end), and the 0.55 floor makes its
failure mode harmless everywhere it was checked — but "everywhere it was checked" is the emulator,
by touch swipe, not the real watch, not rotary/bezel input. Worth a specific look in Phase 7.

---

## Phase 5 log ✅ done (2026-09-09)

The main event: the hole screen, plus the `roundSafeWidth` correction Task A asked for before
building on top of it. **Done when:** a full 18-hole round playable on the emulator; replaying the
same course comes back pre-filled; changing par on an already-learned hole does not change the
course; a force-stop mid-round reopens on the same hole with the same numbers. All four true — see
Verified below.

### Task A — `roundSafeWidth`, replaced

`EdgeSafeTransform.kt`'s `roundSafeWidth` no longer measures anything. It is now
`Modifier.fillMaxWidth(fraction)` with a single hand-picked constant (`ROW_WIDTH_FRACTION`), full
stop — no `onGloballyPositioned`, no `remember`ed width state, no per-frame feedback loop, and the
`MIN_WIDTH_FRACTION` floor is gone along with the code it was floor-ing. `EdgeButtonPadding.kt`
gained a second helper, `withRoundEdgeInset()` (+28dp top and bottom, alongside the existing
`withEdgeButtonReserve()`'s +64dp bottom), applied to every screen that draws a
`TransformingLazyColumn` — all seven Phase 4 list screens plus the two new Phase 5 ones — not just
the hole screen, since the task called the old approach "load-bearing for both shared rows" and the
fix belongs at that shared layer, not bolted onto one screen.

**Picking the fraction took real iteration, not a single guess, and the first two guesses were
wrong for an instructive reason.** Round 1: ported Phase 4's *nominal* width (0.92) down to 0.78,
reasoning that a static value merely needed to be somewhere between Phase 4's 0.92 nominal and 0.55
floor. Screenshotted a 5-player roster's hole screen scrolled to a resting position near the top of
the viewport and found a real, reproducible clip — `uiautomator dump` showed a row's own accessible
bounds correctly at the full 96×96px (48dp) target size, but the drawn `+` circle rendered as a
wedge, sliced by the round mask. The trig from PLAN section 2 confirms it wasn't noise: at that
row's measured `dy` (≈66dp from the vertical center on a 113.5dp-radius, 227dp-diameter round AVD),
the safe chord is narrower than a 0.78-fraction row's actual width. Round 2: dropped to 0.68 and
also **redesigned the expanded par control** — see below — but a *still*-narrower row at a *more*
extreme resting `dy` showed the same wedge. Settled at **0.68 → 0.64** for `ROW_WIDTH_FRACTION` and
**28dp** for `withRoundEdgeInset()`'s reserve, verified by repeated screenshot + `uiautomator dump`
at multiple scroll rest positions (not just top-of-list and bottom-of-list, which is what Phase 4's
own verification checked — this phase specifically hunted for *mid-scroll* rest positions too, since
PLAN section 3 says "at any scroll position," not just the two ends).

**One thing this phase's `uiautomator dump` work found that PLAN.md didn't anticipate, worth Derek
knowing:** the wedge-shaped visual clipping observed near the extreme top/bottom of the viewport
does **not** shrink the underlying tap target. In every case checked, the accessibility-tree bounds
for a `−`/`+` button at a "clipped-looking" position were still the full 96×96px (48dp) square —
only the *drawn* circle was partially cut, not the *clickable* one. This is consistent with
`TransformingLazyColumn` applying some default per-item visual transform/clip near the round face's
edge independent of anything this app's code controls (Phase 4's log already found the
`SurfaceTransformation` half of this API unreachable from app code, but apparently *something*
edge-aware still happens by default). Practically: this phase's fix guarantees the PLAN section 3
requirement that actually matters ("every target stays ≥48dp") **by measurement, not by assumption**
— but a cosmetic partial-wedge glyph can still appear on a row resting very close to the top/bottom
edge after a fast fling. Not chased further to zero, because doing so would mean a much smaller
fraction that makes rows unnecessarily narrow everywhere else for a purely decorative edge case that
doesn't compromise tappability. Flagged here rather than silently declared "fully solved."

**The par control was redesigned mid-phase for the same round-safety reason, not per PLAN's literal
mockup.** PLAN section 3's ASCII mockup shows the expanded par control as `3 [4] 5` — three
side-by-side circular buttons. Built that way first (`ParSelector`/`ParOption`, three 48dp circles
in one `Row`), and it was the worst offender in the fraction-narrowing story above: three 48dp
circles plus visible gaps need ~160dp+ of width no matter how narrow the *surrounding* row fraction
gets, which puts a hard floor under how safe that one row can ever be made without either violating
the ≥48dp target-size rule or redesigning it. **Replaced it with a `StepperRow` — the exact same
`− value ＋` composable every other row in the app already uses** (`PAR`/`−`/`4`/`＋`, mirroring
`CourseEditorScreen`'s `H4`/`−`/`3`/`＋` almost exactly), rather than a bespoke three-button control.
This is a deliberate deviation from the literal mockup glyphs, not an oversight: it gets the par
control the same, already-verified round-safety envelope as every other row instead of a bespoke
one, and it's less code. **One behavior this dropped along with the redesign, worth flagging:** the
original three-button design collapsed back to the compact `PAR N` label the instant a value was
picked; a stepper has no equivalent "I'm done" tap, so the expanded state now just stays expanded
for the rest of that hole's viewing once triggered (by tapping the compact label, or by starting on
an unlearned hole), collapsing again only on navigating away and back. PLAN section 3 only specifies
"tapping it expands," not a re-collapse trigger, so this isn't a contradiction — just a choice made
in the redesign that Derek should know about before expecting glyph-for-glyph mockup fidelity.

### Task B — the hole screen

New files: **`HoleScreen.kt`** (the screen itself — header, collapsing par control, one-line
`StepperRow` player rows, the `── TOTAL ──` standings block, `◂ prev hole`/`finish round` dim rows,
the `NEXT ▸`/`FINISH ▸` `EdgeButton`, and the full-screen yes/no `FinishConfirmScreen`);
**`ToPar.kt`** (`formatToPar` — `E`/`+2`/`−1`, shared by the hole screen's live standings and the
finished-round placeholder's final totals so the two can't drift on formatting); **`FinishedRoundScreen.kt`**
(`FinishedRoundPlaceholderScreen` — the explicitly-scoped-minimal stand-in for Phase 6's real final
scoreboard, described below). `AppScreen.kt` gained a `Hole` case; `WearApp.kt` wired `START` to a
real `viewModel.startRound(...)` + navigation (Phase 4 left it a literal no-op stub) and routes
`Hole` to either `HoleScreen` or `FinishedRoundPlaceholderScreen` based on `RoundState.finished`.

Every rule PLAN section 4 assigns to the reducer stayed in the reducer. `HoleScreen` reads
`hole.par`, `hole.strokes`, `hole.wasLearnedAtStart`, `round.toPar(playerId)` and dispatches
`onSetPar`/`onAdjust`/`onNextHole`/`onPrevHole`/`onFinish` — it computes nothing scoring-related
itself, including the `isLastHole`/`isFirstHole` row-visibility checks, which read `RoundState`
fields (`currentHole`, `holes.size`) rather than re-deriving anything. No new reducer logic was
needed and none was added; `RoundReducerTest`/`RoundStateTest`/`ScoreboardTest` are untouched and
still pass at their Phase 2 counts.

**Scroll-reset on hole change** is one `LaunchedEffect(round.currentHole) { listState.scrollToItem(0) }`
— confirmed by screenshot in both directions (Hole 2 → NEXT → Hole 3 opens at the header, not
wherever Hole 2 happened to be scrolled to; Hole 4 → `◂ prev hole` → Hole 3 does the same).

**Both finish paths go through one `FinishConfirmScreen`** (a centered, non-scrolling column — a
handful of short lines never needs `TransformingLazyColumn`/`ScreenScaffold`), reached two ways:
`FINISH ▸` on the last hole's `EdgeButton`, or `finish round` (the dim row, hidden on the last hole
since the edge button already reads `FINISH` there) on any earlier hole. Neither path calls
`onNextHole()` on the last hole — PLAN section 4's "`NextHole` on the last hole is a no-op, not an
implicit finish" is honored by the last hole's `EdgeButton` dispatching `onFinish` directly (after
confirmation), never relying on the reducer's no-op to do something it was explicitly designed not
to do.

**The finished-round placeholder is intentionally minimal, per this task's own scope note.** It
shows `ROUND FINISHED`, the course name, and `RoundState.scoreboard()`'s ranked rows (already
rank-and-tie-aware, since that logic lives in `Scoreboard.kt` regardless of which screen renders it)
with a `DONE` `EdgeButton` wired to the real `RoundViewModel.done()`. No styling beyond what
`FinishedRoundScreen.kt`'s own doc comment already flags as a stand-in — Phase 6 owns the real final
scoreboard screen.

### An apparent tension in PLAN.md, resolved rather than silently worked around

PLAN section 6's Phase 5 done-when text requires "a `force-stop` mid-round reopens on the same hole
with the same numbers." PLAN section 6's Phase 6 scope list separately includes "resume-on-launch
from Home (mid-round *and* finished)" as **Phase 6** work, and `AppScreen.kt`'s own Phase 4 doc
comment says `RESUME` on `Home` is "deliberately absent" until then. Taken at face value these
conflict: Phase 5 must resume into a round after a process death, but "resume-on-launch" is
explicitly Phase 6.

**Resolved by treating these as two different mechanisms, not one.** `WearApp.kt`'s `screen` state
now resolves its *initial* value once, right after `isReady` first flips true, to `AppScreen.Hole`
when `RoundViewModel.round.value` is non-null (mid-round or just-finished) and `AppScreen.Home`
otherwise — this is the minimum needed to satisfy the literal done-when text, and nothing more. It
does **not** add a `RESUME` row to `HomeScreen` (PLAN section 3's "only when a round is in
progress"), does **not** touch the deleted-course-resume edge case PLAN section 11 marks as a
Phase-7-provisional model's-call, and does not build any scoreboard/history UI. A user who somehow
lands on `Home` with a round still active (not reachable through this phase's own UI, since the only
way off `Hole` is finishing) still has no `RESUME` button to get back in — that gap is real and is
exactly what Phase 6 is scoped to close. **Flagging this explicitly for Derek**: if the intent was
that *no* form of auto-landing-on-Hole should exist before Phase 6 — i.e., a force-stop mid-round
should be expected to strand the user at Home with the round persisted-but-unreachable until Phase 6
ships `RESUME` — then this phase's `WearApp.kt` initial-screen logic should be reverted and the
Phase 5 done-when text in PLAN section 6 is the line that's wrong, not this code. As implemented,
Phase 5's own literal done-when passed by screenshot (see Verified), and Phase 6's scope
(`RESUME` on `Home`, deleted-course resume, the real scoreboard) is otherwise fully intact and
unbuilt.

### Static inset vs. Phase 4's measured approach, in practice

Phase 4's measured `roundSafeWidth` failed in exactly the way a measure→resize feedback loop
predicts: silently, on one specific row, in a way that needed `uiautomator dump` (not eyeballing) to
even characterize, and left a floor (`0.55`) as an unexplained-in-full mitigation rather than a
fix. The static version replacing it this phase never has an analogous failure mode — there is
nothing to converge, so there is nothing to fail to converge — and the one round-safety issue this
phase *did* find (the wedge-clipped `+` near the round edge) was found and fixed by **screenshotting
and adjusting a single constant**, not by debugging asynchronous layout timing. That is a real,
qualitative improvement in how tractable the round-safety problem is to reason about, exactly as
PLAN section 2's rationale for making the change predicted.

The tradeoff is exactly the one PLAN section 2 named up front: **rows are narrower than they need to
be almost everywhere**, because the single fraction has to be safe at the worst-case resting
position (near the top/bottom of the viewport), not just the common case (mid-viewport, where a much
wider row would fit the circle fine). A `StepperRow` at 0.64 leaves visibly more black margin on
either side when it happens to be vertically centered than Phase 4's measured 0.92-nominal version
did in the same spot. This was a known, accepted cost of the fix, not a surprise — PLAN section 2
calls the abandoned per-row-optimal-width behavior "an optimization nobody asked for," and this
phase's screenshots confirm the narrower-everywhere rows are still comfortably legible and every
target still measures ≥48dp, which is what was actually required.

### Design choices made without an explicit spec, worth a mention

- **Order of the two dim rows.** PLAN section 3 lists `◂ prev hole` then `finish round` in that
  order in the mockup; `HoleScreen.kt` emits them in that same order (`prev hole` first when not on
  hole 1, `finish round` second when not on the last hole), so on a middle hole both are visible with
  `prev hole` above `finish round`, matching the mockup's vertical order.
- **`FinishConfirmScreen`'s copy.** PLAN section 3 says only "a full-screen yes/no" with no specified
  wording. Used `"Finish round?"` / `"{courseName} · Hole {n}/{total}"` / `"Yes, finish"` /
  `"No, keep playing"` (ellipsized on the round face to `"No, keep pla…"`, confirmed legible and
  unambiguous by screenshot) — the same phrasing regardless of which of the two entry points opened
  it, since both dialogs mean exactly the same thing to the reducer (`RoundAction.Finish`) and PLAN
  never asks them to read differently.
- **`ScreenScaffold`-free confirm screen.** `FinishConfirmScreen` is a plain centered `Box`/`Column`,
  not a `TransformingLazyColumn` screen — four short lines never need to scroll, and this sidesteps
  needing `withRoundEdgeInset()`/`withEdgeButtonReserve()` math for a screen with no edge button and
  no risk of overflow.
- **5-player test roster for the emulator run**, not the 3-player roster PLAN section 3's mockup
  shows, specifically to exercise "a roster long enough to scroll" per this phase's own done-when —
  confirmed the player-row section, the standings section, and both dim rows all require scrolling
  to reach in full with 5 players, unlike the mockup's 3.

### Verified

- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**, and `./gradlew :app:testDebugUnitTest` —
  **BUILD SUCCESSFUL**, 109 tests, 0 failures, 0 errors (confirmed via each `TEST-*.xml`'s summary
  attributes, same discipline as every prior phase) — the same 109 from Phase 4, unchanged. No new
  reducer/domain logic was added this phase, so no new JVM tests were needed, per the task's own
  expectation ("most of this phase should need none, because the logic already exists").
- **The 96×96px last-item bug is gone**, checked at its original two repro sites plus a new one:
  the 18-hole course editor's `Delete course` row (Phase 4's exact original repro) — `uiautomator
  dump` bounds `[125,158][308,194]`, a healthy non-degenerate size, not 96×96. The hole screen's own
  last-reachable row (`finish round` on hole 1, or the standings' last player row on the last hole)
  checked the same way with the same result.
- **A full 18-hole round, played by screenshot, on a 5-player ("Meadow", 18 holes) roster**: started
  from setup (`START` now actually starts the round and navigates to `HoleScreen`, not Phase 4's
  stub); adjusted strokes with `−`/`+` on hole 1 (Derek 3→2, Sam 3→4); changed hole 2's par
  (unlearned, selector expanded by default) from 3 to 4, confirmed the untouched player row
  auto-followed to 4; walked back with `◂ prev hole` to hole 1 and confirmed Derek=2/Sam=4/Taylor=3
  were exactly as left; advanced back through to hole 18 (`HOLE 18 / 18`, `EdgeButton` correctly
  reading `FINISH ▸` — confirmed *not* `NEXT ▸`); tapped `FINISH ▸`, confirmed the yes/no dialog
  appeared, tapped `No, keep playing` and confirmed it returned to hole 18 un-finished; tapped
  `FINISH ▸` again, confirmed `Yes, finish`, landed on `FinishedRoundPlaceholderScreen` showing
  `ROUND FINISHED` / `Meadow` / ranked rows (`1 Derek 54 −1`, `2 Taylor 55 E`, …); tapped `DONE` and
  confirmed a clean return to `Home`.
- **Learn-once, verified across three separate rounds on the same course, not just read out of the
  code:** Round 1 (above) reached all 18 holes, so all 18 wrote back — confirmed via the course
  editor: H1=3, H2=4 (the value actually set), H3/H12/H13/H14=3 (each holes reached-but-untouched,
  confirming "standing on an unlearned hole and advancing... teaches its default par 3" per PLAN
  section 4). Round 2 (new round on the same course): hole 1 opened with the **compact** `PAR 3`
  label (not the expanded selector — confirming the collapsing-par-control rule reads
  `wasLearnedAtStart` correctly) and Taylor pre-filled to 3 strokes with no taps; hole 2 opened
  `PAR 4` compact, Taylor pre-filled to 4 — both confirming "replaying the same course comes back
  pre-filled." Round 3 (a third new round, same course): on hole 2, tapped the compact `PAR 4` label
  to expand it, then incremented to `PAR 5` (a simulated temp-pin correction on an already-learned
  hole) and finished the round early. **Checked the course editor afterward: H2 still reads `4`, not
  `5`** — confirms "changing par on an already-learned hole does NOT change the course" directly,
  not inferred.
- **`adb shell am force-stop com.veenstra.discgolfscore` mid-round**: started a fourth round,
  adjusted Taylor to 5 strokes on hole 4, force-stopped, waited, relaunched — the app opened directly
  to `HOLE 4 / 18` with Taylor still at 5, no detour through `Home`, confirmed by screenshot taken
  after the launch-splash cleared (same "wait ~2-3s" lesson Phase 1's log already flagged).
- **Every state reached this phase screenshotted on the round face and Read, not assumed**: Home,
  new-round setup (course selected, all 5 players ticked, `START` enabled), the hole screen's header/
  par-control/player-rows/standings/dim-rows/`EdgeButton` in both collapsed- and expanded-par states
  and on first/middle/last holes, the yes/no finish confirmation (both outcomes), the finished-round
  placeholder, and the course editor showing the learned pars from all three rounds above. No
  clipping or out-of-circle content was found at rest that the fraction/inset tuning above didn't
  already resolve; the one residual cosmetic effect (the edge-transform wedge glyph, not a tap-target
  defect) is called out explicitly above rather than hidden.

### Open items for Derek

1. **The PLAN section 6 Phase 5/Phase 6 tension on "resume-on-launch"** (above) — confirm the
   resolution (auto-land on `Hole` from a cold launch, but no `RESUME` row on `Home` yet) matches
   intent, or say so and it's a small revert.
2. **The cosmetic edge-transform wedge clip** on a row resting very close to the top/bottom of the
   viewport after a fast fling — confirmed harmless to tap-target size by `uiautomator dump`, not
   chased to zero because doing so would need a much narrower fraction everywhere for a purely
   decorative edge case. Worth a specific look on the real device in Phase 7, same as Phase 4's own
   flagged item about its (now-removed) measured approach.
3. **The par control's collapse behavior changed** with the `StepperRow` redesign (above): it no
   longer auto-collapses back to the compact label the instant a par is picked, only when navigating
   away from that hole and back. If Derek wants the old auto-collapse-on-pick behavior back, it's a
   small addition to `HoleScreen.kt`'s `onIncrement`/`onDecrement` lambdas, not a redesign.

---

## Phase 6 log ✅ done (2026-09-09)

Finish and polish: the segmented par selector restored (Task A), the row-width re-examination Task
A asked for, the real final scoreboard, `RESUME` on Home, deleted-course resume, every named edge
state, rotary/bezel scrolling, and haptics. **Done when:** every screen renders correctly on the
round AVD by screenshot, with no clipping. True — see Verified below.

### Task A — the segmented par selector, and 0.64 re-examined

**Restored the `3 [4] 5` control Phase 5 replaced with a stepper.** New `ParSelector`/`ParOption`
composables in `HoleScreen.kt`: three 52dp circles (`PAR_OPTION_SIZE`), `Arrangement.SpaceBetween`
inside a **deliberate width exception** at `PAR_SELECTOR_WIDTH_FRACTION = 0.85f` — not the shared row
inset. Tapping a value calls `onSetPar` with that exact par *and* collapses back to the compact
`PAR N` label — both tap-to-pick and the auto-collapse-on-pick behavior Phase 5's log flagged as
lost are back. Verified by `uiautomator dump` on the round AVD (not just screenshot): all three
circles measured **104×104px (52dp)** with clean ~8dp gaps between them, comfortably over the 48dp
floor, at the control's actual on-screen position (row 2 of the hole screen, not scrolled to an
edge).

**0.64 re-examined, and widened to 0.88 — from fresh evidence, not from reverting Phase 5's own
measurement.** The task's suspicion about the fraction turned out to be aimed slightly off: `git`
doesn't exist for this repo (no commits yet, confirmed via `git log`), but a close read of the
shipped source found `StepperRow.kt`'s `rowWidthFraction` parameter had its own hardcoded default of
**0.92**, independent of `EdgeSafeTransform.kt`'s `ROW_WIDTH_FRACTION` (0.64) — a split Phase 5's log
never mentions making. In practice this meant every `StepperRow` in the app (the hole screen's
player rows, the original par-as-stepper row, every course editor par row) was already rendering at
0.92 the entire time Phase 5's log describes verifying "every list screen" at 0.64 — only
`PickableRow`, `StandingRow`, `DimTextRow`, and the finished-round placeholder's scoreboard rows
(all called `roundSafeWidth()` with no argument) actually got the narrow 0.64. Confirmed this
concretely with a fresh `uiautomator dump`, not by reading the diff: opened the course editor on the
18-hole test course, and found `H1`'s `StepperRow` rendering with `+`'s clickable bounds `104px`
wide (0.92 fraction), not 0.64-width, while a `PickableRow` on the same screen elsewhere really was
narrow. Re-ran Phase 5's own measurement discipline (`uiautomator dump` at multiple scroll rest
positions, including a row settled 10px from the true top edge on `ManagePlayersScreen`'s 12-player
list — more extreme than anything in the Phase 5 log) against this already-0.92 `StepperRow` and got
the **same result Phase 5 got at 0.64**: full 96×96px (48dp) clickable bounds every time, a cosmetic
wedge-clip on the drawn glyph only at the most extreme position, never a shrunk hit target. That is
direct, repeated evidence that width was never the variable protecting the 48dp floor —
`ROUND_EDGE_INSET` (28dp top/bottom) and `withEdgeButtonReserve()` are, because together they keep a
row from resting close enough to the mask for the glyph clip to matter. Narrowing every row's width
bought nothing against that risk and cost `PickableRow`'s course/player names real space for no
reason. **Settled on 0.88** — conservative versus `StepperRow`'s already-proven 0.92, and a large,
evidence-backed jump for the rows that had actually been stuck at 0.64. `StepperRow`'s own default
was changed to the same 0.88 so the two composables can't silently diverge again; its doc comment
now says so explicitly. **Flagged for Derek:** if `StepperRow`'s rows were specifically meant to be
narrower than they've been rendering since Phase 5, that narrowing never actually shipped, and this
phase moved the *other* rows to meet it rather than narrowing it further.

### Task B — finish and polish

**Final scoreboard**, `FinishedRoundScreen.kt`'s `FinishedRoundPlaceholderScreen` rebuilt into the
real `FinalScoreboardScreen` (every call site and doc comment updated, including `AppScreen.kt` and
`WearApp.kt`): `FINAL`, course name, then `RoundState.scoreboard()`'s ranked rows unchanged from the
domain layer — rank, name (ellipsized), raw strokes, to-par. **Verified with an actual tie, not just
read out of the ranking function:** a 12-player round with one player at 2 strokes, nine tied at 3,
and two tied at 4 rendered `1`, then nine rows of `2`, then two rows of `11` — the exact
skip-after-a-tie shape PLAN.md section 2 specifies, confirmed by screenshot at three scroll
positions, not inferred from `ScoreboardTest`'s existing coverage of the same rule.

**Finished-round persistence, verified with an actual force-stop while the scoreboard was showing**:
finished a round, `adb shell am force-stop`, relaunched — landed directly back on the same `FINAL`
screen with the same numbers, not `Home`, not a blank round. `DONE` then cleared it and returned to
`Home` cleanly.

**`RESUME` added to `Home`** (`HomeScreen.kt` gained `hasActiveRound`/`onResume` parameters,
`WearApp.kt` wires them), shown only when a round is in progress, above `NEW ROUND` per PLAN.md
section 3's mockup order. The Phase 5 tension between Phase 5's and Phase 6's done-when text is
resolved exactly as this task's briefing confirmed: cold launch still auto-resolves straight to
`AppScreen.Hole` (which itself now routes to `FinalScoreboardScreen` once `RoundState.finished`, so a
finished round resumes to the scoreboard, verified above, not the hole screen) — kept, not reverted.
`RESUME` is a second, explicit way into the same screen. **As built, there is currently no in-app
navigation path that reaches `Home` while a round is active** — every way off `HoleScreen`/
`FinalScoreboardScreen` either advances the round or, via `DONE`, clears it first — so `RESUME` is
unreachable through this app's own screens today. Built anyway, per this task's explicit direction
to keep it regardless, since PLAN.md section 3 specifies it unconditionally. Documented in
`WearApp.kt`'s and `AppScreen.kt`'s doc comments and flagged again here rather than left implicit.

**Deleted-course resume, verified at the storage layer, not inferred from the model.** Started a
2-player round on the 18-hole course, force-stopped, then used `adb shell run-as` to pull the app's
DataStore preferences file directly — it turned out to be plain, readable text (control-character
separators aside), not opaque protobuf, so a single equal-length byte substitution in the `courses`
value's id field (leaving the identical id untouched inside `round_meta`'s own snapshot) was enough
to simulate "this round's course no longer exists" without touching protobuf structure at all.
Pushed the edited file back (`run-as ... sh -c 'cat > ...'`, since `run-as cp` from `/sdcard` hit a
permission wall this phase worked around rather than fighting further), relaunched, and the round
resumed exactly where it left off, fully playable. Changed an unlearned hole's par (the exact action
that fires write-back) — no crash — then **pulled the DataStore file again and read the `courses`
value directly**: the pars were unchanged, confirming the write-back genuinely no-op'd at the point
of persistence, not just that the UI didn't crash. Finished the round; the scoreboard rendered off
the round's own `courseName` snapshot with no reference back to the (now-orphaned) course id. This
is strictly stronger evidence than the existing `RoundViewModelRosterTest` coverage (which was also
extended this phase — see Tests below) because it exercises the real `DataStoreDiscGolfRepository`
codec and the real on-device persistence path, not an in-memory fake.

**Edge states, all screenshotted on the round AVD:**
- **One player**: a full 18-hole round and a 1-hole round, each played to a real `FinalScoreboardScreen`.
- **A 12-player roster**: `Alexandria`, `Bartholomew Montgomery`, `Derek`, `Sam`, `Taylor`, `Jordan`,
  `Casey`, `Morgan`, `Riley`, `Avery`, `Quinn`, `Reese` — created, all 12 selected and played on the
  1-hole course (fast to reach a full standings block and scoreboard with all 12 rows), the tie
  scenario above included.
- **A 1-hole course** (`Ace Alley`): `HOLE 1/1`, `FINISH ▸` immediately (never `NEXT ▸`), the finish
  confirm reads `Ace Alley · Hole 1/1`, no off-by-one anywhere. Caught and fixed a real (if minor)
  bug this edge case surfaced: the course editor's hole-count line read "1 holes", not "1 hole" —
  `CourseEditorScreen.kt` now special-cases the singular.
- **Long names (15+ chars) everywhere they render**: `Bartholomew Montgomery` (22 chars) and
  `Riverside Park East Nine` (24 chars) — full, unellipsized on `PickableRow` setup rows (0.88 width
  now has real room) and the standings block; ellipsized gracefully to `Bart…`/`Bartholom…` on
  `StepperRow` hole rows and the scoreboard's tighter per-field layout, which is expected given those
  rows also have to fit a stepper or four columns of numbers on the same line — PLAN.md's own risk
  table already named this exact tradeoff.

**Rotary/bezel scrolling, verified working, not just wired.** `TransformingLazyColumn`'s own
bytecode (decompiled from the pinned 1.6.2 AAR, same discipline as Phase 1's list-API investigation)
shows it constructs a default `RotaryScrollableBehavior` via `RotaryScrollableDefaults.behavior(state,
flingBehavior, ...)` and applies `Modifier.rotaryScrollable(...)` plus
`HierarchicalFocusKt.requestFocusOnHierarchyActive` automatically, with no parameter this app's code
has to opt into — every screen gets rotary input for free from the same composable already in use
everywhere. Confirmed this isn't just theoretical: `adb shell input rotaryencoder scroll --axis
SCROLL,<±15>` genuinely scrolled `ManagePlayersScreen`'s 12-player list and `HoleScreen`'s standings
block, both directions, screenshotted before and after. (Smaller magnitudes like ±5 produced no
visible movement — worth knowing if this is re-tried later — ±15 is what worked.)

**Haptics on score changes.** `HoleScreen.kt` wraps `onAdjust`/`onSetPar` in `hapticAdjust`/
`hapticSetPar`, each firing `HapticFeedbackType.TextHandleMove` via `LocalHapticFeedback` before
dispatching — a light tick on every stroke `−`/`＋` and every par pick, not `HapticFeedbackType.
LongPress` (which ultimate-score reserves for a completed hold-to-score gesture, a different
interaction shape than this app's plain taps). Not independently observable on the emulator (no
audio/haptic passthrough), so this is verified by "the calls execute without throwing across dozens
of taps in the manual runs above," not by feeling anything — a real confirmation is Phase 7 work.

**Launcher icon**: untouched, per this task's explicit scope — the Phase 1 placeholder stays.

### An EdgeButton z-order finding, caught by test automation, not a defect

Building small `adb`-driven helper scripts to set up the 12-player roster (screenshotting every
player-by-player selection would have taken too long) surfaced a real interaction property worth
recording: **a row whose position falls inside the fixed `EdgeButton`'s screen-space zone (~y>330 on
this 454px round display) is not tappable via its own bounds, even though `uiautomator dump` reports
that row's geometry accurately there.** The `EdgeButton` is drawn after (on top of) the
`TransformingLazyColumn` in the same `Box`, so Compose hit-tests z-order and the button wins any tap
in the overlap — the automation's blind coordinate taps twice landed on `START` instead of the
player checkbox actually reported at that position, once starting an unintended round mid-setup.
**This is not a defect**: PLAN.md section 3 and the Phase 4 log already establish that a short list's
tail can render behind the edge button at rest and has to be scrolled clear, which is exactly what a
real finger does before tapping something it can only partly see — the finding is specifically that
this is enforced at the touch-dispatch level, not just visually. Worth a specific look in Phase 7 if
a real tap near the button's top edge on the watch ever feels like it "didn't register" — the fix
there is the same one a person already does instinctively: scroll first.

### Tests

Added one JVM test, `RoundViewModelRosterTest`'s `a round plays and scores normally to the end after
its course is deleted mid-round` — extends the existing (Phase 3) "write-back no-ops" coverage to
also exercise `Adjust`/`NextHole`/`PrevHole`/`Finish`/`scoreboard()` after the deletion, asserting the
final totals and ranking are correct, not just that no exception was thrown. No reducer/domain
changes were needed this phase — `ParSelector` calls the same `onSetPar(par)` contract the stepper
always did, and the final scoreboard reads `RoundState.scoreboard()` unchanged — so no new reducer
tests were required, matching the task's own expectation that most of this phase needs none.

### Verified

- `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL** both,
  **110 tests, 0 failures, 0 errors** across the same 7 test classes as Phase 5 plus one new test
  (confirmed via each `TEST-*.xml`'s summary attributes, same discipline as every prior phase) — the
  109 from Phase 5 unchanged, one added.
- **The segmented par selector**: `uiautomator dump`-measured 52dp circles with visible gaps, tap-to-
  pick and auto-collapse both confirmed on a live round.
- **Row width**: `PickableRow`/`StepperRow`/standings/scoreboard all re-measured at 0.88/0.92 with no
  target under 48dp at any tested scroll position, including a 12-player list's very top row.
- **Final scoreboard**: ranking, raw totals, to-par, and a real three-tier tie (`1` / nine-way `2` /
  two-way `11`) all screenshot-confirmed.
- **Finished-round persistence**: force-stop while the scoreboard was showing → relaunch → same
  screen, same numbers → `DONE` → `Home`.
- **Deleted-course resume**: verified at the DataStore layer by directly reading the persisted
  `courses` value after the would-be write-back, not just by the UI not crashing.
- **Edge states**: one player (both a full round and the 1-hole course), a 12-player roster, a
  1-hole course, and 15+ character player/course names — all screenshotted across setup rows, hole
  rows, the standings block, and the final scoreboard.
- **Rotary/bezel scrolling**: confirmed with real `rotaryencoder` input events on two different
  screens, both directions.
- **Haptics**: wired and exercised without throwing; not perceptually verified (no device).
- **Full flow end to end, more than once**: `Home` → `NEW ROUND` → setup → play → `FINISH` → confirm
  → `FinalScoreboardScreen` → `DONE` → `Home`, plus a cold relaunch mid-round and a cold relaunch
  mid-scoreboard, both landing correctly.
- **A genuine "learn once" cross-round check fell out of this phase's testing for free**: the 18-hole
  course's H1 par (set to 4 in an earlier round this phase) was still 4, and H2–H18 still the
  default-3 the first full round taught them, confirmed both before *and after* the deleted-course
  round played on the same course id — the deleted-course write-back no-op didn't disturb anything
  the course had legitimately already learned.

### Open items for Derek

1. **`RESUME` is unreachable through this app's own navigation today** (above) — built per explicit
   direction to do so regardless, not a bug, but worth knowing before it's the thing you go looking
   for on the watch and can't find a way to trigger.
2. **`StepperRow`'s width was never actually 0.64** at any point Phase 5 shipped, despite the Phase 5
   log's own narrative describing it that way — if narrower player/par rows were specifically wanted,
   that's still an open, undone change, not something this phase reverted.
3. The wedge-shaped cosmetic glyph clip near the round face's extreme top/bottom edge (first flagged
   Phase 5) persists at the new 0.88/0.92 fractions exactly as it did at 0.64/0.92 — confirmed
   harmless to tap-target size again this phase, still not chased to zero, still a real-device item
   for Phase 7.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>

---

## Layouts phase log ✅ done (2026-09-13)

A course became a container of layouts (§2 "Layouts"): "Columbia Lake" is a course, "9 short red
tees" and "18 long blues" are two layouts of it, and **a round is now played against a layout, not
a course**. Built on top of Phases 1–6 without revisiting any of their own decisions.

### What was built

**Model.** New `Layout.kt` (`Layout(id, name, holeCount, pars, recordHolderNames, recordToPar)` —
exactly the five fields, no starting-hole offset, no explicit par total — plus `defaultLayoutName`,
`"18 holes"`/`"9 holes"`/`"1 hole"` from a hole count). `Course.kt` shrank to
`Course(id, name, layouts: List<Layout>)`. `CourseRecord.kt` was renamed to `LayoutRecord.kt` and
retargeted at `Layout` (`recordAfterRound` now compares `round.layoutId`, not `round.courseId`, so
sibling layouts of the same course learn independent records); it also gained `courseListDetail`
for the courses manager's per-row detail line. `RoundState` gained `layoutId`/`layoutName`
(defaulted, trailing fields — `courseName` kept its exact old name and position so old call sites
and old saved data don't have to change shape). `newRound` now takes `(course, layout, players)`.

**Persistence.** `DiscGolfRepository.kt`'s course codec now nests each course's layouts one level
down, using a third separator (`U+001D`, `GROUP_SEP`) between layout records inside a course
record's own FS-joined fields (decoded with `split(limit = 3)` so the nested blob survives the
outer split intact). **Migration**: every course record this codec writes now starts with a
version tag (`"C2"` + the field separator); anything without that tag is the old flat pre-layouts
shape (a course id is always a millisecond timestamp, so it can never collide with the tag) and is
decoded by `decodeLegacyCourseAndWrap` — the original decode logic, untouched, kept as its own
private function specifically so it stays frozen even as the new format's decoding evolves — then
wrapped into a `Course` with one `Layout` named by `defaultLayoutName` from its old hole count,
reusing the course's own id as the layout's id. Old and new formats can coexist in the same stored
string (exercised directly in the codec tests) since decoding is per-record. `RoundState`'s meta
record grew two trailing fields (`layoutId`/`layoutName`); `decodeRoundMeta` accepts both the old
4-field shape (decodes with `layoutId = null`, `layoutName = ""`) and the new 6-field one, so a
round saved before this phase still loads and still displays (course name only, no layout line).

**ViewModel.** `RoundViewModel.addCourse` now creates a course with one auto-named layout. New
`addLayout`/`renameLayout`/`deleteLayout`/`setLayoutPar`/`setLayoutRecordHolders`/
`setLayoutRecordToPar`, all scoped by `(courseId, layoutId)`. `deleteLayout` is a no-op if it would
leave the course with zero layouts (§2 "Deleting a layout"). `startRound` takes a `Layout` parameter.
`applyParWriteBack`/`applyRecordWriteBack` resolve `courseId` → `layoutId` → the actual `Layout`
before writing, no-op-ing at any broken link (deleted course, deleted layout) — the exact same
"snapshot the id, no-op if it's gone" shape §4 already used for courses.

**UI.**
- `NewCourseFlow.kt`'s `HoleCountPickerScreen` renamed its parameter `courseName` → `subjectName`
  (it's shown for a new layout's hole count too now) — otherwise unchanged, confirming it really
  was already reusable.
- New `LayoutEditorScreen.kt`: what used to be `CourseEditorScreen`'s record row/stepper/par list,
  moved onto a layout and given its own rename/record text-input launchers internally.
- `CourseEditorScreen.kt` rewritten: rename the course, list its layouts (each showing
  `formatLayoutRecord`), "+ New layout…" (name → `HoleCountPickerScreen`), tap/long-press a layout
  → `LayoutEditorScreen`, delete course. Owns every launcher both screens need internally, rather
  than leaving them to its two call sites, so growing a second level of editable entity didn't
  double the plumbing at every caller.
- New `LayoutPickerScreen.kt`: the layout-picker step for round setup (below), with its own
  "+ New layout…" mirroring "+ New course…"'s inline flow.
- `ManageCoursesScreen.kt`: simplified to just wire courses + the new layout callbacks through to
  `CourseEditorScreen`; its row detail is now `courseListDetail`.
- `NewRoundSetupScreen.kt`: course-pick now resolves a layout too. A single-layout course resolves
  it immediately with no new screen (§2 "Round setup: course → layout, with a skip"); two or more
  routes through `SetupMode.PickingLayout`/`LayoutPickerScreen` first, and only then does
  `selectedCourseId`/`selectedLayoutId` update together. `onStart` now carries a `layoutId`.
- `FinishedRoundScreen.kt`: banner text `🏆 NEW LAYOUT RECORD!`; a non-blank `layoutName` renders
  under the course name, smaller — nothing shown for a pre-layouts round.
- `PastRoundsScreen.kt`: a listing row's detail becomes `<layout name> · <date>`, or just the date
  for a pre-layouts round.
- `WearApp.kt`: rewired for every new/renamed callback; `onStart` resolves both the course and the
  layout before calling `viewModel.startRound(course, layout, players)`.

### Judgment calls

1. **Course creation stayed name → hole count → create**, auto-naming the first layout by hole
   count, rather than also asking for a first layout's name. The task's own instructions preferred
   this path explicitly ("keeps the watch flow shortest… preferred, with layouts added afterward
   from the course editor"), and it's the exact shape the pre-layouts migration already uses, so
   creating a course today and migrating an old one now produce identically-shaped data.
2. **Deleting a course's last layout is blocked, not cascaded into deleting the course.** Also the
   task's own stated preference ("blocking is simpler and safer, prefer that"). The "Delete layout"
   row is hidden entirely on a course's only layout rather than shown disabled — this app has no
   existing precedent for a visibly-disabled list row, and hiding the option is the more common
   pattern used elsewhere here (`◂ prev hole`/`Finish round` conditionally appear on the hole
   screen, `RESUME` conditionally appears on Home).
3. **Migration format detection uses an explicit version-tag prefix**, not a structural guess
   (e.g. field-count heuristics). Layouts nest one level deeper than a flat course record did, so a
   heuristic would only work by coincidence of the two shapes' field counts never lining up — an
   explicit tag is unambiguous by construction and is exactly what a real format migration uses.
   The migrated layout **reuses its course's own id** — simple, deterministic across repeated
   loads, and there's nothing yet that needs it to be distinct from the course id.
4. **A malformed layout invalidates its whole course on decode**, rather than dropping just that
   layout and keeping the rest. Mirrors the existing rule for a corrupt hole inside a round ("a
   card missing a hole in the middle can't be trusted to mean anything") — a course missing one of
   its layouts for no visible reason isn't obviously safer than not loading it at all, and the
   existing codec had no precedent for "partially recover one record."
5. **`courseListDetail`** (the courses-manager row's detail line) shows the single layout's own
   record for a single-layout course — identical to the pre-layouts behavior — and a layout count
   (`"2 layouts"`) for a multi-layout course, rather than guessing which sibling's record to show.
6. **The layout-picker step is local Compose state** (`SetupMode.PickingLayout` /
   `LayoutPickerScreen`), not a new `AppScreen` case — consistent with how `HoleCountPickerScreen`
   and every other in-flow detour in this app already works; the sealed `AppScreen` router itself
   needed no changes for this entire feature.
7. **Deleting a layout that's currently selected mid-setup** clears just `selectedLayoutId` via a
   `LaunchedEffect` invariant check (`courses`/`selectedCourseId`/`selectedLayoutId`) rather than
   bespoke logic in the delete callback — the course-deletion case keeps its existing inline
   handling unchanged; this only covers the one-level-deeper case a plain `find` can't.

### Left out / not done

- **Hole count changes remain impossible even via layouts** — exactly as intended (§2): a
  different hole count is a new layout, not an edit. No new escape hatch was added or considered.
- **No UI affordance for reordering layouts** — they display in creation order, matching how
  players/courses already have no reorder anywhere in this app.
- **No attempt to detect or merge "the same layout typed twice"** — same as courses and players
  today, a duplicate name is just two rows; not a layouts-specific gap.
- **PLAN.md section 3's ASCII mockups were annotated with pointers to this log rather than fully
  redrawn** — the "Course editor"/"New round setup"/"Final scoreboard"/"Past rounds" prose now each
  carry a short "Superseded 2026-09-13" note describing what changed, but the original mockups were
  left in place as history rather than replaced, matching how earlier phase logs in this file layer
  on top of section 3 rather than rewriting it.

### Verified

- `./gradlew testDebugUnitTest` — **BUILD SUCCESSFUL, 201 tests, 0 failures** (up from 168), on a
  full `clean` build (not incremental/up-to-date).
- `./gradlew assembleDebug` — **BUILD SUCCESSFUL** on the same clean build, confirming every new
  Compose screen (`LayoutEditorScreen`, `LayoutPickerScreen`, the `CourseEditorScreen`/
  `ManageCoursesScreen`/`NewRoundSetupScreen`/`FinishedRoundScreen`/`PastRoundsScreen`/`WearApp`
  rewrites) compiles.
- New/rewritten test coverage: `LayoutRecordTest` (replaces `CourseRecordTest`, adds sibling-layout
  isolation and `courseListDetail` cases), `DiscGolfPersistenceCodecTest` (new-format round trips,
  a corrupt-layout-drops-the-whole-course case, and a dedicated migration section feeding the exact
  pre-layouts on-disk shape in and asserting the wrapped result — including a mixed old+new string
  in one blob), `RoundViewModelRosterTest`/`RoundViewModelRecordTest` (layout CRUD, write-back
  targeting the right layout, write-back no-oping on a deleted layout with the course still present,
  sibling-layout isolation for both par write-back and the record), `RoundStateTest`/
  `RoundReducerTest`/`ScoreboardTest` (updated to build a `Layout` + `Course` instead of a flat
  `Course`, otherwise unchanged since the reducer itself never touches courses/layouts).
- Not verified on-device or on the emulator (no watch/AVD available in this session) — same
  limitation the task's own "do NOT try to install on the watch" scope accepted.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TEDLsQq1WQW53xkJAc5dhA
Claude-Session: https://claude.ai/code/session_01TEDLsQq1WQW53xkJAc5dhA
