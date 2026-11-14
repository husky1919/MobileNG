# MobileNG

Android 上跑 Neo Geo 街机游戏的前端。自己写 libretro 宿主（C++/JNI），加载 FBNeo 核心，OpenGL ES 渲染，AudioTrack 出声，屏幕虚拟手柄。

**不含任何游戏、BIOS 或模拟器核心**，运行时自行导入。

---

## 功能

- 加载 libretro 核心（实测 FBNeo），按 zip 短名识别 romset
- OpenGL ES 2.0 渲染：RGB565 / XRGB8888 / 0RGB1555 自适应
- 音频：核心音频回调 → 环形缓冲 → AudioTrack 流式播放
- 虚拟手柄：自动布局（按键塞进 4:3 画面两侧黑边）/ 自定义位置（拖动保存）
- 改键位（16 个 libretro 键位）与两枚组合键（位掩码，多键同按）
- Neo Geo 模式：MVS 欧洲/美国/日本、AES 欧洲/日本、Universe BIOS
- 画面：适应 / 整数倍 / 拉伸，平滑 / 锐利 / 扫描线滤镜，亮度·对比度·饱和度
- 即时存档 / 读档（`retro_serialize`）
- 金手指：按地址写主 RAM，每 60 帧回写
- 帧数 / 速率 HUD
- 运行日志（Java + native 同一文件）
- Debug 版额外提供内存读写的内存编辑器

## 构建

只需要 Termux（或任意 Linux + Android SDK 组件），不用 Gradle：

```bash
pkg install aapt2 d8 apksigner openjdk-17 clang zip unzip
export ANDROID_JAR=/path/to/android.jar     # 或改 build.sh 里的 JAR
bash build.sh            # 正式版  build/MobileNG.apk
bash build.sh debug      # debug 版 build/MobileNG-debug.apk（包名 com.ng.emu.debug）
```

`BUNDLE_CORE=1 bash build.sh` 会把 `build/core/fbneo_libretro_android.so` 打进 assets（默认不打包，运行时导入）。

## 运行前准备

1. **核心**：从 libretro buildbot 取 `fbneo_libretro_android.so.zip`（arm64-v8a），解压得到 `.so`，在 App 里「导入核心 (.so)」。
2. **BIOS**：`neogeo.zip`（含 `sp-s3.sp1`、`sm1.sm1`、`sfix.sfx`、`000-lo.lo` 等），「导入 BIOS」。
3. **游戏**：标准 FBNeo romset，例如 `mslug.zip`，用「游戏列表」指向所在目录即可。

导入的 BIOS 落在 `/sdcard/Android/data/com.ng.emu/files/system/`，核心落在应用私有目录。

## 目录结构

```
app/AndroidManifest.xml          正式版清单
app/AndroidManifest-debug.xml    debug 版清单（com.ng.emu.debug，debuggable）
src/com/ng/emu/MainActivity.java 生命周期、菜单、SAF、音频线程
src/com/ng/emu/PadView.java      虚拟手柄 / 布局编辑
src/com/ng/emu/KeyConfig.java    键位与组合键
src/com/ng/emu/Native.java       JNI 声明
src/com/ng/emu/Logger.java       日志
jni/frontend.cpp                 libretro 宿主：env/video/audio/input/GL/内存
build.sh                         一键构建
```

## 免责

本项目仅包含模拟器前端代码，不附带游戏 ROM、BIOS 或第三方核心。所需文件请自行依所在地法律取得。游戏名称与商标归各自权利人所有。
