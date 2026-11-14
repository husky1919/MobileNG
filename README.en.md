# MobileNG

A Neo Geo arcade frontend for Android. Custom libretro host (C++/JNI) loading the FBNeo core, OpenGL ES rendering, AudioTrack output, on-screen gamepad.

**Ships no games, no BIOS, no emulator core.** You provide them at runtime.

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

`BUNDLE_CORE=1 bash build.sh` embeds `build/core/fbneo_libretro_android.so` into assets (off by default).

## Before running

1. **Core** — grab `fbneo_libretro_android.so.zip` (arm64-v8a) from the libretro buildbot, then "导入核心 (.so)" in the app.
2. **BIOS** — `neogeo.zip` containing `sp-s3.sp1`, `sm1.sm1`, `sfix.sfx`, `000-lo.lo`, … then "导入 BIOS".
3. **Game** — a standard FBNeo romset such as `mslug.zip`; point "游戏列表" at its folder.

## License

MIT for this project's code. Third-party cores and system files keep their own licenses and are not distributed here.
