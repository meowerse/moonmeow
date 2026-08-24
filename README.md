# Moonmeow

An Android client for streaming a desktop — or a game — from your PC to your phone.

Moonmeow is a fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android)
(formerly Moonlight Noir), which is itself a fork of
[Moonlight](https://github.com/moonlight-stream/moonlight-android). It connects to
[sunmeow](https://github.com/meowerse/sunmeow) — our fork of
[Apollo](https://github.com/ClassicOldSong/Apollo) — and remains compatible with
Apollo and [Sunshine](https://github.com/LizardByte/Sunshine) hosts.

## What it is for

Most Moonlight clients are built for gaming. Moonmeow is tuned for the case its
author actually uses: **desktop and office work from a phone** — writing code,
reading logs, driving a terminal — over the same low-latency video path.

That shapes what we care about. Text input, pointer precision, keyboard shortcuts
that survive Android's interception, arbitrary resolutions that match a phone
screen instead of a TV, and panning around a desktop that is larger than the
display in your hand. Frame pacing for a 4K shooter matters less to us than
whether Ctrl+Shift+P reaches the host.

Paired with sunmeow's virtual display, the phone becomes a real monitor with its
own resolution rather than a mirror of a physical one.

## Features

Inherited from Artemis, and the reason we fork it rather than upstream Moonlight:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support.
18. Natural trackpad mode with touch screen.
19. Non-QWERTY keyboard layout support.
20. Quick Meta key with physical BACK button.
21. Frame rate lock fix for some devices.
22. Video scale mode: Fit/Fill/Stretch.
23. View pan/zoom support.
24. Rotate screen in-game.
25. Option to quit the app directly.
26. Samsung DeX scrolling support.
27. Proper click/scroll/right-click for trackpad on generic Android tablets when using local cursor.
28. Virtual Display integration (requires Apollo or sunmeow).
29. Server Command integration (requires Apollo or sunmeow).
30. Clipboard sync (requires Apollo or sunmeow).
31. SBS 3D for external displays (using AI MiDaS v2 Lite).

## Relationship to upstream

We track Artemis's `moonlight-noir` branch, not Moonlight's `master`, because
Artemis is where the development is:

| Repository | Latest commit | Notes |
| --- | --- | --- |
| [moonlight-stream/moonlight-android](https://github.com/moonlight-stream/moonlight-android) | 2024-07-27 | last release v12.1, February 2024 |
| [ClassicOldSong/moonlight-android](https://github.com/ClassicOldSong/moonlight-android) (`moonlight-noir`) | 2025-10-18 | 568 commits ahead of upstream |

Artemis is ahead on every axis we care about and is explicitly tuned for
desktop/office use rather than gaming. Note that `master` and `next` in the
Artemis repository are stale (2024) and far behind `moonlight-noir`.

Moonmeow exists on top of that for one reason: to be *our* build — our
`applicationId`, our signing key, our release cadence, and a place to put
desktop-oriented changes that we do not expect anyone upstream to carry. We
merge from Artemis regularly and try hard to add files rather than edit
inherited ones, so those merges stay cheap.

Moonmeow ships as `meow.alxnko.moonmeow`, so it installs alongside Moonlight
and Artemis rather than replacing either. The Java namespace is still
`com.limelight` on purpose — renaming it would conflict with every upstream
merge and change nothing a user can see.

## Downloads

There are no prebuilt releases. Moonmeow is built from source — see below.

## Building

Requirements:

* Android SDK (`compileSdk 36`, `minSdk 21`)
* Android NDK **27.0.12077973** — the version is pinned in `app/build.gradle`;
  install it through the SDK Manager so Gradle can find it under `$ANDROID_HOME/ndk/`
* JDK 17 or newer

Steps:

```bash
git clone https://github.com/meowerse/moonmeow.git
cd moonmeow
git submodule update --init --recursive     # pulls moonlight-common-c and enet

# point Gradle at your SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleNonRoot_gameDebug      # no signing key needed
```

The APKs land in `app/build/outputs/apk/nonRoot_game/debug/`, split per ABI
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). Install the one matching your
device:

```bash
adb install -r app/build/outputs/apk/nonRoot_game/debug/*arm64-v8a*.apk
```

For a release build — smaller, faster, and what we actually ship — use
`./gradlew assembleNonRoot_gameRelease`. It outputs to `.../release/` and needs
a signing key first; see [Signing](#signing) below.

Unit tests:

```bash
./gradlew testNonRoot_gameDebugUnitTest
```

The debug build installs as `meow.alxnko.moonmeow.debug`, so it sits side by side
with a release install rather than replacing it. There is also a `root` flavour,
which exists only for pre-Android-8 devices that need root for mouse capture
(`maxSdk 25`); you almost certainly do not want it.

### Signing

Release builds are signed from a gitignored `keystore.properties` in the repo
root:

```properties
storeFile=/path/to/your-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

**A release build requires that file.** The `release` build type wires up the
`moonmeow` signing config unconditionally, so without `keystore.properties` the
build gets as far as packaging and then fails:

```
Execution failed for task ':app:packageNonRoot_gameRelease'.
> SigningConfig "moonmeow" is missing required property "storeFile".
```

Generate your own key with `keytool -genkeypair -v -keystore my-release.jks
-keyalg RSA -keysize 2048 -validity 10000 -alias moonmeow` and point
`keystore.properties` at it — it does not have to be ours. If you only want to
run the app, `./gradlew assembleNonRoot_gameDebug` needs no keystore at all; it
uses Android's debug key and installs as `meow.alxnko.moonmeow.debug`.

`keystore.properties`, `*.jks`, and `local.properties` are all gitignored; never
commit them.

## Contributing

`CLAUDE.md` (and `AGENTS.md`, which points at it) describes how work is done
here: additive-only changes wherever possible, `moonlight-common-c` treated as a
sealed dependency, and a build + unit-test + on-device launch check before
anything is pushed. Read it before your first change.

## License

Moonmeow is licensed under the **GNU General Public License v3.0**, inherited
from Moonlight and Artemis. See [LICENSE.txt](LICENSE.txt) for the full text.

## Authors and credits

Moonlight — the project all of this descends from — is the work of students at
[Case Western](http://case.edu) and was started as a project at
[MHacks](http://mhacks.org):

* [Cameron Gutman](https://github.com/cgutman)
* [Diego Waxemberg](https://github.com/dwaxemberg)
* [Aaron Neyer](https://github.com/Aaronneyer)
* [Andrew Hennessy](https://github.com/yetanothername)

Artemis (Moonlight Noir), the fork Moonmeow is built on, and Apollo, the host it
was designed against, are by [ClassicOldSong](https://github.com/ClassicOldSong).
Nearly every desktop-oriented feature listed above is their work.

Sunshine, the host software Apollo forks, is by
[LizardByte](https://github.com/LizardByte).

Moonmeow is maintained by [meowerse](https://github.com/meowerse). It is an
unofficial fork and is not endorsed by any of the projects above.
