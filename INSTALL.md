# Installation guide

LiteDock has two parts: a sysmodule on the Switch and an app on the Android TV box. Both are in the [releases](../../releases).

## What you need

- A **Switch Lite** running [Atmosphère](https://github.com/Atmosphere-NX/Atmosphere). Running it from **emuMMC** and keeping that system offline is strongly recommended (see [Bans](#bans)).
- An **Android TV box** with Android 9 or later and a USB host port. Developed on an **X96Q Pro** (Allwinner H313, Android 10); other boxes should work but are untested.
- A **USB-C to USB-A data cable** between the Switch and the box. Some charge-only cables do not carry data.
- A TV with **game mode**: without it most TVs add 100-200 ms of latency.

## 1. Install the sysmodule on the Switch

LiteDock replaces SysDVR: they share the same title ID, so installing one overwrites the other.

1. **Back up** `/atmosphere/contents/00FF0000A53BB665/` from the SD card if you already have SysDVR, so you can go back to it.
2. Pick a build from the release:
   - **`LiteDock-sysmodule-usb-only.zip` (recommended).** USB only, uses less memory, starts in USB mode without any configuration.
   - **`LiteDock-sysmodule-full.zip`.** Full SysDVR build with the LiteDock changes, if you also want SysDVR's network modes. It includes `config/sysdvr/usb` so it starts in USB mode. To switch modes, use **SysDVR Settings** (`SysDVR-conf.nro`) from the [SysDVR 6.3 release](https://github.com/exelix11/SysDVR/releases); it is compatible.
3. Extract the zip to the **root of the SD card**, merging folders. You can access the card with a card reader, or with Hekate: *Tools → USB Tools → SD Card*. On a Mac, eject the card properly; if homebrew misbehaves afterwards, run `dot_clean` on the card to remove macOS `._*` files.
4. Reboot the console. The sysmodule starts about 20 seconds after boot.

## 2. Install the app on the TV box

1. Download `LiteDock-<version>.apk` from the release.
2. Install it:
   - **adb:** `adb install -r LiteDock-<version>.apk` (enable developer options and USB/network debugging on the box first);
   - **or** copy it to a USB stick and open it with a file manager on the box (allow installing apps from that file manager when asked).
3. Open **LiteDock** once from the launcher.

## 3. First connection

1. Connect the Switch to the box with the cable. The console should be on, with a game or the HOME menu on screen.
2. Android asks whether to allow **LiteDock** to access the USB device. Tick **"Always open LiteDock when this device is connected"** and confirm. After that the app opens by itself and gets access every time the Switch is plugged in.
3. The stream starts within a couple of seconds:
   - games: H.264 video, 60 fps;
   - the HOME menu: JPEG screenshots, up to 30 fps;
   - dock mode: the Switch screen turns off, its speaker is muted and auto sleep is disabled. Everything is restored when you unplug it.
4. Enable **game mode** on the TV for the box's HDMI input.

On the remote, **OK** shows or hides the statistics overlay.

## Games that block video recording

Some games do not allow video capture (you cannot record a clip with the capture button). For those, LiteDock shows the picture through JPEG screenshots and **there is no game sound**. [dvr-patches](https://github.com/exelix11/dvr-patches) lift the restriction for all games: they patch the system `am` module, must match your firmware version, and may cause issues with some games. Install them following their instructions.

## Uninstall or go back to SysDVR

Delete `/atmosphere/contents/00FF0000A53BB665/` (or restore your backup of it) and reboot. Uninstall the app on the box like any other app.

## Troubleshooting

| Problem | What to do |
|---|---|
| The Switch screen stays off after unplugging | Reboot the console, or turn the backlight back on with SysDVR Settings |
| The app stays on "WaitingForDevice" | Check the cable carries data; make sure the sysmodule is installed (in the full build, USB mode must be selected) and the console was rebooted |
| No sound in a game, but sound in the HOME menu | The game blocks video recording, see [above](#games-that-block-video-recording) |
| The picture lags behind the console a lot | Enable game mode on the TV; check the box outputs 60 Hz |
| The console does not boot after installing | Remove `/atmosphere/contents/00FF0000A53BB665/` from the SD card with a card reader (or Hekate USB mass storage) and reboot |

## Bans

The sysmodule only uses local system services and sends nothing online. The ban risk comes from custom firmware itself: keep Atmosphère and homebrew on an **emuMMC that never connects to Nintendo's servers**, and use the clean sysMMC for online play.
