# DIY Cloud Saves — Google Sheet backup & restore

Design doc for the cloud-save feature. Same shape as `PLAN.md`: decisions with their reasoning
first, then the format, then the build phases. **Fold sections 2 and 3 into `PLAN.md` section 2's
decisions table and add a "Phase 8 log" when this ships** — this file is the working design, not a
permanent second home for it.

Answers already settled with Derek (2026-09-13), which the rest of this doc assumes:

| Question | Answer |
| --- | --- |
| What is the sheet for | **Both** — a true restore source *and* something readable/chartable |
| Transport | **Apps Script web app** — deploy URL + shared secret, no OAuth on the watch |
| Sheet layout | **Hybrid** — readable columns, plus an exact-restore payload where readable is lossy |
| Trigger | **Manual only** — a Home row, no auto-push, no background work, no queue |
| Restore conflict rule | **Merge by id, sheet wins** |
| Config entry | **adb**, with the on-watch screen only showing configured/not and offering Clear |
| Extra readable tabs | **Courses+layouts**, **Players**. Career stats deferred — pivot by hand for now |
| JSON | **`org.json`**, hand-rolled encode/decode, no new runtime dependency |

---

## 1. What this is

A `CLOUD` row on Home with two buttons: **BACK UP NOW** pushes the watch's players, courses (with
layouts and records) and finished round history into a Google Sheet you own; **RESTORE** pulls the
same data back down and merges it in. Between them, a wiped or replaced watch gets its history back,
and in the meantime the sheet is a scorecard archive you can actually read and chart.

There is no account, no server of ours, and no background sync. You deploy a ~120-line Apps Script
bound to your own sheet, paste its URL and a secret into the watch once over adb, and from then on
the watch talks to that one endpoint over plain HTTPS.

### Design principles

- **The watch sends JSON. The script owns the spreadsheet.** The app never builds rows, columns,
  headers, or formulas. It POSTs one canonical JSON document and gets one back. Every readable
  column, every derived stat, every layout decision lives in the Apps Script — so the sheet's
  presentation can be reworked whenever you like without touching, rebuilding, or reinstalling the
  app. This is the single most important structural call in this document.
- **Pure codecs, JVM-testable.** `encodeBackup`/`decodeBackup`/`mergeBackup` are top-level functions
  with no Android imports and no coroutines, exactly like the existing codecs in
  `DiscGolfRepository.kt`. Everything interesting is covered by plain JUnit, with no Robolectric and
  no instrumentation.
- **Drop bad records, never crash.** The same rule the DataStore codecs already follow: one
  unparsable round is dropped and every other round still loads. A hand-edited sheet is *expected*
  to contain mistakes.
- **Never lose data on the watch.** Restore merges; it never clears. Backup upserts; it never
  deletes. Neither direction can destroy something that isn't also somewhere else.

### Explicitly out of scope

- Automatic sync, background workers, retry queues, offline buffering.
- Syncing the **active round**. Only finished history goes up (see decision below).
- Multi-watch conflict resolution beyond "last push wins, merge on pull".
- Any attempt to be a real sync engine. This is a manual save-file, in a spreadsheet.

---

## 2. Decisions

| Decision | Call | Why |
| --- | --- | --- |
| **Transport** | Apps Script web app, deployed by Derek, "Execute as me / Anyone with the link". App POSTs to the `/exec` URL. | The only way to write a Google Sheet from a standalone Wear OS app without an on-watch Google sign-in. OAuth consent on a 1.4" screen is genuinely awful, and it drags in a Cloud project, an OAuth client, and Play Services. The script runs as *you*, so it needs no credentials of its own and no API key ships in the APK. |
| **Auth** | A shared secret in the POST **body**, compared against a `PropertiesService` script property. Not in the URL. | The `/exec` URL is deployed "anyone with the link", so the URL alone is the whole door. A secret in the body keeps it out of request logs, referrers, and shell history in a way a query param doesn't. |
| **Watch never formats the sheet** | App sends/receives JSON only; the script writes rows. | See design principles. Redesigning the Rounds tab shouldn't require an APK. |
| **Payload column only where readable is lossy** | **Rounds** carries a `_payload` cell. **Players** and **Courses** do not. | A player is `id/name/color` and a layout is `id/name/holeCount/pars/record` — every field fits a column, losslessly, so the readable columns *are* the backup and you can fix a wrong par or a misspelled course by editing the cell. A round also carries `wasLearnedAtStart`, `touched`, `currentHole` and per-player id mapping, none of which belong in a readable scorecard, so a round needs the payload. Carrying a payload on tabs that don't need one would make hand-edits silently ineffective — the worst possible outcome for a sheet you're invited to edit. |
| **`_payload` on the round's first row only** | One row per player-round on the Rounds tab; the payload cell is written on the round's first player row and left blank on the rest. | The readable shape Derek picked is one row per player-round, but the payload is per *round*. Repeating a multi-kilobyte JSON cell four times per round would bloat the sheet for nothing. Restore reads the non-blank payload cells and ignores the readable columns entirely on that tab. |
| **Active round is not backed up** | Only `players`, `courses`, and finished `history` go up. | Manual-only backup means you push between rounds, not mid-round. And restoring a half-played round from a three-week-old snapshot — landing you on hole 7 of a round you finished long ago — is a worse failure than not having it. It's a ~10-line addition later if that turns out to be wrong. |
| **Push is upsert, never delete** | A round deleted on the watch stays in the sheet. Deletions happen by hand, in the sheet. | The sheet is an archive, not a mirror. If push deleted, then a watch that lost its history and pushed before it restored would wipe the backup — the exact disaster this feature exists to prevent. Cost: deleting a round on the watch and then restoring brings it back. Acceptable, and stated in the UI. |
| **Restore merges by id, sheet wins** | Ids present in both take the sheet's version. Ids only on the watch are kept untouched. Ids only in the sheet are added. | Makes "I fixed a par in the sheet" work, and makes "I lost rounds" work, without either destroying the other. A wipe-and-replace restore would silently discard anything played since the last push. |
| **Layouts merge one level deeper** | For a course present on both sides, layouts merge by `Layout.id` under the same rule. | A course is a container. If the sheet's copy of "Columbia Lake" has only the 18-hole layout because the 9-hole one was added on the watch afterward, replacing the whole course would delete a layout that nothing asked to delete. |
| **`org.json`, not kotlinx.serialization** | Hand-rolled `JSONObject`/`JSONArray` encode and decode, in the same explicit style as the existing codecs. Add `testImplementation("org.json:json:…")` so tests get the real library instead of android.jar's stubs. | `org.json` is already in the Android framework: no plugin, no runtime dependency, no APK growth on a watch app. Its one real problem — every `org.json` method in a unit test throws `Stub!` because android.jar is stubbed — is solved completely by the test-only Maven artifact, which is the same implementation Android ships. Keeps the "codecs are pure and JVM-tested" property this codebase is built on. |
| **Config over adb, via an explicit broadcast** | `adb shell am broadcast -n <pkg>/.SyncConfigReceiver …`. The watch screen shows *Configured / Not configured* + last sync time, and offers **CLEAR**, nothing else. | An Apps Script `/exec` URL is ~70 characters of base64-ish noise; entering it by watch keyboard or voice is not a real option, and `TextInputLauncher` would make it look like one. DataStore's file lives in app-private storage, so adb can't write it directly on an unrooted watch — a receiver is the supported path. |
| **Explicit component in the broadcast** | The adb command must pass `-n com.veenstra.discgolfscore/.SyncConfigReceiver`. | Implicit broadcasts to manifest-declared receivers have been blocked since Android 8. Without `-n` the command appears to succeed and silently does nothing. |
| **One version tag, forward-compatible decode** | `"v": 1` at the envelope root; unknown fields are ignored, not rejected. | Same reasoning as `COURSE_FORMAT_TAG` in the course codec. A newer app writing an extra field must not break an older one reading it. |
| **Manual redirect handling** | The HTTP client follows the 302 an Apps Script `/exec` returns, by hand, re-issuing as GET. | `HttpURLConnection` does not auto-follow a redirect on POST, and Apps Script *always* 302s to `script.googleusercontent.com`. Skipping this is the single most common way this integration "mysteriously returns empty". Called out again in section 5. |

### The tradeoff on hand-editing

Making Players and Courses hand-editable means the app must treat those tabs as untrusted input:
a par column with `4,3,x,5` in it, a hole count that disagrees with the par list, a record with no
holder. Every one of those is dropped-not-crashed, exactly like a corrupt DataStore record — but
"dropped" means *that layout silently doesn't come back*, which is a worse experience for a typo you
made on purpose than for a corrupt byte you never saw.

Mitigation, in the script rather than the app: the Apps Script validates on **push** (it has just
been handed the authoritative version, so it can rewrite the readable columns cleanly every time)
and the restore response includes a `warnings` array naming rows it couldn't parse. The watch shows
`Restored 12 rounds · 2 rows skipped`. Not perfect, but it's the difference between a silent loss
and a visible one.

---

## 3. The wire format

One envelope, both directions, `POST` with `Content-Type: application/json`.

### Request

```json
{
  "v": 1,
  "op": "push",
  "secret": "…",
  "deviceId": "ticwatch",
  "sentAt": 1757779200000,
  "data": { "players": [...], "courses": [...], "rounds": [...] }
}
```

`op` is `"push"` or `"pull"`. `data` is present on push only. `deviceId` is provenance for a `_Meta`
tab and nothing else — it does not participate in merging.

### Response

```json
{ "ok": true,  "v": 1, "counts": { "players": 4, "courses": 3, "rounds": 27 }, "warnings": [] }
{ "ok": true,  "v": 1, "data": { "players": [...], "courses": [...], "rounds": [...] }, "warnings": ["Courses!C14: par list length 17 ≠ hole count 18"] }
{ "ok": false, "error": "unauthorized" }
```

### `data`

```json
{
  "players": [
    { "id": "1725812345678", "name": "Derek", "color": 4280649689 }
  ],
  "courses": [
    {
      "id": "1725812300000",
      "name": "Columbia Lake",
      "layouts": [
        {
          "id": "1725812300000",
          "name": "18 long blues",
          "holeCount": 18,
          "pars": [3,4,3,5,3,3,4,3,3,4,3,3,5,3,4,3,3,4],
          "recordToPar": -6,
          "recordHolders": ["Derek", "Sam"]
        }
      ]
    }
  ],
  "rounds": [
    {
      "id": "1757779200000",
      "finishedAt": 1757779200000,
      "courseId": "1725812300000",
      "courseName": "Columbia Lake",
      "layoutId": "1725812300000",
      "layoutName": "18 long blues",
      "currentHole": 18,
      "finished": true,
      "players": [ { "id": "1725812345678", "name": "Derek", "color": 4280649689 } ],
      "holes": [
        { "par": 3, "learned": true, "strokes": { "1725812345678": 2 }, "touched": ["1725812345678"] }
      ]
    }
  ]
}
```

Field notes:

- **`color` is a decimal `Long`**, the packed ARGB value `Player.color` already holds.
  `0xFFFFFFFF` is 4294967295, which **overflows `Int`** — decode with `optLong`, never `optInt`.
  The script converts it to `#RRGGBB` for the readable Players tab; the JSON stays canonical.
- **`recordToPar` is `null` when there is no record**, and `recordHolders` is then `[]`. Zero and
  negative are both valid records (even par, six under), so `0` must never be treated as absent —
  the same trap `decodeLayoutFields` already documents.
- **`pars` may contain `0`**, meaning "this hole hasn't been learned yet". Preserve it; don't
  helpfully substitute `DEFAULT_PAR`.
- **`learned`** is `HoleScore.wasLearnedAtStart`. Short name because it appears once per hole per
  round and the payload cell has a 50 000-character limit.
- **Ids are wall-clock millisecond strings** throughout, as everywhere else in this app. Keep them
  strings in JSON — a `strokes` map key has to be a string anyway, and it keeps the two sides from
  disagreeing about numeric precision.
- Round `players` is the round's **snapshot** roster (`RoundState.players`), not the current roster.
  A player renamed or deleted after the round still appears here with the name they had. That's the
  whole point of the snapshot and it must survive the round trip.

### Round-trip guarantee

`decodeBackup(encodeBackup(players, courses, history))` equals the input, exactly, including
`touched` sets, `0` pars, `null` records, and the player snapshot. This is the first test to write
and the one that matters most.

---

## 4. The sheet

Four tabs. The script creates any that are missing on first push.

### `Rounds` — one row per player-round, newest first

| roundId | date | course | layout | player | total | toPar | h1 | h2 | … | hN | _payload |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1757779200000 | 2026-09-13 | Columbia Lake | 18 long blues | Derek | 54 | −6 | 2 | 4 | … | 3 | `{"id":…}` |
| 1757779200000 | 2026-09-13 | Columbia Lake | 18 long blues | Sam | 61 | +1 | 3 | 5 | … | 4 | *(blank)* |

- `date` is a real Sheets date value (from `finishedAt`), not a string, so it sorts and charts.
- `total` / `toPar` are values, not formulas — the app computes them the same way
  `RoundState.strokesThrough`/`toPar` does, so the sheet can't disagree with the watch.
- The `h1…hN` block is sized to the largest hole count ever pushed; shorter rounds leave the tail
  blank. Start at 18 and let the script widen it.
- `_payload` is the last column, on the round's first player row only. **This column is the restore
  source for this tab.** The readable columns are ignored on pull.

### `Courses` — one row per layout, readable columns canonical

| courseId | course | layoutId | layout | holes | pars | recordToPar | recordHolders |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1725812300000 | Columbia Lake | 1725812300000 | 18 long blues | 18 | `3,4,3,5,…` | −6 | `Derek, Sam` |

`pars` is a comma-joined list; `recordHolders` is comma-and-space joined. Blank `recordToPar` means
no record. Edit any of these and the change comes back on the next restore.

### `Players` — readable columns canonical

| playerId | name | color | rounds |
| --- | --- | --- | --- |
| 1725812345678 | Derek | `#1E6FD9` | 27 |

`color` round-trips through hex. `rounds` is **derived by the script** from the Rounds tab and
ignored on pull — it's there to read, not to edit.

> **Career stats: deferred (2026-09-13).** A derived `Stats` tab was in the original plan and has
> been dropped for now. Nothing else in this design depends on it: the Rounds tab already carries
> one row per player-round with `total`, `toPar` and every hole, which is exactly the shape a pivot
> table wants. Build the pivot by hand, and if it turns out to want to be automatic later, the
> script can write a `Stats` tab from data it already has — no format change, no app change, no
> coordination with anything in section 3.

### `_Meta`

One row per push: timestamp, deviceId, app versionName, counts, format version. The audit trail for
"did that backup actually go through, and when".

---

## 5. Implementation notes for whoever builds this

The land mines, in the order you'll hit them.

1. **Apps Script's 302.** `/exec` always redirects to `script.googleusercontent.com`.
   `HttpURLConnection` will not follow it on a POST. Read the `Location` header on 302/301/307 and
   re-issue as a **GET** to that URL to read the body. Without this you get a 0-length response and
   no error. Budget one redirect hop, not a loop.
2. **`setFollowRedirects` doesn't save you** — it's the global static, and it still won't cross a
   POST→GET method change the way a browser does. Handle it explicitly.
3. **Implicit broadcasts are dead on O+.** The adb config command must name the component with
   `-n`. Put the exact working command in the doc comment on `SyncConfigReceiver`, because you will
   need it again on a new watch a year from now.
4. **`org.json` in unit tests.** Add the Maven `org.json:json` artifact as `testImplementation` only.
   Without it every test touching `JSONObject` throws `RuntimeException("Stub!")`, which reads like
   a broken test rather than a missing dependency and will cost an hour.
5. **`optLong`, not `optInt`, for `color`.** `0xFFFFFFFF` overflows `Int` and comes back as −1 or 0
   depending on how you read it, turning `DEFAULT_PLAYER_COLOR` into a black or transparent player.
6. **`isNull` vs `opt` for `recordToPar`.** `optInt("recordToPar", 0)` on a missing field returns 0,
   which is a *valid even-par record* — you'd invent a record for every layout that doesn't have
   one. Check `has()`/`isNull()` explicitly.
7. **Apps Script cell limit is 50 000 characters.** A `_payload` for an 18-hole, 6-player round is
   comfortably under 3 KB, so this isn't a real constraint — but if a round payload ever approaches
   it, split across two columns rather than silently truncating. Worth an assert in the script.
8. **Network on Wear.** Requires wifi or a BT-tethered phone; a watch on neither will fail
   immediately. That's fine — say so in the error ("No network") rather than spinning.
9. **`android.permission.INTERNET`** in the manifest. It's not there today.
10. **Threading.** All of it on `Dispatchers.IO`, launched from `viewModelScope`, with 20-second
    connect and read timeouts. A hung request on a watch is indistinguishable from a crash.
11. **The app must be launched once after install before you broadcast.** A package that has never
    been started (or was force-stopped) since install is in Android's "stopped" state, and a stopped
    app's manifest-declared receivers get nothing — not even an explicit `-n` broadcast. The correct
    order is: `install` → launch the app once, by hand → **then** `adb shell am broadcast …`. Get the
    order wrong and the broadcast reports success with nothing to show for it, which reads exactly
    like the `-n` mistake in item 3 but isn't. This is also why `RoundViewModel` must *observe*
    `SyncConfigStore` (a `Flow`) rather than read it once at startup: the correct order broadcasts
    the config into an app that's already running, so a one-shot read at `init` misses it and the
    Cloud screen is stuck on "Not configured" until a force-stop and relaunch — found on-device,
    cost real debugging time, fixed by making the config live instead of snapshotted.

---

## 6. Build phases

### Phase A — Format and merge, no network, no UI

`CloudBackup.kt`, all pure, no Android imports:

- `encodeBackup(players, courses, history): String`
- `decodeBackup(json: String?): BackupData?`
- `mergeBackup(local: BackupData, remote: BackupData): BackupData`
- `data class BackupData(players, courses, rounds)`

Tests (`CloudBackupCodecTest`, `BackupMergeTest`): round-trip fidelity including `touched`, `0`
pars, `null` vs `0` records, and the round's snapshot roster; malformed/blank/truncated JSON;
unknown fields ignored; a wrong `v`; every merge rule including layout-level merge and history
re-sort by `finishedAt` descending. **No networking exists yet at the end of this phase**, and the
whole feature's correctness is already covered.

### Phase B — Transport

`BackupClient` interface (`suspend fun push(BackupData): PushResult` / `suspend fun pull():
PullResult`), one `HttpURLConnection` implementation, one fake for tests. `SyncConfigStore`
(interface + DataStore impl) holding url, secret, and `lastSyncAt`. `SyncConfigReceiver`. Manifest
gets `INTERNET` and the receiver.

### Phase C — The Apps Script

`scripts/Code.gs` in the repo, with setup steps in its header comment: create sheet → Extensions →
Apps Script → paste → set `SECRET` in Script Properties → Deploy as web app, execute as me, access
anyone with the link → copy `/exec` URL. `doPost` handles both ops; all readable-column rendering
lives here.

### Phase D — UI

`AppScreen.CloudSync`, a `CLOUD` row on Home, status line + `BACK UP NOW` + `RESTORE` (behind the
existing `ConfirmScreen`) + `CLEAR CONFIG`. Result text naming counts and warnings.

### Phase E — Real device

Install, configure over adb, push, verify the sheet by eye, edit a par in `Courses`, restore, verify
the change landed and nothing local was lost. Then the real test: wipe app data, restore, confirm
the watch comes back.

---

## 7. Open questions for Derek

1. **Sheet size.** At one row per player-round, a foursome playing weekly is ~200 rows a year —
   fine indefinitely. Worth confirming you don't want a per-year tab split.
2. **Multiple watches.** The design tolerates two devices pushing to one sheet (upsert + merge), but
   nothing detects a genuine conflict — the later push wins the cell. Fine for one watch, worth
   knowing before it's two.
3. **Secret rotation.** Changing it means re-running the adb command and editing the script
   property. No UI for it. Assumed acceptable.
4. ~~Should `Stats` be a tab at all?~~ **Settled 2026-09-13: deferred.** Pivot by hand off the
   Rounds tab; revisit if that gets tedious.
