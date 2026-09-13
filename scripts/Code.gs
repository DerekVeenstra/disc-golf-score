/**
 * DIY Cloud Saves — Apps Script backend for the Disc Golf Score watch app.
 *
 * See CLOUD_SAVES.md in the app repo for the full design doc (wire format, sheet layout, merge
 * rules, and *why* each of those was chosen). This file is the entire server side of that
 * feature: the watch never builds a row, a column, or a formula — it POSTs one JSON document and
 * gets one back, and every readable-column decision lives here, so the sheet's presentation can be
 * reworked any time without touching, rebuilding, or reinstalling the app.
 *
 * ── SETUP (do this once) ──────────────────────────────────────────────────────────────────────
 *
 * 1. Create a new Google Sheet. Any name. Leave it empty — this script creates the `Rounds`,
 *    `Courses`, `Players`, and `_Meta` tabs itself on the first successful push. Don't create them
 *    by hand; the header rows it writes must match exactly what it later expects to read back.
 * 2. Extensions → Apps Script. Delete the placeholder `myFunction() {}` Google gives you, and
 *    paste this entire file in its place.
 * 3. Set the shared secret: the gear icon (Project Settings) → scroll to *Script Properties* →
 *    *Add script property* → name it exactly `SECRET`, value whatever you like (a long random
 *    string is fine — this is what the watch's `adb`-configured secret must match byte-for-byte).
 *    This is the ONLY thing standing between "anyone with the link" and your sheet
 *    (CLOUD_SAVES.md section 2 "Auth"), so don't skip it and don't leave it blank.
 * 4. Deploy → New deployment → gear icon next to "Select type" → **Web app**.
 *      - Description: anything ("disc golf cloud saves" is fine).
 *      - Execute as: **Me** (your own Google account — the script writes to the sheet as you,
 *        so the watch itself never needs a Google sign-in at all).
 *      - Who has access: **Anyone with the link**.
 *    Click Deploy. Google will ask you to authorize the script (it's yours, so this is safe) —
 *    approve it. Copy the `/exec` URL it gives you at the end; that's the URL the watch needs.
 * 5. Configure the watch over adb with that URL and the same secret from step 3 — see
 *    `SyncConfigReceiver.kt` in the app source for the exact command.
 * 6. **Whenever you edit this script afterward**, editing the code alone does NOT update the live
 *    `/exec` URL. You must Deploy → Manage deployments → pencil (edit) icon on the existing
 *    deployment → *Version: New version* → Deploy. Otherwise the watch keeps talking to whatever
 *    code was live at the last deployment, silently.
 *
 * ── WHAT THIS FILE DOES NOT DO ────────────────────────────────────────────────────────────────
 *
 * No OAuth, no API key, no npm/clasp build step — this is a single file pasted into the Apps
 * Script online editor, exactly as thick as it needs to be and no thicker (CLOUD_SAVES.md's own
 * "This is a manual save-file, in a spreadsheet," not a real sync engine).
 */

// ---------------------------------------------------------------------------------------------
// Wire-format constants (CLOUD_SAVES.md section 3).
// ---------------------------------------------------------------------------------------------

var SECRET_PROPERTY_NAME = 'SECRET';
var FORMAT_VERSION = 1;

// Apps Script / Sheets per-cell character limit (CLOUD_SAVES.md section 5 item 7). A `_payload`
// cell for an 18-hole, 6-player round is comfortably under 3 KB, so this is an assertion that
// should never fire in practice -- if it ever does, that's a sign the payload shape grew a lot
// bigger than anticipated and needs to split across two columns rather than silently truncate.
var CELL_CHARACTER_LIMIT = 50000;

var ROUNDS_SHEET_NAME = 'Rounds';
var COURSES_SHEET_NAME = 'Courses';
var PLAYERS_SHEET_NAME = 'Players';
var META_SHEET_NAME = '_Meta';

// Fixed (non-hole) Rounds columns, before the h1..hN block and after it.
var ROUNDS_FIXED_HEADERS = ['roundId', 'date', 'course', 'layout', 'player', 'total', 'toPar'];
var ROUNDS_PAYLOAD_HEADER = '_payload';
var ROUNDS_MIN_HOLE_COLUMNS = 18; // "Start at 18 and let the script widen it" (CLOUD_SAVES.md section 4)

var COURSES_HEADERS = ['courseId', 'course', 'layoutId', 'layout', 'holes', 'pars', 'recordToPar', 'recordHolders'];
var PLAYERS_HEADERS = ['playerId', 'name', 'color', 'rounds'];

// CLOUD_SAVES.md section 4 describes _Meta as "timestamp, deviceId, app versionName, counts,
// format version" -- the request envelope (section 3) has no field carrying the app's
// versionName, though, so there is nothing to put in that column yet. Rather than invent a new
// wire-format field unasked, this omits "app versionName" entirely and notes the gap here; add an
// `appVersion` field to the push request (BackupClient.kt's buildPushRequestBody) and a matching
// column here if that turns out to matter later.
var META_HEADERS = ['timestamp', 'deviceId', 'players', 'courses', 'rounds', 'formatVersion'];

// ---------------------------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------------------------

function doPost(e) {
  var response;
  try {
    var request = JSON.parse(e.postData.contents);
    response = handleRequest(request);
  } catch (err) {
    // Malformed JSON, a missing e.postData, or anything else unexpected -- reported the same
    // shape as a normal ok:false response so the watch's parsePushResponse/parsePullResponse
    // (BackupClient.kt) handles it exactly like an authorization failure: drop bad input, never
    // 500 (CLOUD_SAVES.md's "drop bad records, never crash" design principle, applied here too).
    response = { ok: false, error: 'bad request: ' + err.message };
  }
  return ContentService.createTextOutput(JSON.stringify(response)).setMimeType(ContentService.MimeType.JSON);
}

function handleRequest(request) {
  var expectedSecret = PropertiesService.getScriptProperties().getProperty(SECRET_PROPERTY_NAME);
  if (!expectedSecret) {
    return { ok: false, error: 'server not configured: no SECRET script property set' };
  }
  if (!request || request.secret !== expectedSecret) {
    return { ok: false, error: 'unauthorized' };
  }
  if (request.op === 'push') {
    return handlePush(request);
  }
  if (request.op === 'pull') {
    return handlePull();
  }
  return { ok: false, error: 'unknown op: ' + request.op };
}

// ---------------------------------------------------------------------------------------------
// Push: the watch's data is authoritative. Rewrite Players/Courses wholesale (cheap: there is no
// merging to do, the app just handed over its full current roster/course list); upsert Rounds by
// roundId, never deleting a round the app doesn't currently know about (CLOUD_SAVES.md section 2
// "Push is upsert, never delete" -- a round removed on the watch stays in the sheet until someone
// deletes its row by hand).
// ---------------------------------------------------------------------------------------------

function handlePush(request) {
  var data = request.data || {};
  var players = data.players || [];
  var courses = data.courses || [];
  var rounds = data.rounds || [];

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var roundsSheet = getOrCreateSheet(ss, ROUNDS_SHEET_NAME);
  upsertRoundsTab(roundsSheet, rounds);

  // Players' derived `rounds` column counts rows in the just-updated Rounds tab, so Players is
  // written after Rounds, not before.
  var playersSheet = getOrCreateSheet(ss, PLAYERS_SHEET_NAME);
  writePlayersTab(playersSheet, players, roundsSheet);

  var coursesSheet = getOrCreateSheet(ss, COURSES_SHEET_NAME);
  writeCoursesTab(coursesSheet, courses);

  var metaSheet = getOrCreateSheet(ss, META_SHEET_NAME);
  appendMetaRow(metaSheet, request.deviceId || '', players.length, courses.length, rounds.length);

  return {
    ok: true,
    v: FORMAT_VERSION,
    counts: { players: players.length, courses: courses.length, rounds: rounds.length },
    warnings: [],
  };
}

/** Fully rewrites the Players tab from [players] (`CLOUD_SAVES.md` section 2: no `_payload` here -- the readable columns *are* the backup). `color` is written as `#RRGGBB`, dropping the alpha byte every [Player.color] in this app always sets to full opacity; JSON keeps the canonical decimal `Long`, this tab is for reading. */
function writePlayersTab(sheet, players, roundsSheet) {
  var roundCounts = countRoundsPerPlayerName(roundsSheet);
  var rows = players.map(function (player) {
    return [player.id, player.name, colorDecimalToHex(player.color), roundCounts[player.name] || 0];
  });
  clearAndWrite(sheet, PLAYERS_HEADERS, rows);
}

function colorDecimalToHex(decimalColor) {
  // decimalColor is a packed ARGB value (Player.color); Apps Script's numbers are doubles, but
  // this stays well within the 53-bit safe-integer range. Mask off alpha (this app's player
  // colors are always fully opaque) and left-pad each byte to two hex digits.
  var rgb = decimalColor & 0xffffff;
  var hex = rgb.toString(16).toUpperCase();
  while (hex.length < 6) hex = '0' + hex;
  return '#' + hex;
}

/** How many player-round rows already on [roundsSheet] have `player` equal to each name -- the Players tab's derived `rounds` column (`CLOUD_SAVES.md` section 4: "derived by the script... ignored on pull"). Matched by *name*, not id, since that's the only thing the Rounds tab's readable columns carry (the id lives only inside `_payload`) -- a renamed player's older rows won't reconcile under their new name, the same snapshot-by-name limitation `RoundState.players` already has everywhere else in this app. */
function countRoundsPerPlayerName(roundsSheet) {
  var counts = {};
  var values = roundsSheet.getDataRange().getValues();
  var playerColumn = ROUNDS_FIXED_HEADERS.indexOf('player');
  for (var row = 1; row < values.length; row++) { // skip header row
    var name = values[row][playerColumn];
    if (!name) continue;
    counts[name] = (counts[name] || 0) + 1;
  }
  return counts;
}

/** Fully rewrites the Courses tab, one row per layout (`CLOUD_SAVES.md` section 4). No `_payload` -- every field here fits a column losslessly, so these columns are themselves the restore source. */
function writeCoursesTab(sheet, courses) {
  var rows = [];
  courses.forEach(function (course) {
    (course.layouts || []).forEach(function (layout) {
      rows.push([
        course.id,
        course.name,
        layout.id,
        layout.name,
        layout.holeCount,
        (layout.pars || []).join(','),
        layout.recordToPar === null || layout.recordToPar === undefined ? '' : layout.recordToPar,
        (layout.recordHolders || []).join(', '),
      ]);
    });
  });
  clearAndWrite(sheet, COURSES_HEADERS, rows);
}

/**
 * Upserts [rounds] into the Rounds tab by `roundId` (column A): every existing row-group whose
 * roundId matches an incoming round is removed first, then fresh rows for every incoming round are
 * appended -- never the reverse, and a round already in the sheet but *absent* from [rounds] is
 * never touched (CLOUD_SAVES.md section 2 "Push is upsert, never delete"). The whole data range is
 * then re-sorted by `date` descending so the tab reads newest-first after every push, matching
 * section 4's stated row order, rather than only being newest-first by accident of insert order.
 *
 * The `h1..hN` block widens (never narrows) to fit the largest hole count seen in [rounds] or
 * already present in the sheet -- "Start at 18 and let the script widen it."
 */
function upsertRoundsTab(sheet, rounds) {
  ensureRoundsHeader(sheet, maxHoleCount(rounds));
  var header = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  var payloadColumn = header.indexOf(ROUNDS_PAYLOAD_HEADER);
  var holeColumnCount = payloadColumn - ROUNDS_FIXED_HEADERS.length;

  var incomingIds = {};
  rounds.forEach(function (round) { incomingIds[String(round.id)] = true; });

  var lastRow = sheet.getLastRow();
  var survivingRows = [];
  if (lastRow > 1) {
    var existing = sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
    existing.forEach(function (row) {
      if (!incomingIds[String(row[0])]) survivingRows.push(row);
    });
  }

  var freshRows = [];
  rounds.forEach(function (round) {
    buildRoundRows(round, holeColumnCount).forEach(function (row) { freshRows.push(row); });
  });

  var allRows = survivingRows.concat(freshRows);
  allRows.sort(function (a, b) {
    var dateColumn = ROUNDS_FIXED_HEADERS.indexOf('date');
    return toEpochMillis(b[dateColumn]) - toEpochMillis(a[dateColumn]); // descending -- newest first
  });

  sheet.getRange(2, 1, Math.max(sheet.getMaxRows() - 1, 1), sheet.getLastColumn()).clearContent();
  if (allRows.length > 0) {
    sheet.getRange(2, 1, allRows.length, sheet.getLastColumn()).setValues(allRows);
  }
}

function toEpochMillis(value) {
  if (value instanceof Date) return value.getTime();
  return 0;
}

function maxHoleCount(rounds) {
  var max = 0;
  rounds.forEach(function (round) {
    var count = (round.holes || []).length;
    if (count > max) max = count;
  });
  return max;
}

/** Creates the Rounds tab's header (fixed columns + `h1..hN` + `_payload`) if it doesn't exist yet, or widens the existing `h1..hN` block if [neededHoleColumns] is larger than what's already there. Never narrows -- a sheet that once saw a 27-hole round keeps 27 hole columns even after every round since has had fewer. */
function ensureRoundsHeader(sheet, neededHoleColumns) {
  var width = Math.max(ROUNDS_MIN_HOLE_COLUMNS, neededHoleColumns);
  if (sheet.getLastRow() === 0) {
    sheet.getRange(1, 1, 1, ROUNDS_FIXED_HEADERS.length + width + 1).setValues([buildRoundsHeaderRow(width)]);
    return;
  }
  var header = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  var currentPayloadColumn = header.indexOf(ROUNDS_PAYLOAD_HEADER);
  var currentWidth = currentPayloadColumn - ROUNDS_FIXED_HEADERS.length;
  if (width > currentWidth) {
    // Insert the extra h(n) columns just before the existing _payload column, then rewrite the
    // whole header row -- existing data rows keep their values in every column to the left of the
    // insertion point untouched; the newly inserted hole columns are blank on every existing row,
    // which is exactly "shorter rounds leave the tail blank" (CLOUD_SAVES.md section 4).
    sheet.insertColumnsBefore(currentPayloadColumn + 1, width - currentWidth);
    sheet.getRange(1, 1, 1, ROUNDS_FIXED_HEADERS.length + width + 1).setValues([buildRoundsHeaderRow(width)]);
  }
}

function buildRoundsHeaderRow(holeColumnCount) {
  var holeHeaders = [];
  for (var i = 1; i <= holeColumnCount; i++) holeHeaders.push('h' + i);
  return ROUNDS_FIXED_HEADERS.concat(holeHeaders).concat([ROUNDS_PAYLOAD_HEADER]);
}

/**
 * One player-round's rows: one per player in [round.players] (the round's snapshot roster), newest
 * player-count-many rows sharing the same roundId/date/course/layout. `total`/`toPar` are computed
 * the exact same way `RoundState.strokesThrough`/`toPar` do on the watch -- summed over holes
 * `1..currentHole`, never the whole card -- so the sheet can never disagree with the app about a
 * score. `_payload` (the verbatim round JSON the watch sent, unchanged) is written once, on the
 * first player's row, and left blank on the rest (`CLOUD_SAVES.md` section 2 "`_payload` on the
 * round's first row only").
 */
function buildRoundRows(round, holeColumnCount) {
  var players = round.players || [];
  var holes = round.holes || [];
  var currentHole = round.currentHole || holes.length;
  var countedHoles = holes.slice(0, currentHole);

  var payloadJson = JSON.stringify(round);
  if (payloadJson.length > CELL_CHARACTER_LIMIT) {
    // CLOUD_SAVES.md section 5 item 7: this should never happen for a real round, but fail loudly
    // (a script error surfaces as an ok:false response) rather than silently truncate a payload
    // that would then fail to decode on the way back down.
    throw new Error('round ' + round.id + ' payload exceeds the ' + CELL_CHARACTER_LIMIT + '-character cell limit');
  }

  return players.map(function (player, index) {
    var total = 0;
    var toPar = 0;
    countedHoles.forEach(function (hole) {
      var strokes = (hole.strokes || {})[player.id];
      if (typeof strokes === 'number') {
        total += strokes;
        toPar += strokes - hole.par;
      }
    });

    var holeCells = [];
    for (var h = 0; h < holeColumnCount; h++) {
      holeCells.push(h < countedHoles.length ? countedHoles[h].par : '');
    }

    return [round.id, new Date(round.finishedAt), round.courseName, round.layoutName, player.name, total, toPar]
      .concat(holeCells)
      .concat([index === 0 ? payloadJson : '']);
  });
}

/** Appends one `_Meta` row: a real timestamp, the pushing device's id (provenance only -- never used for merging), the counts just written, and this script's format version -- the audit trail for "did that backup actually go through, and when" (`CLOUD_SAVES.md` section 4). */
function appendMetaRow(sheet, deviceId, playerCount, courseCount, roundCount) {
  if (sheet.getLastRow() === 0) sheet.appendRow(META_HEADERS);
  sheet.appendRow([new Date(), deviceId, playerCount, courseCount, roundCount, FORMAT_VERSION]);
}

// ---------------------------------------------------------------------------------------------
// Pull: reconstruct a BackupData-shaped JSON document from whatever is currently on the sheet.
// Rounds' restore source is the `_payload` column, verbatim -- the readable columns on that tab
// are for humans only and are ignored here entirely (CLOUD_SAVES.md section 4). Players/Courses
// have no payload column, so their readable columns *are* parsed, and a hand-edit mistake there
// (CLOUD_SAVES.md's "tradeoff on hand-editing") produces a warning and a dropped row rather than
// a bad restore -- the same "drop bad records, never crash" rule the watch's own codec follows.
// ---------------------------------------------------------------------------------------------

function handlePull() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var warnings = [];

  var players = readPlayersTab(getOrCreateSheet(ss, PLAYERS_SHEET_NAME), warnings);
  var courses = readCoursesTab(getOrCreateSheet(ss, COURSES_SHEET_NAME), warnings);
  var rounds = readRoundsPayloads(getOrCreateSheet(ss, ROUNDS_SHEET_NAME), warnings);

  return {
    ok: true,
    v: FORMAT_VERSION,
    data: { players: players, courses: courses, rounds: rounds },
    warnings: warnings,
  };
}

function readPlayersTab(sheet, warnings) {
  var values = sheet.getDataRange().getValues();
  var players = [];
  for (var row = 1; row < values.length; row++) {
    var cells = values[row];
    var id = String(cells[0] || '').trim();
    var name = String(cells[1] || '').trim();
    if (!id || !name) {
      if (cells.join('')) warnings.push('Players!A' + (row + 1) + ': missing playerId or name, row skipped');
      continue;
    }
    players.push({ id: id, name: name, color: hexToColorDecimal(cells[2]) });
  }
  return players;
}

function hexToColorDecimal(hex) {
  var cleaned = String(hex || '').replace('#', '');
  var rgb = parseInt(cleaned, 16);
  if (isNaN(rgb)) return 0xffffffff; // DEFAULT_PLAYER_COLOR (opaque white) -- see Player.kt
  return 0xff000000 + rgb; // opaque -- every color this app writes always is
}

function readCoursesTab(sheet, warnings) {
  var values = sheet.getDataRange().getValues();
  var coursesById = {};
  var order = [];
  for (var row = 1; row < values.length; row++) {
    var cells = values[row];
    var courseId = String(cells[0] || '').trim();
    var courseName = String(cells[1] || '').trim();
    var layoutId = String(cells[2] || '').trim();
    var layoutName = String(cells[3] || '').trim();
    var holeCount = parseInt(cells[4], 10);
    var rowNumber = row + 1;

    if (!courseId || !courseName || !layoutId || !layoutName || !holeCount || holeCount <= 0) {
      if (cells.join('')) warnings.push('Courses!A' + rowNumber + ': missing id/name/holeCount, row skipped');
      continue;
    }

    var parsText = String(cells[5] || '');
    var pars = parsText.length === 0 ? [] : parsText.split(',').map(function (s) { return parseInt(s.trim(), 10); });
    var parsValid = pars.length === holeCount && pars.every(function (p) { return !isNaN(p); });
    if (!parsValid) {
      warnings.push('Courses!F' + rowNumber + ': par list length ' + pars.length + ' ≠ hole count ' + holeCount + ', layout skipped');
      continue;
    }

    var recordToParText = String(cells[6] === undefined || cells[6] === null ? '' : cells[6]).trim();
    var recordToPar = null;
    if (recordToParText.length > 0) {
      var parsedRecord = parseInt(recordToParText, 10);
      if (isNaN(parsedRecord)) {
        warnings.push('Courses!G' + rowNumber + ': recordToPar is not a number, ignored (layout kept)');
      } else {
        recordToPar = parsedRecord;
      }
    }
    var recordHolders = recordToPar === null ? [] : String(cells[7] || '').split(',').map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });

    var layout = { id: layoutId, name: layoutName, holeCount: holeCount, pars: pars };
    if (recordToPar !== null) {
      layout.recordToPar = recordToPar;
      layout.recordHolders = recordHolders;
    }

    if (!coursesById[courseId]) {
      coursesById[courseId] = { id: courseId, name: courseName, layouts: [] };
      order.push(courseId);
    }
    coursesById[courseId].layouts.push(layout);
  }

  return order
    .map(function (id) { return coursesById[id]; })
    .filter(function (course) { return course.layouts.length > 0; }); // a course with zero surviving layouts can't be restored (Course.layouts is never empty -- see Course.kt)
}

/** Every non-blank `_payload` cell, parsed back into the round object the watch originally sent — the entire restore source for the Rounds tab (`CLOUD_SAVES.md` section 4: "the readable columns are ignored on pull"). A cell that isn't valid JSON (hand-mangled by someone editing the sheet directly) is dropped with a warning rather than failing the whole pull. */
function readRoundsPayloads(sheet, warnings) {
  var values = sheet.getDataRange().getValues();
  var header = values[0] || [];
  var payloadColumn = header.indexOf(ROUNDS_PAYLOAD_HEADER);
  if (payloadColumn === -1) return [];

  var rounds = [];
  for (var row = 1; row < values.length; row++) {
    var payloadCell = values[row][payloadColumn];
    if (!payloadCell) continue; // blank on every row but a round's first, by design
    try {
      rounds.push(JSON.parse(payloadCell));
    } catch (err) {
      warnings.push('Rounds!row ' + (row + 1) + ': _payload is not valid JSON, round skipped');
    }
  }
  return rounds;
}

// ---------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------

function getOrCreateSheet(spreadsheet, name) {
  var sheet = spreadsheet.getSheetByName(name);
  if (sheet) return sheet;
  return spreadsheet.insertSheet(name);
}

/** Replaces a tab's entire content (header + data rows) in one shot -- used for Players/Courses, which are always fully rewritten on push rather than upserted (there is nothing to preserve: the watch just handed over its complete, current list). */
function clearAndWrite(sheet, headers, rows) {
  sheet.clear();
  var allRows = [headers].concat(rows);
  sheet.getRange(1, 1, allRows.length, headers.length).setValues(allRows);
}
