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
that decides how *they* behave. The follow margin does not use it: it follows the input that
last moved the cursor (decision 5), so a physical mouse gets the comfort margin whatever the
touch mode is.

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
pointer, and a user who pinches over a spot wants that spot. The pointer is still kept
visible there: the first finger (or the pen) is followed when it is dragged into the 4% edge
band, and a pointer moved by anything else -- a mouse, the host itself -- is followed with the
comfort margin. Only the zoom and pan gestures themselves never chase it, since zooming away
from the last tap is what the fingers asked for.

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
* the pointer cannot leave the screen; pushing it into an edge scrolls the view. While the
  view can still scroll, the pointer is held a sprite's width (24 screen px) inside the right
  and bottom edges so its arrow is seen; once the view is against the desktop's edge it goes
  all the way to the last pixel, so the panel, the tray and the corner stay reachable;
* the estimate keeps the exact position the client asked for, not the reference-grid copy
  the library sends, so slow motion keeps its full gain (a 1-desktop-pixel move is 0.36 of a
  reference pixel on the 5360-wide desktop, and rounding it lost 30%);
* host pointer acceleration does not apply while zoomed, so motion is linear. At 4x on the
  5360x1440 desktop in a 2712-wide view, one screen pixel of finger travel moves the cursor
  about two screen pixels. That is the cost, and it is only while zoomed.
* pushing past the visible edge scrolls the view by the overshoot **in the same event**, so
  the cursor keeps moving at finger speed with the desktop sliding under it. Pinning it at
  the edge while the view eased after it was the "druggy" feel in the second report.
* whenever the estimate is only a guess (stream start, after unzoomed relative travel, after
  a capture toggle), the zoom-in anchors on the estimate and then makes it true — the pointer
  is placed where the estimate says, clamped into the new view — or, with nothing known, at
  the middle of the view. The zoom goes where the cursor is, not to the middle of the desktop.

**Which hosts, and when.**

* A host that has proven it is a meow host (the viewport echo arrived) is given
  `FIRST_REPORT_WAIT_MS` (1.5 s) after the subscription to send its first 0x3004 report. A
  reporting host answers at once, so until then the client moves the pointer for nobody.
* A proven host that has not reported by then (an older sunmeow, like the user's host on
  2026-09-24), and a stock host, are dead-reckoning hosts: zoomed in, the client owns the
  pointer as above. **Unzoomed, relative input always stays relative** -- with the host's
  pointer acceleration, and with pointer-locked games working -- because the whole desktop is
  on screen and there is nothing to keep visible. The price is that the unzoomed estimate is a
  guess; the zoom-in anchors on it and then makes it true (the pointer is placed where the
  estimate says, inside the new view). An earlier revision owned the pointer unzoomed too on
  proven hosts; review found it broke raw-input games at 1x and was reverted.

**Against a host that reports its cursor (0x3004), none of this happens**: its positions are
the truth, relative input stays relative, and the controller never re-syncs. The only
absolute positions the client sends are the ones the user asked for (a tap, a hover, a pan
that carries the cursor); for a round trip after each, a host report may be older than it,
so it is held (`HOST_REPORT_GRACE_MS`, 300 ms) and taken when the window ends unless a newer
one replaces it -- never dropped, because it may be the host's correction of the placement.

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

Only cursor changes the user or host made, and view changes that left the cursor off
screen, arm the follower. The placements the controller makes to anchor a zoom or carry a
pan do not, so a cursor that is on screen after a zoom or pan is left alone and the view never
drifts under the fingers mid-gesture.

The margin follows the input that last moved the cursor: 4% (edge-scroll only) for 0.5 s after
a tap, a hover, a pen or a native touch -- the cursor is under the user's finger or pointer --
and 15% after relative motion or the host's own moves.

Leaving the "fast" regime, the view brakes at the fast regime's rate until it is back under
the gentle speed cap, so a cursor coming back on screen never ends the move in a hard stop.

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
| 9 | Captured mouse, relative (any touch mode) | — | Z | H | as 6; comfort margin (relative input) | `…capturedMouseRelative` |
| 10 | Captured mouse, absolute-mouse mode | — | Z | H | as 6 (absolute moves mirror the library's base) | `…capturedMouseInAbsoluteMouseMode` |
| 11 | Captured mouse / trackpad / flick | any | Z | DR | client-owned pointer: never leaves the view; past the edge the view scrolls by the overshoot in the same event | `…capturedMouseAgainstAHostThatDoesNotReport`, `…aDeadReckoningHostCursorNeverLeavesTheViewEvenOnAFlick`, `CursorFollowControllerTest.zoomedWithoutHostReportsARelativeMoveIsPlacedInsideTheView`, `…pushingPastTheEdgeScrollsTheViewAtFingerSpeed` |
| 12 | Any relative input | any | unzoomed | DR | sent as relative (host acceleration kept, games work); estimate becomes a guess, made true at the next zoom-in | `CursorFollowControllerTest.unzoomedRelativeMovesStayRelative`, `…aProvenHostGetsTimeToReportBeforeTheClientMovesThePointer` |
| 12d | Slow, precise motion | any | Z | DR | full gain at 1, 2, 3 desktop px per event | `CursorFollowControllerTest.slowMotionKeepsItsFullGainDespiteTheReferenceGrid` |
| 12c | Pinch after moving the cursor unzoomed | pointer | 1→Z | DR | zoom goes where the cursor is, the pointer is not dragged to the middle | `…aDeadReckoningHostZoomsWhereTheCursorIsNotToTheMiddle` |
| 13 | Trackpad fling momentum | pointer | Z | any | same path as a stroke (main-thread `sendMouseMove`) | via 6 / 11 |
| 14 | Gamepad mouse emulation, on-screen keyboard mouse keys | pointer | Z | any | same path as a stroke (both send on the main thread) | via 11 |
| 15 | Local cursor (hover) | pointer | Z | any | pointer under the Android pointer; edge-scroll only at the very edge | `…localCursorHover` |
| 16 | Absolute touch (tap / drag) | direct | Z | any | edge-scroll only: only a tap or drag within 4% of an edge scrolls; a tap elsewhere never moves the view | `…absoluteTouch`, `…aTapInsideTheEdgeBandDoesNotMoveTheView` (a tap 22% in) |
| 17 | Native multi-touch, stylus (pen events) | direct | Z | any | mapped through the view (C3); the first finger or the pen is the pointer: dragged into the 4% edge band it scrolls the view, also against a host that never reports its cursor | `…multiTouch`, `…multiTouchAgainstAnOldHostEdgeScrollsWithTheFinger`, `CursorFollowControllerTest.aFingerDraggedIntoTheEdgeBandScrollsTheViewInADirectTouchMode` |
| 18 | Host teleports the cursor (dialog, warp), or it jumps to the other monitor of the 5360x1440 union | any | Z | H | fast regime; back on screen within ~0.6 s for a 4-view jump | `…aHostTeleportToTheOtherEndOfTheDesktopIsFollowedQuickly`, `CursorFollowMotionTest.theGentleRegimeSettlesSmoothlyAndTheFastOneQuickly` |
| 19 | Host moves its own mouse (someone at the desktop) | any | Z | H | as 18 | as 18 |
| 20 | Host cursor hidden (video, game) | any | Z | H | not chased while hidden, however much the mouse moves, including a hidden cursor a game confines against the desktop edge once the host has shown its cursor this stream; the exceptions are in row 44 | `CursorFollowBindingTest.aHiddenCursorIsNotFollowed`, `CursorFollowControllerTest.aCursorAGameHidLongAgoIsNotChasedThoughTheMouseMoves`, `…aGameCursorHiddenAfterTheHostShowedItIsNotChasedToTheEdge`, `…aHiddenCursorCreepingOnePixelAtATimeIsNotChased` |
| 21 | Zoom at a desktop corner / edge | pointer | Z | any | view clamps; cursor stays on screen | `…aDesktopCornerIsReachableWithoutHidingTheCursor` |
| 22 | Letterbox padding (desktop aspect ≠ stream) | any | Z | any | cursor and client-owned placements are clamped to the desktop content box, never into the padding | `HostCursorTest.theEstimateStaysOnTheDesktopNotInTheLetterbox` |
| 23 | Very high zoom (up to 10x) | any | Z | any | margins are fractions of the view; speed caps scale with it | `CursorFollowMotionTest.speedAndAccelerationAreCapped` |
| 24 | Rotation, PiP, split-screen / freeform resize, external display attach | pointer | Z | any | cursor brought back into view after the resize | `…aResizeBringsTheCursorBack` |
| 25 | Soft keyboard opens | pointer | Z | any | visible area ends at the keyboard; cursor brought above it; the host crop moves to cover what is above it (its size keeps the surface aspect) | `CursorFollowWiringTest.theBinderWatchesTheSoftKeyboardAndForwardsViewChanges` (wiring only, see limits) |
| 26 | Pointer capture toggled | pointer | Z | DR | estimate forgotten; next move places the pointer inside the view | `…afterACaptureToggleTheNextMovePlacesTheCursorInView` |
| 27 | Mouse-mode switch | any | Z | any | visibility re-checked | `CursorFollowWiringTest.aMouseModeSwitchAndACaptureToggleReCheckVisibility` |
| 28 | Reconnect / new stream (incl. a remembered zoom) | any | any | any | estimate reset; first zoomed-in move places the pointer inside the view | `HostCursorTest.aNewStreamForgetsEverything`, row 26's mechanism |
| 29 | Keyboard-only use | — | Z | any | no cursor movement, no view movement | — |
| 30 | Unzoomed | any | 1x | any | nothing to follow; everything visible | `CursorFollowBindingTest.unzoomedThereIsNothingToFollow` |
| 31 | Following turned off in Settings | any | any | any | view never moves on its own, pointer never placed, no 0x3004 subscription | `CursorFollowBindingTest.withFollowingOffTheViewStaysPut`, `CursorFollowControllerTest.aDisabledControllerNeverSubscribesOrPans` |
| 32 | "Remove animations" on | any | Z | any | jumps to the target in one frame | `CursorFollowControllerTest.withAnimationsOffTheViewJumpsInOneFrame` |
| 33 | A host report older than a position the client just sent | any | Z | H | ignored for 300 ms | `CursorFollowControllerTest.aStaleHostReportJustAfterTheClientMovedThePointerIsIgnored` |
| 34 | External-display controller (phone as trackpad for a second screen) | pointer | any | any | *Assumed, not traced:* its input reaches the host through the same `NvConnection` methods, so rows 6 / 11 apply; its own Pan/Zoom toggle mirrors `Game.toggleZoomMode` | — |
| 35 | Moving left vs right, all four edges and corners, letterboxed 5360x1440 desktop, host acceleration 1.8x (the second report) | pointer | Z | DR | the same everywhere: the cursor stays on screen, and view and cursor reach every edge and corner (within 20 desktop px on all four sides) | `…everyEdgeAndCornerIsReachedWithTheCursorOnScreen` (fails without the client-owned pointer: `host cursor 352 off screen: visible 550..1030`), `CursorFollowControllerTest.pushingLeftAndRightIsSymmetric` |
| 36 | Slow drag in gaming touch mode at a sensitivity other than 100% | pointer | any | any | no motion lost: the sub-pixel remainder is carried (at 150% a 1-px-per-sample drag used to send two thirds of it; at 70% nothing) | `TouchDeltaAccumulationTest.gamingTouchModeLosesNoMotionAtAnySensitivity`, `SubPixelAccumulatorTest` |
| 37 | An on-screen overlay (PC keyboard) covers the bottom of the stream | pointer | Z | any | visible area ends above it; cursor kept above it; host crop moves with it | `CursorFollowBindingTest.anOverlayOverTheBottomOfTheStreamKeepsTheCursorAboveIt` |
| 38 | Trackpad natural and gaming against an old host (echo v1, no 0x3004), portrait 2160x3840 stream in a 1220x2169 container, 5360x1440 desktop, 1.8x acceleration: unzoomed moves, pinch, strokes both ways | pointer | 1→Z | DR | cursor on screen after every stroke | `…deviceSession*AgainstAnOldHost` (landscape and portrait) |
| 39 | The same with the zoom restored by "remember zoom" before the stream starts (no pinch) | pointer | Z | DR | as 38 | `…aZoomRestoredBeforeTheStreamIsFollowed*` |
| 40 | Stream start (or rotation) with the desktop filling < 60% of the window in one axis (5360x1440 on an upright phone: a 1220x330 px strip) | any | 1→A | any | starts zoomed so the desktop fills the window, capped at 2 screen px per desktop px and at the handler's 10x, centred on the cursor when it is known -- usually the desktop's middle, since the zoom happens at the first echo, before a 0x3004 report can arrive; a report then brings the cursor into the comfort margin, and against an old host the first move puts the pointer mid-view. The window measured is the stream's box, not shortened by the soft keyboard or an overlay, and each geometry is measured once, so typing and echoes never re-zoom. Follow works from there | `…aWideDesktopOnAnUprightPhoneStartsZoomedAndFollowedTrackpadNatural/Gaming`, `AutoCursorZoomTest` |
| 41 | 16:9 desktop in landscape, auto zoom switched off, or a zoom restored by "remember zoom" | any | 1 / Z | any | no auto zoom | `…aSixteenByNineDesktopInLandscapeIsNotZoomed`, `…withTheSwitchOffTheStripStays`, `…aRestoredZoomIsTheUsersAndIsKept` |
| 42 | The user pinches after an auto zoom (in or all the way out) | any | A→Z | any | their zoom wins for the rest of the stream: later echoes and rotations do not re-zoom | `…aUserWhoPinchesOutStaysOut` |
| 43 | Native-touch tap inside the edge band | direct | Z | any | no scroll: a contact is followed only once it has travelled 24 px (a drag), so the lift reaches the host where the finger went down | `…aNativeTouchTapInTheEdgeBandIsNotDraggedByAPan`, `CursorFollowControllerTest.aTapInTheEdgeBandDoesNotScrollTheView` |
| 44 | The host reports its cursor hidden (0x3004 visible=false) | any | Z | host | followed only where the user drove it: a cursor that was visible and goes hidden within 500 ms of pointer input (the emulator host hid it at the desktop edge mid-swipe), or, before the host has shown its cursor this stream, one pinned against the desktop edge while driven (a resumed session whose first report is the cursor still hidden there). Decided per report, so a follow in progress runs to its end. sunmeow sends one report when its cursor hides and none while it stays hidden, so the resumed case is decided by the user's first relative move *into* the edge the hidden cursor lies on, instead of a report (never the desktop's top-left origin, which is sunmeow's placeholder for a cursor its capture has not seen). Against sunmeow the view therefore stops at the hide point until the cursor is shown again; a follow of a hidden cursor ends once the view has reached it, so a later re-check (keyboard, rotation) does not pull the view back. For a host that does re-send while hidden: a report within 1 px of the point it was decided at keeps it, one sliding along the edge it was decided on while the user drives it (a diagonal swipe) keeps it and moves the anchor, and one that has moved off that point any other way (even 1 px at a time) drops it, as does dragging a still-hidden cursor off the edge. A hidden cursor at its point is followed until the view reaches it. A cursor hidden without input, or hidden and wandering (an RTS right-drag), is not chased (row 20). Accepted: a game that hides the cursor at the moment the user moves it (an FPS recentring on capture) gets a pan to that point | `CursorFollowControllerTest.aCursorTheHostCallsHiddenIsFollowedWhileTheUserMovesIt`, `…aFollowStartedOnADrivenHiddenCursorRunsToItsEnd`, `…aCursorHiddenWhileDrivenThatWandersOnIsNotChased`, `…aCursorHiddenByTheHostOnItsOwnIsNotChasedEvenWhenTheMouseMovesLater`, `…aResumedCursorHiddenAtTheEdgeIsFollowedWhenDriven`, `…aResumedCursorHiddenAtTheEdgeIsFollowedOnTheFirstMoveThoughTheHostSendsNoMore`, `…aHiddenCursorSlidAlongTheEdgeByADiagonalSwipeStaysFollowed`, `…aHiddenPlaceholderAtTheDesktopOriginIsNotChasedOnTheFirstMove`, `…aMoveAwayFromTheEdgeDoesNotPanToAHiddenCursorThere`, `…aSettledHiddenFollowDoesNotPullTheViewBackLater`, `GameCursorFollowModesTest.aReportingHostThatHidesTheCursorAtTheEdgeIsFollowedToIt`, `…aResumedSessionFollowsACursorLeftHiddenAtTheEdge` |
| 45 | Reporting host, trackpad, portrait 1080x2400, 1920x1200 monitor, auto zoom 3.56x; also after a multi-touch-to-trackpad switch and in a second session in a new `Game` | pointer | A | host | followed on every swipe | `GameCursorFollowModesTest.aReportingHost*` |

## Known limits, stated

* **Unzoomed there is nothing to follow, by design.** Build 601cff94's "mouse is not
  followed" report was *not* reproduced: every trackpad session the tests replay (natural and
  gaming, portrait and landscape, an old host without 0x3004) follows once zoomed, and the
  trackpad path did not change in that build. The device's input log shows no two-finger
  event in either session, and a reinstall drops a remembered zoom, so the stream was most
  likely at 1x, where a 5360x1440 desktop on an upright phone is a 1220x330 px strip. Auto
  cursor zoom (rows 40-42) now starts such a stream zoomed. `adb logcat -s MeowFollow` prints
  the zoom at stream start and every auto zoom, which settles the next report.
* **The resumed-session case depends on the position sunmeow reports for a hidden cursor.**
  It publishes the last position its capture saw visible, and (0, 0) when it never saw one
  (`cursor_pipewire.h`). A cursor left hidden at the edge by a fast swipe may be reported a
  little inside the edge band, or, on a fresh capture, at the placeholder the client
  ignores; in both cases the resumed view is not pulled to the edge until the cursor shows.
  Better fixed on the host (report no position it never saw); verify with
  `adb logcat -s MeowFollow` ("first host cursor report").
* **A resending host's slide along the edge holds only while the follow is under way**: once
  the view has reached the hidden cursor the follow ends, and a later hidden report further
  along the edge is not followed; turning a corner between two reports drops it too.
  sunmeow does not re-send while hidden, so neither arises there.
* **The hidden-cursor "pinned" rule (row 44) is judged against the whole desktop's box.**
  On a multi-monitor desktop a cursor left hidden at an *inner* edge (the bottom of the
  shorter monitor) is not seen as pinned, and before the viewport echo arrives the box is
  the whole stream, letterbox included. A stream that *starts* inside a fullscreen game
  with a confined, hidden cursor can be panned to the edge the cursor touches, until the
  host first shows its cursor.
* **Auto zoom does not remember a zoom per host or orientation.** The user's pinch holds for
  the stream; the next stream measures again -- unless "Remember zoom" (off by default) is
  on: it saves whatever zoom the stream ended at, auto zoom included, and a restored zoom is
  the user's, never auto-zoomed over, so rotation no longer adapts it either.
* **A stream that is itself a strip may zoom twice at start** against a meow host: once at
  stream start, measured against the whole frame, and again at the first echo, which knows
  the desktop's box and size. Stock hosts send no echo and zoom once.
* **Rotation is exercised by reasoning, not a test**: the resize path re-measures unless the
  user has zoomed. Robolectric does not lay out the decor view, so the keyboard/overlay test
  (`…theKeyboardOrAnOverlayDoesNotReZoom`) passes with or without the fix; the guarantee is
  that `StreamViewportBinder.window()` returns the parent's own box.

* **The soft keyboard row is not exercised in a unit test.** Robolectric does not dispatch
  IME insets. The visible-area arithmetic is the tested `windowFromLocationInWindow`, fed a
  window shortened by the IME inset read from `getRootWindowInsets()` (API 30+; older devices
  treat the keyboard as not covering the stream). The trigger relies on insets being
  dispatched to the stream container, which a fullscreen window does; failing that, the next
  cursor move re-checks.
* **Linear pointer while the client owns it** (decision 4, zoomed in only). A game that needs
  raw relative mouse input will not get it while zoomed in against a host that does not
  report its cursor; unzoomed it always does.
* **The client-owned pointer overrides host-side warps.** An app that recentres or warps the
  pointer (a dialog grabbing it, Blender's continuous grab) is undone by the next move while
  the client owns the pointer, because the client does not see the warp. Hosts that report
  their cursor are not affected.
* **Stock hosts with a letterboxed desktop.** With no echo there is no desktop size: relative
  deltas are taken as stream pixels (motion 2.8x too fast on a 5360-wide desktop in a
  1920-wide stream) and the follower cannot tell padding from desktop. The cursor is still
  kept on screen for dead-reckoned motion, but a pointer the host clamped at a desktop edge
  that lies inside the stream can differ from the estimate.
* **Relative moves sent off the UI thread** (evdev capture on rooted devices, not in this
  build's flavour) are never owned by the client and mark the estimate as a guess.
* **Soft keyboard below API 30** is not seen (no IME inset API); overlays can declare
  themselves with `StreamViewportBinder.setBottomObstruction`.
* **The crop swap and the follower are independent.** While the view eases, the host crop
  follows with the usual viewport round trip, so the edges of a pan show the soft zoom of the
  previous crop for a moment (see `MEOW-TOUCH(viewport-compose)`).
* **Rows 29 and 34 are reasoning, not tests.**
* **Device behaviour is verified by the orchestrator with the user**, not here: the rows are
  pinned against Robolectric's `Game`, a fake host and pumped vsyncs.
