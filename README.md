# XY8: four CC layers + four note layers (Plaits / Rings)

**Native Android Studio project, landscape locked.** No Processing runtime, ControlP5, or TheMidiBus required. Build on GitHub Actions to get a sideloadable **debug APK**.

## Build on GitHub

1. Create an empty GitHub repository. Upload the **contents** of this ZIP to its root (including the hidden `.github` folder). This is an Android Studio Gradle project, not a Processing sketch.
2. Open **Actions** > **Build XY8 Plaits Rings APK** > **Run workflow**. (A push to `main` also triggers a build.)
3. Download the `XY8-Plaits-Rings-debug-apk` artifact and extract `app-debug.apk`. Enable sideloading for your file manager if required.

The workflow installs Java 17, the Android SDK and NDK, checks out official Mutable Instruments sources at commit `08460a6`, then builds the APK using Gradle + CMake. Native source is intentionally fetched during the build, rather than reproduced or renamed here. Internet access is required in GitHub Actions for the original code.

### What is implemented

- Landscape fullscreen Android UI with touch XY pad, separate **CC** and **NOTE** banks, four layers per bank.
- Layer selection, global transport, loop, arm record, mute/solo, layer pause/play, clearing, BPM changes and gate arpeggiator.
- Independent X/Y CC numbers and ranges, CC free-running or sync-to-bars, original default CC assignments, incoming CC controller remapping.
- X is a scale-quantised MIDI note; Y is assignable per note layer (including Plaits timbre/morph/harmonics and Rings damping/position/brightness). Each layer chooses key, root note, octave range, program (lead/bass/chord/drum), time span and external MIDI channel.
- **Actual Mutable Instruments Plaits** DSP (24 models) and **actual Mutable Instruments Rings** DSP (6 resonator modes), built as a C++ Android NDK shared library.
- Note sound editor: engine/model selection, Plaits harmonics, timbre, morph, decay and LPG colour; Rings structure, brightness, damping and position; gain and auxiliary-output mixing.
- Master tape section: wet/dry, saturation, wow, flutter, hiss, wear/dropouts and feedback echo. It is a **new software tape algorithm**, not a bit-for-bit port of the separate PocketTracker tape DSP.
- Android MIDI input/output selection (supported USB/Bluetooth/virtual devices), internal audio, 4 local project banks, complete JSON import/export and restoration, plus legacy CC automation/routing assets imported from `ccultimate.zip`.

### Performance and limitations

This is the first integration source revision, **not yet built or exercised on a physical Android handset**. GitHub Actions is the build check and may expose integration errors in Native DSP compilation. Audio is mono at 48 kHz and uses `AudioTrack`, not AAudio/Oboe. Up to 16 native voices are maintained. The note recording/playback engine is MIDI event-based, not Korg audio overdubbing. Tape affects internal audio but not outgoing MIDI. Note sound presets and tape settings save inside the project JSON. Plaits' calibrated oscillator clock is close to, but not identical with, the Android 48 kHz audio rate.

### Third-party source / licensing

Plaits, Rings and stmlib software copyright Émilie Gillet and contributors; MIT licensed (the build fetches source files containing their original copyright notices). Source: <https://github.com/pichenettes/eurorack>. This application is a separate, unaffiliated creative instrument and does not use the hardware brands or graphics. Do not remove the upstream source licence notices when redistributing.

## Source map

- `app/src/main/java/com/oll/xy8/Engine.java`: CC/note automation, event recording, loop scheduling, MIDI output.
- `Model.java`: track models, patches, persistence.
- `MainActivity.java`, `PerformanceView.java`: touch layout, edits, bank UI, presets.
- `MidiIO.java`: Android MIDI transport.
- `Synth.java`: audio thread and JNI bridge.
- `app/src/main/cpp/native_synth.cpp`: Mutable DSP wrapping, voice allocation, stereo submix to mono, tape emulation.
- `app/src/main/cpp/CMakeLists.txt`: external C++ source compilation.
- `scripts/fetch-mutable.sh`: pinned Mutable Instruments fetch.
- `.github/workflows/build-apk.yml`: GitHub APK build.

## Android 15 privacy and display compatibility (v0.3.0)

- `compileSdk 35`, `targetSdk 35`; `minSdk 26` remains so older supported Android devices can still install the app. Targeting API 35 removes the deliberate Android 9 compatibility target.
- Screen orientation remains **landscape**. Immersive fullscreen uses `WindowInsetsController` on Android 11+, and the XY touch canvas reserves camera-cutout safe areas in landscape.
- The manifest declares **no** Internet, camera, microphone, contacts, or broad-storage permissions. Project JSON import/export continues through Android's user-controlled document picker.
- Android cloud backup is disabled for this build (`android:allowBackup="false"`). Export project JSON manually if you want to transfer saved sessions. On some devices, device-to-device transfer behavior can differ.
- APKs from GitHub Actions are **debug builds**, not signed production releases. Builds created on different runners may use different debug signing keys; if an APK won't update an earlier debug install, export your projects first, then uninstall the old version and install the new one.
- Installation and on-device testing are still required to verify performance, MIDI availability, orientation, and fullscreen cutout handling on your particular phone.
