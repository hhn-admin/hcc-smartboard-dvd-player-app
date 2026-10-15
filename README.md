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
