# LiteDock

**A TV dock for the Nintendo Switch Lite, built on an Android TV box.**

The Switch Lite has no video output: its USB-C port does not carry DisplayPort, so a regular dock does nothing for it. LiteDock works around that in software. The console, running [Atmosphère](https://github.com/Atmosphere-NX/Atmosphere), streams its picture and sound over USB, and an Android TV box plays them on the TV. Plug the Switch in and the stream starts; unplug it and the box shows a waiting screen.

The project is a fork of [SysDVR](https://github.com/exelix11/SysDVR) by exelix11. The sysmodule is extended for the dock use case, and the original client is replaced with a new Android app written in Kotlin. The original SysDVR readme is in [README.sysdvr.md](README.sysdvr.md).

> **Status: experimental.** Everything was tested on a single setup: a Switch Lite and an X96Q Pro TV box (Allwinner H313, Android 10). The case model is designed for the X96Q Pro and is still a draft (see [hardware](hardware/README.md)).

## Features

| | |
|---|---|
| **Games** | H.264 720p **60 fps** from the system recorder (`grc:d`), hardware decoding on the box |
| **HOME menu, overlays** | Whole-screen capture through the system screenshot service `caps:sc` (JPEG, up to ~30 fps). Kicks in automatically whenever the game provides no video |
| **Sound** | The console's final audio output through the `audrec` recorder: game sound **and HOME menu sounds**. Captured before the volume control, so the console speaker can be muted |
| **Dock mode** | While connected, the console screen turns off, the built-in speaker is muted and auto sleep is disabled. Everything is restored on disconnect |
| **Auto start** | The app opens by itself when the Switch is plugged in over USB |
| **Latency** | About 90 ms from the Switch screen to a PC monitor. A TV without game mode can add another 100-200 ms |

## How it works

```
Switch Lite (Atmosphère + LiteDock sysmodule)
  ├─ video: grc:d (H.264 720p60)  ──┐
  ├─ menu:  caps:sc (JPEG)         ─┼─► USB 2.0, SysDVR protocol + extensions
  └─ sound: audrec (PCM 48 kHz)    ─┘
                                       │
Android TV box (LiteDock app)
  ├─ sysdvr-usb: USB, handshake, packet parsing, reconnection
  ├─ MediaCodec → SurfaceView (H.264)
  ├─ JPEG → ImageView over the video
  └─ AudioTrack (adaptive buffer)
```

## Repository layout

| Path | What it is |
|---|---|
| [`sysmodule/`](sysmodule) | The SysDVR sysmodule with the LiteDock changes (`capture.c`, `modes/USBmode.c`, `modes/proto.*`, `util.c`) |
| [`AndroidApp/`](AndroidApp) | The Android app: the `sysdvr-usb` module (USB and protocol) and `app` (video, audio, UI) |
| [`tools/`](tools) | Test homebrew used to check `caps:sc` (`capstest`) and `audrec` (`audrectest`) |
| [`hardware/`](hardware) | 3D model of the dock case for the X96Q Pro box (draft) |
| `Client/`, `SysDVRConfig/` | The original SysDVR client and settings app, unchanged |

## Protocol extensions

Every extension is enabled by handshake flags that only the LiteDock client sends. Upstream SysDVR clients work with this sysmodule as before, and the LiteDock client works with the upstream sysmodule, minus the new features.

| Where | Value | Meaning |
|---|---|---|
| `FeatureFlags` bit 2 | `JpegFallback` | JPEG frames while `grc` provides no video (HOME menu, games without video capture) |
| `FeatureFlags` bit 3 | `JpegOnly` | JPEG only, for comparison |
| `FeatureFlags` bit 4 | `SystemAudio` | Sound from `audrec` instead of `grc` (falls back to `grc` on error) |
| `FeatureFlags` bit 5 | `MuteSpeaker` | Mute the console speaker while connected |
| `FeatureFlags` bit 6 | `KeepAwake` | Disable auto sleep while connected |
| `Reserved[0]` | 1-60 | Maximum JPEG frame rate (0 means the default, 30) |
| `PacketMeta` bit 6 | `Content_Jpeg` | The packet carries a JPEG instead of H.264 |
| Error packets, types `0x10`-`0x14` | | Sysmodule diagnostics: JPEG and `audrec` capture errors, capture and dock mode status |

## Limitations

- **720p only.** The system capture paths (`grc`, `caps:sc`) produce 1280x720; that is fixed in the system, not in the protocol.
- **Games that block video recording:** the picture comes through JPEG, with no sound. [dvr-patches](https://github.com/exelix11/dvr-patches) fix this; they patch the system `am` module and are tied to the firmware version.
- **USB works in handheld mode only**, which is not a limitation for the Lite.
- If the sysmodule crashes while the screen is off, the backlight does not come back by itself. Reboot, or use the SysDVR Settings app.

## Installing

Download the sysmodule and the APK from the [releases](../../releases) and follow the **[installation guide](INSTALL.md)**.

## Building from source

### Sysmodule

Requires [devkitPro](https://devkitpro.org/wiki/Getting_Started) with the `switch-dev` package.

```bash
cd sysmodule
make -j8                      # full build (works with SysDVR Settings)
make -j8 DEFINES="-DUSB_ONLY" # USB only, uses less memory
```

Copy `sysmodule.nsp` to the SD card as `/atmosphere/contents/00FF0000A53BB665/exefs.nsp` (together with `flags/boot2.flag` and `toolbox.json` from the original SysDVR release) and reboot the console. Back up the original sysmodule before replacing it.

### Android app

```bash
cd AndroidApp
./gradlew :app:assembleDebug           # APK in app/build/outputs/apk/debug/
./gradlew :sysdvr-usb:testDebugUnitTest
```

Requires Android 9 (API 28) or later. For the lowest latency, enable game mode on the TV for the box's input.

## Credits and license

- [SysDVR](https://github.com/exelix11/SysDVR) and [dvr-patches](https://github.com/exelix11/dvr-patches) by exelix11.
- [libnx](https://github.com/switchbrew/libnx), [Atmosphère](https://github.com/Atmosphere-NX/Atmosphere).
- The research published by Switch Frame Tap (TomasUribe) helped understand how capture works on the Switch.

License: **GPL-2.0**, same as SysDVR (see [LICENSE](LICENSE)).
