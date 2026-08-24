# Touch-point registry

Every place moonmeow code has been welded into an upstream (Artemis) file, per
`CLAUDE.md` §2 and §3. Additive-only is the rule; everything listed here is an
exception that had to earn its place.

Audit before each upstream sync:

```bash
git grep -n 'MEOW-TOUCH' -- app/src
```

A growing registry means features are being welded into upstream code instead of
layered beside it. Keep it short.

---

## `MEOW-TOUCH(inline-pinch-zoom)`

**Feature:** pinch-to-zoom that works inline, with no mode toggle.

**Why upstream had to be touched at all:** zooming used to require flipping
`Game.isPanZoomMode` from the game menu or the overlay button. While the mode was on,
touch drove the local view and the mouse was gone; while it was off, pinching did
nothing. Chrome Remote Desktop has no such mode, and the round trip through a menu is
the single biggest reason this app feels clunky next to it. The gesture has to be
routed at the point where touch events are already dispatched, which is inside
`Game`. The explicit Pan/Zoom mode is left intact for users who prefer it.

### `app/src/main/java/com/limelight/Game.java` — 4 sites

| Site | Edit |
| --- | --- |
| field declaration | `private InlinePinchZoomController inlinePinchZoom;` |
| `onCreate`, beside the existing `PanZoomHandler` construction | one constructor call wiring the controller to the handler and to two method references |
| finger branch of `handleMotionEvent`, immediately after the `isPanZoomMode` block | one `if` that offers the event to the controller and returns early if it was consumed |
| beside `cancelStaleTouchState` | `cancelInFlightTouchContexts()`, a 7-line helper |

The touch hook is skipped when `prefConfig.enableMultiTouchScreen` is set. In that
mode the host receives native touch events, so a pinch belongs to the remote
application rather than to our local view; stealing it would be a regression.

**Consequence worth stating plainly: inline pinch does nothing in the default mouse
mode.** `preferences.xml` defaults `mouse_mode_list` to `0` (Multi-touch), and
`PreferenceConfiguration` maps mode 0 to `enableMultiTouchScreen = true`. On a fresh
install the feature is therefore inert; it is active in Normal-mouse (modes 1/5) and
both Trackpad modes (2/3), which is where the desktop-use audience already lives.

The guard is also deliberately broader than its own rationale. Native touch only
actually reaches the host when `trySendTouchEvent(...)` succeeds; when the host does
not support touch, events fall through to ordinary mouse handling and inline pinch
would be safe there too. Extending it to that case means deciding per event whether
the host took the touch, which is a behaviour change to native-touch mode and wants
its own branch. Left alone on purpose.

`cancelInFlightTouchContexts()` exists because a gesture is ambiguous when it starts.
By the time it is confirmed as a pinch, the `TouchContext`s have already seen the
pointers go down. Without cancelling them, lifting the fingers at the end of a pinch
would land on the host as a two-finger-tap right click.

It calls `cancelTouch()` **and** `setPointerCount(0)`, mirroring the upstream
`cancelStaleTouchState`. Both calls are load bearing and dropping either is a trap:

- Without `setPointerCount(0)` the context keeps `pointerCount == 2`, so the next
  gesture's `setPointerCount(1)` hits `TrackpadContext`'s `this.pointerCount == 2 &&
  pointerCount == 1` branch, arming a 200ms `isScrollTransitioning` cursor stall *and*
  firing a spurious button-up at the start of an unrelated gesture.
- `setPointerCount(0)` is also what clears `confirmedDrag`, `isClickPending` and
  `isDblClickPending`, so a stale drag cannot leak forward.

### Known upstream defect this feature makes easier to reach

`TrackpadContext.cancelTouch()` releases `getMouseButtonIndex()`, which is derived from
the *current* pointer count rather than from the button that was actually pressed. A
quick tap presses `BUTTON_LEFT` and holds it for up to 230ms
(`CLICK_RELEASE_DELAY`); if two fingers land inside that window, `touchDownEvent`
converts it to `confirmedDrag` with LEFT still down, and any subsequent cancel releases
`BUTTON_RIGHT` instead — stranding the left button on the host desktop.

This is **not introduced here**. Upstream already reaches it through the ordinary
double-tap-then-two-finger-drag path and through any 3+ finger gesture, both of which
route into the same `cancelStaleTouchState`. Inline pinch adds one more route to it
(tap, then pinch within 230ms).

It is deliberately **not** fixed on this branch: the correct fix is for
`TrackpadContext` to record the button it pressed and release that one, plus clear
`confirmedDrag` in `cancelTouch()`. That is a stateful change to a third upstream file
with its own test surface, and §9 says one feature per branch. It deserves its own.

### `app/src/main/java/com/limelight/utils/PanZoomHandler.java` — extract + implement

The scale transform was moved out of `ScaleListener.onScale` into `pinchBy(...)` and
the pan transform out of `GestureListener.onScroll` into `panBy(...)`, both verbatim;
the listeners now delegate. The class declares
`implements InlinePinchZoomController.ZoomTarget`.

This is a multi-line upstream edit rather than a new class on purpose. The
alternative — reimplementing the scale clamp, focal pivot and bounds constraint in
`meow/` — would have created a second copy of the zoom transform *and* a second
source of truth for `scaleFactor`, which `Game` reads back for the
`rememberZoomPan` preference. One implementation, driven from two entry points, is
the smaller long-term liability. The explicit Pan/Zoom mode now provably runs the
same code as the inline path.

### New code (additive, in `meow/`)

- `app/src/main/java/com/limelight/meow/gesture/TwoFingerGestureArbiter.java` —
  scroll-vs-pinch disambiguation. Plain Java, no Android imports.
- `app/src/main/java/com/limelight/meow/gesture/InlinePinchZoomController.java` —
  event routing. Its core, `handle(...)`, takes plain numbers so it is testable
  without fabricating `MotionEvent`s.

Tested by `app/src/test/java/com/limelight/meow/gesture/`.

### While a zoom is latched, multi-finger gestures are unavailable

`InlinePinchZoomController` consumes `ACTION_POINTER_DOWN` / `ACTION_POINTER_UP` once a
gesture has latched to zoom, so `handleMultiTouchGesture` never runs and
`threeFingerDownTime` / `fourFingerDownTime` / `fiveFingerDownTime` are never set. The
keyboard toggles and — worth knowing — the **five-finger game-menu gesture** are dead
until every finger lifts. That is at most the length of one pinch, and the alternative
(letting a third finger re-enter the multi-touch path mid-zoom) means guessing at an
ambiguous gesture while the view is being transformed. Accepted on purpose.

What is *not* accepted is the zoom latching in the first place while one of those
gestures is still landing — see the dwell below.

### The ZOOM latch waits 40ms, and that is load bearing

`TwoFingerGestureArbiter.DEFAULT_ZOOM_LATCH_DWELL_MS` holds the ZOOM latch back for 40ms
after the second finger lands. SCROLL is not delayed.

The reason is the section above. Latching ZOOM is *consuming*: it cancels the in-flight
touch contexts and swallows every later `ACTION_POINTER_DOWN`, and the 3/4/5 finger
gestures are recognised **further down** `Game`'s dispatch than the inline-pinch hook. So
anything the latch swallows, they never see.

The ordering looks like it protects them, and this is the trap: a third finger arrives as
`ACTION_POINTER_DOWN` with `pointerCount == 3`, which disqualifies the arbiter before any
of this matters. But that only holds **if ZOOM has not already latched**. Fingers in a
multi-finger tap do not land in the same frame — at 120-240Hz there are several
`ACTION_MOVE` frames between the second contact and the third — and a "grab"-shaped tap
whose fingers converge as they land can push the span past the slop inside that gap.
Without the dwell the gesture latches ZOOM first and the user gets a zoom instead of the
soft keyboard or the game menu, *intermittently*, which is the worst kind.

Note that the tests which look like they cover this — `threeFingerGestureIsNeverStolen`
and `aThirdFingerDoesNotReArmAfterItLifts` — do **not**. Both put the third finger down
with no intervening `ACTION_MOVE`, which is the one ordering that can never fail.
`aMultiFingerTapIsNotStolenWhenTheFirstTwoFingersConvergeAsItLands` and
`aFourFingerGestureLandingOverSeveralFramesIsNeverStolen` are the ones that bite; both go
red if the dwell is set to 0.

**Time, not a frame count.** Three frames is 12.5ms at 240Hz and 25ms at 120Hz, so a
frame count that covers the gap on one device misses it on another. The timestamp is
`MotionEvent.getEventTime()`, passed into the arbiter as a plain `long` — the arbiter
stays Android-free and reads no clock of its own, which is what keeps it unit testable on
a bare JVM. There is deliberately **no** overload that omits the timestamp: one would
silently skip the guard in exactly the tests written to cover it.

**What the dwell costs, stated plainly.** It is not free, and 40ms is the low end of the
range for that reason. While the arbiter is undecided the events pass through to the
touch contexts, and those confirm a move at 20px — only 2px above `MAX_SLOP_PX`, where we
would otherwise have latched. For an anchored pinch (one finger still) the moving finger
travels the whole span change, so a pinch that crosses 20px inside 40ms — roughly
500px/s — will leak a scroll frame or two to the host before the latch, where previously
it leaked none. A slow or symmetric pinch leaks nothing. That is a real cost paid on
every fast pinch, traded against a stolen keyboard/menu gesture; every extra millisecond
of dwell buys less multi-finger coverage than the last while costing the same, because
most multi-finger taps land well inside 40ms.

Closing the leak *as well* means withholding two-finger events while undecided and
replaying them on a SCROLL latch — the same change rejected under "What the slop does not
cover" below, and rejected here for the same reason, plus a new one: swallowing the move
frames would leave the contexts believing the fingers never moved, so lifting them would
land on the host as a two-finger tap, i.e. a spurious right click.

Do not raise the dwell to cover sloppier multi-finger taps without re-reading the
paragraph above; `theDefaultDwellIsShortEnoughToStayImperceptible` pins it to the 25-60ms
band on purpose.

### Invariant worth knowing before you retune anything

`TwoFingerGestureArbiter`'s span slop must stay below the point at which the touch
contexts confirm a move and start emitting scroll to the host. Those thresholds are
fixed pixel counts that do **not** scale with display density:

| Context | Threshold |
| --- | --- |
| `RelativeTouchContext.TAP_MOVEMENT_THRESHOLD` | 20px (and `TAP_DISTANCE_THRESHOLD` 25px) |
| `TrackpadContext.TAP_MOVEMENT_THRESHOLD` | 30px |

20px is therefore the binding constraint. A symmetric pinch moves each finger by half
the span change and an anchored pinch moves one finger by the whole span change, so
latching under 20px of span change is what stops a pinch from briefly scrolling the
remote desktop on its way in.

The obvious default, `ViewConfiguration.getScaledTouchSlop()`, does not satisfy this:
8dp is 26px on the Poco X7 Pro (520dpi, measured) and 32px at 640dpi.
`InlinePinchZoomController.effectiveSlopPx(...)` caps it at `MAX_SLOP_PX` = 18px for
that reason, and
`InlinePinchZoomControllerTest.theSlopActuallyUsedInProductionStaysUnderTheScrollLeakThreshold`
feeds it those densities directly so the cap goes red if anyone raises it. (Asserting on
`TwoFingerGestureArbiter.DEFAULT_SPAN_SLOP_PX` alone does **not** cover the cap —
production never uses that default. Verified by mutation: raising `MAX_SLOP_PX` to 20,
25 or 33 all fail the test.)

Erring low is cheap: the slop only decides *when* the arbiter commits, not *what* it
commits to — that is the dominance rule in `classify(...)`.

**What the slop does not cover.** The contexts' second move test is a path integral,
not a displacement test: `RelativeTouchContext` accumulates `distanceMoved` against a
25px `TAP_DISTANCE_THRESHOLD`. The arbiter bounds displacement while undecided, and no
displacement bound bounds a path length — so a gesture that wanders with the fingers
together and only then pinches can still leak one scroll frame before the latch.
Closing that means withholding two-finger events while undecided and replaying them on
a SCROLL latch, which is disproportionate to the residual leak. Lowering the slop
cannot close it; do not try.
