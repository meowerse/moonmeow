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
| 3126 | finger branch of `handleMotionEvent`, **after** the multi-finger gesture block | one `if` that offers the event to the controller and returns early if it was consumed. The `touchContextMap[0] == null` early return was turned into a `touchContextsUnavailable` local and moved below the hook, so pinch still works when touch-as-mouse is off — see "Dispatch order is the guarantee" below |
| 3382 | beside `cancelStaleTouchState` | `cancelInFlightTouchContexts()`, a 7-line helper |
| 4263 | `applyMouseMode`, after the touch contexts are rebuilt | `inlinePinchZoom.reset()`, so a latched zoom cannot survive the gesture surface being torn out from under it |

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

**Feature:** tell the host which rectangle of the *stream frame* the client is currently
displaying, so it can map that back into its desktop and crop before scaling into the
encoder.

**Why it exists:** a 5360x1440 two-monitor desktop scaled into a 5-8 Mbps encoder is
unreadable. The user pinches in to read something; every bit spent on the other 90% of
the desktop is wasted. Reporting the visible rectangle lets a host that understands it
spend the same bitrate on a fraction of the pixels. **This branch is the client half
only** — it reports the rectangle and consumes the host's answer, and changes nothing
about what the client renders.

**Off by default.** See `ViewportPreference` for the argument. The preference is read once
in `Game.onCreate`, so toggling it takes effect on the **next** stream, not the running
one.

### `app/src/main/jni/moonlight-core/Android.mk` — 1 site

| Line | Site | Edit |
| --- | --- | --- |
| 48 | after the `LOCAL_SRC_FILES` block | a three-line stanza appending `meowjni.c` |

### `app/src/main/jni/moonlight-core/callbacks.c` — 2 sites

| Line | Site | Edit |
| --- | --- | --- |
| 416 | above `BridgeConnListenerCallbacks` | `#include "meowjni.h"` |
| 436 | inside `BridgeConnListenerCallbacks` | `.setViewport = MeowBridgeClSetViewport,` |

The callback body itself lives in `meowjni.c`, not here, so this upstream file gains an
include and a struct member and nothing else. The declaration is shared through
`meowjni.h` rather than repeated as an `extern` here: a parameter list that drifted between
the two translation units is undefined behaviour the compiler cannot see, and this feature
already changed that signature once. It is inert until
`MeowViewportBridge` is class-initialised — which only happens when the preference is on —
so an install that has not opted in reaches a `NULL` check and returns.

**Read the JNI hazard section of `CLAUDE.md` before touching `meowjni.c`.** Two symbols
bind by static mangled name:
`Java_com_limelight_meow_viewport_MeowViewportBridge_sendViewport` and
`..._nativeInit`. Moving or renaming `MeowViewportBridge` without renaming them produces a
build that succeeds and dies at first call with `UnsatisfiedLinkError`.

There is deliberately **no `FindClass`** in that file even though it now calls back into
Java. `nativeInit` is handed its `jclass` by the JNI calling convention, so the class
identity travels with the mangled name and there is no slash-form string that a package
move would leave stale — which is the half `nm -D` cannot see, and the half that already
shipped broken in this repo once. `MeowViewportBridgeContractTest` derives both mangled
names and the `(IIIIII)V` method descriptor from the class object, checks the struct member
is wired, checks `meowjni.c` is on an **uncommented** `LOCAL_SRC_FILES` line, and fails if a
`FindClass` or a slash-form class string ever appears.

`meowjni.c` reaches `GetThreadEnv()`, which `callbacks.c` exports, rather than caching a
`JNIEnv`: the echo arrives on moonlight-common-c's async callback thread, which is not a
Java thread.

### `app/proguard-rules.pro` — 1 site, deliberately untokenised

`MeowViewportBridge.onViewportEcho()` is called only from `meowjni.c`. R8 sees no Java
caller and **strips it from the release dex** — verified against `dexdump` on the built
APK, not assumed. `GetStaticMethodID()` then returns `NULL`, the host's echo is silently
dropped, and capability detection never succeeds: a build that passes every other check
while the feature quietly never engages. A `-keepclassmembers` rule names the method and
its exact parameter list, and `MeowViewportBridgeContractTest.theEchoEntryPointSurvivesR8`
fails if it is removed or its signature drifts.

This file lives outside `app/src`, so `git grep -n 'MEOW-TOUCH' -- app/src` (CLAUDE.md §3)
cannot see it. The token is therefore deliberately **not** written there — putting it in
would make the registry claim a site the audit command cannot verify. The site is recorded
here instead, and the rule carries a comment pointing back at this file.

### `app/src/main/java/com/limelight/utils/PanZoomHandler.java` — 4 sites

| Line | Site | Edit |
| --- | --- | --- |
| 31 | field declaration | `private ZoomTransformObserver zoomTransformObserver;` |
| 50 | `setZoomTransformObserver(...)` | the setter, plus a two-line `notifyZoomTransformChanged()` helper below it |
| 105 | end of `constrainToBounds()` | one `notifyZoomTransformChanged()` call |
| 213 | end of `setInitialZoomAndPan(...)` | one `notifyZoomTransformChanged()` call |

`constrainToBounds` is the single choke point for the transform — `pinchBy`, `panBy` and
`handleSurfaceChange` all end there — which is why one call site covers pinch, pan,
rotation, PiP resize and external-display attach alike, in explicit Pan/Zoom mode and
inline pinch equally. `setInitialZoomAndPan` is the one transform that bypasses it; without
the second call a zoom restored by `rememberZoomPan` would leave the host uncropped until
the user next moved. Both are pinned by `ViewportWiringTest`.

**There is exactly one observer slot, and it is single-ownership.** Calling
`setZoomTransformObserver` again silently displaces whatever was there. That is fine with
one caller (`Game.onCreate`, once per activity) and is documented on the setter; if a second
feature ever needs the transform, make it a list *then* rather than adding a second call and
assuming both survive.

The observer interface lives in `meow/` rather than here so this class only gains a field,
a setter and two calls.

### `app/src/main/java/com/limelight/Game.java` — 5 sites

| Line | Site | Edit |
| --- | --- | --- |
| 157 | field declaration | `private StreamViewportBinder viewportBinder;` |
| 500 | `onCreate`, after the inline-pinch wiring | a block that builds the binder **only when the preference is on and the render mode is `MODE_2D`** and attaches it to `panZoomHandler` |
| 1764 | `onDestroy()`, before the capture provider is destroyed | one guarded `viewportBinder.release()` |
| 3522 | inside `stopConnection()`'s teardown worker, above `conn.stop()` | one guarded `viewportBinder.onStreamStopped()` |
| 3751 | `connectionStarted()` | one guarded `viewportBinder.onStreamStarted(displayWidth, displayHeight)` |

The construction is inside the preference guard on purpose: an install that has not opted
in leaves `viewportBinder` null, so not one line of this feature executes and behaviour is
bit-for-bit what it was before.

The guard also requires `StreamContainer.StreamMode.MODE_2D`, obtained from the public
`streamContainer.mapIntToStreamMode(prefConfig.renderMode)` rather than compared against a
bare `0`. The stereo modes are the one configuration where `ViewportGeometry`'s premise
fails: `StreamContainer.onMeasure` short-circuits to `super.onMeasure` for them,
`getSurfaceView()` returns a `GLSurfaceView` rendering a stereo composition rather than the
stream frame, and the frame-to-host mapping is meaningless.

`onStreamStarted` is the *only* place a restored-zoom rectangle can be reported.
`setInitialZoomAndPan` runs from a `streamContainer.post(...)` in `onCreate`, hundreds of
milliseconds before the connection is up, so its notify is discarded. `StreamViewportBinder`
therefore reads the live transform back immediately after the probe and posts it behind the
probe on the same queue.
`StreamViewportBinderTest.aStreamStartingOnARestoredZoomReportsTheCropNotJustTheFullFrame`
pins it.

`stopConnection()` is the correct place for the uncrop rather than `onDestroy`:
`LiSendViewportEvent` may only be called between `LiStartConnection` and
`LiStopConnection`, and calling it after the peer is destroyed is a use-after-free rather
than merely a lost packet.

It sits **inside** that method's existing teardown worker, immediately above `conn.stop()`,
not on the UI thread above it. `onStreamStopped()` blocks until the uncrop reaches the
library, and the comment on that worker already says why network I/O does not belong on the
UI thread. Both orderings are pinned: `theStreamStopUncropsBeforeTheConnectionGoesDown` and
`theUncropDoesNotRunOnTheUiThread`.

**`onDestroy()` releases the binder unconditionally, and that is not redundant.**
`stopConnection()` is guarded on `connecting || connected`, and `connecting` is never
assigned `true` anywhere in `Game` — so a handshake that fails, or a user who backs out
while connecting, never reaches `onStreamStopped()` at all. Without the `release()` call the
reporter's `HandlerThread` would outlive the Activity, once per attempt, and the static echo
listener would keep pointing at a dead binder that holds `streamView` and `parent` — which
is to say, the Activity. On the flaky mobile link this feature exists for that is not a rare
path. `release()` is idempotent and safe from any thread.

`connectionStarted()` passes `displayWidth`/`displayHeight` — the resolution actually
negotiated in `StreamConfiguration`, which is inverted from `prefConfig` in portrait, so
reading `prefConfig.width` here would be wrong.

### New code (additive, in `meow/viewport/`)

| File | Android? | What it is |
| --- | --- | --- |
| `ViewportRect.java` | no | immutable rectangle, clamped to the `uint16` wire range |
| `ViewportGeometry.java` | no | view transform &rarr; visible stream-frame rectangle |
| `ViewportReferenceFrame.java` | no | the host's letterbox transform, mirrored from the echoed desktop size |
| `ViewportReporter.java` | no | the state machine: lifecycle, capability probing, clamping |
| `ZoomTransformObserver.java` | no | the one-method seam `PanZoomHandler` gained |
| `MeowViewportBridge.java` | JNI | the native call out and the echo back in |
| `HandlerDeadlineScheduler.java` | yes | `Scheduler` over a `Handler` |
| `StreamViewportBinder.java` | yes | reads the views, owns the reporter's thread |
| `ViewportPreference.java` | yes | reads the preference, and documents why it defaults off |

Tested by `app/src/test/java/com/limelight/meow/viewport/`.

### Capability detection is the echo, and only the echo

`LiSendViewportEvent` returning `0` proves nothing about the host. `packetTypes` is selected
from the advertised app version alone (`ControlStream.c`, `initializeControlStream`), and
stock Sunshine advertises `7.1.431.-1`, which selects `packetTypesGen7Enc` — whose
`IDX_VIEWPORT` entry is `0x3003`, not `-1`. So against a host that has never heard of this
extension the call succeeds **and a reliable control packet really goes out**. `-3` is
returned only for GFE Gen 3/4/5 and unencrypted Gen 7.

An earlier revision of this branch gated on that return value. The consequence, confirmed
by reading the table rather than assumed, was up to ~20 unknown reliable packets a second at
stock Sunshine for an entire session.

`ViewportReporter` therefore **probes**: one full-frame rectangle at stream start, then
silence until `ConnListenerSetViewport` answers. No echo inside `ECHO_DEADLINE_MS` (2 s)
retries once; no echo after that latches the feature off for the session. A non-supporting
host sees two packets in total. The user-facing preference summary says exactly this.

**Silence is the host's own contract, not just an absence.** `meow::viewport::apply_request()`
(`sunmeow/src/meow/viewport_runtime.h`) answers *every* understood request, including one it
refuses — it echoes the full content area — so "no echo" is unambiguous. It stays silent in
exactly three cases, and the client is right to latch off in all three: the host has
`meow_viewport_following` off, so `map_request_handler()` installs nothing; the host's
encoder never takes the software scaling path, so it could not crop anyway; or the host is
not sunmeow.

The one false negative the retry exists for: a probe that lands before the host's scaler has
published its geometry is dropped with no state stored, because the host does not yet know
the coordinate system an answer would be in. The retry at 2 s covers that race. Shortening
`ECHO_DEADLINE_MS` trades that margin for nothing — the cost of waiting is that following
engages a moment late; the cost of impatience is losing the feature against a host that
supports it.

**The retry has to bypass the library's deduplication, and this is easy to get wrong.**
`LiSendViewportEvent` drops a rectangle the host already has and returns `0` — and the retry
probe carries the *same* rectangle as the first by construction. Sent through the ordinary
path it would be swallowed, the caller would be told "accepted", nothing would go on the
wire, and the race above would be permanent rather than covered. Probes therefore go through
`LiSendViewportEventForced`, which exists for this and nothing else.
`ViewportReporterTest.theRetryProbeIsForcedSoTheLibraryCannotDeduplicateItAway` pins it, and
two sibling tests pin that ordinary rectangles and the terminal uncrop are **not** forced —
the rate limit is wanted on a gesture path, and the uncrop is already covered by
`flushFinalViewportEvent()` at teardown.

### The coordinate space is settled: the stream frame

`Limelight.h` used to say the rectangle was in *host desktop pixels*. **The client cannot
know the host's desktop size** — `serverinfo` does not report it and nothing else in the
handshake carries it — so that was never implementable. Both halves independently reached
the same convention, and the header has been corrected to match: the wire carries the
rectangle in the **negotiated stream resolution**, uncropped, which is the same reference
space `LiSendMousePositionEvent` already uses. The host (`meow::viewport::to_desktop()` in
`sunmeow/src/meow/viewport.h`) maps it into desktop pixels and answers in the same space.

**Aspect ratio is handled by the echo.** Sunshine pads to preserve aspect ratio, so a
5360x1440 desktop at 1920x1080 occupies only 1920x515 of the frame with ~282 rows of black
above and below. The echo carries `capture_width`/`capture_height` behind
`flag_desktop_extent`, and `ViewportReferenceFrame` reproduces the host's
`full_frame_plan()` arithmetic exactly — `float` scalar, truncating multiply, integer
halving — to recover the content box. Rectangles are then clamped into it, so the client
never asks for a region that is pure padding (which the host refuses, falling back to the
whole desktop). Until the first echo arrives there is no reference frame and rectangles go
out unclamped; the host clamps them itself, so that is less precise rather than unsafe.

### Threading: the send is not on the UI thread

`PanZoomHandler.constrainToBounds()` runs inside touch dispatch. The JNI call it causes
reaches `sendMessageEnet`, which takes the ENet mutex and sleeps `PltSleepMs(1)` up to ten
times on reliable-packet backpressure — and backpressure is the *expected* case on the
5-8 Mbps link this feature exists for. Doing that synchronously meant up to ~200 ms of
blocked UI thread per second during a pinch, i.e. jank in exactly the gesture that drives
the feature.

`StreamViewportBinder` therefore owns a private `HandlerThread`. The UI thread reads the
transform and posts an immutable `ViewportRect`; the reporter, the JNI call and the probe
deadline all run on that thread, which makes the reporter single-threaded and lock-free.
The host's echo is posted onto the same handler.

`onStreamStopped()` is the one place that blocks, bounded at
`STOP_DRAIN_TIMEOUT_MS` (250 ms): the terminal uncrop must reach the wire before
`LiStopConnection`, because sending after that races the ENet peer's destruction.

The echo listener is a static, because the native callback is a bare C function pointer
with no context parameter. Deregistration is `clearEchoListener(this)` rather than
`setEchoListener(null)`, so a stream restart through PiP — where the new `Game` registers
before the old one tears down — cannot silently deregister the live session's binder.

An earlier revision carried a `ViewportThrottle` and a 120 ms settle timer of its own. Both
are **gone**: `LiSendViewportEvent` already rate limits to 50 ms, drops redundant
rectangles, retries failed sends and flushes the trailing one from the loss-stats thread.
The second layer only delayed a below-threshold final rectangle from 50 ms to 120 ms.

### Known gap: cropping and local zoom compose wrongly

When a host honours the viewport, the encoded frame stops being the whole desktop and
becomes the crop, scaled up to the same encoder resolution. The client, meanwhile, is
still displaying that frame under the user's local zoom — so the user sees the region they
asked for magnified *again*, and absolute mouse coordinates (`getNormalizedCoordinates`
&rarr; `LiSendMousePositionEvent`) now address the crop rather than the desktop.

**A second, smaller gap in the same area: the host's revocation echo is received and
deliberately not acted on.** `meow::viewport::take_revocation_echo()` exists so the client
learns the host dropped its crop by itself — an encoder reinit or a display-mode change
mid-session. It arrives through the same callback, so `ViewportReporter` records it in
`appliedRect()` and otherwise does nothing: the host is uncropped and the user stays zoomed
into a full-desktop frame until they next move. Acting on it is not a one-liner, because an
echo carrying the full content area is indistinguishable from "your request was refused",
and re-sending on a refusal loops. It belongs with the composition work below.

Wiring the echo did not make this fall out: the echo tells the client *what* was applied,
but resolving the composition means the client resetting its local transform to 1:1 when the
host confirms a crop and composing later zooms on top of the applied rectangle rather than on
the raw frame — and that contradicts "reset the viewport to full-frame when zoom returns to
1:1", because under composition zoom is *always* back at 1:1. `ViewportReporter.appliedRect()`
and `referenceFrame()` are the inputs that work would need, and are exposed for it. It is a
different feature from "report the rectangle", and it is the reason the preference ships off.

### Deferred, stated plainly

**The new strings are English-only.** `title_checkbox_enable_viewport_follow` and
`summary_checkbox_enable_viewport_follow` exist only in `values/strings.xml`; the repo
carries 33 locales. Nothing is *stale* — no other locale carries the key, so every one falls
back cleanly — but a non-English user sees two English lines in Settings. That is acceptable
only while the preference ships off, and it wants doing before it ships on.

**`0x3003` sits inside Apollo's `0x3000` extension block.** If Apollo ever assigns that
number to something else, an Apollo host's packet would be dispatched into `IDX_VIEWPORT`.
The `version == 1` and non-zero-extent checks filter almost anything real, but a value that
passed both would latch `SUPPORTED` against a host that does not implement this. Inherited
from the packet-type choice, not introduced here; the host half guards its own side with
`packet_type_collision()`.

### The submodule changed, and it is under separate review

Findings 1, 5 and 6 could not be fixed in the Java layer alone, so
`app/src/main/jni/moonlight-core/moonlight-common-c` moved on branch `meow` of
`meowerse/moonlight-common-c`:

- `ConnListenerSetViewport` gained `desktopWidth`/`desktopHeight` (0 when the host did not
  report them), and the receive path parses them defensively — flag bit present, bytes
  actually there, extents non-zero — falling back to "unknown" rather than reading past the
  payload or discarding an otherwise valid rectangle.
- `stopControlStream()` now calls `flushFinalViewportEvent()`, which ignores the rate limit,
  before the threads are interrupted. Without it a terminal uncrop sent inside the 50 ms
  window was accepted (`0` returned to the caller), left in `viewportPending`, and then
  discarded when the loss-stats thread that would have flushed it was joined — leaving the
  host cropped with no session left to correct it.
- `LiSendViewportEventForced()` was added for capability probing. `LiSendViewportEvent()`
  is unchanged in behaviour; both now share one internal implementation with a `force` flag
  that skips the redundant-rectangle drop and the rate limit.
- The `Limelight.h` and `ControlStream.c` comments that described the wire as host desktop
  pixels, and the `-3` return as "any non-Apollo host", were corrected. Both were wrong in
  ways that produced wrong code.
