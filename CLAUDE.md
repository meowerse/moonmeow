# moonmeow — agent rules

Android streaming client. Fork of **ClassicOldSong/moonlight-android** (Artemis),
branch `moonlight-noir`.

Read this fully before your first edit. Companion host: `sunmeow`.

---

## 1. What this repo is, and why

`moonmeow` forks **Artemis**, not upstream `moonlight-stream/moonlight-android`,
because upstream is effectively dormant while Artemis is not:

| | Latest commit | Notes |
| --- | --- | --- |
| moonlight-stream/moonlight-android | 2024-07-27 | last release v12.1, Feb 2024 |
| ClassicOldSong/moonlight-android (`moonlight-noir`) | 2025-10-18 | 568 commits ahead of upstream |

Artemis is *ahead* of the original on every axis we care about, and is explicitly
tuned for desktop/office use rather than gaming — custom virtual buttons, multiple
mouse modes (touchpad / multi-touch / local cursor), custom resolutions.

Note `master` and `next` in that repo are **stale** (2024) and 573 commits behind
`moonlight-noir`. Always track `moonlight-noir`.

### Branding: applicationId only, never the namespace

Shipped:
- `applicationId` → `meow.alxnko.moonmeow` (`.root`, `.debug` variants)
- App label → "Moonmeow", UI strings rebranded across all 33 locales
- Own release signing key

Deliberately **not** changed: the `com.limelight` Java namespace.

A full namespace rename was implemented, verified, and then reverted. It worked —
including the mangled JNI symbols — but it touched **170 files / 696 occurrences**,
which would conflict on every future upstream merge, and **nothing about it is
visible to a user**. The applicationId is what separates our install from Moonlight
and Artemis; the namespace is invisible.

**If you are ever asked to rename the namespace, re-read this section first.**

That work is recoverable from reflog if the project ever hard-forks and abandons
upstream tracking — it is not lost, just deliberately unmerged.

### JNI hazard — read before touching native code

Native methods bind by **static mangled name**, not `RegisterNatives`:

```c
Java_com_limelight_nvstream_jni_MoonBridge_startConnection(...)
```

and the C side looks the class up **by string**:

```c
FindClass(env, "com/limelight/nvstream/jni/MoonBridge")
```

Any package change must update **both**, or the app compiles cleanly and then dies at
launch with `ClassNotFoundException`. This exact bug shipped once here: exported
symbols verified fine while the `FindClass` string still pointed at the old package.

Checking `nm -D` on the `.so` is **not** sufficient — grep for the slash-form string too:

```bash
grep -rn 'com/limelight' app/src/main/jni --exclude-dir=moonlight-common-c
```

---

## 2. THE PRIME DIRECTIVE — additive-only

**Never modify an upstream file in place when you can add a new one instead.**

1. **New class in `meow/`** package — our code lives there.
2. **Subclass / wrap** an upstream class rather than editing it.
3. **One-line hook** in an upstream file calling into `meow/`. Nothing more.
4. **Multi-line upstream edit** — needs a `MEOW-TOUCH` marker (§3) and a PR rationale.

### Never touch `moonlight-common-c`

The submodule at `app/src/main/jni/moonlight-core/moonlight-common-c` is the protocol
core: RTSP handshake, FEC reassembly, jitter buffering, depacketization, crypto, NAT
traversal. ~23k lines representing a decade of latency tuning and per-device codec
workarounds.

**Treat it as a sealed dependency.** Everything worth improving in this app — zoom,
keyboard, touch modes, layout — lives in the ~43k-line Java layer above it. Nothing a
user complains about is in the protocol.

If you believe you need a protocol change, stop and escalate. You almost certainly
need a Java-layer change instead.

---

## 3. Touch-point registry

```java
// MEOW-TOUCH(zoom): forward touch events to our zoom controller
if (MeowZoom.onTouch(event)) return true;   // <- the entire edit
```

Recorded in `docs/meow/TOUCHPOINTS.md`. Before each upstream sync:

```bash
git grep -n 'MEOW-TOUCH' -- app/src
```

Keep it short. A growing registry means features are being welded into upstream code
instead of layered beside it.

---

## 4. Syncing with upstream

Monthly minimum.

```bash
git fetch upstream
git log --oneline HEAD..upstream/moonlight-noir | wc -l
git merge-tree --write-tree --name-only HEAD upstream/moonlight-noir   # conflicts, non-destructively
git branch backup/sync-$(date +%Y%m%d)    # BEFORE resolving
git merge upstream/moonlight-noir
```

Never `--ours`/`--theirs`. Read both sides. If a side's intent is unclear, **stop and
ask a human**. After resolving, prove nothing vanished:

```bash
git log --oneline backup/sync-<date>..HEAD
git grep -nE '^(<<<<<<<|=======|>>>>>>>)'
```

---

## 5. Testing

### What we actually inherited

Not "nearly empty". `app/src/test` carries a working JVM/Robolectric suite —
**15 files under `app/src/test/java/com/limelight/`: 10 test classes holding 51
`@Test` methods, plus 5 helpers.**

| Area | Classes | `@Test` |
| --- | --- | --- |
| Startup / lifecycle | `StartupCrashTest`, `StartupTest`, `SimpleStartupTest` | 32 |
| Layout inflation | `LayoutInflationTest` | 1 |
| `profiles/` | `ProfilesManagerTest`, `ProfilesActivityUiTest`, `ProfilesNavigationTest`, `ProfilesOverlayTest`, `OverlayPreferencesTest` | 16 |
| Text input | `ui/StreamViewCommitTextTest` | 2 |

Helpers, not tests: `shadows/ShadowMoonBridge`, `shadows/ShadowGameManager`,
`shadows/ShadowBackdropFrameRenderer`, `TestLogSuppressor`, `ProfileTestHelper`.

Stack: JUnit 4.13.2, Robolectric 4.16, Mockito 5.19.0, `androidx.test:core` 1.7.0.
`testOptions.unitTests.includeAndroidResources = true` lets real layout XML inflate;
`robolectric.properties` registers `ShadowBackdropFrameRenderer` globally.

```bash
./gradlew testNonRoot_gameDebugUnitTest      # the gate task
./gradlew testNonRoot_gameReleaseUnitTest    # same suite, release variant
./gradlew :app:test                          # all four flavour/build-type combinations
```

Report: `app/build/reports/tests/testNonRoot_gameDebugUnitTest/index.html`.
Conventions for adding to the suite live in `android_test_setup.md`.

**The real hole is instrumented tests: `app/src/androidTest` does not exist — zero.**
Anything decoder-, surface-, or device-specific is verified only by hand on a real
phone today. That is what rule 5 below exists to compensate for.

So: treat the JVM suite as a safety net that already works and must stay green —
never delete a failing inherited test to get a green gate. Our own code is held to a
higher bar than what we inherited.

### Rules

1. **Every class in `meow/` ships with unit tests.** No exceptions.
2. **Every bug fix starts with a failing test.**
3. **Extract logic from Activities/Views so it is testable.** Gesture recognition,
   zoom transforms, and keyboard mapping are pure functions over input events — put
   them in plain classes with no Android dependency and test them directly.
4. **Instrumented tests for anything touching the decoder or surface** — these break
   per-device and a JVM unit test will not catch it. This means creating
   `app/src/androidTest`, which does not exist yet.
5. **Runtime verification is mandatory for UI changes.** Build, install, launch, and
   confirm no crash:

```bash
./gradlew assembleNonRoot_gameRelease
adb install -r app/build/outputs/apk/nonRoot_game/release/*arm64-v8a*.apk
adb logcat -c && adb shell am start -n meow.alxnko.moonmeow/com.limelight.PcView
sleep 5 && adb logcat -b crash -d      # MUST be empty
```

**Spell the component out in full.** The shorthand `meow.alxnko.moonmeow/.PcView` does
not work here and never will: `am` expands a leading `.` against the *applicationId*,
but our activities live in the `com.limelight` namespace (§1). The shorthand fails with
`Error type 3 … Activity class {meow.alxnko.moonmeow/meow.alxnko.moonmeow.PcView} does
not exist` — which looks exactly like a crash if you are not reading closely, and is the
one place the applicationId/namespace split leaks into a command you type.

The crash buffer check is not optional. A build that succeeds and an app that runs
are different claims — this repo has already shipped a green build that crashed on
launch.

### Priority order for new tests

Ordered by what is actually uncovered, not by what sounds important:

1. **Instrumented tests (`app/src/androidTest`)** — the one genuinely empty bucket.
   Decoder negotiation and surface lifecycle cannot be reached from Robolectric.
2. **Gesture/touch handling** — the area we are actively changing, and absent from
   the inherited suite.
3. **Keyboard/input mapping** — `StreamViewCommitTextTest` covers `commitText` only.
4. **Preference parsing and migration** — `OverlayPreferencesTest` covers the overlay
   subset; everything else is uncovered.
5. **Anything in `meow/`** — covered by rule 1 above, listed here for completeness.
---

## 6. The gate — run before every push

```bash
./gradlew assembleNonRoot_gameRelease     # release build, lintVital runs here
./gradlew testNonRoot_gameDebugUnitTest   # 51 tests, all must pass
```

Then the runtime check in §5. Red gate → nothing gets pushed.

Both halves are required. The build alone proves the app compiles and links; it says
nothing about whether the app works, and this repo has shipped a green build that
crashed on launch.

**Never add a lint baseline to silence `lintVital`.** It failed once here on 12 dead
strings that existed only in `values-ru` and were referenced nowhere — the fix was to
delete them, not to hide the error. A baseline would have buried a real defect.

---

## 7. Signing

Release keystore: `~/.keystores/moonmeow-release.jks`, credentials in gitignored
`keystore.properties`.

**Back both up off-machine.** Android requires the same signing key to update an
installed app. Losing it means every user uninstalls and loses their settings and
paired hosts — there is no recovery path.

Never commit the keystore or its properties file. `.gitignore` covers
`keystore.properties`, `*.jks`, and `local.properties` — verify before any commit that
adds build files.

---

## 8. Security

- **Never weaken pairing.** The PIN exchange is what stops an arbitrary tailnet device
  from streaming the host desktop.
- **Never log secrets** — no certificates, keys, or PINs in logcat.
- **Validate host-supplied data** before use.
- **New dependencies need justification.** This app holds credentials for the user's
  desktop.

---

## 9. Working in parallel

- One feature per branch, each confined to its own `meow/` class.
- Two agents must not edit the same upstream file in a cycle — coordinate via
  `TOUCHPOINTS.md`.
- Rebase on the synced base before opening a PR.

---

## 10. Reporting

Distinguish **verified** (ran it, output here), **assumed** (not checked), and **not
done** (and why). "Should work" is not a status.
