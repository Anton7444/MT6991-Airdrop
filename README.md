# MT6991-Airdrop

AirDrop for MediaTek MT6991 (Dimensity 9400+) OnePlus / OPPO devices. Send and receive files with Apple devices over the stock Wi-Fi chip, with a control-panel tile, live activity cards in the system share app, real transfer progress, and a policy that keeps normal Wi-Fi working while AirDrop runs.

| Device | Model | Status |
| --- | --- | --- |
| OnePlus Pad 2 | OPD2508 | Tested |
| OPPO Find X8s | PKT110 | Not tested on hardware |
| OPPO Find X8s+ | PLB110 | Not tested on hardware |

Requires root with `/data/adb/service.d` boot-script support, and LSPosed. Tested with iPhone only. The prebuilt kernel modules are built for kernel `6.6.118-android15-8` only.

## How it works

- A root service in `/data/adb/o13` loads the `wonder` and `mtk_wonder_dev` kernel modules, brings up the AWDL interface and runs the `owly` daemon. A watchdog keeps it healthy; a second script tunes the transmit rate during transfers.
- The app is also an LSPosed module scoped to the system share app (`com.coloros.oshare`). It adds the AirDrop target to the share panel, shows live activity cards for sending and receiving, and relays real progress.
- Wi-Fi coexistence is configurable: keep Wi-Fi connected and let AirDrop use a separate channel, or let AirDrop take priority while a transfer runs.
- Removing the app removes the service: the watchdog notices the package is gone and cleans up after 90 seconds. The in-app Remove service button does the same immediately.

## Updates

The app checks the latest GitHub release at most once a day (switchable in Settings) and shows a banner on Home and in Settings when a newer version exists. This is the only network access the app uses; it sends no personal data. Downloading and installing stay manual.

## Install

1. Install the APK and enable the module in LSPosed, scoped to the system share app.
2. Open the app and press Install on the Settings page. The app unpacks the bundled radio package and starts the service.
3. Turn AirDrop on from the app or the control-panel tile.

## Build

```
gradle assembleDebug
```

Android SDK 36, JDK 17, Gradle 8.13. `app/src/main/radio/package-mt6991.py` rebuilds `app/src/main/assets/radio-mt6991.zip` from the scripts in `app/src/main/radio`.

## Layout

- `app/src/main/java` – app, daemon client and LSPosed module
- `app/src/main/radio` – root service scripts, rate tuner source, packaging script
- `app/src/main/assets/radio-mt6991.zip` – prebuilt radio package
- `kernel/mtk_wonder_dev` – source of the platform-device shim module

## License

GPL-3.0, see `LICENSE`. The radio package contains third-party components under their own licenses, listed in `NOTICE`.

This software loads kernel modules and modifies system settings as root. Use it at your own risk.
