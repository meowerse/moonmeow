# PC keyboard, keyboard-aware layout, and typing fixes

*Added 2026-09-24, branch `feat/pc-keyboard`.* Code: `app/src/main/java/com/limelight/meow/keyboard/`.
Touch-points: `docs/meow/TOUCHPOINTS.md`, `MEOW-TOUCH(pc-keyboard)`.

The request, in the owner's words: move the stream up when a keyboard is open; do not hide
the bottom bar behind keyboards (the stream is letterboxed anyway); add a key that opens a
desktop keyboard like Chrome Remote Desktop's, with an Fn menu for keys that do not fit,
modifiers that stick so shortcuts are easy, balanced sizes, fast, polished.

## What the user gets

| Where | What |
| --- | --- |
| Quick bar | a **PC** button (PC keyboard) next to **KB** (system keyboard). The three-finger tap and the game menu's keyboard item open whichever of the two was used last (remembered across streams). |
| PC keyboard, portrait | toolbar (system keyboard, shortcut chips that fit — Ctrl+C/V/Z/X/A/S/F, Ctrl+Shift+Z — hide), then six 11-unit rows: `Esc` and the punctuation row, `Tab` + digits, qwerty + Backspace, asdf + Enter, Shift + zxcvbnm + ↑ + `/`, and Ctrl · Fn · Super · Alt · Space · ← ↓ → |
| PC keyboard, landscape | real ANSI, 15 units, five rows; bottom row Ctrl · Fn · Super · Alt · Space · Esc · ← ↑ ↓ →. No toolbar: vertical space is the scarce axis |
| Fn layer | a **Super** key (a lone Super press: Start menu / KDE launcher) and Super+L, F1–F12, PrtSc, ScrLk, Pause, Ins, Del, Home, End, PgUp, PgDn, Caps, Menu, volume/media, Ctrl+Alt+Del, Ctrl+Shift+Esc, Alt+F4, Alt+Tab, Ctrl+Shift+C/V (terminal copy/paste), Ctrl+Shift+Z, system-keyboard and hide keys. Same bottom row, same Shift and arrow positions as the main layer |
| Above the system keyboard | a one-row strip: Esc, Tab, Ctrl, Alt, Super, ← ↑ ↓ →, and a key to switch to the full PC keyboard (Termux's extra-keys idea). Setting: *PC keys above the system keyboard* |
| Stream | slides up out from under any keyboard (system, strip or PC) and back when it closes. Setting: *Move the stream above the keyboard* |
| Quick bar (toolbar) | **always on screen** unless *Auto-hide toolbar* is on. Placed in a letterbox when one is deep or wide enough (bottom first, then the right-hand side), so it covers nothing. When the stream fills the view — the normal case on a phone with auto cursor zoom — it stands over the stream on the edge that hides the least of it (across the bottom in portrait, down the right side in landscape) and is reported to the viewport binder as a bottom or right obstruction: cursor follow keeps the cursor clear of it and the host crop ends at it. The stream is not moved for it, except the least lift that brings the cursor above it when the cursor is on the desktop's last rows (no pan can reach those). With a keyboard open it rides above the keyboard, across the bottom. *Auto-hide toolbar* (off by default) restores the 3-second collapse; a two-finger tap toggles it either way |
| System bars | in full screen, only the status bar hides; the navigation bar stays and the window is laid out above it. Setting: *Keep the navigation bar visible* (off = the old immersive mode) |

## Modifier behaviour (Ctrl, Alt, Shift, Super, Fn)

| Action | Result | Host sees |
| --- | --- | --- |
| tap an off modifier | **latched** (green outline + dot): applies to the next key, then turns off | nothing yet |
| tap a latched modifier within the double-tap timeout (system value, ~300 ms) | **locked** (solid green + bar) | modifier down now |
| tap a latched modifier later | off | nothing |
| tap a locked modifier | off | modifier up |
| long-press a modifier (system long-press timeout) | locked, with a haptic | modifier down now |
| long-press fires, then keys are pressed while the finger is still down (held like a physical key) | a chord after all: **off** when the finger lifts, so a slow Ctrl+C never leaves Ctrl locked | down … up |
| hold a modifier and press keys with another finger | a chord: applies to those keys, off when lifted (a lock survives) | down/up around each key |
| key pressed with modifiers latched | | modifiers down, key down … key up, latched modifiers up |
| several latched | combine (Ctrl + Shift + T) | all of them around the key |
| Fn latched / held / locked | Fn layer shown for one key / while held / until tapped | never sent |
| latched modifier, then a key from a **physical keyboard** or the system keyboard's key events | applies to that key, then releases | as above |
| latched Ctrl/Alt/Super/Shift, then **committed text** from the system keyboard (one character) | typed as that key: Ctrl + "c" is Ctrl+C; Cyrillic letters use the ЙЦУКЕН key they sit on (Ctrl + "с" is Ctrl+C) | key presses, not text |
| latched modifier, then a whole word or an emoji | typed as text; the latch is spent | text |
| shortcut chip (Ctrl+C …) | presses its chord; combines with latches (Shift latched + Ctrl+Z = redo) | chord down/up |
| hold a normal key (Backspace, arrows) | stays down on the host until the finger lifts | the host's own repeat delay and rate apply, exactly as for a physical key |
| window loses focus, app paused, keyboard hidden | every held key up, every latch and lock off | key-ups for everything held |

Why lazy modifiers: tapping Super to latch it and tapping again to cancel must not send a lone
Super press, which opens the KDE launcher / Start menu. (A *quick* second tap locks instead, and
unlocking a lock that was never used does send that lone press — as releasing a held Super key
would. For the launcher on purpose, the Fn layer has a Super key.) Why an eager lock: a locked Ctrl or
Shift must also modify mouse clicks, and a locked Alt must hold the Alt+Tab switcher open
across several Tabs. A merely latched modifier does not apply to mouse clicks (lock it for
Ctrl+click) — stated rather than discovered.

On a macOS host, Super is Command (Sunshine maps it) and Right-Alt-as-Command still applies to
physical keyboards; the chips are labelled with the keys they press, so on a Mac use Super + C.

Ctrl+Alt+Shift+Q/Z/C are the client's own combos (quit, toggle capture, cursor) in
`Game.handleSpecialKeys`, and the PC keyboard goes through the same path, so they behave as
they do on a physical keyboard.

## Design decisions

**One custom View, not seventy Buttons.** `PcKeyboardView` draws every key from a `KeyGrid`
(flat arrays built on a size change) with paints allocated once. The press path — hit test,
engine, haptic, invalidate — allocates nothing; `PcKeyboardAllocationTest` measures it with the
JVM's per-thread allocation counter (under 4 KB over 10,000 press cycles, i.e. none per press).

**Keys go down the physical-keyboard path.** `GameKeySink` calls `Game.keyboardEvent`, the
`EvdevListener` entry the Artemis on-screen keys already use: `KeyboardTranslator` maps, Game's
modifier flags stay in step, Right-Alt-as-Command is honoured, and it ends in
`LiSendKeyboardEvent`. No mapping is duplicated. Volume/media keys have no Android key code, so
they go through `Game.sendKeys` as the Windows VK codes Sunshine maps per OS.

**Sizes derive from the window.** Rows never below 44 dp (the whole cell, gaps included, is the
touch target — hits go to the nearest key), up to 56 dp portrait / 50 dp landscape; the keyboard
aims at ≤ 42% of a portrait and ≤ 60% of a landscape window and lets the 44 dp floor win over
that on short windows; a key unit is never wider than 64 dp, so tablets centre instead of
stretching. Portrait 412 dp: 37 dp keys, ~52 dp rows. Landscape 915 dp: ~60 dp keys.

**Portrait is not ANSI.** 15 units across 392 dp would be 26 dp keys. Eleven units (Gboard's
width) on six rows fits every ANSI key on the main layer anyway, by giving punctuation its own
row instead of hiding it on a layer a coder would visit constantly.

**Visual states are shape and colour.** Latched: green outline and a dot. Locked: solid green
and a bar. TalkBack says "Control, on for the next key" / "Control, locked on", and modifiers
are exposed as checkable. Pressed keys lighten; character keys show a larger preview above the
finger (the finger covers the key). While Shift is active, keys show what they will type
(upper case, shifted symbols). Palette from meowerse `tokens.css`: `#00ff82` / `#00e676` /
`#06331b` on `#0d0d0d`; key labels ≥ 7:1 against their key, the small shifted-symbol hints ≥ 4.5:1.

**Haptic and sound follow the system.** `KEYBOARD_TAP` on press and `LONG_PRESS` on a lock via
`performHapticFeedback` (honours "touch vibration"); `playSoundEffect(CLICK)` (honours
"touch sounds", usually off). No private toggles.

**Move the stream, never shrink it** (`StreamLift`). Nothing covered: no move. The stream fits
above the keyboard (portrait): centred in what is left. It does not fit (landscape): it keeps
its size — a desktop shrunk into the band above a keyboard is unreadable — and slides up so the
point of interest is centred, clamped so it never moves down or past its own top. With no point
of interest known, the bottom edge sits on the keyboard (what the keyboard covered is what the
user wants to see). The point of interest is the host cursor: `StreamViewportBinder`, where
every cursor position already arrives (touch, mouse, and the dead-reckoned relative cursor),
records it and is the area's `FocusSource`; when it moves by more than an eighth of the stream
while a keyboard is open, the stream is re-placed. Applied as `translationY` on the stream container, which nothing else
transforms (`PanZoomHandler` transforms the surface *inside* it), with a 220 ms decelerate that
the system animation scale governs (off when animations are off).

**Insets.** On API 30+ the window is `adjustNothing` and reads `WindowInsets` (`ime()`,
`statusBars()`, `navigationBars()`); the IME inset is reported in every adjust mode there. On
API 26–29 there is no IME inset type, so a full-screen, single-window stream asks for
`adjustResize` (which a `FLAG_FULLSCREEN` window does not actually get) and the IME is the part
of the window the visible display frame lost, above 15% of the height. Without that flag (full
screen off, multi-window) adjustResize would really shrink the stream, so there the window keeps
its old mode and the lift is not applied on those API levels. `targetSdk` is 34, so Android 15's enforced
edge-to-edge does not apply yet; the arithmetic uses root insets, not layout assumptions, so it
stays right when targetSdk moves.

**The navigation bar.** Upstream immersive mode hid both bars. The stream is letterboxed on
every phone (16:9 on 19.5–20:9), so a visible navigation bar costs no picture in portrait and
at most the gesture-bar height in landscape, while back and home stay one tap away — which is
what the owner asked for. Only the status bar hides. Off restores the old behaviour exactly;
multi-window keeps the old behaviour.

**Rotation, split-screen, PiP, hardware keyboards.** The view re-derives its layout from its
measured size (rotation and split-screen are just new sizes); PiP hides the keyboard and drops
the lift; a physical keyboard arriving hides the PC keyboard (like the system keyboard does),
detaching it changes nothing. The system keyboard opening while the PC keyboard is up replaces
it; opening the PC keyboard hides the system keyboard.

## Wired to cursor follow and the host crop (PR #16)

`KeyboardVisibleArea` is published per window and found from any view with
`KeyboardVisibleArea.of(view)`. Its visible bottom is what no keyboard (system, strip, PC) and
no permanent quick bar covers. Since #16 merged, the two features are wired both ways, all in
`StreamViewportBinder`:

- **Keyboards → follow and crop.** The binder listens to the area and turns every change into
  `setBottomObstruction(windowHeight - visibleBottom)`, #16's overlay API. The visible
  rectangle the follower keeps the cursor in, and the host is asked to crop to, therefore ends
  above whichever keyboard or bar is open, not only above the IME.
- **Follow → lift.** The binder is the area's `FocusSource`: the host cursor
  (`CursorFollowController.cursor()`, reported or dead-reckoned) mapped through the live
  transform into container pixels. When the follower's frames see the cursor move by more than
  an eighth of the container, the binder posts `onFocusMoved()` and the stream lift re-places
  the stream, so the rows under the cursor come back into reach even though part of the
  container is hidden behind the keyboard.
- **Insets have one owner per view.** #16's binder owns `setOnApplyWindowInsetsListener` on the
  stream container; the keyboard controller owns it on its own keyboard view. Neither replaces
  the other.

`GamePcKeyboardFollowTest` drives it through `Game` in an attached window: zoomed 3x in touch
trackpad mode, the PC keyboard opened, the cursor driven to the desktop's last row. The stream
lifts, the obstruction equals the keyboard's cover, and the followed cursor ends above the
keyboard's top edge; closing the keyboard clears the obstruction.

**A follower bug this surfaced (fixed in `CursorFollowController`).** With part of the
container covered, the cursor could sit on rows the view can never pan to. The follower then
requested a frame every vsync forever (a pan that `PanZoomHandler` clamps to nothing never
reaches "settled") — in Robolectric an out-of-memory, on a phone a wasted vsync loop. A pan
the view refuses now settles the follower
(`aPanTheViewRefusesEndsTheFollowInsteadOfRequestingFramesForever` failed before the fix). Only a
requested, non-zero pan counts: a zero step is the motion holding while it brakes from the other
direction, and the follow goes on (`aReversalWhileTheViewBrakesStillCompletesTheFollow`, which
failed on the first version of the fix). With the lift off, a cursor on rows under the keyboard
stays there until the keyboard closes or the cursor moves up.

**The stream moving also re-reports.** The lift and the sideways slide animate the container;
when the animation ends the controller calls `KeyboardVisibleArea.onStreamMoved()`, and the
binder recomputes what is visible and re-reports the crop, so a re-lift for the cursor with no
change in what covers the window still updates the host.

**Accepted deviation: the lift follows the cursor only with cursor follow on.** The focus source
is #16's `HostCursor`, which is fed only when cursor follow is enabled (on by default since
schema 2). With it off the lift has no point of interest and puts the stream's bottom on the
keyboard, as before PR #17 had a cursor at all. Re-feeding the cursor with follow off would mean
installing #16's input tap for a disabled feature; not done.

**Side obstructions.** The binder takes the area's right edge too
(`setRightObstruction`, the side twin of #16's `setBottomObstruction`), for the quick bar standing
down the side of a landscape stream that fills the view.

**With PR #18's frame presenter.** On API 33+ each frame is drawn into a child `SurfaceControl`
of the stream `SurfaceView`, positioned and cropped by `FrameLayerGeometry` from the same view
transform. It therefore moves with the lifted container and stays in the SurfaceView's plane,
behind the app window, so the PC keyboard and the quick bar (views in that window) always draw
over it; `PcKeyboardWiringTest.theVideoLayerStaysBelowTheKeyboardAndTheQuickBar` pins the
parenting and `setZOrderOnTop(false)`. The crop the presenter asks the host for comes from the
same visible rectangle, which ends above the keyboard through the bottom obstruction.

**Defaults.** `MeowDefaults` schema 2 (#16) turns viewport follow, cursor follow and auto
bitrate on for existing installs (auto cursor zoom is on by its default); schema 3 (#18) turns
host audio on. This branch adds no step to either. This branch's four settings are new keys whose defaults are
the "on" behaviour, so existing installs read them on without a step of their own;
`MeowDefaultsCoexistTest` runs the migration (now to schema 3) on a schema-1 install and checks
them all together.

## Typing fixes found on the way

**Duplicate words with "commit text" on (fixed; `ImeInputConnectionTest`).** The stream's input
connection overrode `commitText` over a `BaseInputConnection` in dummy mode. Dummy mode buffers
composing text and sends the buffer as key events from `finishComposingText()`; since the
override never let the base class clear it, a composed-then-committed word stayed buffered and
reached the host a second time (via `ACTION_MULTIPLE` → `handleKeyMultiple`). The failing test
(`expected:<[]> but was:<[hello]>` against the old connection) pins the buffer.
`ImeInputConnection` replaces it and mirrors composition *live* (`ImeComposer`: type the new
part, backspace only what changed), so words appear as they are typed and autocorrect costs a
few backspaces. Edits drain from one queue and yield a main-loop turn after each insert,
because `Game` queues text but sends backspaces at once.

**Stuck modifiers after focus loss (fixed; `PcKeyboardControllerTest`, `PcKeyboardWiringTest`).**
`Game.onWindowFocusChanged` zeroed its modifier flags without telling the host, so a Ctrl held
when a dialog or the notification shade took focus stayed down on the host. The hook releases
every flagged modifier (left and right) first.

**Stuck keys on pause.** The PC keyboard releases everything it holds in `onActivityPaused`,
before `onStop` tears the connection down.

**Not changed, recommended.** "Commit text" (`checkbox_enable_commit_text`) is still off by
default. With it off, the system keyboard talks to Android's fallback connection: words reach
the host only when committed, as key events or, for Cyrillic, `ACTION_MULTIPLE` text. With it
on, composition is now live and duplicate-free. Turning it on by default is a behaviour change
for every user and wants its own PR.

## The quick bar no longer hides

The owner: *"why also buttons bar is still hiding to this line? let's always show it"*.
`QuickBarView` collapsed to a 48×6 dp handle 3 s after every interaction. Now it stays
(`QuickBarAlwaysVisibleTest` failed on the old bar, passes now), with *Auto-hide toolbar* for
anyone who wants the old behaviour. It is a `KeyboardVisibleArea.Obstruction`: the controller
places it above any keyboard, then narrows the stream's visible area by it — bottom for a
horizontal bar, right or left for a vertical one — only where it actually overlaps the stream
(`PcKeyboardController.clearOf`). With a keyboard open the stream lifts above both
(`StreamLift.liftFor`). With none, the stream stays put and the bar is only an obstruction: the
published `KeyboardVisibleArea` includes it, and the binder turns it into #16's
`setBottomObstruction` (or `setRightObstruction` for the side bar), so cursor follow keeps the
cursor above it and the host crop ends at it. The desktop's last rows are the exception, since no
pan reaches them: there the stream is nudged up by exactly as much as brings the cursor above the
bar (`StreamLift.nudgeFor`).

A first version fell back to auto-hiding wherever no letterbox fits. With #16's auto cursor zoom
the stream fills the view on every phone, so that fallback was the normal case and the bar
collapsed again (seen on the emulator). The fallback is gone: only the setting collapses the bar.
`GamePcKeyboardFollowTest.withTheStreamFillingTheViewTheQuickBarStaysShownAndTheCursorAboveIt`
(portrait 1080x2400, stream filling the view) failed on that version: bar shown, obstruction equal
to its cover, stream not moved for it, and the followed cursor ending above it. The bar lives inside the
content view, which with *Keep the navigation bar visible* on is laid out above that bar, so the
two never overlap; with it off the navigation bar only appears transiently, over everything.

**No defaults migration.** The setting is new: no install has a stored value, so every existing
install reads the default (off = always visible) exactly like a new one, and nothing collides
with #16's bump of `applyDefaultsMigration()` to schema 2 (`QuickBarPreferencesTest` runs the
migration and checks).

**Nothing else collapses to a line.** Checked: the Artemis floating menu button is replaced by
the bar, the zoom-toggle button is top-centre and static, and the virtual-controller and
on-screen-keyboard configure buttons are fixed translucent buttons with no timer.

## Known limits

- Where no letterbox fits the bar it stands over the edge of the stream (owner: "let's always
  show it"); the cursor is kept clear of it, but the desktop under the bar is only visible by
  panning or with *Auto-hide toolbar* on.
- Clearing the stream of the bar follows *Move the stream above the keyboard*; with that off, or
  on an external display (no keyboard controller), the permanent bar may cover the stream.
- A permanent bar also sits above the Artemis on-screen controls (virtual gamepad, custom
  keys, the Artemis keyboard layout) where they overlap; users of those may prefer
  *Auto-hide toolbar*. Not changed: they are positioned by the user.

- Before the cursor has been seen (no pointer input since the stream started), landscape lifts
  with no point of interest: the bottom of the stream on the keyboard.

- A latched (not locked) modifier does not apply to mouse clicks. Lock it.
- If a latched modifier turns a composed character into a shortcut, the IME still believes the
  character is composing; its next edit is diffed against text the host never received. Rare
  (modifiers are for shortcuts, not composition) and bounded to one word.
- The key preview is drawn inside the keyboard, so the top row's preview overlaps the toolbar
  (portrait) or its own row (landscape).
- Media keys depend on the host OS honouring the VK codes; Sunshine maps them on Windows and
  Linux.
