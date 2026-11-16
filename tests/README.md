# BioSense Log tests

Run every test suite from a terminal, without an IDE.

## Quick start

From the project folder:

| System | Command |
|---|---|
| Windows (Command Prompt or double-click) | `tests\run-tests.cmd` |
| Windows (PowerShell) | `.\tests\run-tests.ps1` |
| macOS / Linux / Git Bash | `tests/run-tests.sh` |

With no argument, the scripts run the `local` suite (everything that needs no phone).
Add a suite name to run only part of it, for example `tests\run-tests.cmd integration`.

Requirements: JDK 17 or later and the Android SDK, both already needed to build the app.
The first run downloads Gradle and dependencies, so it takes a few minutes.

## Suites

| Suite | What it checks | Device needed |
|---|---|---|
| `unit` | Measurement algorithms: pulse detection, HRV, breathing rate, tremor, step counting, finger detection | No |
| `integration` | Database upgrades from older app versions (no data lost), saving and clearing measurements, welcome notice memory, CSV and report exports | No |
| `security` | No internet access, only expected permissions, no screens open to other apps, health data excluded from cloud backup | No |
| `lint` | Android static analysis, including lint's built-in security checks | No |
| `local` | `unit` + `integration` + `security` + `lint` (default) | No |
| `ui` | The real app on an emulator or phone: welcome notice, tabs, a full measurement reaching the history, clearing the history, footer notices | Yes |
| `all` | `local` + `ui` | Yes |
| `coverage` | Runs the local tests with JaCoCo and prints line and branch coverage per file | No |

**Integration and security run twice:** in the developer build and in the store (release)
build. Store-only rules, such as "the simulator cannot be switched on" and "no fake readings
at startup", are only meaningful in the store build.

**UI tests need a connected device:** start an emulator from Android Studio's Device Manager,
or connect a phone with USB debugging enabled. They use the developer build's simulator, so no
finger on the camera is needed.

> ⚠️ **UI tests erase BioSense Log's saved measurements and settings on the device they run on.**
> Prefer an emulator. If several devices are connected, the scripts refuse to guess and ask you
> to choose one with `ANDROID_SERIAL`:
>
> - PowerShell: `$env:ANDROID_SERIAL = 'emulator-5554'; .\tests\run-tests.ps1 ui`
> - Command Prompt: `set ANDROID_SERIAL=emulator-5554` then `tests\run-tests.cmd ui`
> - bash: `ANDROID_SERIAL=emulator-5554 tests/run-tests.sh ui`
>
> List connected devices with `adb devices`.

## Results

Each script ends with a PASS / FAIL summary and the path of an HTML report:

- Unit, integration, security: `app/build/reports/tests/testDebugUnitTest/index.html`
  and `app/build/reports/tests/testReleaseUnitTest/index.html`
- Lint: `app/build/reports/lint-results-debug.html`
- UI: `app/build/reports/androidTests/connected/debug/index.html`
- Coverage: `app/build/reports/coverage/test/debug/index.html` (browse file by file, with
  covered lines highlighted). Coverage of the UI tests on a device:
  `gradlew createDebugAndroidTestCoverageReport`, report in `app/build/reports/coverage/androidTest/`.

The exit code is 0 when everything passed and 1 otherwise, so the scripts also work in CI.

## Where the test code lives

Android's build system only finds tests in these standard folders, so the code stays there
and this folder only holds the runners:

| Folder | Contents |
|---|---|
| `app/src/test/java/com/example/sensor/` | Unit tests |
| `app/src/test/java/com/example/data/` | Database integration tests |
| `app/src/test/java/com/example/ui/` | ViewModel integration tests |
| `app/src/test/java/com/example/security/` | Security tests |
| `app/src/androidTest/java/com/example/` | UI tests (run on a device) |

The database schema of each version is saved in `app/schemas/`. When you change the database,
add a migration and a test in `DatabaseIntegrationTest` that upgrades from the previous version.

## Not covered by automated tests

- **Real sensors:** camera pulse, chest breathing, tremor and steps on a physical phone.
  Test them by hand before each release.
- **Dependency vulnerability scanning:** not set up. Check dependency updates periodically,
  for example with Android Studio's "Project Structure > Suggestions".
