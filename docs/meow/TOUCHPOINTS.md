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

### `app/src/main/java/com/limelight/Game.java` — 5 sites

| Line | Site | Edit |
| --- | --- | --- |
| 155 | field declaration | `private InlinePinchZoomController inlinePinchZoom;` |
| 496 | `onCreate`, beside the existing `PanZoomHandler` construction | one constructor call wiring the controller to the handler and to two method references |
| 3122 | finger branch of `handleMotionEvent`, **after** the multi-finger gesture block | one `if` that offers the event to the controller and returns early if it was consumed. The `touchContextMap[0] == null` early return was turned into a `touchContextsUnavailable` local and moved below the hook, so pinch still works when touch-as-mouse is off — see "Dispatch order is the guarantee" below |
| 3378 | beside `cancelStaleTouchState` | `cancelInFlightTouchContexts()`, a 7-line helper |
| 4259 | `applyMouseMode`, after the touch contexts are rebuilt | `inlinePinchZoom.reset()`, so a latched zoom cannot survive the gesture surface being torn out from under it |

Line numbers are a convenience for the audit, not a contract; `git grep -n 'MEOW-TOUCH'`
is the authority and the count above must match it.

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

### Dispatch order is the guarantee, and it is load bearing

The inline-pinch hook sits **below** the `pointerCount > 2` multi-finger block in
`handleMotionEvent`, so `handleMultiTouchGesture` gets first refusal on every event that
could be a 3/4/5 finger gesture — in every mouse mode where the touch contexts exist. Do
not move it back up.

The exception is **mouse mode 4** ("touch mouse disabled"), where `touchContextMap[0]` is
null, the multi-finger block is skipped, and the hook gets first refusal again. That is
harmless rather than a hole: 3/4/5 finger gestures have never worked in that mode, upstream
included, because the `touchContextMap[0] == null` return has always sat above the block.
Two consequences are worth stating rather than leaving to be rediscovered: while a zoom is
latched in mode 4 the multi-finger gestures stay unavailable, and mode 4 is the only place
the controller's pause-and-rebaseline path (`needsRebaseline`) is still reachable — a third
finger there pauses the zoom instead of ending it.

The reason is that latching ZOOM is a **consuming** decision: `InlinePinchZoomController`
cancels the in-flight touch contexts and swallows every later `ACTION_POINTER_DOWN` for
the rest of the gesture. Anything the hook swallows, the recognisers below it never see —
so with the hook first, a third finger that lands *after* the latch is simply gone, and
the soft keyboard, full keyboard and five-finger game menu die silently until every finger
lifts.

It looks as though the arbiter's `disqualify()` protects them, and that is the trap: a
third finger arrives as `ACTION_POINTER_DOWN` with `pointerCount == 3` and disqualifies
the arbiter — but only **if ZOOM has not already latched**. Fingers in a multi-finger tap
do not land in the same frame; at 120-240Hz there are several `ACTION_MOVE` frames between
the second contact and the third, and a "grab"-shaped tap whose fingers converge as they
land crosses the span slop inside that gap.

**Putting the multi-finger block first costs zoom nothing.** That block only ever acts on
`ACTION_POINTER_DOWN`, `ACTION_POINTER_UP` and `ACTION_UP` at `pointerCount > 2`; it never
handles `ACTION_MOVE`. Zoom is driven entirely by `ACTION_MOVE`, so every frame that
matters still reaches the hook unchanged, and a two-finger gesture never enters the block
at all. `theMultiFingerBlockIsNeverOfferedAMoveFrame` pins that, and goes red if upstream
ever adds `ACTION_MOVE` to the block.

**A three-finger gesture during a zoom ends the zoom, which is correct.**
`handleMultiTouchGesture` calls `cancelStaleTouchState`, which dispatches a synthetic
`ACTION_CANCEL` back through the view and therefore re-enters `handleMotionEvent`. The
multi-finger block ignores `ACTION_CANCEL`, so it falls through to the hook, which ends
the zoom, fires `onZoomEnd` and resets the arbiter — exactly once, before the outer call
returns. The user gets the keyboard and a clean slate, rather than a zoom still latched
behind an open keyboard.

**The `touchContextMap[0] == null` early return moved below the hook, deliberately.**
That return fires only in mouse mode 4 ("touch mouse disabled"), and it used to sit
*between* the hook and the multi-finger block, so the hook could not simply be moved down
past it without also disabling pinch in that mode. Losing pinch there would be a real
regression: mode 4 is exactly the configuration where zooming the local view is the only
thing touch is still for. It is now a `touchContextsUnavailable` local, checked after the
hook. Nothing is skipped by the deferral — the multi-finger block still never runs with
null contexts (`cancelStaleTouchState` would NPE on them), and `trySendTouchEvent` /
`handleTouchInput` are still unreachable in that mode.

**There is no dwell, and adding one would be a regression.** An earlier revision withheld
the ZOOM latch for 40ms after the second finger landed, to let a third finger arrive
first. That was probabilistic — a third finger landing at 41ms was still stolen, with no
bound on the gap — and it broke the span-slop invariant below: the slop is set under
`RelativeTouchContext.TAP_MOVEMENT_THRESHOLD` precisely so the latch happens *before* the
touch contexts confirm a move and start sending scroll to the host. A dwell adds a *time*
precondition the slop cannot satisfy, so for 40ms events kept flowing to the contexts
however far the fingers had moved, and a deliberate pinch (~500px/s) with a vertical
component leaked real scroll onto the remote desktop. `cancelTouch()` cannot un-send it.
Ordering fixes deterministically what the dwell only made less likely, and costs nothing.

Pinned by `InlinePinchZoomDispatchOrderTest`, which models the dispatch order, replays the
old hook-first ordering to prove the model is falsifiable, and reads `Game.java` itself to
assert the two call sites are still in the right order. Note that the tests which *look*
like they cover this — `threeFingerGestureIsNeverStolen` and
`aThirdFingerDoesNotReArmAfterItLifts` in `InlinePinchZoomControllerTest` — do **not**:
both put the third finger down with no intervening `ACTION_MOVE`, which is the one
ordering that can never fail.

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

---

## `MEOW-TOUCH(viewport-follow)`

**Feature:** tell the host which rectangle of its desktop the client is currently
displaying, so it can crop before scaling into the encoder.

**Why it exists:** a 5360x1440 two-monitor desktop scaled into a 5-8 Mbps encoder is
unreadable. The user pinches in to read something; every bit spent on the other 90% of
the desktop is wasted. Reporting the visible rectangle lets a host that understands it
spend the same bitrate on a fraction of the pixels. **This branch is the client half
only** — it reports the rectangle and changes nothing about what the client renders.

**Off by default.** See `ViewportPreference` for the argument; the short version is that
no released host implements the extension, and this is the least reversible thing a
client preference can do.

### `app/src/main/jni/moonlight-core/Android.mk` — 1 site

| Line | Site | Edit |
| --- | --- | --- |
| 48 | after the `LOCAL_SRC_FILES` block | a three-line stanza appending `meowjni.c` |

`meowjni.c` is a new file, not an edit to `simplejni.c`, so an upstream sync can never
conflict on it. **Read the JNI hazard section of `CLAUDE.md` before touching it.** The
symbol `Java_com_limelight_meow_viewport_MeowViewportBridge_sendViewport` binds by static
mangled name; moving or renaming `MeowViewportBridge` without renaming the symbol produces
a build that succeeds and dies at first call with `UnsatisfiedLinkError`.
`MeowViewportBridgeContractTest` derives the expected symbol from the class object and
asserts it is present in the C source and that the C source is actually compiled in, so
the mistake fails a test rather than a user's stream. There is deliberately **no**
`FindClass` in that file — nothing calls back into Java from it — and the same test fails
if one appears, because a slash-form class string does not move when the package does.

### `app/src/main/java/com/limelight/utils/PanZoomHandler.java` — 3 sites

| Line | Site | Edit |
| --- | --- | --- |
| 31 | field declaration | `private ZoomTransformObserver zoomTransformObserver;` plus a setter and a two-line `notifyZoomTransformChanged()` helper |
| 95 | end of `constrainToBounds()` | one `notifyZoomTransformChanged()` call |
| 203 | end of `setInitialZoomAndPan(...)` | one `notifyZoomTransformChanged()` call |

`constrainToBounds` is the single choke point for the transform — `pinchBy`, `panBy` and
`handleSurfaceChange` all end there — which is why one call site covers pinch, pan,
rotation and PiP resize alike. `setInitialZoomAndPan` is the one transform that bypasses
it; without the second call a zoom restored by `rememberZoomPan` would leave the host
uncropped until the user next moved. Both are pinned by `ViewportWiringTest`.

The observer interface lives in `meow/` rather than here so this class only gains a field,
a setter and two calls.

### `app/src/main/java/com/limelight/Game.java` — 4 sites

| Line | Site | Edit |
| --- | --- | --- |
| 157 | field declaration | `private StreamViewportBinder viewportBinder;` |
| 500 | `onCreate`, after the inline-pinch wiring | a six-line block that builds the binder **only when the preference is on** and attaches it to `panZoomHandler` |
| 3498 | top of `stopConnection()` | one guarded `viewportBinder.onStreamStopped()` |
| 3736 | `connectionStarted()` | one guarded `viewportBinder.onStreamStarted(displayWidth, displayHeight)` |

The construction is inside the preference guard on purpose: an install that has not opted
in leaves `viewportBinder` null, so not one line of this feature executes and behaviour is
bit-for-bit what it was before.

`stopConnection()` is the correct place for the uncrop rather than `onDestroy`:
`LiSendViewportEvent` may only be called between `LiStartConnection` and
`LiStopConnection`, and this site runs on the UI thread before `conn.stop()` is handed to
its worker thread. `ViewportWiringTest` asserts that ordering.

`connectionStarted()` passes `displayWidth`/`displayHeight` — the resolution actually
negotiated in `StreamConfiguration`, which is inverted from `prefConfig` in portrait, so
reading `prefConfig.width` here would be wrong.

### New code (additive, in `meow/viewport/`)

| File | Android? | What it is |
| --- | --- | --- |
| `ViewportRect.java` | no | immutable rectangle, clamped to the `uint16` wire range |
| `ViewportGeometry.java` | no | view transform &rarr; visible host rectangle |
| `ViewportThrottle.java` | no | which updates reach the wire, with an injected clock |
| `ViewportReporter.java` | no | the state machine: lifecycle, host support, settle |
| `ZoomTransformObserver.java` | no | the one-method seam `PanZoomHandler` gained |
| `MeowViewportBridge.java` | JNI | the native call |
| `HandlerSettleScheduler.java` | yes | `Scheduler` over a `Handler` |
| `StreamViewportBinder.java` | yes | reads the views, nothing else |
| `ViewportPreference.java` | yes | reads the preference, and documents why it defaults off |

Tested by `app/src/test/java/com/limelight/meow/viewport/`.

### The coordinate space is an open interop question

`Limelight.h` says the rectangle is in *host desktop pixels*. **The client cannot know the
host's desktop size.** Sunshine's `serverinfo` does not report it and nothing else in the
handshake carries it, so what this sends is the rectangle expressed against the
**negotiated stream resolution** — the `displayWidth`/`displayHeight` passed to
`StreamConfiguration`, which the host also knows exactly. That is the same reference-space
convention `LiSendMousePositionEvent` already uses for absolute mouse positioning.

If the host implements the crop in true desktop pixels and the user's stream resolution is
not their desktop resolution, the two halves disagree and the crop lands in the wrong
place. **This must be reconciled with the host implementation before the preference is
worth turning on.** The protocol already has the mechanism to settle it: the
`ConnListenerSetViewport` echo reports the rectangle the host actually applied, in host
desktop pixels, which is enough to calibrate. That callback is **not** wired up here.

### Known gap: cropping and local zoom compose wrongly

When a host honours the viewport, the encoded frame stops being the whole desktop and
becomes the crop, scaled up to the same encoder resolution. The client, meanwhile, is
still displaying that frame under the user's local zoom — so the user sees the region they
asked for magnified *again*, and absolute mouse coordinates (`getNormalizedCoordinates`
&rarr; `LiSendMousePositionEvent`) now address the crop rather than the desktop.

Resolving that means the client resetting its local transform to 1:1 when the host confirms
a crop, and composing later zooms on top of the applied rectangle rather than on the raw
frame — which is a different feature from "report the rectangle", needs the echo callback,
and contradicts "reset the viewport to full-desktop when zoom returns to 1:1" (under
composition, zoom is *always* back at 1:1). It is deliberately not attempted here, and it
is the reason the preference ships off.
