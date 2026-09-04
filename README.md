# STAR CROSSED — Android Native Media Layer

## 0. Changelog

### 2026-09-03 — self-pause still unresolved: the previous fix is disproven, real APK forensics, root cause NOT yet found, full diagnostic instrumentation added

**The 2026-09-01 "competing audio focus" fix did not resolve the issue** — confirmed by the reporter, and independently confirmed here a different way: the actual debug APK built and tested (build timestamp Sep 2, 11:29) was extracted and its bundled `assets/STAR_CROSSED_v47.html` checksummed byte-for-byte against that commit's source. It matches exactly. That fix genuinely shipped, genuinely ran, and the bug persisted. That hypothesis is retracted, not revised.

**This pass changes approach entirely, per explicit instruction: no new hypothesis was implemented. Instead —**

**Real artifact forensics, for the first time.** Three actual built APKs and their zip/git history were provided. Installed `androguard` and, for the first time in this whole project, checked the *actual compiled output* instead of only source:

- Decoded the real, post-merge `AndroidManifest.xml` from inside the APK (not just the source manifest fed into the build) — clean, matches source exactly, including `stopWithTask="false"` and `foregroundServiceType`. Rules out a manifest-merger surprise from a bundled library.
- Disassembled all 4 `classes*.dex` files (~15MB, ~10,500 classes total — the full app plus every bundled Media3/AndroidX/Kotlin-stdlib class) and searched every method body, across every class in every library, for any `invoke-*` instruction targeting `pause()`, `setPlayWhenReady()`, `stop()`, or `handleSetPlayWhenReady()`. This is not a source read — it is what the compiler actually produced. Result: **this app's own code contains exactly four call sites that can pause the player** — `becomingNoisyReceiver`, `audioFocusListener` (×2, LOSS/LOSS_TRANSIENT), and `executeWidgetAction` — all previously known, all gated on real external events (headset disconnect, real focus loss, a widget tap) that don't occur on a fresh in-app play. No hidden caller exists in this app's compiled code.
- Also found and checked `MediaSessionService.pauseAllPlayersAndStopSelf` (an internal Media3 method) out of caution — zero call sites reference it anywhere in this APK. Not reachable, not relevant.

**Exhaustive JS-side audit.** Every single `.pause()` occurrence in the HTML (10 total, found by plain grep, not just the ones already known from prior passes) was traced to its calling context: `_stopPlayback` (queue-empty/track-removed only), `togglePlay`, `_androidForcePause`, two crossfade-related calls in `AudioEngine` (`loadPlay`'s non-crossfade branch, `tickXf`), and two sleep-timer branches (end-of-track, countdown — inactive unless a sleep timer is explicitly set). None explain a spontaneous pause on a fresh, default-settings, first-ever track play.

**Specific alternative hypotheses checked and ruled out, not assumed:**
- Dual-slot sharing the same underlying `<audio>` element — checked the actual slot-construction code; each slot gets its own `new Audio()`, confirmed independent.
- `ae.audio` resolving to a stale slot — it's `get audio(){return this.slots[this.cur].el;}`, a real dynamic getter; cannot desync from `this.cur`.
- `_scheduleAutoXf` firing too early — it has its own duration/delay/track-identity guards and only ever calls `next()`, never `pause()`.
- A stale/mismatched build — ruled out directly (see APK-vs-source checksum above).

**What remains true: the exact root cause is not yet proven.** Extensive elimination narrows the field considerably (it's very unlikely to be this app's own explicit pause-calling code, JS-side or native), but "very unlikely to be X" is not the same as "proven to be Y." The two open possibilities that elimination *can't* rule out from static/bytecode analysis alone: (a) an external controller (system notification/lockscreen/Bluetooth stack — a different process, invisible to this APK's own bytecode) sending a real pause command immediately after connecting, or (b) WebView's own Chromium-internal audio-focus handling for the `<audio>` element pausing it directly, without going through any bridge call this app can see in its own code (which would also explain why disabling the *Web* MediaSession API specifically didn't help, if the deeper trigger is `<audio>`-element-level rather than API-level). Both are testable — neither is confirmed.

**Full diagnostic instrumentation added, exactly as requested, purely additive:**
- **JS side:** a `_scDiag(tag, msg)` helper logs to the console (visible live via `chrome://inspect`, since `setWebContentsDebuggingEnabled(true)` is already on for debug builds) *and* forwards to native logcat. Instrumented: every `.pause()`/`.play()` call site by name, the `play`/`pause` DOM event handlers themselves (with slot index, current time, duration), `loadPlay()`'s play()-resolved/play()-rejected/crossfade-decision points, and both native→JS entry points (`onAndroidPlay`/`onAndroidPause`).
- **Native side:** every `WebViewProxyPlayer.handle*()` override now logs on entry (this is the funnel *any* command — internal or from an external controller — must pass through to reach JS). `updateFromJs()` logs every JS-reported state change. `MediaSession.Callback.onConnect` logs which controller (package name + uid) connects. A temporary `onPlayerCommandRequest` override (deprecated API, still functional, `@Suppress`-ed, always returns `RESULT_SUCCESS` — identical to the implicit default, so no behavior change) logs which controller requests which command, closing the "is it an external controller" question directly. Service lifecycle (`onCreate`/`onDestroy`/`onTaskRemoved`/`onGetSession`) also logs, to catch an unexpected restart.
- Both sides share two logcat tags: `SCMediaService` (service + session + lifecycle) and `SCWebBridge` (the JS bridge, including forwarded JS console logs) — `adb logcat -s SCMediaService SCWebBridge` shows one correlated, chronological stream spanning JS and native.

**Root cause identified but runtime verification unavailable" does not yet apply — the root cause is not yet identified.** This sandbox still has no Android SDK, emulator, or device; nothing in this project has ever been run by any Claude session, only built by CI and tested by the reporter. Reproduce the bug once with this build installed, capture both `adb logcat -s SCMediaService SCWebBridge` and the `chrome://inspect` console output for that one attempt, and share them — the log line immediately before the first `PAUSE fired` line (or, if none of the four known native callers logged anything and no `onAndroidPause` arrived, the *absence* of any native-side pause trigger, pointing straight at hypothesis (b) above) will identify the actual cause directly, rather than through more elimination.

`versionCode`/`versionName` bumped to `7` / `"47.0-android7-diag"` — named `-diag` deliberately, as a reminder this build's purpose is evidence-gathering, not a claimed fix.

### 2026-09-01 — playback self-pausing immediately after play (critical)

Reported symptom: tapping Play starts audio for a fraction of a second,
then it pauses itself; tapping Play again repeats the same thing —
playback was effectively unusable.

**Root cause — high confidence, not device-verified (see "What I could not
verify" below):** this app runs TWO independent things that can each ask
Android for audio focus on the exact same `<audio>` element, uncoordinated
with each other:

1. This project's own native `MediaSession`/`AudioManager.requestAudioFocus()`
   (`StarCrossedMediaService`, unchanged in this pass, present since the
   very first commit).
2. The HTML's `_mediaSession()` registering `navigator.mediaSession`
   (the *Web* Media Session API) — which hands the page's audio off to
   WebView's own Chromium-internal media-session/audio-focus handling,
   entirely separate from and unaware of (1).

Both requesting `AUDIOFOCUS_GAIN` for the same audio, in the same
process, is exactly the kind of setup that causes this: Android's
`AudioManager` doesn't deduplicate focus requests by app/UID — each
`AudioFocusRequest` is tracked independently, so whichever of the two
registers *second* steals focus back from the first, which — being
well-behaved — silences itself in response. Confirmed against Chromium's
own source/docs that this Web-MediaSession-driven, Android-side focus
handling (`MediaSessionDelegate`) is real or WebView (not just full
Chrome), and separately found a second, independent, definite bug in the
exact same function that would make this worse whenever it fires: **both
the `'play'` and `'pause'` Web MediaSession action handlers called the
same `togglePlay()`** — so any such command arriving is as likely to
flip playback the wrong way as the right one.

**Fix — two changes, no delays/loops/heuristics, both root-cause-level:**

1. `_mediaSession()` (in the Android-patched HTML only) now returns
   immediately when it detects it's running inside this app
   (`typeof window.AndroidMedia!=='undefined'`, the same detection this
   patch already uses elsewhere) — the native MediaSession already
   provides everything the Web one would (system notification, lockscreen,
   Bluetooth, audio focus), so registering both is pure redundancy with a
   real downside. The plain browser build is untouched — it has no native
   layer providing any of this, so it keeps using `navigator.mediaSession`
   exactly as before.
2. Its `play`/`pause` handlers (for anyone who *does* hit this code path
   — the plain browser build, or if this hypothesis turns out to be wrong
   for the Android case) now call `_androidForcePlay()`/
   `_androidForcePause()` instead of `togglePlay()` — idempotent,
   explicit-direction, reusing functions this file already has for
   exactly this reason (see their own doc comment) rather than adding new
   logic.

**Diagnostics added, zero behavior change:** `StarCrossedMediaService`
now logs (`Log.d`, tag `SCMediaService`) every audio-focus request/result,
every `abandonAudioFocus()` call, and every focus-change callback with its
symbolic name, plus when a JS-reported play/pause transition triggers a
focus request. If this issue persists, `adb logcat -s SCMediaService`
during a reproduction will show directly whether a
`AUDIOFOCUS_LOSS[_TRANSIENT]` callback fires immediately after
`requestAudioFocus() -> GRANTED` (confirming or ruling out this exact
mechanism) — real evidence instead of another round of guessing.

**What I could not verify:** this sandbox still has no Android SDK,
emulator, or device — nothing in this project has ever actually been
compiled or run by any Claude session; every fix across every pass,
including this one, has been sourced/config-traced and mechanically
checked, never test-run. The root cause above is a well-evidenced
hypothesis (checked against Chromium's own source/release notes, not
guessed), and the `togglePlay()` mixup is a definite, independently-real
bug regardless of whether it's the primary cause — but "plays reliably
now" is a claim only an actual build on a device can verify. Please run
the checklist below (§6) and check logcat as described above; if it
still self-pauses, the logs will show whether this hypothesis was right.

`versionCode`/`versionName` bumped to `6` / `"47.0-android6"`.

### 2026-08-31 — Browse Files / Import Media fixed (WebChromeClient gap)

Both actions called the same JS pipeline (`$('importBtn')` / the library
tab's empty-state "Browse Files" button both just do
`$('fileInput').click()`), which traces to one root cause: the WebView's
`webChromeClient` was a bare `WebChromeClient()` with no override. Its
default `onShowFileChooser()` — confirmed directly from AOSP's
`WebChromeClient.java` — is `return false`, and returning `false` there
means exactly "use default handling," which for a file `<input>` is
nothing: no picker, no error, no callback. That's the entire bug; the rest
of the JS-side pipeline (`_importFiles`, `_readFile`, `getAudioDuration`,
the IndexedDB write) was read start to finish and is correct as-is —
nothing there needed to change.

- **Fix, three files, no manifest changes:**
  `StarCrossedMediaService`'s WebChromeClient now overrides
  `onShowFileChooser`, holds the pending `ValueCallback` (this WebView is
  service-owned, and only an Activity can launch a picker — same
  Service→Activity handoff shape already used for the notification-
  permission request), and exposes `completeFileChooser(uris)` for
  MainActivity to call back into. `PlaybackStateManager` gained one more
  request flow (`fileChooserRequests`) for that handoff.
  `MainActivity` launches the picker via
  `FileChooserParams.createIntent()` and parses the result via the
  matching static `FileChooserParams.parseResult()` — Android's own
  documented pair for this exact handoff (see `WebChromeClient.java`'s
  `createIntent()` JavaDoc for the 5-step usage contract this follows),
  used instead of hand-building the intent/parsing the result manually so
  this can't drift from whatever a given device's specific WebView
  provider actually expects.
- **No permission added, deliberately.** `ACTION_GET_CONTENT` (what
  `createIntent()` builds) has never required a runtime permission on any
  Android version — the picker runs as a separate, trusted process and
  hands back a `content://` Uri with a temporary, scoped read grant for
  just the selected file(s). `READ_MEDIA_AUDIO` (13+) /
  `READ_EXTERNAL_STORAGE` are a different, unneeded access pattern
  (direct `MediaStore`/filesystem queries), not something picker-mediated
  selection ever requires. Since STAR CROSSED reads each picked file
  exactly once, immediately, into an in-memory `ArrayBuffer` and stores it
  in its own IndexedDB library (`_importFiles`/`_readFile`, unchanged),
  there's no later access to provision for either — no persistable URI
  grant needed. This satisfies the "don't add unnecessary storage
  permissions" requirement about as completely as it can be satisfied:
  nothing was added because nothing is actually needed.
- **Fixes two related, identically-broken imports as a side effect, not
  three separate patches.** The fix is at the WebChromeClient level, which
  is shared by every `<input type="file">` on the page — `plFileInput`
  (playlist JSON import) and `cfgFileInput` (config JSON import) both had
  the exact same silent-failure bug and are both fixed by the same change,
  with zero input-specific code anywhere.
- **Handles, by construction rather than bespoke code:** single selection
  (`Intent#getData`), multiple selection (`Intent#getClipData` — `fileInput`
  has the `multiple` attribute; `parseResult()` resolves this
  automatically), cancel (`RESULT_CANCELED` → `parseResult()` returns
  `null` → `onReceiveValue(null)`, which is exactly what WebView expects
  and what `_importFiles`'s own `change` handler already no-ops on
  gracefully), and an unresolvable picker Intent
  (`ActivityNotFoundException` → completes the callback with `null` rather
  than leaving the JS `<input>` waiting forever — realistically
  unreachable, since every Android device ships a document provider, but
  handled rather than assumed). Invalid/unreadable individual files are
  already handled entirely on the JS side (`_importFiles`'s existing
  per-file `try/catch` — toasts "Failed: <name>" and continues with the
  rest) and needed no native-side change.
- **What I did not do:** run this on a device or emulator. This sandbox
  still has neither. Verified by fetching and reading the actual AOSP
  `WebChromeClient.java` source (confirming the default-`false` root cause
  and the exact `createIntent()`/`parseResult()` contract), tracing the
  complete JS-side pipeline line-by-line, and the same mechanical
  resource/brace-balance checks as every prior pass — not by testing.
  Building and running it is the only way to actually confirm the picker
  opens and a file lands in the library.

`versionCode`/`versionName` bumped to `5` / `"47.0-android5"`.

### 2026-08-30 — startup icon replaced, first real on-device confirmation

A screenshot from an actual debug build (`com.starcrossed.app.debug`, matching
this project's own debug `applicationIdSuffix` — the first real evidence any
version of this project has actually run) showed a solid cyan star at launch,
before the WebView's own splash takes over.

- **Root cause, found by tracing the theme, not guessed:**
  `Theme.StarCrossed.Splash`'s `windowSplashScreenAnimatedIcon` — the Android
  12+ SplashScreen API attribute controlling the icon shown the instant the
  app launches — pointed at `@drawable/ic_notification`, a small placeholder
  vector (a flat cyan 5-point star, `#00EEFF` — exactly the star in the
  screenshot). That drawable is *also* used, correctly and separately, as
  placeholder art for the home-screen widget (its default layout image and
  the widget-picker preview) before any track has loaded — those three uses
  are untouched; only the splash theme's reference was ever wrong.
- **Fix:** added the supplied photo as a new `app/src/main/res/drawable/
  ic_startup_icon.png` — a byte-for-byte copy (verified by checksum),
  unedited/uncropped/unrecolored — and pointed `windowSplashScreenAnimatedIcon`
  at it instead. Nothing else in the splash theme (background color, icon
  background color, animation timing, the handoff to `Theme.StarCrossed`)
  was touched. The image is identical to the one already used for the
  launcher icon (confirmed by checksum — same supplied photo), already
  composed with adaptive-icon-safe-zone margins, which happens to line up
  well with the SplashScreen API's own similar circular-safe-zone icon
  sizing convention.
- **Full-project audit, as requested:** re-checked every resource reference
  (`R.*` in Kotlin, `@type/name` in every XML file) mechanically against
  what's actually declared — nothing unresolved. Checked the rest of the
  launch path (`activity_main.xml`, `MainActivity.onCreate`, manifest theme
  wiring) for any other place a wrong asset could flash before the WebView
  paints — solid `sc_void` black throughout, no other image reference
  anywhere in that path. One genuine gap found and **flagged, not changed**
  (see §5): the media-session notification's small status-bar icon is never
  explicitly set, so Media3 falls back to its own generic bundled icon
  rather than any app branding. Left alone deliberately — it's a different
  UI surface than what was asked about, the fix would mean deciding what
  glyph to reuse there, and that's a branding call, not an audit fix.
- **What I did not do:** run this on a device or emulator — this sandbox
  still has neither. Everything above is verified the way the rest of this
  project has been: real source/config tracing and mechanical checks, not
  a claimed test run. The one actual on-device data point in this whole
  project so far is the screenshot that found this bug in the first place —
  rebuilding and reinstalling is the only way to confirm the fix from here.

`versionCode`/`versionName` bumped to `4` / `"47.0-android4"`.

### 2026-08-29 — real Media3 source verification, several confirmed bugs fixed

This pass had something the two before it didn't: no Android SDK, but full
read access to `androidx/media`'s actual source on GitHub. Every API call
this project makes against `SimpleBasePlayer`, `BasePlayer`,
`DefaultMediaNotificationProvider`, and `MediaSessionService` was checked
against the real `.java` source at the pinned tags (1.2.1 and 1.4.1) rather
than against documentation or memory — a source-level review, not just a
closer reading. That surfaced several real, confirmed bugs the two prior
passes' manual reads didn't catch (unsurprising — they're not the kind of
thing a read-through finds; each one below required pulling the actual
framework source):

- **The likely real reason this was flagged as "could not be compiled":**
  `SimpleBasePlayer` is itself annotated `@UnstableApi`, and `UnstableApi`
  is a Kotlin `@RequiresOptIn(level = ERROR)` annotation — confirmed by
  reading `UnstableApi.java` directly. Any file subclassing or referencing
  `SimpleBasePlayer` needs an explicit `@OptIn(UnstableApi::class)` or the
  Kotlin compiler rejects it outright, and this file had no such opt-in
  anywhere. Added where actually needed (`WebViewProxyPlayer`,
  `StarCrossedMediaService`, and the new favorite-button code below) — not
  scattered everywhere out of caution.
- **`Player.COMMAND_RELEASE` was missing from `availableCommands`.**
  Confirmed in `SimpleBasePlayer.release()`'s source: it's gated by
  `shouldHandleCommand(COMMAND_RELEASE)`, so without this,
  `handleRelease()` silently never ran and `onDestroy()` never actually
  called `player.release()` either — both fixed.
- **Next/Previous didn't work from every input path.** Confirmed directly
  in `BasePlayer`'s source: `seekToNext()`/`seekToPrevious()` (called by
  hardware/Bluetooth media buttons, and by this app's own widget via
  `player.seekToNext()`) dispatch the *bare* `COMMAND_SEEK_TO_NEXT`/
  `COMMAND_SEEK_TO_PREVIOUS`, while `DefaultMediaNotificationProvider`
  always dispatches a tapped notification button as the corresponding
  `*_MEDIA_ITEM` variant — regardless of which pair is what made the button
  visible. Only the `*_MEDIA_ITEM` pair was declared, so the notification
  buttons worked but the *widget's own* Next/Previous buttons — and likely
  hardware media buttons — silently did nothing. Both pairs are now
  declared and handled identically in `handleSeek()`.
- **The favorite/heart toggle had a dead end.** JS-side
  (`_androidToggleFavorite()`) and the dispatch string both already
  existed, but nothing ever called it — `SimpleBasePlayer` has no built-in
  rating/favorite hook to wire it to. Completed via Media3's custom-command
  channel: a heart `CommandButton` in the session's custom layout (shows in
  the notification), routed through `MediaSession.Callback.onCustomCommand`
  → `WebViewProxyPlayer.requestFavoriteToggle()`, kept in sync with
  JS-reported favorite status for whatever track is playing. This
  supersedes the "scoped to local library tracks only, not implemented for
  the system UI" note further down — see §5.
- **A related trap avoided, not hit, while adding the above:**
  `MediaSession.Callback.onConnect`'s
  `AcceptedResultBuilder.setAvailablePlayerCommands(...)` *intersects* with
  the player's own `Player.Commands` rather than adding to it (confirmed in
  `MediaSession`'s own source doc comment) — a hand-typed player-command
  list here could silently re-narrow what `WebViewProxyPlayer` already
  declares. The callback added for the favorite button only ever extends
  the *session*-command set, never touches player commands, so this can't
  happen.
- **Widget button touch feedback.** Minor, unrelated to the above:
  `?android:attr/selectableItemBackgroundBorderless` instead of a fully
  transparent background on the three transport buttons, for standard
  ripple feedback on tap.

None of this touched the icon, the Gradle wrapper, the manifest, or the
HTML — all of that from the 2026-08-28 pass is unchanged and still exactly
as described below. `versionCode`/`versionName` bumped to `3` /
`"47.0-android3"` to mark this pass.

**What's still true:** this still isn't an actual compile. Source-level
verification against the real framework code is the strongest check
possible without one, and it did find and fix real bugs a plain read didn't
— but "verified against source" and "verified by compiling" are different
claims, and only the latter is the real one. The GitHub Actions workflow
from the previous pass (§4, Option B) is exactly how to get that — this
push is a good time to let it run.

### 2026-08-28 — icon, Gradle wrapper, CI workflow

Starting point was the `StarCrossedAndroid_Copy_.7z` project from the
previous session, with a request to turn it into a downloadable APK named
"STAR CROSSED" using a supplied photo as the icon. What this pass did:

- **Icon replaced.** The placeholder gradient-star vector is gone.
  `app/src/main/res/drawable/ic_launcher_foreground.png` is now generated
  from the supplied photo (glowing hand-drawn music notes on black),
  centered and inset to Android's adaptive-icon safe zone so it isn't
  clipped under circle, squircle, or rounded-square launcher masks — this
  was checked against an actual simulated circle-mask crop, not just
  assumed. `ic_launcher_background.xml` is now solid `#000000`, matching
  the photo's own sampled backdrop color exactly, so the two layers blend
  with no visible seam regardless of mask shape. `app_name` was already
  "STAR CROSSED" in `strings.xml` — no change needed there.
- **Gradle wrapper added.** `gradlew`, `gradlew.bat`, and
  `gradle/wrapper/gradle-wrapper.jar` are now present (generated with a
  real local Gradle install, targeting the 8.7 distribution already
  pinned in `gradle-wrapper.properties`). The previous session couldn't
  produce these ("no Android SDK available there"); this environment had
  `gradle` installable via `apt`, which was enough to generate wrapper
  files even though it's far too old to run this project's actual build
  (see below). Android Studio will no longer need to regenerate anything
  on first sync.
- **Code review pass.** Read through all six Kotlin files line by line,
  including the `SimpleBasePlayer` usage in `StarCrossedMediaSession.kt`
  flagged below as highest-risk. Nothing looked wrong against documented
  Media3 1.4.1 API surface. That is a manual read, not a compile — the
  residual risk noted in §4 below is still real and still worth checking
  first, this just adds a second opinion on top of it.
- **What's still true:** this sandbox has no path to the Android SDK,
  Google's Maven repo, or Maven Central (its network allowlist doesn't
  include `dl.google.com`, `maven.google.com`, or Maven Central), so a
  real `./gradlew assembleDebug` could not be run or verified here either
  — confirmed empirically, not assumed: the old apt Gradle (4.4.1) can't
  even parse this project's `settings.gradle` (it predates
  `dependencyResolutionManagement`, a Gradle 6.8+ feature), and the real
  8.7 distribution the wrapper needs isn't reachable from here. A
  `.github/workflows/build-apk.yml` was added so GitHub's own servers
  (which don't have this restriction) can do that last step for you — see
  §4.

---

A native Android wrapper around `STAR_CROSSED_v47.html` that adds a proper
music-notification, lock-screen controls, background playback, Bluetooth/
headset controls, a home-screen widget, and audio-focus handling — **without
touching the existing player's features.**

---

## 1. Architecture decision (read this first)

STAR CROSSED's real audio engine is a Web Audio API graph living inside the
page's own JavaScript: dual-slot crossfade gain nodes, a 5-band biquad EQ,
a dynamics compressor, an analyser-driven visualizer, an ambient synth
oscillator. **None of that is reproducible in ExoPlayer.** Routing playback
through ExoPlayer instead would silently delete most of what this project
has built across dozens of sessions.

So this wrapper does the opposite of the "obvious" approach:

> **The WebView is the one and only audio source, always.** The native layer
> exists purely to make that WebView *controllable and visible* to Android's
> OS-level media plumbing.

Concretely:

- `StarCrossedMediaService` (a `MediaSessionService`) owns **one WebView for
  the app's entire process lifetime**. It is created once, in the service,
  using `applicationContext` — never inside `MainActivity`.
- `MainActivity` **borrows** that WebView's `View` and attaches it into its
  own layout whenever it's visible, and detaches (never destroys) it
  otherwise. Because the WebView object itself is never destroyed, its JS
  keeps running — and therefore the Web Audio graph keeps producing sound —
  regardless of what happens to any particular Activity instance.
- `android:stopWithTask="false"` (manifest) + becoming a foreground service
  the instant playback starts is what keeps the service — and therefore the
  WebView — alive when the user swipes the app away from Recents.
- OS-level control (notification, lockscreen, Bluetooth, widget) is exposed
  via `WebViewProxyPlayer`, a `SimpleBasePlayer` (Media3's own documented
  mechanism for backing a `MediaSession` with a non-ExoPlayer engine — the
  same technique used for things like Cast integrations). It never plays
  audio itself; it mirrors state reported by JS and forwards commands back
  to JS. There is exactly one source of truth, never two players racing
  each other.

`media3-exoplayer` is deliberately **not** a Gradle dependency — that
omission is itself part of the architecture, not an oversight.

## 2. What changed in the HTML

`app/src/main/assets/STAR_CROSSED_v47.html` is the same v47 you know, plus
a small, additive bridge. Verified against the original with a real diff:

```
67 lines added, 5 lines modified in place, 0 lines deleted, 0 unrelated
lines touched — out of 11,505 baseline lines.
node --check passes on the extracted script both before and after.
```

Every existing feature (crossfade, EQ, visualizer, the full romanization
pipeline, playlists, IndexedDB, Discover, cinematic mode, everything) is
untouched. All additions:

- Are wrapped in `_androidCall(...)`, which silently no-ops in a try/catch
  when `window.AndroidMedia` doesn't exist — **opening this file in a plain
  browser behaves exactly as before**, byte-for-byte, with zero new errors.
- Hook into existing, natural call sites (`_updStage`, `_updStageOnline`,
  the `ae.on('play'/'pause')` handlers, the RAF loop's existing once-per-
  second dirty-check, `toggleFav`, `_stopPlayback`) rather than adding any
  parallel state machine.
- Add exactly one new small, clearly-banner-commented method block to the
  `App` class (right after `togglePlay()`), matching the file's own
  `═══` section-header convention.

Inbound (Android → JS) commands arrive via `window.AndroidBridgeReceiver`,
which calls straight into the *same* `app.togglePlay()` / `app.next()` /
`app.prev()` methods the on-screen buttons already use — no duplicate
playback logic anywhere.

## 3. Project layout

```
StarCrossedAndroid/
├── settings.gradle, build.gradle, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle                 (Media3 session+common only, no exoplayer)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/STAR_CROSSED_v47.html   (patched — see §2)
        ├── java/com/starcrossed/app/
        │   ├── MainActivity.kt            (thin: binds to the service, attaches/detaches the WebView, requests notification permission)
        │   ├── StarCrossedMediaService.kt (owns the WebView, the session, audio focus, wake lock, becoming-noisy)
        │   ├── StarCrossedMediaSession.kt (WebViewProxyPlayer — the SimpleBasePlayer proxy — + session builder)
        │   ├── StarCrossedWebBridge.kt    (window.AndroidMedia — @JavascriptInterface)
        │   ├── PlaybackStateManager.kt    (thread-safe StateFlow hub connecting bridge ↔ service ↔ widget)
        │   └── StarCrossedWidget.kt       (home-screen widget)
        └── res/                           (layouts, adaptive-icon vectors, colors matching the app's own CSS palette, strings)
```

## 4. Building it

### Option A — Android Studio (fastest if it's already installed)

1. Open the `StarCrossedAndroid/` folder directly in **Android Studio**
   (Koala/2024.1 or newer recommended). The Gradle wrapper (`gradlew`,
   `gradlew.bat`, `gradle-wrapper.jar`) is already included, so it should
   sync immediately without regenerating anything.
2. Let Gradle sync. If it prompts for a newer AGP/Gradle pairing, accept —
   the versions pinned here (AGP 8.5.0, Kotlin 1.9.24, Media3 1.4.1) were
   current and stable as of this project's last knowledge update; check
   Maven Central for newer patch releases before a real release build.
3. Click **Run** to install straight onto a connected device/emulator, or
   **Build → Build Bundle(s)/APK(s) → Build APK(s)** to just produce the
   file (lands in `app/build/outputs/apk/debug/`). Needs **API 26+**
   (targets API 34).
4. Grant the notification permission when prompted (fires automatically the
   first time you start playback — see `MainActivity.observeFirstPlaybackForPermissionPriming`).

### Option B — GitHub Actions (no local install needed)

Push this whole folder to a GitHub repo (private is fine). The included
`.github/workflows/build-apk.yml` builds a debug APK on GitHub's servers
automatically on every push, or on demand from the Actions tab's
"Run workflow" button. Open the finished run and download
**STAR-CROSSED-debug-apk** from the Artifacts section — unzip it and the
`.apk` inside is ready to copy to a phone and install (enable "install
from unknown sources" for whichever app you transfer it with, since it
isn't from the Play Store).

Either path produces a debug build — signed automatically with a debug
key, fully installable and fully functional (nothing above requires a
Play-Store-style release signature). If you ever want to publish this
somewhere that requires a release build, that needs its own signing
keystore, which neither option sets up by default.

### ⚠️ One file to build/verify first

`StarCrossedMediaSession.kt`'s use of `androidx.media3.common.SimpleBasePlayer`
is still the one place in this delivery with the most residual risk, for
the same underlying reason as always: **no step here has been an actual
compile**, unlike the HTML/JS, which *was* syntax-validated with
`node --check` before and after every edit. The 2026-08-29 pass (§0) traced
every call this file makes — the constructor, every `handle*()` override,
and the full `State`/`MediaItemData` builder chain — against the real
`SimpleBasePlayer.java` source at the pinned tag and found them correct,
and separately found and fixed the specific reason a build likely failed
before (a missing `@OptIn`, not a builder-method mismatch — see §0). That's
materially stronger evidence than a documentation-level read, but it is
still not a compiler. **Build this module first in Android Studio, or push
and let the included GitHub Actions workflow (§4, Option B) build it** —
if something still doesn't match your exact pinned Media3 version, the fix
is contained to this one file; the overall architecture doesn't change.

Everything else (manifest, Gradle, all resource cross-references, the HTML
bridge) was mechanically verified in this delivery: every `R.*` reference
used in Kotlin was checked against an actual declared resource, and every
`@resource/name` reference across every XML file was checked the same way,
with zero unresolved references either direction.

## 5. Design decisions worth knowing about

- **No hand-rolled foreground/notification state machine.** `StarCrossedMediaService`
  never calls `startForeground()`/`stopForeground()` itself — it trusts
  `MediaSessionService`'s own built-in notification manager, driven purely
  by `player.invalidateState()`. This is deliberate: that lifecycle is
  notoriously easy to get subtly wrong by hand, and re-implementing it would
  add risk without adding capability.
- **Position sync piggybacks an existing hook.** Rather than adding a new
  timer, the ~1×/second Android position sync reuses the RAF loop's
  *already-existing* per-second dirty-check (`if(sec!==_lastSec)`) — zero
  added overhead, minimal patch surface.
- **Cold widget taps are handled honestly, not magically.** If you tap
  Play/Next/Previous on the widget while the app/WebView isn't running yet,
  the service starts, opens the app so you can see it initializing, and
  queues *one* action to run the moment JS reports `ready()`. This wrapper
  deliberately does **not** add a new "remember and auto-resume the last
  track from a fully cold start" feature to the HTML — that's a genuine new
  application capability (STAR CROSSED doesn't currently auto-resume a
  specific track on load), and inventing one wasn't in scope for "add a
  bridge," so it isn't silently faked here.
- **Volume ducking captures the real pre-duck value.** `AudioEngine.setVolume()`
  in the HTML overwrites its own "remembered" volume field, not just the
  live gain — so a naive duck/restore would restore to the *ducked* value.
  The focus-loss handler stores the true pre-duck volume in a JS global
  before lowering it, and restores from that on focus gain.
- **Favorite toggle is exposed to system UI via a custom command, not
  `SET_RATING`.** `SimpleBasePlayer` has no built-in rating/favorite hook
  to begin with, so this was never a `Player.Command` question. As of
  2026-08-29 (§0) it's wired end-to-end: a heart button in the
  notification's custom layout round-trips through JS the same way the
  in-page star does, for whatever track is currently playing — local or
  streamed Discover alike, since it defers entirely to the existing
  in-page favorite logic rather than reimplementing rating storage
  natively.
- **Adaptive icon foreground is a PNG derived from a supplied photo**
  (see §0), centered and inset to the safe zone; the background is a
  solid-color vector matching the photo's own backdrop. (Earlier in this
  project's history, before any icon artwork existed, the foreground was
  a hand-authored gradient vector instead — placeholder, not fabricated
  imagery, since a vector shape can legitimately be written by hand where
  a photo-realistic PNG can't.)
- **Splash-screen icon matches the launcher icon** (see §0, 2026-08-30) —
  same supplied photo, used as-is in both places for one consistent brand
  mark from tap to launch.
- **Flagged, not fixed: the media notification's small status-bar icon has
  no explicit branding.** `DefaultMediaNotificationProvider` (Media3's
  built-in notification builder, used here with no customization) falls
  back to its own bundled generic icon when the app doesn't call its
  `setSmallIcon(int)`, which nothing here does. Not touched as part of the
  2026-08-30 audit — genuinely out of scope of "fix the startup image,"
  and the fix isn't mechanical: a notification small icon must be a flat
  single-color (alpha-only) silhouette, so it can't be the photo used
  above, and deciding what glyph *should* represent the app there is a
  branding call, not an audit fix. `ic_notification.xml`'s existing star
  shape would technically work in that specific slot (Android renders
  small icons as a solid tinted silhouette from the alpha channel only, so
  its cyan fill wouldn't actually show) if that's ever wanted — one line,
  `.setSmallIcon(R.drawable.ic_notification)` on the
  `DefaultMediaNotificationProvider` this service already builds — but
  that's a decision for whoever's driving the app's branding, not
  something to change unprompted right after moving away from that exact
  shape everywhere else.

## 6. Manual test checklist (matches the spec's acceptance criteria)

- [ ] **Select a track and tap Play → it keeps playing** (does not
      self-pause within the first second or two) — this is the critical
      one; if it still fails, run `adb logcat -s SCMediaService` and
      reproduce it — see §0 (2026-09-01) for what the log lines mean
- [ ] Pause → Resume from the in-app button works normally
- [ ] Cold launch → the supplied photo appears immediately (no star, no
      blank/white/black flash beyond the instant of process start)
- [ ] The WebView's own in-page splash appears next, unchanged from before
- [ ] Start a track in-app → notification appears with correct title/artist/art
- [ ] Minimize app → audio continues
- [ ] Lock screen → controls visible, audio continues
- [ ] Unlock, open another app, come back → correct state shown
- [ ] Bluetooth/wired headset play-pause/next/previous
- [ ] Unplug headphones / disconnect Bluetooth mid-playback → auto-pauses
- [ ] Notification Play/Pause/Next/Previous
- [ ] Notification heart button toggles favorite; matches in-app star state
      both directions
- [ ] Add home-screen widget → controls work, art/title update live
- [ ] Tap widget while app is cold → app opens, action applies once ready
- [ ] Rotate screen → no reload, no audio glitch (Activity isn't recreated
      for orientation — see `android:configChanges` in the manifest)
- [ ] Swipe app from Recents while playing → playback continues
      (`stopWithTask="false"` + foreground service)
- [ ] Deny notification permission → app still plays, just no visible
      notification
- [ ] Tap "IMPORT MEDIA" (library tab) → system file picker opens → pick
      one audio file → track appears in library → plays correctly
- [ ] Empty library → tap the "Browse Files" empty-state button → same
      picker opens (same underlying fileInput as Import Media)
- [ ] fileInput's `multiple` selection → pick several audio files at once
      → all import, progress bar reflects combined size
- [ ] Cancel/back out of the picker → no crash, no stuck "Importing…"
      toast, `<input>` remains tappable again afterward
- [ ] Pick a non-audio file → JS's own filter rejects it ("No audio files
      selected" toast), no native-side change needed for this
- [ ] Playlist import (Playlists tab) and Settings → import config: same
      picker opens, since it's the same WebChromeClient fix — not
      exercised via "Import Media" but verify neither regressed (foreground-service protection doesn't depend on it)
- [ ] Long background session (30+ min) → still playing, wake lock +
      renderer-priority policy hold the WebView at full priority
