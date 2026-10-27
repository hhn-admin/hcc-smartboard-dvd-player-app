# DVD Player

Full-screen live view of a USB HDMI capture card on an Android tablet or smart board. Plug a DVD player (or any other HDMI source) into the DVD capture card, plug that card into the board, and the picture fills the screen. Sound from the card's USB audio plays through the board's normal media output.

The app does not use Google Play services. It is meant to be sideloaded or installed by an MDM. Teachers do not configure it. Version 1.1 remembers the capture card, hides the buttons while a disc is playing, and adds freeze and snapshot for the board's ink tools.

## What you do

1. Open **DVD Player**.
2. Plug in the DVD capture card. Until it is detected, that sentence, a large plug icon, and an arrow sit at the bottom-left of the screen. The arrow points down-left, toward the USB ports on the front bezel. Playback starts as soon as the card is plugged in.
3. The last capture card that successfully showed a picture starts on its own the next time, even if another USB device is listed. If nothing has been used yet, the only capture card starts. The board's own camera is not a source. If more than one capture card is listed and none of them is the remembered one, tap the USB source.
4. The first time that card is plugged in, Android may ask whether to open DVD Player for the USB device. Open it and choose **Always** (the checkbox is "use by default for this USB device") so the next plug does not ask. If you opened the app from the icon and that default is not set yet, Android instead asks once for access to `/dev/bus/usb/...`. Allow it.
5. The picture fills the screen, the system bars hide, and the screen stays on. The top buttons hide after a few seconds (4 by default). Tap the picture once to bring them back. Tap twice to toggle **Zoom to fill**. **Change source** returns to the list and stays there until that card is unplugged. Unplugging the card that is playing returns to the list with "Capture card unplugged - plug it back in".
6. **Freeze** holds the current picture on screen. The DVD and the sound keep going underneath. The screen says **FROZEN - tap to go live**. Tap that line, or **Live**, to see the picture again.
7. **Snapshot** saves the current frame (the frozen one, if the picture is held) as a PNG in `Pictures/DVD Player`. A thumbnail and the line "Snapshot saved to Pictures/DVD Player" confirm it. Open that picture in the board's ink tools. On Android 9 and older the app asks once for storage access so the file can land in the shared Pictures folder. If that is denied, the file is kept in the app's own pictures folder instead.
8. A small bar in the bottom-right corner moves when the capture card's sound is coming through. If it stays empty, the sound did not open. A black picture for a few seconds after the card opens is replaced with "No signal - make sure the DVD player is turned on and the HDMI cable is connected".
9. Hold a finger on the picture for 3 seconds to lock the controls. Change source and Zoom hide, so a student cannot switch away. Freeze and Snapshot stay. Hold for 3 seconds again to unlock. An MDM can lock this so the hold does not unlock it. See [Managed settings](#managed-settings).

## How video is opened

SMART boards always have a SMART Ultra HD Camera 120 (model SWC-120UHD) on USB. That webcam is not a DVD source. Any USB device whose vendor id is `0x0B8C` (SMART Technologies, from SMART's support docs; the product id varies) is dropped from the list. Names containing "SMART", "Smart Technologies", "Ultra HD Camera", "SWC-120", "webcam", or "Integrated" are dropped too, including a Camera2 camera that shows up under one of those names. Built-in cameras are not listed.

What remains is a USB video device. A name that looks like a capture dongle (for example "capture", "HDMI", "UVC", "Mack", "Elgato", "ezcap", "AVG", "MS210x", or "MS213x") is treated as the DVD capture card and can start on its own when it is the only row. If a USB video device is present but the name is ambiguous, it is still listed and the SMART webcam stays hidden, but playback does not start until someone taps it. Camera2 is used only when no USB source was kept and the camera's own name looks like a capture card. Until then the screen keeps saying "Plug in the DVD capture card".

The USB path uses [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) `1.0.13` (`com.herohan:UVCAndroid` on Maven Central). It is a maintained libusb/libuvc stack (the line of saki4510t/UVCCamera and AndroidUSBCamera) that opens the UVC interface through the Android USB host API. No root. libuvc does not use `android.permission.CAMERA`.

The picture fills the screen without stretching. UVCAndroid's default open, when no size is passed, is **640×480**. That 4:3 frame fit inside a 16:9 board looks smaller than a 16:9 frame, and a DVD menu at 720×480 does the same. The app now asks for **1920×1080 MJPEG** first, then the next smaller listed MJPEG mode (720p, then 480p). It does not ask for 4K: uncompressed or 4K MJPEG is too much for a USB 2.0 host. The preview surface is measured to the screen, not to the capture's pixel size, so a small frame scales up until it touches an edge (fit, centered). The aspect ratio is kept. When the negotiated size changes, the surface is measured again.

Tap the picture twice, or tap **Zoom to fill** on the top bar, to crop the bars instead. That still does not stretch. It is off by default unless the MDM sets `zoom_to_fill_default`. Bars that are already inside a 16:9 frame (a 4:3 DVD menu captured as 1080p) stay, because the frame itself matches a 16:9 screen. Long-press the status line (not the picture — a 3-second hold on the picture locks the controls) for the capture resolution and whether fit or zoom is on. The buttons hide while the lock is on.

`PickerActivity` also handles `USB_DEVICE_ATTACHED` with `res/xml/device_filter.xml`. The filter matches USB video class 14 and composite class 239 subclass 2, on the device descriptor or any interface, which is the same match as `UsbVideoFilter`. A device filter cannot exclude a vendor id, so the SMART webcam matches too. An attach for vendor `0x0B8C` (or a SMART / webcam name) is ignored: no playback and no USB permission request. If that attach is what opened the activity, the activity closes. Accept **Always** only on the chooser for the capture card.

## How sound is played

The capture card's video and audio are separate USB functions (UVC and UAC) on the same USB device. The app does not try to pull audio out of the video stream, and it does not use the board's speaker volume as a substitute for a missing input.

When preview starts, sound is written to an `AudioTrack` with `USAGE_MEDIA` and `CONTENT_TYPE_MOVIE`. From Android 10 up the track sets `ALLOW_CAPTURE_BY_ALL`, so screen share and cast apps that capture media playback can hear it. Nothing forces the track onto the USB gadget; media usage follows the board's current route (room speakers or the board's HDMI out). The built-in mic is refused: looping it into the room speakers howls.

`android.permission.RECORD_AUDIO` is not requested up front. The direct isochronous reader uses the USB device permission already required for video. If that reader delivers PCM, the microphone dialog never appears. If it does not, the player asks for `RECORD_AUDIO` and then uses `AudioRecord` on a USB device, USB headset, or USB accessory input. When that permission is already granted (an MDM policy, or a previous allow), `AudioRecord` stays first and the direct reader is the fallback, because that `AudioRecord` path is the one confirmed on a board. Camera2 has no UAC interface to read, so opening a Camera2 source asks for the microphone permission immediately. If the input is not there yet, the app waits and retries.

USB vendor `0x0B8C` is never that input. The SMART camera's mic is excluded by vendor id, by the webcam names above, and by a USB address that points at that device. Cheap HDMI sticks such as the Audio Express AXHDCAP (MacroSilicon-class, commonly `534d:2109`, but the code does not require that id) leave the audio product string empty or call it "USB Audio", while Android's address looks like `card=2;device=0` rather than `/dev/bus/usb/...`. That input is used when it is the only non-SMART USB device that actually has a UAC interface. A second capture card with its own UAC interface is not guessed by name.

UVCAndroid 1.0.13 claims the video interfaces only, so the kernel can keep the audio driver. Some board images still drop the USB input once video is open. After a short wait with no usable input, the app claims the audio interfaces on the playing capture card (never the video interfaces, and never vendor `0x0B8C`) and reads the isochronous PCM itself. Sticks in this class often describe that stream as 16-bit mono at 96 kHz while the bytes are stereo at 48 kHz; the direct reader follows the descriptor (the input terminal says two channels), and the Android input path does the same when the HAL reports only 96 kHz mono. Neither path is tied to one vendor id.

Long-press the status line to show USB devices (vendor, product, names, interfaces) and every audio input with its type, address, rates, and whether this app will use it. Leave that screen up and photograph it if sound is still missing. The corner meter is the quick check: it moves only when PCM is actually being read. If no audio input could be opened, the status line says so and the picture continues.

A short hold (80 ms on the UVC path, 40 ms on Camera2) keeps the sound from racing ahead of the picture. The track itself uses a small buffer and, on Android 8+, low-latency mode.

## Pre-granting permissions with an MDM

Auto-grant these two runtime permissions. The app declares both. The USB capture card does not ask for them on a normal play, and granting them removes the dialog on the fallback paths:

- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`

`CAMERA` is used only when a Camera2 system source is opened. libuvc does not use it. `RECORD_AUDIO` is used for `AudioRecord`: Camera2 playback, and USB playback when the direct isochronous reader does not deliver PCM. Android calls that USB input a microphone. It is not the built-in mic.

The USB device permission (the dialog "Allow DVD Player to access `/dev/bus/usb/...`?") cannot be pre-granted by a runtime-permission policy. `adb install -g` grants the two permissions above and does not grant this one. Android grants it for the current connection when the teacher accepts the `USB_DEVICE_ATTACHED` chooser, or when the app calls `UsbManager.requestPermission` because that grant is missing. Devices with an empty serial number drop the grant on unplug. The **Always** checkbox on the attach chooser is what skips both USB prompts the next time that card is plugged in.

`WRITE_EXTERNAL_STORAGE` is declared with `maxSdkVersion="28"` for the snapshot button on Android 7 through 9. Android 10 and newer save the PNG through MediaStore and do not need it. Pre-grant it only for those older images if you want the snapshot in the shared Pictures folder without a prompt.

## Managed settings

DVD Player reads Android managed configurations. The MDM pushes a bundle, the app reads it with `RestrictionsManager`, and it listens for `ACTION_APPLICATION_RESTRICTIONS_CHANGED`, so a change applies without reinstalling. A key the MDM sends replaces the default. A key the MDM leaves out keeps the default. The only setting stored on the board is the 3-second control lock, and a managed `lock_controls` value replaces that too.

The keys are declared in `res/xml/app_restrictions.xml`. The manifest points at that file with `android.content.APP_RESTRICTIONS`, which is what the EMM console uses to build the form.

### Set them from SMART or another MDM

1. Upload the signed `dvd-player-1.1.0-release.apk` as an update to the existing app `com.hcc.dvdplayer`. Do not create a second package.
2. In the MDM, open that app's configuration. SMART MDM and other Android Enterprise consoles call this **Managed configuration**, **Application configuration**, or **App restrictions**. The nine keys below should appear from the APK. If the console has no form, push a managed-configuration bundle that uses these exact key names.
3. Save and sync the board. The player and the source list pick up the new values on the restrictions broadcast, and again the next time that screen is shown. There is nothing for the teacher to turn on inside the app.

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `auto_start` | bool | `true` | Start the last successful source when it is plugged in. If nothing was remembered, start when exactly one capture card is connected. `false` always waits on the list. |
| `zoom_to_fill_default` | bool | `false` | Start cropped to the screen. `false` fits the whole frame. A teacher can still tap twice to switch, unless controls are locked. |
| `lock_controls` | bool | `false` | Hide **Change source** and **Zoom**, and ignore the 3-second hold. Freeze and Snapshot stay if they are enabled. Back does not leave the picture. When this key is present, the board cannot override it. |
| `show_audio_meter` | bool | `true` | The corner bar that moves with the capture card's sound. |
| `enable_snapshot` | bool | `true` | Show **Snapshot**. `false` hides it, including while controls are locked. |
| `enable_freeze` | bool | `true` | Show **Freeze**. `false` hides it and drops a freeze that was already up. |
| `controls_autohide_seconds` | integer | `4` | How long the top buttons stay after a tap. Values outside 1–60 are clamped. |
| `exclude_smart_devices` | bool | `true` | Hide USB vendor `0x0B8C` and SMART / webcam names from the list and from audio. Leave this on. `false` lets that webcam be listed. |
| `extra_excluded_vids` | string | empty | More USB vendor ids to hide, comma-separated hex, `0x` optional. Example: `0x1234,534D`. Empty hides nothing extra. These are hidden even when `exclude_smart_devices` is on. |

`exclude_smart_devices` does not remove the SMART webcam from Android's USB attach chooser. A device filter cannot exclude a vendor id. The app still ignores that attach while this key is true.

Prompts that can still appear:

| Prompt | When it shows |
| --- | --- |
| Open DVD Player for this USB device, with **Always** | The capture card is attached and DVD Player is not yet the default handler for it. After **Always**, later plugs launch the app and grant USB access with no dialog. The same chooser can name the SMART webcam, because the filter cannot exclude vendor `0x0B8C`. Cancel that one. The app ignores the webcam. |
| Allow access to `/dev/bus/usb/...` (Cancel / OK, and the note about calls and alarms) | The app was opened from the icon, or playback started, and this connection has no USB grant yet. Once per connection. This dialog has no Always checkbox. It does not appear after the attach chooser was accepted for this connection. |
| Take pictures and record video (`CAMERA`) | A Camera2 system source is opened. Not on the USB capture-card path. |
| Record audio (`RECORD_AUDIO`) | The direct USB audio reader did not deliver PCM, or a Camera2 source is opened. Not on the first USB run when direct audio plays. Hidden when the MDM grant is already in place. |

## Build

JDK 17, Android SDK 35.

```bash
export ANDROID_HOME="$HOME/android-sdk"   # or wherever the SDK lives
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/dvd-player-1.1.0-debug.apk`.

`minSdk` is 24 (Android 7.0). `targetSdk` and `compileSdk` are 35. The landscape layout is sized for a board: large type, large rows, D-pad focus.

GitHub Actions (`.github/workflows/build-debug-apk.yml`) runs the unit tests and uploads that debug APK as the `dvd-player-debug` artifact.

A release APK for MDM is a separate build. See [Signed release for Smart MDM](#signed-release-for-smart-mdm).

## Signed release for Smart MDM

Smart MDM (and any other device-management install) needs a release APK signed with a key you keep. The package name is `com.hcc.dvdplayer`. That id stays fixed: changing it makes the next upload look like a different app, and the board will not update in place.

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

Run this once, on a machine you control. Keep the `.jks` file and both passwords somewhere that is not the git repo. If this key is lost, MDM cannot update the installed app; every board would need the old package removed first. The launcher name is DVD Player. The keystore file name, alias, and certificate subject stay as they were when the secrets were created. Do not make a new key just to match the new name.

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

Until all four exist, the Release APK workflow still succeeds. It builds an unsigned release APK, prints that signing was skipped, and does **not** upload `dvd-player-release`. After the secrets are saved, the next run on `main` uploads the signed APK as the `dvd-player-release` artifact.

A local signed build uses the file path, not the base64 secret:

```bash
export ANDROID_KEYSTORE_PATH="$HOME/keys/hdmi-in-release.jks"
export ANDROID_KEYSTORE_PASSWORD='store-password'
export ANDROID_KEY_ALIAS='hdmi-in'
export ANDROID_KEY_PASSWORD='key-password'
./gradlew assembleRelease
```

The signed file is `app/build/outputs/apk/release/dvd-player-1.1.0-release.apk` (the version in the name follows `hdmiVersionName`).

### When the workflow publishes

`.github/workflows/release-apk.yml` runs on pull requests, on pushes to `main`, and on tags `vX.Y.Z` such as `v1.0.0`.

- Pull requests and `main` pushes upload `dvd-player-release` only when the four secrets are set.
- A tag `vX.Y.Z` must match `hdmiVersionName` in `app/build.gradle.kts`. `v1.1.1` with `hdmiVersionName = "1.1.0"` fails the job.
- On a matching tag, and only when the APK was signed, the workflow also creates or updates a GitHub Release and attaches that APK.

### Bump a version for an MDM update

MDM treats a build as an update only when `applicationId` is unchanged, the signing certificate is the same keystore, and `versionCode` is an integer greater than the one already installed.

1. In `app/build.gradle.kts`, increase `hdmiVersionCode` by at least 1 and set `hdmiVersionName` to the new version (`1.1.1`, `1.2.0`, …). Leave `applicationId` as `com.hcc.dvdplayer`.
2. Merge that change to `main`.
3. Tag the merge commit and push the tag:

```bash
git tag v1.1.1
git push origin v1.1.1
```

4. Download `dvd-player-1.1.1-release.apk` from the Actions artifact `dvd-player-release`, or from the GitHub Release attached to that tag.
5. Upload that APK in Smart MDM as an update to the existing DVD Player app. Do not create a second app entry with a different package name, and do not switch keystores.

## Sideload onto a smart board

Boards usually have no Play Store. Any of these work:

**adb over the network** (many boards enable it in Settings → About, or under a hidden developer menu):

```bash
adb connect BOARD_IP:5555
adb install -r dvd-player-1.1.0-debug.apk
```

`adb install -r -g` grants `android.permission.CAMERA` and `android.permission.RECORD_AUDIO`. It does not grant the USB device permission. See [Pre-granting permissions with an MDM](#pre-granting-permissions-with-an-mdm).

**USB stick or the board's file manager:** copy the APK on, open it, and allow installs from that source (Settings → Security → install unknown apps, wording varies by vendor).

Then open **DVD Player** from the launcher. On Android TV-style launchers it is registered as a leanback app as well.

Plug in the DVD capture card on a USB host port, not a "device" or charge-only port. Hubs are a frequent cause of a card that never enumerates.

## Limitations

These are real constraints, not a test plan:

- HDCP-protected HDMI sources (many set-top boxes and some DVD menus) show a black frame. The capture card cannot strip HDCP. After a few seconds of black frames the screen says there is no signal. A plain DVD player is often fine; a protected player is not. A copy that fails is not treated as a black frame, so a board that refuses `PixelCopy` can stay on a black picture without that sentence.
- Freeze holds one bitmap. The live preview and the audio keep running under it. Snapshot of a frozen picture saves that bitmap. On Android 7–9, denying storage permission saves the PNG under the app's own pictures folder, which the board's ink tools may not see. Android 10 and newer write `Pictures/DVD Player` without that permission.
- The no-signal line is a black-frame check every couple of seconds. A very dark movie scene can trip it. The line goes away on the next sample that is not black.
- Preview is not a wire. Expect a few frames of delay. The audio hold is a fixed compromise, not a measured lip-sync lock, so some cards will still be slightly early or late.
- Opening the dongle with libuvc claims the video interfaces. On some Android builds that also knocks out the USB audio driver. The app then reads the UAC interface itself. A stick with no audio function, or a board that refuses isochronous USB reads, still plays picture only. The corner meter stays empty and the status line says no audio input could be opened. Long-press the status line for the USB and input list.
- If two capture cards are connected, a generic "USB Audio" input is not guessed. Direct USB audio follows the card that is playing video. Video and audio can still come from different dongles when each input has a distinct product name.
- 4K capture is not requested. USB 2.0 hosts drop it. A 1080p frame is scaled up to the screen. A stick that only offers an uncompressed mode above 1080p may fail to stream.
- USB hot-plug broadcasts are missing on some boards. The source list also re-reads USB devices every couple of seconds while it is on screen.
- The USB device dialog is a system screen. Denying it returns to the list. The library waits on that grant on its own thread; leaving the dialog up and backing out is safe, but the grant still has to be answered. Accepting the attach chooser for this connection skips that dialog. Runtime-permission policy cannot pre-grant it.
- The app is landscape (`sensorLandscape`). A board whose firmware freezes orientation may letterbox it.
- No Play services, no network permission, no account. Teachers have nothing to set up. The MDM keys in [Managed settings](#managed-settings) are the configuration. The 3-second hold is the only control stored on the board, and a managed `lock_controls` replaces it.

## What a build does not prove

Unit tests cover source filtering, which source starts on its own, managed-configuration parsing, blank-frame detection, the PCM meter, preview-size choice, the audio format plan, and the delay line. `assembleDebug` proves the app and the UVCAndroid AAR compile and package. The arm64 and x86_64 copies of libuvc in that APK are 16 KB aligned; that was checked from the ELF headers, not by booting a 16 KB-page device.

Nothing in the build can show a real UVC frame, a USB permission dialog, hot-plug, USB audio routing, media-playback capture, or lip-sync. Those need a capture card on the target board.
