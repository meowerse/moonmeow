# Cursor follow — behaviour when zoomed in

**Goal (owner, 2026-09-24):** whenever the user is zoomed in, the host cursor is on screen.
The report that started this redesign, verbatim: *"its kinda follow but bad … i zoomed in
another place and when move it also moves and mouse is not visible all the time"*.

The implementation is `meow/cursor/CursorFollowController`; the wiring is in
`docs/meow/TOUCHPOINTS.md` (`MEOW-TOUCH(cursor-follow)`). This file is the behaviour
contract: every row below is meant to be true, and the rows marked with a test name are
pinned.

## Why the first version failed the user

Against a host that does not report its cursor (the user's host at the time — no 0x3004),
the client only had an estimate, and the estimate started as a guess (the middle of the
view) and drifted with every relative move the host accelerated. The view followed the
*estimate*. Zooming in "another place" put the view somewhere the real pointer was not, and
every subsequent move followed a cursor that did not exist on screen.
`GameCursorFollowModesTest.zoomElsewhereThenMoveKeepsTheCursorVisible` reproduces it with real
two-finger `MotionEvent`s through `Game` and failed before this change
(`host cursor 960.0,540.0 off screen: visible 225.0+480.0, 187.5+270.0`).

## Decisions

### 1. Two families of touch mode

| Family | Mouse modes | The pointer is… |
| --- | --- | --- |
| **Pointer modes** | touch trackpad natural (2) and gaming (3), touch disabled (4), and every physical mouse / touchpad / gamepad / keyboard mouse | the host cursor |
| **Direct-touch modes** | multi-touch (0), normal mouse (1), normal mouse with swapped buttons (5) | the finger: a tap clicks under it |

`Game` tells the controller which family touch is in (`!touchscreenTrackpad &&
touchContextMap[0] != null`); pinches and pans are touch gestures, so that is the family
that decides how they behave.

### 2. Zoom anchors on the cursor (pointer modes) — at its current screen position

A pinch keeps the cursor exactly where it is on screen, not the fingers' midpoint.
*Centring* was the alternative and was rejected: a zoom that also moves the cursor to the
middle is two motions at once, and the user loses the thing they were looking at next to the
cursor. Anchoring in place is what map and photo apps do with a pointer (the scroll-wheel zoom
of every desktop app anchors on the pointer, not the window centre).

It is done after the fact and before drawing: `PanZoomHandler` applies the pinch about the
fingers as it always did, the binder hears the transform change, and the controller pans by
exactly the amount the cursor moved on screen, in the same UI message. So every zoom entry
point — inline pinch, the explicit Pan/Zoom mode's pinch, anything added later — is anchored
without a hook of its own. The app has no double-tap zoom, and neither the zoom button nor
the quick bar zooms: the button only toggles the explicit Pan/Zoom mode, whose pinches are
anchored like any other.

At a desktop edge the view clamps; the cursor then moves on screen but stays on it (the
frame still covers the screen), and the follower settles it back inside the comfort margin.

In **direct-touch modes** zoom anchors on the fingers, as before: the fingers are the
pointer, and a user who pinches over a spot wants that spot.

### 3. A manual pan carries the cursor (pointer modes)

A two-finger drag while zoomed (inline pinch-and-drag, or a drag in the explicit Pan/Zoom
mode) moves the view **and the host pointer with it**: the cursor keeps its screen position
and ends up pointing at whatever desktop is now under that spot. One absolute position per
transform change.

"Free look that snaps back on the next pointer motion" was the alternative. It is exactly the
state the user reported — the cursor gone from the screen — and the snap back throws away the
pan the moment the user touches the trackpad again, which is more surprising than the cursor
coming along. In direct-touch modes a pan does not move the pointer (the next tap puts it
under the finger anyway).

### 4. Against hosts that do not report the cursor: the client owns the pointer while zoomed

Dead reckoning cannot be made exact from the client — the host applies its own pointer
acceleration (KWin/libinput's adaptive profile, Windows' "enhance pointer precision") to
relative motion, and the client never sees the result. Periodic re-syncs would make the
cursor jump by the accumulated error at every re-sync. So **while zoomed in**, relative
movement is not sent as relative at all: `NvConnection.sendMouseMove` offers it to the
controller first, which adds it to the known position, clamps it to the visible part of the
desktop and sends an **absolute** position instead (at a 4x finer reference than the stream,
so it lands on the intended desktop pixel). Consequences:

* the estimate is exact by construction — the host is told where the pointer is;
* the pointer cannot leave the screen; pushing it into an edge scrolls the view;
* host pointer acceleration does not apply while zoomed, so motion is linear. At 4x on the
  5360x1440 desktop in a 2712-wide view, one screen pixel of finger travel moves the cursor
  about two screen pixels. That is the cost, and it is only while zoomed.
* whenever the estimate is only a guess (stream start, after unzoomed relative travel, after
  a capture toggle), the first zoomed-in move — or the zoom-in itself — re-syncs it: the
  pointer is placed at the estimate if there is one, else at the middle of the view.

Unzoomed nothing changes: relative input stays relative, with the host's acceleration,
because the whole desktop is on screen anyway.

**Against a host that reports its cursor (0x3004), none of this happens**: its positions are
the truth, relative input stays relative, and the controller never re-syncs. The only
absolute positions the client sends are the ones the user asked for (a tap, a hover, a pan
that carries the cursor); for a round trip after each, a host report is treated as possibly
older than it and ignored (`HOST_REPORT_GRACE_MS`, 300 ms), so the view does not jump back to
where the cursor was before.

### 5. How the view moves

A cursor change arms the follower; each vsync (`Choreographer`) moves the view one step until
the cursor is inside the comfort margin (15% of the view; 4% — edge-scroll only — in
direct-touch modes and for 0.5 s after an absolute input such as a tap or a hover):

| Cursor is… | Regime | Ease τ | Speed cap | Acceleration cap |
| --- | --- | --- | --- | --- |
| on screen, in the margin band | gentle | 90 ms | 2.5 views/s | 30 views/s² |
| off screen (teleport, other monitor, correction) | fast | 45 ms | 10 views/s | 250 views/s² |

No overshoot (a step never exceeds what remains), no reversal (deceleration from the other
direction holds rather than backs up), and nothing below half a pixel moves. No jitter at the
margin: the target is the margin *line*, the follower stops there, and only a cursor change
re-arms it. With **"Remove animations"** on (`ValueAnimator.areAnimatorsEnabled()` false),
the view moves to the target in one frame instead of easing.

Only cursor changes and view changes that left the cursor off screen arm the follower; a
cursor that is on screen after a zoom or pan is left alone, so the view never drifts under the
fingers mid-gesture.

## Interaction table

Z = zoomed in. "Visible" means inside the part of the view above the soft keyboard. H =
host reports its cursor (0x3004); DR = it does not (dead reckoning).

| # | Input | Mode family | Zoom | Host | Behaviour | Pinned by |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Pinch (fingers anywhere) | pointer | any | H | cursor keeps its screen position | `GameCursorFollowModesTest.aPinchInATrackpadModeAnchorsOnTheCursorNotTheFingers` |
| 2 | Pinch away from the cursor, then move (the user's report) | pointer | 1→Z | DR | pointer placed in the middle of the new view on zoom-in; stays visible through every move after | `…zoomElsewhereThenMoveKeepsTheCursorVisible` |
| 3 | Pinch | direct | any | any | anchors on the fingers; host pointer not moved | `…aPinchInADirectTouchModeAnchorsOnTheFingers` |
| 4 | Two-finger drag (pinch-and-drag / Pan-Zoom mode) | pointer | Z | any | view pans, cursor carried at its screen position | `…aTwoFingerPanCarriesTheCursorAlong`, `CursorFollowBindingTest.aUserPanIsKeptAndCarriesTheCursorWithIt` |
| 5 | Two-finger drag | direct | Z | any | view pans; pointer stays; not chased | `CursorFollowControllerTest.inADirectTouchModeAZoomAwayFromTheCursorIsNotChased` |
| 6 | Trackpad stroke (natural / gaming) | pointer | Z | H | view follows the reported cursor, gently | `…touchTrackpadNatural`, `…touchTrackpadGaming` |
| 7 | Trackpad stroke near the screen edge, cursor moving the other way | pointer | Z | H | view follows the cursor, not the finger | `…aTrackpadStrokeNearTheScreenEdgeFollowsTheCursorNotTheFinger` |
| 8 | Physical touchpad | pointer | Z | H | as 6 | `…physicalTouchpad` |
| 9 | Captured mouse, relative | pointer | Z | H | as 6 | `…capturedMouseRelative` |
| 10 | Captured mouse, absolute-mouse mode | pointer | Z | H | as 6 (absolute moves mirror the library's base) | `…capturedMouseInAbsoluteMouseMode` |
| 11 | Captured mouse / trackpad / flick | pointer | Z | DR | client-owned pointer: never leaves the view, pushes it | `…capturedMouseAgainstAHostThatDoesNotReport`, `…aDeadReckoningHostCursorNeverLeavesTheViewEvenOnAFlick`, `CursorFollowControllerTest.zoomedWithoutHostReportsARelativeMoveIsPlacedInsideTheView` |
| 12 | Any relative input | any | unzoomed | DR | sent as relative (host acceleration kept); estimate becomes a guess | `CursorFollowControllerTest.unzoomedRelativeMovesStayRelative` |
| 13 | Trackpad fling momentum | pointer | Z | any | same path as a stroke (main-thread `sendMouseMove`) | via 6 / 11 |
| 14 | Gamepad mouse emulation, on-screen keyboard mouse keys | pointer | Z | any | same path as a stroke (both send on the main thread) | via 11 |
| 15 | Local cursor (hover) | pointer | Z | any | pointer under the Android pointer; edge-scroll only at the very edge | `…localCursorHover` |
| 16 | Absolute touch (tap / drag) | direct | Z | any | edge-scroll only; a tap inside the 4% band does not move the view | `…absoluteTouch`, `…aTapInsideTheEdgeBandDoesNotMoveTheView` |
| 17 | Native multi-touch, stylus (pen events) | direct | Z | any | mapped through the view (C3); marks direct pointing (edge band) | `…multiTouch` |
| 18 | Host teleports the cursor (dialog, warp), or it jumps to the other monitor of the 5360x1440 union | any | Z | H | fast regime; back on screen within ~0.6 s for a 4-view jump | `…aHostTeleportToTheOtherEndOfTheDesktopIsFollowedQuickly`, `CursorFollowMotionTest.theGentleRegimeSettlesSmoothlyAndTheFastOneQuickly` |
| 19 | Host moves its own mouse (someone at the desktop) | any | Z | H | as 18 | as 18 |
| 20 | Host cursor hidden (video, game) | any | Z | H | not chased while hidden | `CursorFollowBindingTest.aHiddenCursorIsNotFollowed` |
| 21 | Zoom at a desktop corner / edge | pointer | Z | any | view clamps; cursor stays on screen | `…aDesktopCornerIsReachableWithoutHidingTheCursor` |
| 22 | Letterbox padding (desktop aspect ≠ stream) | any | Z | any | cursor and client-owned placements are clamped to the desktop content box, never into the padding | `HostCursorTest.theEstimateStaysOnTheDesktopNotInTheLetterbox` |
| 23 | Very high zoom (up to 10x) | any | Z | any | margins are fractions of the view; speed caps scale with it | `CursorFollowMotionTest.speedAndAccelerationAreCapped` |
| 24 | Rotation, PiP, split-screen / freeform resize, external display attach | pointer | Z | any | cursor brought back into view after the resize | `…aResizeBringsTheCursorBack` |
| 25 | Soft keyboard opens | pointer | Z | any | visible area ends at the keyboard; cursor brought above it; host crop shrinks to match | `CursorFollowWiringTest.theBinderWatchesTheSoftKeyboardAndForwardsViewChanges` (wiring only, see limits) |
| 26 | Pointer capture toggled | pointer | Z | DR | estimate forgotten; next move places the pointer inside the view | `…afterACaptureToggleTheNextMovePlacesTheCursorInView` |
| 27 | Mouse-mode switch | any | Z | any | visibility re-checked | `CursorFollowWiringTest.aMouseModeSwitchAndACaptureToggleReCheckVisibility` |
| 28 | Reconnect / new stream (incl. a remembered zoom) | any | any | any | estimate reset; first zoomed-in move places the pointer inside the view | `HostCursorTest.aNewStreamForgetsEverything`, row 26's mechanism |
| 29 | Keyboard-only use | — | Z | any | no cursor movement, no view movement | — |
| 30 | Unzoomed | any | 1x | any | nothing to follow; everything visible | `CursorFollowBindingTest.unzoomedThereIsNothingToFollow` |
| 31 | Following turned off in Settings | any | any | any | view never moves on its own, pointer never placed, no 0x3004 subscription | `CursorFollowBindingTest.withFollowingOffTheViewStaysPut`, `CursorFollowControllerTest.aDisabledControllerNeverSubscribesOrPans` |
| 32 | "Remove animations" on | any | Z | any | jumps to the target in one frame | `CursorFollowControllerTest.withAnimationsOffTheViewJumpsInOneFrame` |
| 33 | A host report older than a position the client just sent | any | Z | H | ignored for 300 ms | `CursorFollowControllerTest.aStaleHostReportJustAfterTheClientMovedThePointerIsIgnored` |
| 34 | External-display controller (phone as trackpad for a second screen) | pointer | any | any | *Assumed, not traced:* its input reaches the host through the same `NvConnection` methods, so rows 6 / 11 apply; its own Pan/Zoom toggle mirrors `Game.toggleZoomMode` | — |

## Known limits, stated

* **The soft keyboard row is not exercised in a unit test.** Robolectric does not dispatch
  IME insets. The visible-area arithmetic is the tested `windowFromLocationInWindow`, fed a
  window shortened by the IME inset read from `getRootWindowInsets()` (API 30+; older devices
  treat the keyboard as not covering the stream). The trigger relies on insets being
  dispatched to the stream container, which a fullscreen window does; failing that, the next
  cursor move re-checks.
* **Linear pointer while zoomed against non-reporting hosts** (decision 4). A game that needs
  raw relative mouse input will not get it while zoomed in against such a host; zoomed-in
  gaming is not a supported case, and unzoomed behaviour is unchanged.
* **The crop swap and the follower are independent.** While the view eases, the host crop
  follows with the usual viewport round trip, so the edges of a pan show the soft zoom of the
  previous crop for a moment (see `MEOW-TOUCH(viewport-compose)`).
* **Rows 29 and 34 are reasoning, not tests.**
* **Device behaviour is verified by the orchestrator with the user**, not here: the rows are
  pinned against Robolectric's `Game`, a fake host and pumped vsyncs.
