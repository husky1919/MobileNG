# MobileNG

A Neo Geo arcade frontend for Android. Custom libretro host (C++/JNI) loading the FBNeo core, OpenGL ES rendering, AudioTrack output, on-screen gamepad.

**Ships no game ROMs and no BIOS.** The core is imported at runtime by default, or can be built in (see below).

## Features

- Loads libretro cores (tested with FBNeo); identifies romsets by zip short name
- OpenGL ES 2.0: RGB565 / XRGB8888 / 0RGB1555 auto-detect
- Audio: core callback → ring buffer → AudioTrack
- On-screen pad: auto layout into the 4:3 pillarbox, or drag-to-place with persisted normalized coordinates
- Remappable buttons (16 libretro IDs) + two combo buttons (bitmask, multi-key)
- Neo Geo mode: MVS EUR/USA/JAP, AES EUR/JAP, Universe BIOS
- Video: fit / integer scale / stretch, smooth / sharp / scanline, brightness·contrast·saturation
- Save states via `retro_serialize`
- Cheats: write to main RAM, re-applied every 60 frames
- FPS / speed HUD
- Unified Java + native log file
- Debug build adds a memory editor

## Build

No Gradle. Termux or any Linux with Android SDK components:

```bash
pkg install aapt2 d8 apksigner openjdk-17 clang zip unzip
export ANDROID_JAR=/path/to/android.jar
bash build.sh            # build/MobileNG.apk
bash build.sh debug      # build/MobileNG-debug.apk (com.ng.emu.debug)
```

`BUNDLE_CORE=1 bash build.sh` embeds `build/core/fbneo_libretro_android.so` into `assets/core/libfbneo.so` (off by default).

## Download

[Releases](https://github.com/husky1919/MobileNG/releases) publish two packages:

| Asset | Notes |
|---|---|
| `MobileNG-<ver>-bundled.apk` | **FBNeo core included** — just import BIOS and ROMs |
| `MobileNG-<ver>.apk` | no core; use "导入核心 (.so)" in the app |

The bundled core is the unmodified `fbneo_libretro_android.so` from the [libretro buildbot](https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/), packed only as a convenience.

## Third-party licenses

- **FBNeo** — https://github.com/finalburnneo/FBNeo , non-commercial license (full text: https://github.com/finalburnneo/FBNeo/blob/master/src/license.txt ). Used here as an unmodified binary with its copyright notice retained, distributed free of charge for non-commercial purposes. The full notice is shipped as `assets/FBNeo.txt` and shown under "开源许可" in the app menu.
- This project's own code is MIT-licensed — see [LICENSE](LICENSE).
- Game ROMs and BIOS files are not part of this project and are not distributed.

## Before running

1. **Core** — skip this if you use the bundled build. Otherwise grab `fbneo_libretro_android.so.zip` (arm64-v8a) from the libretro buildbot, then "导入核心 (.so)" in the app.
2. **BIOS** — `neogeo.zip` containing `sp-s3.sp1`, `sm1.sm1`, `sfix.sfx`, `000-lo.lo`, … then "导入 BIOS".
3. **Game** — a standard FBNeo romset such as `mslug.zip`; point "游戏列表" at its folder.

## License

MIT for this project's own code.

This project contains emulator frontend code only — **no game ROMs and no BIOS**, and is **not sold and not used commercially**.

The bundled build includes the unmodified FBNeo binary from the libretro buildbot, used under its own non-commercial license with notices retained. If a rights holder objects to this packaging, open an issue and it will be removed, leaving only the source and the core-free package.
