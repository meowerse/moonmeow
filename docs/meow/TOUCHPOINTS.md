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
spend the same bitrate on a fraction of the pixels. This section is the reporting half;
how the client *presents* a cropped frame is `MEOW-TOUCH(viewport-compose)` below.

**On by default** (see `MEOW-TOUCH(defaults)`). The preference is read once in
`Game.onCreate`, so toggling it takes effect on the **next** stream, not the running one.

### `app/src/main/jni/moonlight-core/Android.mk` — 1 site

| Line | Site | Edit |
| --- | --- | --- |
| 48 | after the `LOCAL_SRC_FILES` block | a three-line stanza appending `meowjni.c` |

### `app/src/main/jni/moonlight-core/callbacks.c` — 2 sites

| Line | Site | Edit |
| --- | --- | --- |
| 416 | above `BridgeConnListenerCallbacks` | `#include "meowjni.h"` |
| 437-439 | inside `BridgeConnListenerCallbacks` | `.setViewportV2 = MeowBridgeClSetViewportV2,` plus `.cursorPosition` (`MEOW-TOUCH(cursor-follow)`) and `.bitrateApplied` (`MEOW-TOUCH(auto-bitrate)`) |

`setViewportV2` replaced `setViewport` when the submodule moved to `meow` `1869ace`: the
library calls exactly one of the two for an echo, and only V2 carries the frame index the
frame-accurate crop swap needs. The callback bodies live in `meowjni.c`, not here, so this
upstream file gains an include and struct members and nothing else. The declaration is shared through
`meowjni.h` rather than repeated as an `extern` here: a parameter list that drifted between
the two translation units is undefined behaviour the compiler cannot see, and this feature
already changed that signature once. It is inert until
`MeowViewportBridge` is class-initialised — which only happens when the preference is on —
so an install that has not opted in reaches a `NULL` check and returns.

**Read the JNI hazard section of `CLAUDE.md` before touching `meowjni.c`.** Its symbols
bind by static mangled name: `Java_com_limelight_meow_viewport_MeowViewportBridge_sendViewport`
and `..._nativeInit`, and `Java_com_limelight_meow_stream_MeowStreamBridge_nativeInit`,
`..._sendCursorSubscribe`, `..._sendReceiverReport` and `..._getVideoNetworkStats`. Moving or
renaming either class without renaming them produces a build that succeeds and dies at first
call with `UnsatisfiedLinkError`. `MeowStreamBridgeContractTest` pins the second class the way
`MeowViewportBridgeContractTest` pins the first.

There is deliberately **no `FindClass`** in that file even though it now calls back into
Java. `nativeInit` is handed its `jclass` by the JNI calling convention, so the class
identity travels with the mangled name and there is no slash-form string that a package
move would leave stale — which is the half `nm -D` cannot see, and the half that already
shipped broken in this repo once. `MeowViewportBridgeContractTest` derives both mangled
names and the `(IIIIII)V` method descriptor from the class object, checks the struct member
is wired, checks `meowjni.c` is on an **uncommented** `LOCAL_SRC_FILES` line, and fails if a
`FindClass` or a slash-form class string ever appears. The descriptor is `(IIIIIII)V` since
echo v2 added the frame index.

`meowjni.c` reaches `GetThreadEnv()`, which `callbacks.c` exports, rather than caching a
`JNIEnv`: the echo arrives on moonlight-common-c's async callback thread, which is not a
Java thread.

### `app/proguard-rules.pro` — 1 site, deliberately untokenised

`MeowViewportBridge.onViewportEcho()` is called only from `meowjni.c`. R8 sees no Java
caller and **strips it from the release dex** — verified against `dexdump` on the built
APK, not assumed. `MeowStreamBridge.onCursorPosition()` and `onBitrateApplied()` are in the
same position and have a second rule of the same shape; `dexdump` on the release APK shows
all three with their exact descriptors. `GetStaticMethodID()` then returns `NULL`, the host's echo is silently
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
| 160 | field declaration | `private StreamViewportBinder viewportBinder;` |
| 510 | `onCreate`, after the inline-pinch wiring | a block that builds the binder **when the render mode is `MODE_2D`** and attaches it to `panZoomHandler`; the preference is passed to `setEnabled` rather than gating construction. The same block wires the logical transform (`setTransformSource`, `ReferencePointer.install`), the cursor follower and the bitrate session, and turns on the capability probe — see the sections below |
| 1765 | `onDestroy()`, before the capture provider is destroyed | one guarded `viewportBinder.release()` (which also releases the follower and the bitrate session) and `ReferencePointer.uninstall` |
| 3598 | inside `stopConnection()`'s teardown worker, above `conn.stop()` | one guarded `viewportBinder.onStreamStopped()` |
| 3832 | `connectionStarted()` | one guarded `viewportBinder.onStreamStarted(displayWidth, displayHeight)` |

**Construction is no longer inside the preference guard, and that is a deliberate change.**
It used to be, on the argument that an install which had not opted in should run bit-for-bit
the code it ran before. That argument was sound while the binder did one thing. It stopped
being sound once the same object also owned cursor-follow, which sends nothing and needs no
host: gating it on a *bitrate* preference made a local feature depend on a remote one. The
preference now reaches `ViewportReporter.setEnabled` instead, and with it off
`onStreamStarted` records the host as `UNSUPPORTED` before probing, so **not one packet goes
on the wire** — which is what the old guard was actually protecting. Pinned by
`CursorFollowBindingTest.panningHappensWithTheViewportPreferenceOff`.

The cost, stated plainly: the reporter's `HandlerThread` is now started for every 2D stream
rather than only for opted-in ones. It is one idle looper, released in `onDestroy()`.

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
| `StreamViewportBinder.java` | yes | reads the views, owns the reporter's thread; drives the compositor, the cursor follower and the bitrate session through the stream lifecycle |
| `ViewportPreference.java` | yes | reads the preference, and documents why it defaults on |

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

### Resolved: cropping and local zoom compose correctly

*Was "Known gap: cropping and local zoom compose wrongly".* When a host honoured the
viewport, the encoded frame became the crop scaled up to the encoder resolution, and the
client kept presenting it under the user's local zoom — the region magnified *twice* (F1) —
while absolute input addressed the container rather than the point under the finger.
Resolved in `MEOW-TOUCH(viewport-compose)` below, and without the contradiction this section
used to worry about ("reset the viewport to full-frame when zoom returns to 1:1" versus "zoom
is always back at 1:1 under composition"): the client never resets the user's zoom. The
**logical** transform stays the user's view V over the uncropped frame; only the transform
*presented* on the `SurfaceView` changes, per crop, on the frame the host names.

**The revocation echo is acted on now.** A revocation is simply a crop equal to the full
content area, so it swaps to the identity mapping the same way, on its frame; the user stays
zoomed with a soft picture until they next move, and the next move's rectangle asks the host
for the crop again. Nothing re-sends on a refusal, so the refusal loop the old text warned
about cannot happen.

`ViewportReporter.appliedRect()` is no longer caller-less: the compositor is fed from it, so a
rectangle the reporter rejected as outside the stream frame never reaches the view.

### Deferred, stated plainly

**The meow strings are English-only, and that is now the convention rather than a
deferral.** The viewport, cursor-follow and automatic-bitrate titles and summaries and the
overlay's applied-bitrate line exist only in `values/strings.xml`; the repo carries 33
locales. Nothing is *stale* — no other locale carries the keys, so every one falls back
cleanly — but a non-English user sees English lines in Settings. They ship on by default
now; translating them is a separate, whole-app task.

**`0x3003` sits inside Apollo's `0x3000` extension block.** If Apollo ever assigns that
number to something else, an Apollo host's packet would be dispatched into `IDX_VIEWPORT`.
The `version == 1` and non-zero-extent checks filter almost anything real, but a value that
passed both would latch `SUPPORTED` against a host that does not implement this. Inherited
from the packet-type choice, not introduced here; the host half guards its own side with
`packet_type_collision()`.

### The submodule changed, and it is under separate review

*2026-09-24:* the pin moved to `meow` `1869ace` — `meow` merged with `real/master` `62e0663`,
plus echo v2 (`ConnListenerSetViewportV2`, frame index), 0x3004 cursor and 0x3005 receiver
report. See `moonlight-common-c/docs/meow-protocol.md` and CLAUDE.md §4. The history below is
the 2026-08-25 change.

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

---

## `MEOW-TOUCH(cursor-follow)`

**Feature:** when the user is zoomed in, the visible crop chases the host cursor so the
cursor cannot walk off the phone screen.

**Why upstream had to be touched at all:** the cursor position is only known where the input
events are already dispatched, which is inside `Game`. Everything else — deciding whether and
how far to pan, and dead-reckoning the cursor in the captured-pointer modes — lives in
`meow/cursor/`.

### The bug this registry entry was created by

*Historical: the method it describes, `handleCursorViewPosition`, is gone — see "What
changed on 2026-09-24" below. The lesson about the gate still stands and is still pinned.*

It shipped inside `MEOW-TOUCH(viewport-follow)` and did not work. Two independent gates,
both of them the wrong gate:

1. `StreamViewportBinder.handleCursorViewPosition` early-returned on `!live`, and `live`
   means *"the host echoed our viewport message"*. Panning the local view is client side —
   it moves a `SurfaceView` and sends nothing. Against a host without the extension
   `ViewportReporter` latches `UNSUPPORTED` about four seconds into the session
   (`PROBE_ATTEMPTS` × `ECHO_DEADLINE_MS`), and cursor-follow died with it for a reason that
   has nothing to do with cursor-follow.
2. The binder was only constructed when the viewport preference was on. That preference's
   own summary string promises host-side bitrate cropping and says nothing about the cursor,
   so a user who turned it off — reasonably, since cropping is opinionated — silently lost an
   unrelated feature.

`CursorFollowBindingTest.panningHappensEvenWhenTheHostNeverEchoes` and
`panningHappensWithTheViewportPreferenceOff` fail against the old gates.

`streamStarted` replaces `live` as the guard. It is the honest precondition: cursor-follow
needs the negotiated stream size, and nothing else.

### What changed on 2026-09-24: follow the cursor, not the finger

The first implementation followed whatever position the input path happened to hold: the
finger in every touch mode (a `Game.java` block that read `getRawX()` of pointer 0 and
allocated two arrays per event), and a dead-reckoned estimate fed by only two of the eight
places that move the host cursor. Touch trackpad, gaming touch, physical touchpad, the
on-screen keyboard's mouse keys and gamepad mouse emulation never reached it, relative
deltas were over-read 2.8x on a 5360-wide desktop, the estimate was never reset, and a
cursor held in the border slammed the view along a whole margin per event (F3).

All of that is gone. `CursorFollowController` follows a single cursor model, `HostCursor`:

- the host's own **0x3004** reports, once the host has proven it is a meow host (the first
  viewport echo, which subscribes on the reporter thread) — exact in every mode, including
  a cursor moved on the host itself;
- otherwise **dead reckoning** fed by *every* movement the client sends, tapped where they
  all converge (`NvConnection`), with relative deltas scaled by the echoed desktop extent
  and `LiSendMouseMoveAsMousePositionEvent` mirrored exactly; reset on pointer-capture
  toggles and on every stream start.

A cursor change arms the follower; each vsync (`Choreographer`) moves the view one
`CursorFollowMotion` step — an exponential ease capped at three views a second — toward
keeping the cursor inside a 15% comfort margin, or a 4% edge band right after absolute or
touch input (the cursor is under the finger then, and a tap near an edge must not slide the
desktop away). Pans go through `PanZoomHandler.panBy`, so the new view reaches the host as a
viewport update and the crop follows. Only cursor changes arm it, so a user who pans away
from a still cursor stays where they panned.

### The redesign after the first device test (2026-09-24, same PR)

The behaviour contract — every input × mode × zoom state, the decisions and why — is
**`docs/meow/cursor-follow-ux.md`**. In short: zoom anchors on the cursor and a manual pan
carries it (pointer modes); against hosts without 0x3004 the client owns the pointer while
zoomed, replaying relative motion as absolute positions clamped to the view, so the cursor
cannot leave the screen; a fast regime brings an off-screen cursor back; "Remove animations"
is respected. The upstream sites grew by two lines (below); everything else is in
`meow/cursor/`.

### `app/src/main/java/com/limelight/Game.java` — 4 sites

| Line | Site | Edit |
| --- | --- | --- |
| 217 | field declaration | `private CursorFollowController cursorFollow;` |
| 510 | inside the `MEOW-TOUCH(viewport-follow)` block | construct the follower, give it a pointer sink (`conn.sendMousePosition`) and the touch-mode query, `viewportBinder.setCursorFollow(...)`; the binder drives its lifecycle |
| setInputGrabState | after the capture change | `cursorFollow.resetEstimate()` — capture toggled, the estimate starts over |
| applyMouseMode | after the touch contexts are rebuilt | `cursorFollow.ensureVisible()` |

Removed from `Game.java` in the same change: the `RelativeCursorTracker` field, the two
`followDeadReckonedCursor` call sites in the relative-mouse branch and the helper, the
~50-line finger-following block after the touch contexts, and the follow block in
`updateMousePosition`. The relative-mouse branch is back to upstream's shape.

### `app/src/main/java/com/limelight/nvstream/NvConnection.java` — 5 sites

One line each, first statement of `sendMouseMove`, `sendMousePosition`,
`sendMouseMoveAsMousePosition`, `sendTouchEvent` and `sendPenEvent`: a call into
`CursorInputTap`. In `sendMouseMove` it is `if (CursorInputTap.relative(...)) return;`: while
zoomed against a host that does not report its cursor, the follower has already sent the move
as an absolute position and the relative one must not also go out. This is the one funnel every input mode already goes through, and the only
place where "every relative-send path" is true by construction. Fully-qualified, no import.

### `app/src/main/java/com/limelight/binding/input/touch/RelativeTouchContext.java` — 2 sites

A `SubPixelAccumulator` field, and the gaming-mode `sendMouseMove` line routed through it.
Upstream truncated `delta * sensitivity` per sample and dropped the fraction: at 150% a slow
drag sent two thirds of its motion and at 70% none (`TouchDeltaAccumulationTest`, red before).
CRLF file; the edit keeps its line endings.

### `app/src/main/java/com/limelight/meow/viewport/StreamViewportBinder.java` — API for overlays

Not an upstream site, recorded here because another feature is meant to call it:
`setBottomObstruction(int windowPx)` declares that an on-screen overlay (the PC keyboard on
`feat/pc-keyboard`) covers the bottom of the stream, and `onVisibleAreaChanged()` re-checks.
The visible rectangle then ends above it, for cursor follow and for the host crop alike.

### `app/src/main/res/xml/preferences.xml` — 1 site

`checkbox_meow_cursor_follow`, default `true` — the explicit off switch.
`CursorFollowPreferenceTest` checks the XML default against `CursorFollowPreference.DEFAULT`.

### New code (additive, in `meow/cursor/`)

| File | Android? | What it is |
| --- | --- | --- |
| `CursorFollowController.java` | Choreographer | arming, vsync stepping, margins, cross-thread inbox |
| `CursorFollowMotion.java` | no | the per-axis target and the eased, speed-capped step |
| `HostCursor.java` | no | host-reported or dead-reckoned cursor in reference pixels |
| `CursorInputTap.java` | no | the static seam `NvConnection` calls |
| `CursorFollowPreference.java` | yes | reads the off switch |

`CursorFollowPlanner`, `CursorFollowPlan` and `RelativeCursorTracker` were deleted with their
tests; `ViewportGeometry.hostPointFromView`/`viewDeltaForHostDelta` went with them.

Tested by `app/src/test/java/com/limelight/meow/cursor/`, by
`viewport/CursorFollowBindingTest` (real `PanZoomHandler`, binder and follower), and by
`cursor/GameCursorFollowModesTest`, which builds a real `Game` under Robolectric and drives
real `MotionEvent`s through its handlers — touch trackpad natural and gaming, physical
touchpad, captured mouse relative and in absolute-mouse mode, a host that does not report,
absolute touch, local cursor hover and multi-touch — against a fake meow host behind the
natives (`shadows/ShadowMoonBridgeWithHost`). Nine of its eleven tests fail with following
switched off; the other two are the negative controls.

**Allocation and threads.** Nothing on the per-event or per-vsync path allocates. Host
positions (library callback thread) and relative moves from timer threads (fling momentum,
gamepad mouse) are folded into atomics and drained by one reused runnable on the UI thread.

---

## `MEOW-TOUCH(viewport-compose)`

**Feature:** present a host-cropped frame at exactly one magnification, and map absolute
input into the uncropped frame (spec C2/C3, audit fact F1).

**How.** `PanZoomHandler` stays the owner of the user's *logical* transform (zoom and pan
over the uncropped reference frame) and writes it to the view exactly as before. The binder
is notified right after, and its `ViewportCompositor` replaces it with the *presented*
transform for whatever the decoded frame shows (`ViewComposition`): the identity while the
host streams the whole desktop, and for a crop the logical transform divided by the host's
magnification (`HostCropPlan`, a mirror of sunmeow's `plan()` that recovers the desktop
source from the rounded echo). A pinch or pan shows at once as a soft zoom of the frame on
screen; the sharp crop replaces it on the frame the echo names (`frame_index`), which
`DecodedFrameGate` detects by pairing host frame numbers with codec timestamps. Hosts without
echo v2 swap on receipt.

### Sites

| File | Site | Edit |
| --- | --- | --- |
| `utils/PanZoomHandler.java` | `panBy` | pan from the handler's own `childX/childY`, not `streamView.getX()` — the view now carries the presented transform |
| `binding/video/MediaCodecDecoderRenderer.java` | `submitDecodeUnit`, after the timestamp is final | `DecodedFrameGate.onFrameQueued(frameNumber, timestampUs)` |
| `binding/video/MediaCodecDecoderRenderer.java` | top of `updateDecodeLatencyStats` | `DecodedFrameGate.onFramePresented(presentationTimeUs)` — the one call every render path makes per presented frame |
| `binding/input/touch/AbsoluteTouchContext.java` | `updatePosition` | the position mapped through `ReferencePointer.x/y` |
| `Game.java` | `updateMousePosition` | the same, for the absolute mouse and the local cursor |
| `Game.java` | `getStreamViewRelativeNormalizedXY` | the same, for native touch and pen |

`MediaCodecDecoderRenderer.java`, `AbsoluteTouchContext.java` and `NvConnection.java` are CRLF
files; the hooks keep their line endings. Everything else is in `meow/viewport/`:
`FrameMapping`, `HostCropPlan`, `ViewComposition`, `ViewportCompositor`, `DecodedFrameGate`,
`ReferencePointer`. `ZoomTarget` gained the three logical getters `PanZoomHandler` already had,
so nothing reads the zoom back off the view — `LocalCursorScaler` included.

**Guard band (2026-09-24).** The binder no longer asks for exactly the visible rectangle V:
`GuardBand` asks for V plus a margin at the encode surface's aspect ratio, clamped into the
desktop -- 10% a side at rest, growing with pan speed up to 35%, tightened back 300 ms after
the view stops. The request is kept while V stays inside it (with 2% slack) and it is not more
than 1.25x the size the margin calls for, so small pans and cursor-follow steps are shown sharp
from pixels already received (the compositor presents V inside the applied crop at one
magnification) instead of re-cropping the host every frame. The cost is 1/(1+2m) of the
encoder's pixels per axis for V: 83% at rest. `GuardBandTest`,
`StreamViewportBinderTest.smallPansInsideTheGuardBandDoNotChangeTheCrop`,
`CropCompositionTest.aCropWithAGuardBandShowsTheViewAtOneMagnificationAndSmallPansStaySharp`.

**What remains inexact, stated.** The echo is rounded to whole reference pixels and does not
carry the desktop-space source, so the recovered mapping is within one reference pixel for 99%
of crops and within 1.75 for all (`HostCropPlanTest`). The swap is aligned to the frame the
renderer hands to the display path, and a `SurfaceView` property change is not latched with
the codec buffer, so the swap is within one or two display frames, not frame-exact: in balanced
frame pacing the hook fires when a buffer enters the renderer's two-deep output queue, up to
two vsyncs before `doFrame` releases it. The spec's "exact frame" wording is stronger than the
Android surface pipeline can guarantee; A1 on hardware is where a one-frame pop at a crop swap
would show. A stream stop keeps the presented crop so the frozen last frame is not magnified
twice; the next stream start resets.

Tested by `CropCompositionTest` (F1 end to end: 4x zoom, honoured crop, presented at 1:1 —
red without the compositor), `ViewportCompositorTest` (the single-magnification invariant in
every state: idle, mid-pinch, before and after the swap, v1 host, trailing echo, pan after
swap, revocation, reconnect, PiP resize, out-of-order echoes), `HostCropPlanTest`,
`DecodedFrameGateTest`, `ReferencePointerTest` and `ViewportCompositionWiringTest` (the hooks).

---

## `MEOW-TOUCH(auto-bitrate)`

**Feature:** automatic bitrate (spec C5). The user's bitrate setting becomes the ceiling;
each stream starts at the last stable bitrate on that host; a 1 Hz 0x3005 receiver report
(goodput, pre-FEC loss, RTT, decode queue and time) goes to a host that has proven it is a
meow host; the performance overlay shows what the host applied.

| File | Site | Edit |
| --- | --- | --- |
| `Game.java` | field | `private BitrateSession bitrateSession;` |
| `Game.java` | inside the `MEOW-TOUCH(viewport-follow)` block | build the session, `viewportBinder.setBitrateSession(...)`, `setCapabilityProbe(true)` |
| `Game.java` | `StreamConfiguration.Builder` | `.setBitrate(BitrateSession.negotiate(bitrateSession, <the setting>))` |
| `binding/video/MediaCodecDecoderRenderer.java` | after the stats window flips | `DecodeTimeWindow.publish(...)` of the window the renderer already measured |
| `binding/video/MediaCodecDecoderRenderer.java` | before the overlay text is finished | `BitrateOverlay.append(sb, ...)` |
| `jni/moonlight-core/callbacks.c` | struct member | `.bitrateApplied = MeowBridgeClBitrateApplied` |
| `res/xml/preferences.xml` | after the metered bitrate | `checkbox_meow_auto_bitrate`, default `true` |

New code in `meow/bitrate/`: `StartingBitrate`, `ReceiverReport`, `ReceiverReporter` (pure),
`BitrateMemory`, `BitrateSession`, `BitrateOverlay`, `DecodeTimeWindow`,
`AutoBitratePreference`; and `meow/stream/MeowStreamBridge` for the JNI.

**Stopping is ordered.** `BitrateSession.onStreamStopped()` runs from the binder on `Game`'s
teardown worker, before `conn.stop()`, and blocks (bounded, 250 ms) until no report can still
be in flight: sending after `LiStopConnection` is a use-after-free. A `stopped` latch keeps a
host-proven signal that was still queued on the viewport thread from restarting reports after
that drain. It persists the stable bitrate (an APPLIED value held for ten seconds), keyed per
host *and* per metered/unmetered network, and forgets it when a proven host never answered
this session, so a host that stopped adapting cannot keep capping later sessions.

**No report without an RTT.** The wire has no "unknown" RTT and a 0 would become the host's
windowed-minimum baseline (N4), so reports wait for ENet's first estimate and carry the last
known one through a dropout. Only a report the library accepted counts toward the five-report
give-up; a transient ENet failure does not.

**`CONN_STATUS_POOR`**, `Game.connectionStatusUpdate`, 2 lines: when automatic bitrate is
on and the host has adapted this session (an APPLIED arrived), the "slow connection -- lower
the bitrate" advice is replaced by a short "Connection slow · adapting bitrate (X Mbps)" in the
same non-blocking overlay line; the host is already lowering it. Stock hosts, and automatic
bitrate off, keep the original advice (`BitrateSessionTest.aPoorConnectionOnAnAdaptingHost…`,
`BitrateWiringTest.thePoorConnectionWarningIsUntouched`),
and the Tailscale packet-size path is pinned by `meow/net/TailnetPacketSizeTest` (N3).

---

## `MEOW-TOUCH(auto-av1)`

**Feature:** "automatic" codec selection offers AV1 on a hardware, whitelisted AV1 decoder
with a low-latency path (`FEATURE_LowLatency`, or a dedicated `*.lowlatency` codec as
MediaTek ships). The RTSP negotiation already prefers AV1 whenever both sides offer it, and
AV1 needs fewer bits than HEVC for the same desktop detail. Upstream offers AV1 only when
forced.

| File | Site | Edit |
| --- | --- | --- |
| `binding/video/MediaCodecDecoderRenderer.java` | `findAv1Decoder`, the "only when forced" guard | `&& !AutoCodecPolicy.av1InAuto(prefs)` appended; CRLF kept |

New code: `meow/video/AutoCodecPolicy.java` (the decision is the pure `offerAv1(...)`),
tested by `AutoCodecPolicyTest`. On a device without such a decoder nothing changes.

---

## `minsdk-26` legacy removal — deliberately untokenised

**This section registers a change that cannot carry inline markers, because its edits are
deletions.** You cannot put a `MEOW-TOUCH` comment on a block that is gone. §3's
`app/proguard-rules.pro` precedent applies: register the site here, leave the `git grep`
audit exact.

Raising `minSdk` 23 → 26 (Android 8.0) made a large class of code unreachable. **24 upstream
Java files were edited in place and 13 files deleted.** Across the whole branch that is 46
files, ~1,082 insertions and ~2,666 deletions — figures that will drift, so re-derive rather
than trust them:

```bash
git diff --stat $(git merge-base HEAD origin/moonlight-noir)..HEAD
```

That is the most expensive thing §2 contemplates, so it is written down.

### What changed, by class of edit

| Class | Files | What it is |
| --- | --- | --- |
| Dead-guard collapse | 24 upstream Java files | `Build.VERSION.SDK_INT` guards whose predicate is now constant. ~85 sites. |
| Root flavour removal | `app/src/root/**`, `evdev_reader/**`, `NullCaptureProvider`, `ShieldCaptureProvider`, `EvdevCaptureProviderShim` | The flavour carried `maxSdk 25`, so Play already refused to serve it to any device that can install this app. |
| Resource qualifier merge | `values-v14`, `values-v21`, `values-v24` deleted | `v14`/`v21` were byte-identical to `values/`. **`v24` was NOT dropped — its `windowBackground = @android:color/black` was merged into `values/styles.xml`**, which is what every API-24+ device was already resolving to. `values-v29/` is deliberately retained: API 26–28 devices exist and must keep the MaterialComponents base. |
| Re-indentation | ~918 lines | Body dedent after unwrapping `if` blocks. Unavoidable given the collapse, but it is why the raw diff is larger than the semantic one. |

### The standing policy for future syncs — this is the part that matters

`ClassicOldSong/moonlight-android` is merged monthly (§4) and is active. When upstream next
edits *inside* a block this change removed, git has no surviving context to three-way-merge
against: it produces a conflict with the whole hunk deleted on our side, and the resolver has
to re-derive intent. So the intent is recorded here instead:

> **An upstream change that re-adds or edits a `SDK_INT < 26` guard gets re-collapsed, not
> re-instated.** Take upstream's change to the *surviving* branch and discard the branch that
> cannot execute at `minSdk 26`. Do not restore the guard "to be safe" — that silently
> reintroduces dead code and makes the next sync worse.
>
> The same applies to the root flavour: upstream edits to `EvdevCaptureProvider`,
> `EvdevReader`, `EvdevTranslator`, `EvdevEvent`, `ShieldCaptureProvider` or
> `NullCaptureProvider` are **dropped**, not resurrected.

### One thing that looks like leftover cruft and is load-bearing

`com.limelight.binding.input.evdev.EvdevListener` **survives the removal on purpose.** It is
not evdev residue: `Game` implements it, and `KeyBoardController` / `KeyBoardLayoutController`
call through it for the on-screen keyboard. Deleting it "because evdev is gone" breaks that
path. Its `-keep` rule in `app/proguard-rules.pro` is retained for the same reason.

### Guards that survived because they are still live

Not everything sub-`O` was collapsible. These remain and must not be swept up in a later pass:
`O_MR1` (API 27) at `KeyBoardLayoutController.java` and `KeyBoardController.java`, and
`SDK_INT <= O` at `ControllerHandler.java`. All three genuinely branch at minSdk 26.

Also worth stating because it would be silent: `Game.java`'s `SDK_INT >= N` block that sets
`android.content.extra.IS_SENSITIVE` when `hideClipboardContent` is on **survived intact**.
Had it been dropped along with its guard, host clipboard content would start appearing in
system clipboard previews — a §8 security regression that no test would catch.

### The flavour dimension is intentionally left in place

`flavorDimensions.add("root")` now has a single member, `nonRoot_game`. Collapsing it would
rename every Gradle task and every APK output path — `.github/workflows/ci.yml` hard-codes
`lintVitalNonRoot_gameRelease`, `testNonRoot_gameReleaseUnitTest` and
`assembleNonRoot_gameDebug`; `CLAUDE.md` documents
`app/build/outputs/apk/nonRoot_game/release/`; and the Obtainium update URL's
`apkFilterRegEx: "nonRoot"` matches the flavour-derived filename, so **every existing user's
update check would silently stop matching**. A cosmetically odd dimension name is much cheaper
than that.

---

## `MEOW-TOUCH(defaults)` and `MEOW-TOUCH(native-res)`

**Feature:** three preference defaults changed — viewport-following on, auto-orientation on,
and the stream resolution derived from the device's own panel instead of a fixed 16:9
`1280x720`.

**Sites are named, not numbered.** An earlier revision of this entry gave line numbers; every
one of them was wrong by the time it was committed, and they would have rotted on the first
upstream merge regardless. A conflict resolver searches for the enclosing element.

**Why upstream had to be touched at all:** a preference default can only be changed where the
preference is read. The decision content is additive and pure —
`com.limelight.meow.res.NativeResolutionDefault`, no Android types, unit tested — so only the
supply of the value lives in upstream files.

**Why the resolution default changed:** every entry in `res/values/arrays.xml` is 16:9. A 16:9
stream surface on a phone that is not 16:9 cannot fill the screen; it is letterboxed against
the display, and those bars sit *outside* the video surface, so client zoom never reaches
them. On a 20:9 phone that is a black bar down each side for the whole session.

**Why a migration exists at all — the part that is easy to get wrong.** Changing an
`android:defaultValue` reaches nobody who already has the app. `PcView` calls
`PreferenceManager.setDefaultValues(..., false)`, which short-circuits on the
`_has_set_default_values` flag, so on any install that has launched once no XML default is
ever materialised again — and the old value is already persisted. Without the migration, the
person who reported the black bars would have had to clear app data to receive the fix.

### `app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java`

| Element | Edit |
| --- | --- |
| imports | `DisplayMetrics`, `WindowManager`, `MediaCodecInfo`, `MediaCodecList`, `NativeResolutionDefault`, `ViewportPreference` |
| `getDefaultResolution(Context)` | new — supplies panel geometry and the TV flag to the pure decision. Never throws; a default that crashes is worse than a stale one |
| `isResolutionDecodable(String)` | new — asks real `VideoCapabilities.isSizeSupported()`, because the panel-derived default bypasses `StreamSettings`' decoder pruning and its native-resolution warning. **Fails open**: undeterminable means yes |
| `applyDefaultsMigration(Context)` | new — one-shot, version-gated on `DEFAULTS_MIGRATION_VERSION` |
| `readPreferences(Context, SharedPreferences)` | one call to `applyDefaultsMigration`, before any read below |
| `getDefaultBitrate(Context)` and the `resStr` read | fallbacks aligned to `getDefaultResolution(context)` so they cannot disagree with the migrated value |
| `readPreferences`'s `autoOrientation` line | `false` → `true` |

**The migration writes only to the canonical store.** `getOverlayingSharedPreferences()`
returns an `OverlaySharedPreferences` while a profile is active, and `EditProfileActivity`
passes an in-memory map built from a profile's *sparse* option set. Writing into either would
silently bake these keys into that profile as overrides the user never set — and
`saveProfile()` would persist them, undetected, because `diff()` compares against a store
holding the same value. `DefaultsMigrationTest.aCallerSuppliedStoreIsNeverWrittenTo` guards
this.

**What the migration will and will not overwrite.** The resolution is only re-seeded when the
stored string is still exactly what the old default wrote, and the bitrate only when it too
still matches what that resolution derived — so a chosen resolution or a hand-tuned bitrate
survives. The two booleans cannot be told apart this way: a stored `false` is identical
whether inherited or chosen, so they are set once. That is the accepted cost of changing a
boolean default, and the reason the migration is version-gated rather than run every launch.

### Schema 2 (2026-09-24): every meow feature on — `meow/res/MeowDefaults.java`

`DEFAULTS_MIGRATION_VERSION` is now `MeowDefaults.SCHEMA_VERSION` = 2. The schema-1 steps above
are wrapped in `if (migratedTo < 1)` so bumping the version cannot re-run them and undo a
resolution or orientation lock chosen since; the new steps live in `MeowDefaults.apply()`, one
call from the migration. Below schema 2 it switches on viewport following (a second time, on
purpose: until this release a crop was magnified twice, which is a good reason to have turned
it off), cursor follow and automatic bitrate. Pinned by `DefaultsMigrationTest` (virgin
install, an install at schema 1, off switches that stay off, profile stores never written) and
`MeowDefaultsTest`.

### `app/src/main/java/com/limelight/meow/viewport/ViewportPreference.java`

`DEFAULT` `false` → `true`. The class comment argued the case for off; it is rewritten to
state why two of its three reasons no longer hold and why the third was accepted, rather than
being left contradicting the constant beneath it.

### `app/src/main/res/xml/preferences.xml`

| Element | Edit |
| --- | --- |
| `checkbox_auto_orientation` | `android:defaultValue` `false` → `true` |
| `checkbox_enable_viewport_follow` | `android:defaultValue` `false` → `true`; must match `ViewportPreference.DEFAULT` |
| `list_resolution` | **unchanged, deliberately.** Keeping `1280x720` means `ListPreference.getValue()` is never null for the profile editor, whose sparse store has no such key; the migration replaces the value afterwards, so ordering between the two does not matter. An earlier revision removed it and introduced exactly that null |

### Not touched, on purpose

`StreamSettings.java` — an earlier revision aligned a `getString` fallback there. Reverted:
the migration guarantees the key exists before that code runs, so the edit bought nothing, and
the file is CRLF. Rewriting it LF would have turned a 4-line change into 1061 and made the
next upstream merge conflict on every line of it.

`PreferenceConfiguration.DEFAULT_RESOLUTION` still reads `"1280x720"`. It is no longer a
fallback but the migration compares against it to recognise the old inherited value, so it is
load-bearing again rather than dead.

### Known consequences, stated rather than discovered later

- **Default bitrate rises** on fresh installs, because it derives from resolution — roughly
  3.5x for a 2712x1220 panel versus 720p. Intended; the alternative is a large stream at a
  bitrate sized for a small one.
- **Cutout behaviour changes.** `Game.shouldIgnoreInsetsForResolution` returns true for native
  resolutions, so fresh installs get `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` — content under
  the notch. Consistent with removing black bars.
- **`LocalCursorScaler` is now constructed by default**, because it lives inside the same
  `if` as the viewport binder in `Game`. Its own preference,
  `checkbox_enlarge_cursor_at_low_zoom`, still defaults off, so nothing changes visually until
  the user opts in — but the object is live where it previously was not.

---

## Policy: back-ports from the ORIGINAL upstream

*Added 2026-09-03 with the first batch of cherry-picks from
`moonlight-stream/moonlight-android` (`CLAUDE.md` §1).*

§3 exists to list "the places a merge can hurt". A hunk restored verbatim from the
original upstream into an upstream file does the opposite: it moves our copy of that
file **toward** the code a future merge will bring in, so it removes conflict surface
rather than adding it. This is the same reasoning §3 already applies to repairing an
inherited test that upstream drift broke.

So the rule for back-ports is:

- **Faithful port** — the resulting changed lines are identical to the upstream commit's.
  **No `MEOW-TOUCH` marker, no row here.** The commit message names the upstream SHA;
  that is the audit trail. Adding markers for these would inflate the registry with
  entries that describe *less* divergence than before the commit.
- **Adapted port** — we deliberately deviated from the upstream diff, because Artemis
  had already changed that code or already fixed part of the bug. That divergence **is**
  a place a future merge will hurt. **Marker + row below.**
- **Skipped** — Artemis already carries the fix. Recorded in the PR, not here; nothing
  was touched.

### `MEOW-TOUCH(upstream-backport 280454fd)` — `binding/input/driver/XboxOneController.java`

Upstream's Xbox Series S/X commit adds four `InitPacket` entries: `0x0b05`, `0x0b13`,
`0x0b12` and `0x02fe`. Artemis already carries the last two further down `INIT_PKTS`.
`sendInitializationPackets()` sends **every** matching entry rather than stopping at the
first, so re-adding them would transmit the init packet twice to those controllers. Only
`0x0b05` and `0x0b13` were added. `SERIES_S_INIT` is kept as upstream defines it — today
byte-identical to `ONE_S_INIT` — so the constant table still matches upstream.

**On a future merge:** keep exactly one entry per product ID.

### `MEOW-TOUCH(upstream-backport 0711e236)` — `utils/UiHelper.java`

Artemis had already fixed half of the Meta Quest bug, with a null check on
`getSystemService(GameManager.class)` plus a `LimeLog.warning`. Upstream's fix is a
`catch (Throwable)` around the whole block, for OEM builds whose `GameManager` throws
instead of being absent, and it does **not** have the warning. The block is therefore
upstream's try/catch wrapped around Artemis' null check, matching neither side verbatim.

**On a future merge:** upstream's version is strictly weaker here — it loses both log
lines. Prefer ours.

### `MEOW-TOUCH(upstream-backport 3c6a0d12)` — `binding/input/ControllerHandler.java`

Upstream's rumble fix dereferences the `VibratorManager` unconditionally on S+:

```java
this.deviceVibratorManager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
this.deviceVibrator = this.deviceVibratorManager.getDefaultVibrator();
```

`ControllerHandler` is constructed on **every stream start**, so an OEM build that does
not register `VIBRATOR_MANAGER_SERVICE` would take streaming down entirely with an NPE
in a constructor. The deprecated `getSystemService(VIBRATOR_SERVICE)` call it replaces
could not fail that way, so the port would have introduced a crash path that did not
previously exist. This fork already ships a fix for exactly that class of device — see
the `0711e236` entry above, where a Meta Quest returns a null `GameManager` — so the
risk is not hypothetical here.

Ours falls back to the legacy vibrator when the manager is absent, which is strictly
safer and preserves upstream's behaviour everywhere the manager exists.

Two cosmetic deviations live on the same lines and are covered by this row rather than
one of their own: upstream's stray tab-indented blank lines are dropped, and the
redundant `(Vibrator)` cast on `getDefaultVibrator()` is omitted since it already
returns `Vibrator`.

**On a future merge:** keep the null check.

### `MEOW-TOUCH(upstream-backport 5c0c2390)` — `res/values/strings.xml`

*Added 2026-09-24.* Upstream's `local_network_rationale` begins "Moonlight requires ...".
Our default `strings.xml` contains no other "Moonlight" (`CLAUDE.md` §1, Branding), so the
string says "Moonmeow". The name and the rest of the text are upstream's.

**On a future merge:** keep "Moonmeow".

### Formerly blocked: the Android 16.1 back-ports (unblocked 2026-09-24)

`ddb674a9` (native keyboard capture) and `6d4c64a5` (disable surface producer
throttling) need `compileSdk 37`. Both were ported, compiled and then **reverted**,
because raising `compileSdk` from 36 to 37 breaks the unit-test gate.

*Update 2026-09-24: unblocked.* OkHttp 5.5 (`98c12beb`) forced the question — its
`okhttp-android` AAR refuses to build against anything below `compileSdk 37` — and
Robolectric 4.17 supports SDK 37, so `compileSdk` is now 37 with Robolectric 4.17 and the
suite green. `6d4c64a5` went in verbatim, so it has no row. `ddb674a9`'s logging hunk went
in on 2026-09-03; its keyboard-capture half (`WindowManager.LayoutParams
.setKeyboardCaptureEnabled` behind `SDK_INT_FULL >= BAKLAVA_1`, plus the
`CAPTURE_KEYBOARD` permission) now compiles but is still out: it changes what the keyboard
grab captures and adds a manifest permission, and the test phone is API 36, so nobody can
verify it on a device here. It wants its own PR. The original failure, for the record:

```
app/src/test/java/com/limelight/meow/viewport/CursorFollowBindingTest.java:107:
error: cannot access FingerprintManager
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  class file for android.hardware.fingerprint.FingerprintManager not found
```

API 37 **removed** `android.hardware.fingerprint.FingerprintManager`
(`unzip -l $ANDROID_HOME/platforms/android-37.0/android.jar | grep -c FingerprintManager`
→ 0; the same grep against android-36 → 4). Robolectric 4.16's generated `Shadows`
class still declares a `shadowOf(FingerprintManager)` overload, so javac cannot resolve
*any* `shadowOf` call once the test classpath is the API 37 stub. `CLAUDE.md` §5 forbids
weakening an inherited test to get a green gate, so the bump was dropped instead.

**The prerequisite is a Robolectric upgrade, not more porting effort.** Verified along
the way: AGP 9.3.2 accepts `compileSdk 37`, the `android-37.0` platform is installed, and
both back-ports compile against it — only the test compile fails.

---

## `MEOW-TOUCH(green-brand)`

*Added 2026-09-24.* The launcher, shortcut, notification, TV and store art recoloured to
meowerse green (`#00ff82` on the dark `#0d0d0d` plate, per meowerse
`packages/ui/src/styles/tokens.css`), with the Artemis aperture replaced by a **moon**
(owner decision: sunmeow keeps a sun, moonmeow a moon).

**One master, everything generated.** `store-assets/meow/moonmeow.svg` is the only
hand-authored artwork; `bash store-assets/meow/build.sh` regenerates every file below
from it. Never hand-edit a PNG or a generated vector drawable — change the master and
re-run. After an upstream sync that touches any of these files, re-run the script and
commit the result rather than resolving the binary conflict by hand.

Why layers 1–3 do not apply: the manifest (`android:icon`, `android:banner`),
`ShortcutHelper` (`R.mipmap.ic_pc_scut`), `TvChannelHelper` (`R.drawable.ic_channel`)
and `ExternalDisplayControlActivity` (`R.drawable.app_icon`) reference these resource
**names**. Replacing the bytes under the same name touches zero lines of code; renaming
would edit four upstream sources and the manifest.

### Replaced in place (upstream files)

| File(s) | Marker | What changed |
| --- | --- | --- |
| `res/values/ic_launcher_background.xml`, `res/values/ic_pc_scut_background.xml` | XML comment | one colour value each (`#000000` / `#FFFFFF` → `#0D0D0D`) plus the marker line; upstream's CRLF line endings preserved, so the diff is exactly those two lines. The generator re-inserts the marker if an upstream sync drops it. An additive `values-v26` override was rejected: with minSdk 26 aapt2 strips the `-v26` qualifier and the two definitions would collide |
| `res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`, `ic_launcher_foreground.png`, `ic_pc_scut.png`, `ic_pc_scut_foreground.png` (20 files) | _(binary — this row)_ | regenerated. **Not reachable on any supported device** (minSdk 26: the anydpi XML wins), so this is not forced by layers 1–3 — it is a deliberate choice, made so the tree and the APK carry no stale Artemis art. Cost: 20 binary conflict points on a sync that touches them; resolution is "re-run the script" |
| `res/drawable/app_icon.png` | _(binary — this row)_ | white moon on transparent. Also unreachable on minSdk 26 (`drawable-anydpi-v26/app_icon.xml` wins); replaced for the same reason as the row above. Upstream's copy was the full-colour launcher PNG, which rendered as a white blob in the status bar |
| `res/drawable-xhdpi/atv_banner.png`, `res/drawable-xhdpi/ouya_icon.png` | _(binary — this row)_ | "Moonmeow / Game Streaming" banner |
| `app/src/main/ic_launcher-web.png`, `fastlane/…/images/{icon,featureGraphic,tvBanner}.png` | _(binary — this row)_ | store icon and banners |

### New files (additive)

| File | Why |
| --- | --- |
| `res/mipmap-anydpi-v26/ic_launcher_foreground.xml`, `ic_pc_scut_foreground.xml` | vector adaptive foregrounds. Same resource name as the density PNGs, so the **unchanged** `ic_launcher.xml`/`ic_pc_scut.xml` pick them up on every device (minSdk 26) and their existing `<monochrome>` reference — which points at the foreground — now gives Android 13+ themed icons a clean alpha glyph |
| `res/drawable-anydpi-v26/app_icon.xml` | vector notification small icon, white/alpha only, 24 dp |
| `res/drawable-anydpi-v26/ic_lime_layer.xml` | green moon for the Android TV channel logo; overrides upstream's untouched `drawable/ic_lime_layer.xml` (the Artemis wedges), which the unchanged `ic_channel.xml` layer-list references by name |
| `store-assets/meow/moonmeow.svg`, `build.sh`, `build_icons.py` | the master and its generator |

Deliberately **not** changed: `ic_launcher.xml`/`ic_pc_scut.xml` themselves, the app theme
(`colorAccent` is white — a neutral "noir" choice, not Moonlight brand), the fastlane
**text** metadata, and `store-assets/lime_layer.svg` (the old wedge source; unused by the
build). There is no `ic_launcher_round`: the manifest declares no `roundIcon`, and adaptive
icons are masked to the launcher's shape on every supported API level.
