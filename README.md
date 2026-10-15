# HDMI In

Full-screen live view of a USB HDMI capture card on an Android tablet or smart board. Plug a DVD player (or any other HDMI source) into a UVC capture dongle, plug the dongle into the board, tap the source, and the picture fills the screen. Sound from the dongle's USB audio plays through the board's normal media output.

The app does not use Google Play services. It is meant to be sideloaded.

## What you do

1. Open **HDMI In**.
2. Allow camera and microphone access. Android labels the capture card's USB audio as a microphone. That permission is not used for the built-in mic.
3. Tap the source. If Android asks for USB permission, allow it.
4. The picture goes full screen and the screen stays on. Back (or the on-screen Back button, which appears when you tap the picture) returns to the list.

One source is still one tap. The list is there so a second card or a system camera is an explicit choice. Plugging or unplugging a card updates the list. Unplugging the card that is playing returns to the list.

## How video is opened

Two paths, picked from the same list:

- **System camera** — Camera2 devices whose lens facing is external (or, on Android 9+, whose hardware level is external). Use this when the board already exposes the dongle. The camera HAL owns the USB video interface, which leaves the USB audio driver alone and usually has less overhead.
- **USB capture** — for the common case where the board has no external-camera HAL and Camera2 never lists the dongle. This path uses [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) `1.0.13` (`com.herohan:UVCAndroid` on Maven Central). It is a maintained libusb/libuvc stack (the line of saki4510t/UVCCamera and AndroidUSBCamera) that opens the UVC interface through the Android USB host API. No root. The preview request prefers MJPEG at or below 1080p, because uncompressed 1080p does not fit on a USB 2.0 host, and sets libuvc's bandwidth quirk that cheap HDMI sticks need in order to negotiate.

Both entries can appear for one physical card. If the system camera stays black, use the USB capture row.

## How sound is played

The capture card's video and audio are separate USB functions (UVC and UAC). The app does not try to pull audio out of the video stream.

When preview starts it looks for an input of type USB device, USB headset, or USB accessory, records PCM from that device, and writes it to an `AudioTrack` with `USAGE_MEDIA` and `CONTENT_TYPE_MOVIE`. From Android 10 up the track sets `ALLOW_CAPTURE_BY_ALL`, so screen share and cast apps that capture media playback can hear it. Nothing forces the track onto the USB gadget; media usage follows the board's current route (room speakers or the board's HDMI out).

A short hold (80 ms on the UVC path, 40 ms on Camera2) keeps the sound from racing ahead of the picture. The track itself uses a small buffer and, on Android 8+, low-latency mode. If the routed input is the built-in microphone, playback stops instead of howling into the room.

## Build

JDK 17, Android SDK 35.

```bash
export ANDROID_HOME="$HOME/android-sdk"   # or wherever the SDK lives
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

`minSdk` is 24 (Android 7.0). `targetSdk` and `compileSdk` are 35. The landscape layout is sized for a board: large type, large rows, D-pad focus.

GitHub Actions (`.github/workflows/build-debug-apk.yml`) runs the unit tests and uploads that debug APK as the `hdmi-in-debug` artifact.

A release APK for MDM is a separate build. See [Signed release for Smart MDM](#signed-release-for-smart-mdm).

## Signed release for Smart MDM

Smart MDM (and any other device-management install) needs a release APK signed with a key you keep. The package name is `com.captureboard.hdmiin`. That id stays fixed: changing it makes the next upload look like a different app, and the board will not update in place.

R8 and resource shrinking are off. The UVC library loads native code and callback classes that shrinking has a habit of breaking. Release builds are still signed and not debuggable.

The keystore and the passwords are not in git. Gradle signs the release build only when these environment variables are set:

| Variable | Meaning |
| --- | --- |
| `ANDROID_KEYSTORE_PATH` | Path to the `.jks` file on the machine that is building |
| `ANDROID_KEYSTORE_PASSWORD` | Store password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

CI does not use `ANDROID_KEYSTORE_PATH`. It decodes a base64 copy of the keystore from a GitHub Actions secret, writes it to a temp file, and then sets the path itself.

Without those values, `./gradlew assembleRelease` still compiles and writes an **unsigned** APK. Do not send that file to MDM. Android will refuse it, or a later signed build will not count as an update.

### Create the keystore

Run this once, on a machine you control. Keep the `.jks` file and both passwords somewhere that is not the git repo. If this key is lost, MDM cannot update the installed app; every board would need the old package removed first.

```bash
keytool -genkeypair -v \
  -keystore hdmi-in-release.jks \
  -storetype PKCS12 \
  -alias hdmi-in \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=HDMI In, O=Smart Board, C=US"
```

`keytool` asks for a store password and a key password. Type the same password both times. This command creates a PKCS12 keystore, and PKCS12 does not keep a separate key password. Put that one password in both `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD`. Validity is about 27 years, which is what you want for a fleet key.

Turn the file into one line of base64. The secret must be the raw base64 text, not a `data:` URL.

```bash
# Linux
base64 -w 0 hdmi-in-release.jks > hdmi-in-release.jks.b64

# macOS
base64 -i hdmi-in-release.jks | tr -d '\n' > hdmi-in-release.jks.b64
```

Delete the `.b64` file after the secret is saved. `*.jks` and `*.keystore` are gitignored; do not force-add them.

### Repository secrets

This environment cannot create the secrets. Add them on the repo yourself:

1. Open the repository on GitHub.
2. Go to **Settings → Secrets and variables → Actions**.
3. Choose **New repository secret** for each of these names.

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The contents of `hdmi-in-release.jks.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | The store password from `keytool` |
| `ANDROID_KEY_ALIAS` | `hdmi-in` (or whatever `-alias` you used) |
| `ANDROID_KEY_PASSWORD` | The key password from `keytool` |

Until all four exist, the Release APK workflow still succeeds. It builds an unsigned release APK, prints that signing was skipped, and does **not** upload `hdmi-in-release`. After the secrets are saved, the next run on `main` uploads the signed APK as the `hdmi-in-release` artifact.

A local signed build uses the file path, not the base64 secret:

```bash
export ANDROID_KEYSTORE_PATH="$HOME/keys/hdmi-in-release.jks"
export ANDROID_KEYSTORE_PASSWORD='store-password'
export ANDROID_KEY_ALIAS='hdmi-in'
export ANDROID_KEY_PASSWORD='key-password'
./gradlew assembleRelease
```

The signed file is `app/build/outputs/apk/release/hdmi-in-1.0.0-release.apk` (the version in the name follows `hdmiVersionName`).

### When the workflow publishes

`.github/workflows/release-apk.yml` runs on pull requests, on pushes to `main`, and on tags `vX.Y.Z` such as `v1.0.0`.

- Pull requests and `main` pushes upload `hdmi-in-release` only when the four secrets are set.
- A tag `vX.Y.Z` must match `hdmiVersionName` in `app/build.gradle.kts`. `v1.0.1` with `hdmiVersionName = "1.0.0"` fails the job.
- On a matching tag, and only when the APK was signed, the workflow also creates or updates a GitHub Release and attaches that APK.

### Bump a version for an MDM update

MDM treats a build as an update only when `applicationId` is unchanged, the signing certificate is the same keystore, and `versionCode` is an integer greater than the one already installed.

1. In `app/build.gradle.kts`, increase `hdmiVersionCode` by at least 1 and set `hdmiVersionName` to the new version (`1.0.1`, `1.1.0`, …). Leave `applicationId` as `com.captureboard.hdmiin`.
2. Merge that change to `main`.
3. Tag the merge commit and push the tag:

```bash
git tag v1.0.1
git push origin v1.0.1
```

4. Download `hdmi-in-1.0.1-release.apk` from the Actions artifact `hdmi-in-release`, or from the GitHub Release attached to that tag.
5. Upload that APK in Smart MDM as an update to the existing HDMI In app. Do not create a second app entry with a different package name, and do not switch keystores.

## Sideload onto a smart board

Boards usually have no Play Store. Any of these work:

**adb over the network** (many boards enable it in Settings → About, or under a hidden developer menu):

```bash
adb connect BOARD_IP:5555
adb install -r app-debug.apk
```

`adb install -r -g` also grants the runtime permissions, which helps when the settings UI is awkward.

**USB stick or the board's file manager:** copy the APK on, open it, and allow installs from that source (Settings → Security → install unknown apps, wording varies by vendor).

Then open **HDMI In** from the launcher. On Android TV-style launchers it is registered as a leanback app as well.

Plug the capture card into a USB host port, not a "device" or charge-only port. Hubs are a frequent cause of a card that never enumerates.

## Limitations

These are real constraints, not a test plan:

- HDCP-protected HDMI sources (many set-top boxes and some DVD menus) show a black frame. The capture card cannot strip HDCP. A plain DVD player is often fine; a protected player is not.
- Preview is not a wire. Expect a few frames of delay. The audio hold is a fixed compromise, not a measured lip-sync lock, so some cards will still be slightly early or late.
- Opening the dongle with libuvc claims the video interface. On some Android builds that also knocks out the USB audio driver, and the picture plays with no sound. The system-camera row avoids that when it exists. A video-only dongle, or a board image with no USB audio HAL, also plays picture only.
- If two capture cards are connected, audio follows a matching product name when it can, otherwise the first USB input. Video and audio can come from different dongles in that case.
- 4K modes are not requested. USB 2.0 hosts on tablets drop them. A stick that only offers an uncompressed mode above 1080p may fail to stream.
- USB hot-plug broadcasts are missing on some boards. The source list also re-reads USB devices every couple of seconds while it is on screen.
- The USB permission dialog is a system screen. Denying it returns to the list. The library waits on that grant on its own thread; leaving the dialog up and backing out is safe, but the grant still has to be answered.
- The app is landscape (`sensorLandscape`). A board whose firmware freezes orientation may letterbox it.
- No Play services, no network permission, no account. There is nothing to configure inside the app.

## What a build does not prove

Unit tests cover source filtering, preview-size choice, the audio format plan, and the delay line. `assembleDebug` proves the app and the UVCAndroid AAR compile and package. The arm64 and x86_64 copies of libuvc in that APK are 16 KB aligned; that was checked from the ELF headers, not by booting a 16 KB-page device.

Nothing in the build can show a real UVC frame, a USB permission dialog, hot-plug, USB audio routing, media-playback capture, or lip-sync. Those need a capture card on the target board.
