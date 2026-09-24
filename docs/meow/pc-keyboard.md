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
| Quick bar (toolbar) | **always on screen** by default — across the bottom in portrait (in the letterbox below the stream), down the right-hand side in landscape (in the letterbox beside a 16:9 stream on a 19.5–20:9 phone). The stream is kept clear of it: where the bar does overlap the stream (a keyboard is open, or the letterbox is too narrow), the stream moves up or left out from under it, never shrinks. Setting *Auto-hide toolbar* (off by default) restores the old 3-second collapse to a handle line; then the bar is a transient overlay and the stream does not move for it. A two-finger tap still toggles it either way |
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

## The "visible area changed" signal (for PR #16)

`KeyboardVisibleArea` is published per window and found from any view with
`KeyboardVisibleArea.of(view)`:

- `visibleTop()` / `visibleBottom()` — window pixels not covered by a status bar or by any
  keyboard (system, strip, PC). `addListener(Listener)` is told on every real change.
- `setFocusSource(FocusSource)` — whoever knows where the host cursor or caret is on the stream
  container returns its y (container pixels), and the lift centres it above the keyboard.

How `feat/real-viewport-cursor-bitrate` should consume it, once both are merged:
1. In `StreamViewportBinder`, replace `decorHeight - imeBottomInset(decor)` with
   `KeyboardVisibleArea.of(parent).visibleBottom()` when the area `isKnown()`, so the PC keyboard
   and the strip count as covering the stream, not only the IME.
2. Register a `KeyboardVisibleArea.Listener` that calls `onVisibleAreaChanged()` (it already
   re-reports the viewport and calls `cursorFollow.ensureVisible()`), instead of relying on an
   `OnApplyWindowInsetsListener`, which the PC keyboard does not trigger.
3. This branch already makes `StreamViewportBinder` the `FocusSource` (the last cursor position
   it was handed, in container pixels) and calls `onFocusMoved()` on large moves. #16 rewrites
   that class: keep the two `recordCursorViewY` calls, or replace the source with one backed by
   `HostCursor`, whose host-reported position is better than any estimate.

Until #16 lands, its follower only sees the IME; nothing breaks either way.

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
(`PcKeyboardController.clearOf`). The stream then lifts (`StreamLift.liftFor`) or slides left
into spare letterbox (`StreamLift.shiftFor`). The published `KeyboardVisibleArea` includes the
bar, so PR #16's `setBottomObstruction` consumer gets it for free. The bar lives inside the
content view, which with the navigation bar kept is laid out above that bar, so the two never
overlap.

**No defaults migration.** The setting is new: no install has a stored value, so every existing
install reads the default (off = always visible) exactly like a new one, and nothing collides
with #16's bump of `applyDefaultsMigration()` to schema 2 (`QuickBarPreferencesTest` runs the
migration and checks).

**Nothing else collapses to a line.** Checked: the Artemis floating menu button is replaced by
the bar, the zoom-toggle button is top-centre and static, and the virtual-controller and
on-screen-keyboard configure buttons are fixed translucent buttons with no timer.

## Known limits

- In landscape on a 16:10 or wider stream (or a tablet), the letterbox is too narrow for the
  bar, and the stream can only slide left as far as its own left letterbox allows; the rest of
  the overlap stays covered.

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
