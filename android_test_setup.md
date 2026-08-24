Quick-start guide: writing JVM/Robolectric unit tests for this repo
===================================================================

0.  Where tests live  
    • Path: `app/src/test/java/com/limelight/...` (plain JVM tests, *not* instrumented).  
    • There is no `app/src/androidTest` — this repo has zero instrumented tests.  
    • Build task (all four exist; verify with `./gradlew :app:tasks --all`):  
      – Default flavour: `./gradlew :app:testNonRoot_gameDebugUnitTest`  ← the gate task  
      – Release variant: `./gradlew :app:testNonRoot_gameReleaseUnitTest`  
      – Root flavour:    `./gradlew :app:testRootDebugUnitTest`  
      – Every variant:   `./gradlew :app:test`  
      There is **no** `:app:testDebugUnitTest` task — the product flavours mean every
      unit-test task name carries a flavour.  
    • HTML report: `app/build/reports/tests/testNonRoot_gameDebugUnitTest/index.html`

1.  Dependencies & Gradle switches  
    `app/build.gradle` already contains everything you need:  

        testImplementation 'junit:junit:4.13.2'
        testImplementation 'androidx.test:core:1.7.0'
        testImplementation 'org.robolectric:robolectric:4.16'
        testImplementation 'org.mockito:mockito-core:5.19.0'

    Extra flag:  

        testOptions.unitTests.includeAndroidResources = true  

    → Tells Robolectric to merge `res/` into the unit-test APK so layout inflation works.

2.  Test-class boilerplate  

    ```java
    @Config(sdk = {33},
            shadows = {
                com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
    @RunWith(RobolectricTestRunner.class)
    public class MyFeatureTest {
        private Context ctx;

        @BeforeClass
        public static void silenceLogs() {
            TestLogSuppressor.install();   // hides noisy “Invalid ID 0x00000000” spam
        }

        @Before
        public void setUp() {
            ctx = ApplicationProvider.getApplicationContext();
            // extra prep (clear prefs, reset singletons, etc.)
        }

        @Test
        public void something_should_work() {
            /* your assertions */
        }
    }
    ```

    • `@Config(sdk = {33})` makes Robolectric emulate Android 13. The module builds against
      `compileSdk 36` with `targetSdk 34`; 33 is what every existing test pins because it is
      the level Robolectric is most stable at here. Keep new tests on 33 unless you need
      a newer API, and raise it deliberately if so.  
    • `shadows = …` suppresses native or platform calls:

      – `ShadowMoonBridge` eliminates the static initializer that tries `System.loadLibrary("moonbr")`, and provides minimal stubs/constants used by the app.  
      – `ShadowGameManager` avoids a ServiceManager lookup present on real devices.

    If you hit another native/SDK obstacle, create a new shadow the same way:

    ```java
    @Implements(SomeProblematicClass.class)
    public class ShadowFoo {
        @Implementation protected static void __staticInitializer__() {}
    }
    ```

3.  Typical helpers used in current tests  

    • **Resetting singletons** – many app classes use `private static` caches.  
      Example (ProfilesManager):

      ```java
      Field f = ProfilesManager.class.getDeclaredField("instance");
      f.setAccessible(true);
      f.set(null, null);       // clear before each test
      ```

    • **Cleaning filesystem state** – tests create/delete `context.getFilesDir()/profiles`.  
      Re-use `deleteRecursively(File)` from existing tests.

    • **Preferences isolation** – wipe them in `@Before`:

      ```java
      SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
      p.edit().clear().commit();
      ```

    • **Activity testing** – use the Robolectric `buildActivity` chain:

      ```java
      MyActivity act = Robolectric.buildActivity(MyActivity.class)
                                  .create().start().resume().get();
      assertFalse(act.isFinishing());
      ```

    • **Intent extras check**: supply them directly to `buildActivity`.  
    • **Configuration changes**: call `activity.onConfigurationChanged(newConfig)` manually.  
    • **Low-memory / lifecycle**: `activity.onLowMemory()`, `activity.onDestroy()`, etc.

4.  Mockito (rarely used yet)  
    If a new test needs mocks (e.g. for `NvConnection`), just:

    ```java
    NvConnection conn = Mockito.mock(NvConnection.class);
    Mockito.when(conn.sendUtf8Text(Mockito.anyString())).thenReturn(0);
    ```

5.  Tips & Gotchas specific to this project  

    • **Native JNI classes** (MoonBridge, etc.) must *always* be shadowed or the test JVM will `UnsatisfiedLinkError`.  
    • **GameManager & BackdropFrameRenderer** are Android-T APIs; stubbing them prevents `ServiceManager` or `SurfaceFlinger` lookups.  
    • **Resources** – because `includeAndroidResources=true`, you can safely inflate real layout XML, but keep the SDK level in `@Config` ≥ the latest attribute you reference.  
    • **Product flavours** – tests are compiled once per flavour; don’t hard-code `BuildConfig.APPLICATION_ID`, use `context.getPackageName()` when needed.  
    • **Suppress noisy log spam** – call `TestLogSuppressor.install()` once per test-class (see above).  
    • **The root `robolectric.properties` is inert — do not rely on it.** It sets
      `shadows=com.limelight.shadows.ShadowBackdropFrameRenderer`, but Robolectric reads that
      file off the *classpath*, so it would need to be at `app/src/test/resources/` — which
      does not exist. After a test run, `find app/build -name robolectric.properties` returns
      nothing, and no `@Config` names that shadow, so it never loads. List every shadow you
      need in `@Config` on the class itself. (Relocating the file would switch the shadow on
      for the whole suite; treat that as a behaviour change, not a tidy-up.)  
    • **Layouts need a Material theme.** The app's layouts use Material components
      (`ExtendedFloatingActionButton` and friends), which throw
      `IllegalArgumentException: The style on this component requires your app theme to be
      Theme.MaterialComponents (or a descendant)` under a plain `Theme.AppCompat`. Inflate with
      the app's own theme instead:

      ```java
      Context context = new androidx.appcompat.view.ContextThemeWrapper(
              ApplicationProvider.getApplicationContext(), com.limelight.R.style.AppTheme);
      ```

    • **Never construct `ArtemisApplication` yourself.** `new ArtemisApplication()` has no base
      context, so the `Toast` in its `onCreate()` dies with
      `Cannot invoke "android.content.Context.getResources()" because "this.mBase" is null`.
      Robolectric already builds the manifest's application — take that one:

      ```java
      ArtemisApplication app = (ArtemisApplication) ApplicationProvider.getApplicationContext();
      ```

    • **Find views as `View`, not a concrete widget class.** Upstream swaps widget types
      (`ImageButton` → `ExtendedFloatingActionButton`) without touching the id, and a test that
      declares the concrete type fails with `ClassCastException` on the next merge.  

6.  Skeleton for a new test file  

    ```
    app/
      src/
        test/
          java/
            com/
              limelight/
                myfeature/
                  AwesomeFeatureTest.java   <-- new
    ```

    ```java
    package com.limelight.myfeature;

    import android.content.Context;
    import androidx.test.core.app.ApplicationProvider;
    import com.limelight.TestLogSuppressor;
    import org.junit.*;
    import org.robolectric.*;
    import org.robolectric.annotation.Config;

    @Config(sdk = {33},
            shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                       com.limelight.shadows.ShadowGameManager.class})
    @RunWith(RobolectricTestRunner.class)
    public class AwesomeFeatureTest {
        private Context ctx;

        @BeforeClass
        public static void init() { TestLogSuppressor.install(); }

        @Before
        public void setUp() { ctx = ApplicationProvider.getApplicationContext(); }

        @Test
        public void newFeature_doesSomething() {
            // Arrange

            // Act

            // Assert
            Assert.assertTrue(true);
        }
    }
    ```

With the above conventions—Robolectric runner, shadows for native pieces, reflection resets,
and resource-enabled unit tests—you can write new coverage quickly without needing the
Android emulator or `androidTest` instrumentation.
