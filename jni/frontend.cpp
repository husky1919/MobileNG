// 极简 libretro 前端 —— 用 FBNeo 核心跑 Neo Geo
#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <map>
#include <algorithm>
#include <cmath>
#include <unistd.h>
#include <cstdio>
#include <cstdarg>
#include <ctime>

// ---------------- libretro 最小 ABI ----------------
#define RETRO_API_VERSION 1
enum { RETRO_PIXEL_FORMAT_0RGB1555 = 0, RETRO_PIXEL_FORMAT_XRGB8888 = 1, RETRO_PIXEL_FORMAT_RGB565 = 2 };
enum { RETRO_DEVICE_NONE = 0, RETRO_DEVICE_JOYPAD = 1 };
enum {
    JB = 0, JY = 1, JSELECT = 2, JSTART = 3, JUP = 4, JDOWN = 5, JLEFT = 6, JRIGHT = 7,
    JA = 8, JX = 9, JL = 10, JR = 11, JL2 = 12, JR2 = 13, JL3 = 14, JR3 = 15, JMASK = 256
};
struct retro_variable { const char *key, *value; };
struct retro_message { const char *msg; unsigned frames; };
struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };

typedef bool (*retro_environment_t)(unsigned, void *);
typedef void (*retro_video_refresh_t)(const void *, unsigned, unsigned, size_t);
typedef void (*retro_audio_sample_t)(int16_t, int16_t);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *, size_t);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned, unsigned, unsigned, unsigned);

// ---------------- 日志 ----------------
static void (*p_log)(int, const char *, const char *, ...) = nullptr;
static FILE *g_logf = nullptr;
static std::mutex g_logMtx;

static void logLine(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (p_log) p_log(4, "NGFE", "%s", buf);
    if (g_logf) {
        std::lock_guard<std::mutex> lk(g_logMtx);
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tm{};
        localtime_r(&ts.tv_sec, &tm);
        fprintf(g_logf, "%02d:%02d:%02d.%03ld [native] %s\n",
                tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000L, buf);
        fflush(g_logf);
    }
}
#define LOGI(...) logLine(__VA_ARGS__)

// ---------------- core 入口 ----------------
static void (*p_set_environment)(retro_environment_t);
static void (*p_set_video_refresh)(retro_video_refresh_t);
static void (*p_set_audio_sample)(retro_audio_sample_t);
static void (*p_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void (*p_set_input_poll)(retro_input_poll_t);
static void (*p_set_input_state)(retro_input_state_t);
static void (*p_init)(void);
static void (*p_deinit)(void);
static void (*p_get_system_av_info)(retro_system_av_info *);
static void (*p_set_controller_port_device)(unsigned, unsigned);
static void (*p_reset)(void);
static void (*p_run)(void);
static bool (*p_load_game)(const retro_game_info *);
static void (*p_unload_game)(void);

static std::string g_systemDir, g_saveDir, g_romPath;

static std::map<std::string, std::string> g_vars;   // core option 当前值
static std::map<std::string, std::string> g_varDef; // 默认值（核心注册时记下）
static std::map<std::string, std::string> g_opts;   // 全部候选值（| 分隔）
static std::string g_neomode;                 // 用户选定的 NeoGeo 模式（空=默认 UNIBIOS）

static int   g_scaleMode = 0;   // 0 适应 1 整数倍 2 铺满
static int   g_filter    = 0;   // 0 平滑 1 锐利 2 扫描线
static float g_bright    = 1.0f, g_contrast = 1.0f, g_sat = 1.0f;
static int   g_uB = -1, g_uC = -1, g_uS = -1, g_uScan = -1;

static std::string trim(const std::string &s) {
    size_t a = s.find_first_not_of(" \t");
    size_t b = s.find_last_not_of(" \t");
    return a == std::string::npos ? "" : s.substr(a, b - a + 1);
}

// 核心注册选项时记下默认值（格式 "Label; 选项1|选项2|..."）
static bool setVars(const retro_variable *v) {
    if (!v) return false;
    for (; v->key; v++) {
        std::string val = v->value ? v->value : "";
        size_t sc = val.find(';');
        if (sc != std::string::npos) val = val.substr(sc + 1);
        size_t bar = val.find('|');
        if (bar != std::string::npos) val = val.substr(0, bar);
        val = trim(val);
        g_varDef[v->key] = val;
        { std::string all = v->value ? v->value : "";
          size_t sc2 = all.find(';');
          if (sc2 != std::string::npos) all = all.substr(sc2 + 1);
          g_opts[v->key] = trim(all); }
        // NeoGeo 模式：默认锁 UNIBIOS，唯一合法值集为 DIPSWITCH|UNIBIOS
        if (std::string(v->key) == "fbneo-neogeo-mode") val = g_neomode.empty() ? "UNIBIOS" : g_neomode;
        if (g_vars.find(v->key) == g_vars.end()) g_vars[v->key] = val;
    }
    return true;
}

static bool getVar(retro_variable *v) {
    if (!v) return false;
    auto it = g_vars.find(v->key);
    if (it == g_vars.end()) { v->value = nullptr; return false; }
    v->value = it->second.c_str();
    return true;
}

static int g_pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static bool g_inited = false, g_loaded = false;
static std::atomic<bool> g_running{false};
static std::atomic<float> g_fpsNow{0.0f};
static std::atomic<long long> g_frames{0};
static std::thread g_thread;
static retro_system_av_info g_av{};
static void *g_core = nullptr;

struct retro_mem_desc { uint64_t flags; void *ptr; size_t offset, start, select, disconnect, len; const char *addrspace; };
struct retro_mem_map { const retro_mem_desc *descriptors; unsigned num_descriptors; };
static retro_mem_desc g_desc[64];
static unsigned g_ndesc = 0;

static void *(*p_get_mem_data)(unsigned) = nullptr;
static size_t (*p_get_mem_size)(unsigned) = nullptr;
static unsigned char *g_sysram = nullptr;
static size_t g_sysramSize = 0;
static const size_t SYS_RAM_BASE = 0x100000;

static size_t (*p_serialize_size)(void) = nullptr;
static bool (*p_serialize)(void *, size_t) = nullptr;
static bool (*p_unserialize)(const void *, size_t) = nullptr;

// 金手指：每 60 帧补写一次
struct CheatEntry { size_t addr; unsigned char val[8]; int len; };
static CheatEntry g_cheats[64];
static int g_ncheats = 0;

static bool resolveMem(size_t a, size_t len, unsigned char **out) {
    for (unsigned i = 0; i < g_ndesc; i++) {
        auto &d = g_desc[i];
        if (!d.ptr || !d.len) continue;
        if (a >= d.start && a + len <= d.start + d.len) {
            *out = (unsigned char *) d.ptr + (a - d.start);
            return true;
        }
    }
    if (g_sysram && a >= SYS_RAM_BASE && a + len <= SYS_RAM_BASE + g_sysramSize) {
        *out = g_sysram + (a - SYS_RAM_BASE);
        return true;
    }
    return false;
}

static void applyCheats() {
    for (int c = 0; c < g_ncheats; c++) {
        unsigned char *dst = nullptr;
        if (resolveMem(g_cheats[c].addr, (size_t) g_cheats[c].len, &dst))
            for (int k = 0; k < g_cheats[c].len; k++) dst[k] = g_cheats[c].val[k];
    }
}

// ---------------- 视频 ----------------
static std::mutex g_vidMtx;
static std::vector<uint16_t> g_front, g_back;
static unsigned g_w = 0, g_h = 0;
static std::atomic<bool> g_hasNew{false};

static void videoCb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !w || !h) return;
    std::lock_guard<std::mutex> lk(g_vidMtx);
    if (w != g_w || h != g_h) {
        LOGI("video %ux%u pitch=%zu fmt=%d", w, h, pitch, g_pixfmt);
        g_w = w; g_h = h;
        g_front.assign((size_t) w * h, 0);
        g_back.assign((size_t) w * h, 0);
    }
    const uint8_t *src = (const uint8_t *) data;
    uint16_t *dst = g_back.data();
    if (g_pixfmt == RETRO_PIXEL_FORMAT_RGB565) {
        for (unsigned y = 0; y < h; y++)
            memcpy(dst + (size_t) y * w, src + y * pitch, (size_t) w * 2);
    } else if (g_pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) {
        for (unsigned y = 0; y < h; y++) {
            const uint32_t *row = (const uint32_t *) (src + y * pitch);
            uint16_t *o = dst + (size_t) y * w;
            for (unsigned x = 0; x < w; x++) {
                uint32_t c = row[x];
                o[x] = (uint16_t) (((c >> 8) & 0xF800) | ((c >> 5) & 0x07E0) | ((c >> 3) & 0x001F));
            }
        }
    } else {
        for (unsigned y = 0; y < h; y++) {
            const uint16_t *row = (const uint16_t *) (src + y * pitch);
            uint16_t *o = dst + (size_t) y * w;
            for (unsigned x = 0; x < w; x++) {
                uint16_t c = row[x];
                unsigned g5 = (c >> 5) & 31;
                o[x] = (uint16_t) ((((c >> 10) & 31) << 11) | (((g5 << 1) | (g5 >> 4)) << 5) | (c & 31));
            }
        }
    }
    std::swap(g_front, g_back);
    g_hasNew = true;
}

// ---------------- 音频 ----------------
static const unsigned RING_PAIRS = 65536;
static int16_t g_ring[RING_PAIRS * 2];
static std::atomic<uint32_t> g_aw{0}, g_ar{0};

static size_t audioBatchCb(const int16_t *data, size_t frames) {
    uint32_t w = g_aw.load(std::memory_order_relaxed);
    uint32_t r = g_ar.load(std::memory_order_acquire);
    for (size_t i = 0; i < frames && (w - r) < RING_PAIRS; i++, w++) {
        uint32_t i2 = (w % RING_PAIRS) * 2;
        g_ring[i2] = data[i * 2];
        g_ring[i2 + 1] = data[i * 2 + 1];
    }
    g_aw.store(w, std::memory_order_release);
    return frames;
}
static void audioSampleCb(int16_t l, int16_t r) { int16_t b[2] = {l, r}; audioBatchCb(b, 1); }

// ---------------- 输入 ----------------
static std::atomic<uint32_t> g_btn[4];

// ---------------- 联机（蓝牙，输入锁步） ----------------
static std::atomic<int> g_netMode{0};          // 0=关 1=主机 2=客户端
static std::atomic<int> g_padPort{0};          // 本机控制的玩家口
static std::atomic<uint32_t> g_txFrame{0};
static std::atomic<uint32_t> g_txMask{0};
static std::atomic<bool> g_txDirty{false};
static std::atomic<uint32_t> g_rxFrame[30];
static uint32_t g_rxMask[30];
static std::atomic<uint32_t> g_rxLatest{0};
static uint32_t g_lastRemote = 0;
static std::atomic<int> g_netDelay{4};  // 默认 4 帧，可运行时改
static void setNetDelay(int d) { if (d < 0) d = 0; if (d > 30) d = 30; g_netDelay.store(d); }
static const int  NET_RING  = 30;   // 30 帧环形缓冲（Kawaks 同款）
static std::atomic<int> g_netPause{0}; // 1=暂停模拟，仅同步输入
static std::atomic<int> g_gamePause{0}; // 1=游戏暂停（画面定格）
static std::atomic<uint32_t> g_txRing[30];
static std::atomic<uint32_t> g_txLatest{0};
static uint32_t g_txSent = 0;
static std::atomic<int> g_restartReq{0};
static std::atomic<int> g_restartDone{0};

static void syncNet() {
    if (!g_netMode.load()) return;
    // 游戏暂停时不同步
    if (g_gamePause.load()) return;
    // 如果暂停，只处理输入同步，不推进游戏帧号
    if (g_netPause.load()) {
        // 仍然从 rxFrame 读取对端输入，用于锁步，但不产生新帧号
        uint32_t f = g_txFrame.load(); // 保持不变
        uint32_t m = g_btn[g_padPort.load()].load();
        { unsigned i = f % (unsigned) NET_RING; g_txRing[i].store(m); }
        g_txMask.store(m);
        g_txDirty.store(true);
        uint32_t delay = g_netDelay.load();
        uint32_t want = f > (uint32_t) delay ? f - (uint32_t) delay : 0;
        if (f > (uint32_t) delay) {
            for (int i = 0; i < 50 && g_netMode.load() && g_running; i++) {
                if (g_rxFrame[want % (unsigned) NET_RING].load() == want) break;
                usleep(1000);
            }
        }
        { unsigned wi = want % (unsigned) NET_RING;
          uint32_t mask = (g_rxFrame[wi].load() == want) ? g_rxMask[wi] : g_lastRemote;
          g_lastRemote = mask;
          g_btn[1 - g_padPort.load()].store(mask);
        }
        return;
    }
    uint32_t f = g_txFrame.load() + 1;
    g_txFrame.store(f);    uint32_t m = g_btn[g_padPort.load()].load();
    g_txMask.store(m);
    { unsigned i = f % (unsigned) NET_RING; g_txRing[i].store(m); }
    g_txLatest.store(f);
    g_txDirty.store(true);
    int delay = g_netDelay.load();
    uint32_t want = f > (uint32_t) delay ? f - (uint32_t) delay : 0;
    if (f > (uint32_t) delay) {
        // 严格等：最多 50ms，超了直接断开
        for (int i = 0; i < 50 && g_netMode.load() && g_running; i++) {
            if (g_rxFrame[want % (unsigned) NET_RING].load() == want) break;
            usleep(1000);
        }
        // 超时断开
        if (g_netMode.load() && g_running && g_rxFrame[want % (unsigned) NET_RING].load() != want) {
            g_netMode.store(0);
            LOGI("net disconnect: frame %u timeout", want);
            return;
        }
    }
    { unsigned wi = want % (unsigned) NET_RING;
      uint32_t mask = (g_rxFrame[wi].load() == want) ? g_rxMask[wi] : 0;
      g_btn[1 - g_padPort.load()].store(mask);
    }
}
static void inputPollCb(void) {}
static int16_t inputStateCb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port >= 4 || index != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    uint32_t m = g_btn[port].load(std::memory_order_relaxed);
    if (id == JMASK) return (int16_t) (m & 0xFFFF);
    if (id > 15) return 0;
    return (int16_t) ((m >> id) & 1);
}

// ---------------- 环境 ----------------
static bool envCb(unsigned cmd, void *data) {
    switch (cmd & 0xFFFF) {
        case 10: // SET_PIXEL_FORMAT
            if (!data) return false;
            g_pixfmt = *(const int *) data;
            return true;
        case 9:  *(const char **) data = g_systemDir.c_str(); return true; // SYSTEM_DIRECTORY
        case 31: *(const char **) data = g_saveDir.c_str();   return true; // SAVE_DIRECTORY
        case 30: *(const char **) data = g_systemDir.c_str(); return true; // CORE_ASSETS
        case 3:  *(bool *) data = true; return true;  // CAN_DUPE
        case 2:  *(bool *) data = true; return true;  // OVERSCAN
        case 8: case 11: case 18: case 35: case 53: case 54: case 55:
            return true;
        case 6: {
            auto *m = (retro_message *) data;
            if (m && m->msg) LOGI("core: %s", m->msg);
            return true;
        }
        case 15: return getVar((retro_variable *) data);   // GET_VARIABLE
        case 16: return setVars((const retro_variable *) data); // SET_VARIABLES
        case 17: *(bool *) data = false; return true;
        case 39: *(unsigned *) data = 0; return true;   // LANGUAGE
        case 52: *(unsigned *) data = 0; return true;   // CORE_OPTIONS_VERSION
        case 51: return true;                           // INPUT_BITMASKS
        case 61: *(unsigned *) data = 4; return true;   // INPUT_MAX_USERS
        case 36: {                                      // SET_MEMORY_MAPS
            auto *m = (retro_mem_map *) data;
            if (m) {
                g_ndesc = m->num_descriptors > 64 ? 64 : m->num_descriptors;
                for (unsigned i = 0; i < g_ndesc; i++) g_desc[i] = m->descriptors[i];
                LOGI("memory maps: %u", g_ndesc);
            }
            return true;
        }
        case 7:  g_running = false; return true;        // SHUTDOWN
        default: return false;
    }
}

// ---------------- 运行线程 ----------------
static double nowSec() {
    using namespace std::chrono;
    return duration<double>(steady_clock::now().time_since_epoch()).count();
}

static void runLoop() {
    double fps = g_av.timing.fps > 1.0 ? g_av.timing.fps : 60.0;
    double frameTime = 1.0 / fps, next = nowSec();
    int tick = 0, stat = 0, win = 0;
    double t0 = nowSec(), tWin = nowSec();
    while (g_running) {
        // 游戏暂停：画面定格，跳过帧更新
        if (g_restartReq.exchange(0)) {
            p_reset();
            g_btn[0].store(0); g_btn[1].store(0);
            g_txFrame.store(0); g_txMask.store(0); g_txDirty.store(false);
            g_txLatest.store(0); g_txSent = 0;
            for (int i = 0; i < 30; i++) { g_rxFrame[i].store(0xFFFFFFFFu); g_txRing[i].store(0); }
            g_rxLatest.store(0); g_lastRemote = 0;
            LOGI("reset done in core thread");
            g_restartDone.fetch_add(1);
        }
        if (g_gamePause.load()) { next = nowSec(); usleep(2000); continue; }  // 暂停：画面定格
        if (g_ncheats && (tick++ % 60) == 0) applyCheats();
        syncNet();
        p_run();
        g_frames.fetch_add(1, std::memory_order_relaxed);
        if (++win >= 20) {
            double tw = nowSec();
            g_fpsNow.store((float) (win / (tw - tWin)), std::memory_order_relaxed);
            win = 0; tWin = tw;
        }
        if (++stat >= 300) {
            double tn = nowSec();
            LOGI("avg fps %.2f  (%.0f%% 实时)", stat / (tn - t0), 100.0 * stat / (tn - t0) / (g_av.timing.fps > 1 ? g_av.timing.fps : 60.0));
            stat = 0; t0 = tn;
        }
        next += frameTime;
        double d = next - nowSec();
        if (d > 0) usleep((useconds_t) (d * 1e6));
        else if (d < -0.25) next = nowSec();
    }
}

// ---------------- GL（全部 dlsym，不依赖编译期头文件） ----------------
#define GL_TEXTURE_2D 0x0DE1
#define GL_RGB 0x1907
#define GL_UNSIGNED_SHORT_5_6_5 0x8363
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_LINEAR 0x2601
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_ARRAY_BUFFER 0x8892
#define GL_STREAM_DRAW 0x88E0
#define GL_FLOAT 0x1406
#define GL_TRIANGLE_STRIP 0x0005
#define GL_TEXTURE0 0x84C0

static void (*glViewport)(int, int, int, int);
static void (*glClearColor)(float, float, float, float);
static void (*glClear)(unsigned);
static void (*glBindTexture)(unsigned, unsigned);
static void (*glTexImage2D)(unsigned, int, int, int, int, int, unsigned, unsigned, const void *);
static void (*glTexSubImage2D)(unsigned, int, int, int, int, int, unsigned, unsigned, const void *);
static void (*glTexParameteri)(unsigned, unsigned, int);
static void (*glGenTextures)(int, unsigned *);
static void (*glGenBuffers)(int, unsigned *);
static unsigned (*glCreateShader)(unsigned);
static void (*glShaderSource)(unsigned, int, const char *const *, const int *);
static void (*glCompileShader)(unsigned);
static unsigned (*glCreateProgram)(void);
static void (*glAttachShader)(unsigned, unsigned);
static void (*glLinkProgram)(unsigned);
static int (*glGetAttribLocation)(unsigned, const char *);
static int (*glGetUniformLocation)(unsigned, const char *);
static void (*glUseProgram)(unsigned);
static void (*glBindBuffer)(unsigned, unsigned);
static void (*glBufferData)(unsigned, long, const void *, unsigned);
static void (*glEnableVertexAttribArray)(unsigned);
static void (*glVertexAttribPointer)(unsigned, int, unsigned, unsigned char, int, const void *);
static void (*glUniform1i)(int, int);
static void (*glUniform1f)(int, float);
static void (*glActiveTexture)(unsigned);
static void (*glDrawArrays)(unsigned, int, int);

static unsigned g_tex = 0, g_prog = 0, g_vbo = 0;
static int g_aPos = -1, g_aUV = -1, g_uTex = -1;
static unsigned g_texW = 0, g_texH = 0;

#define GLSYM(n) do { *(void **) (&gl##n) = dlsym(gles, "gl" #n); if (!gl##n) LOGI("missing gl%s", #n); } while (0);

static void loadGL() {
    void *gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_GLOBAL);
    if (!gles) { LOGI("libGLESv2 load failed"); return; }
    GLSYM(Viewport) GLSYM(ClearColor) GLSYM(Clear) GLSYM(BindTexture)
    GLSYM(TexImage2D) GLSYM(TexSubImage2D) GLSYM(TexParameteri)
    GLSYM(GenTextures) GLSYM(GenBuffers) GLSYM(CreateShader) GLSYM(ShaderSource)
    GLSYM(CompileShader) GLSYM(CreateProgram) GLSYM(AttachShader) GLSYM(LinkProgram)
    GLSYM(GetAttribLocation) GLSYM(GetUniformLocation) GLSYM(UseProgram)
    GLSYM(BindBuffer) GLSYM(BufferData) GLSYM(EnableVertexAttribArray)
    GLSYM(VertexAttribPointer) GLSYM(Uniform1i) GLSYM(Uniform1f) GLSYM(ActiveTexture) GLSYM(DrawArrays)
}

static const char *VS =
    "attribute vec2 p; attribute vec2 uv; varying vec2 v;\n"
    "void main(){ v = uv; gl_Position = vec4(p, 0.0, 1.0); }\n";
static const char *FS =
    "precision mediump float;\n"
    "varying vec2 v;\n"
    "uniform sampler2D t;\n"
    "uniform float uB, uC, uS, uScan;\n"
    "void main(){\n"
    "  vec3 c = texture2D(t, v).rgb;\n"
    "  if (uScan > 0.5) {\n"
    "    float y = mod(floor(gl_FragCoord.y), 2.0);\n"
    "    c *= (y < 1.0) ? 0.76 : 1.0;\n"
    "  }\n"
    "  c = (c - 0.5) * uC + 0.5 + (uB - 1.0);\n"
    "  float g = dot(c, vec3(0.299, 0.587, 0.114));\n"
    "  c = mix(vec3(g), c, uS);\n"
    "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n"
    "}\n";

static unsigned compileShader(unsigned type, const char *src) {
    unsigned s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    return s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_glInit(JNIEnv *, jclass) {
    loadGL();
    if (!glCreateProgram) return;
    unsigned vs = compileShader(GL_VERTEX_SHADER, VS), fs = compileShader(GL_FRAGMENT_SHADER, FS);
    g_prog = glCreateProgram();
    glAttachShader(g_prog, vs);
    glAttachShader(g_prog, fs);
    glLinkProgram(g_prog);
    g_aPos = glGetAttribLocation(g_prog, "p");
    g_aUV = glGetAttribLocation(g_prog, "uv");
    g_uTex = glGetUniformLocation(g_prog, "t");
    g_uB = glGetUniformLocation(g_prog, "uB");
    g_uC = glGetUniformLocation(g_prog, "uC");
    g_uS = glGetUniformLocation(g_prog, "uS");
    g_uScan = glGetUniformLocation(g_prog, "uScan");

    glGenTextures(1, &g_tex);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenBuffers(1, &g_vbo);
    // GL 上下文重建后纹理是空的，必须作废旧尺寸，否则会走 glTexSubImage2D 写空纹理 -> 黑屏
    g_texW = 0; g_texH = 0;
    g_hasNew = true;
    LOGI("glInit done (context %s)", g_tex ? "created" : "?");
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_glDraw(JNIEnv *, jclass, jint vw, jint vh) {
    if (!glDrawArrays) return;
    glViewport(0, 0, vw, vh);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);

    if (g_hasNew.load()) {
        std::lock_guard<std::mutex> lk(g_vidMtx);
        unsigned w = g_w, h = g_h;
        if (w && h && !g_front.empty()) {
            glBindTexture(GL_TEXTURE_2D, g_tex);
            if (w != g_texW || h != g_texH) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, (int) w, (int) h, 0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, g_front.data());
                g_texW = w; g_texH = h;
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, (int) w, (int) h, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, g_front.data());
            }
            g_hasNew = false;
        }
    }
    if (!g_texW) return;   // 还没有第一帧：保留黑屏，别闪

    float sx = 1.0f, sy = 1.0f;
    if (g_scaleMode == 2) {
        // 铺满整屏
    } else if (g_scaleMode == 1) {
        // 整数倍缩放
        float s2 = std::min((float) vw / (float) g_texW, (float) vh / (float) g_texH);
        s2 = std::floor(s2);
        if (s2 < 1.0f) s2 = 1.0f;
        sx = (g_texW * s2) / (float) vw;
        sy = (g_texH * s2) / (float) vh;
    } else {
        float contentAspect = (float) g_texW / (float) g_texH;
        float viewAspect = (float) vw / (float) vh;
        if (viewAspect > contentAspect) sx = contentAspect / viewAspect;
        else                            sy = viewAspect / contentAspect;
    }

    float verts[] = {
        -sx, -sy, 0.0f, 1.0f,
         sx, -sy, 1.0f, 1.0f,
        -sx,  sy, 0.0f, 0.0f,
         sx,  sy, 1.0f, 0.0f,
    };

    glUseProgram(g_prog);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, (long) sizeof(verts), verts, GL_STREAM_DRAW);
    glEnableVertexAttribArray((unsigned) g_aPos);
    glVertexAttribPointer((unsigned) g_aPos, 2, GL_FLOAT, 0, 16, (void *) 0);
    glEnableVertexAttribArray((unsigned) g_aUV);
    glVertexAttribPointer((unsigned) g_aUV, 2, GL_FLOAT, 0, 16, (void *) 8);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, g_filter == 1 ? 0x2600 : GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, g_filter == 1 ? 0x2600 : GL_LINEAR);
    glUniform1i(g_uTex, 0);
    glUniform1f(g_uB, g_bright);
    glUniform1f(g_uC, g_contrast);
    glUniform1f(g_uS, g_sat);
    glUniform1f(g_uScan, g_filter == 2 ? 1.0f : 0.0f);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

// ---------------- JNI 入口 ----------------
extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_setLogFile(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (g_logf) { fclose(g_logf); g_logf = nullptr; }
    g_logf = fopen(p, "a");
    env->ReleaseStringUTFChars(path, p);
    LOGI("==== native 日志已挂接 ====");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ng_emu_Native_init(JNIEnv *env, jclass, jstring corePath, jstring sysDir, jstring saveDir) {
    if (!p_log) {
        void *h = dlopen("liblog.so", RTLD_NOW);
        if (h) *(void **) (&p_log) = dlsym(h, "__android_log_print");
    }
    const char *cp = env->GetStringUTFChars(corePath, nullptr);
    g_core = dlopen(cp, RTLD_NOW | RTLD_LOCAL);
    const char *err = g_core ? "" : dlerror();
    env->ReleaseStringUTFChars(corePath, cp);
    if (!g_core) { LOGI("dlopen core failed: %s", err ? err : "?"); return -1; }

    const char *s1 = env->GetStringUTFChars(sysDir, nullptr);  g_systemDir = s1; env->ReleaseStringUTFChars(sysDir, s1);
    const char *s2 = env->GetStringUTFChars(saveDir, nullptr); g_saveDir = s2;   env->ReleaseStringUTFChars(saveDir, s2);

#define SYM(n) *(void **) (&p_##n) = dlsym(g_core, "retro_" #n); if (!p_##n) return -2;
    SYM(set_environment) SYM(set_video_refresh) SYM(set_audio_sample)
    SYM(set_audio_sample_batch) SYM(set_input_poll) SYM(set_input_state)
    SYM(init) SYM(deinit) SYM(get_system_av_info) SYM(set_controller_port_device)
    SYM(reset) SYM(run) SYM(load_game) SYM(unload_game)
#undef SYM

    *(void **) (&p_get_mem_data) = dlsym(g_core, "retro_get_memory_data");
    *(void **) (&p_get_mem_size) = dlsym(g_core, "retro_get_memory_size");
    *(void **) (&p_serialize_size) = dlsym(g_core, "retro_serialize_size");
    *(void **) (&p_serialize)      = dlsym(g_core, "retro_serialize");
    *(void **) (&p_unserialize)    = dlsym(g_core, "retro_unserialize");

    p_set_environment(envCb);
    p_set_video_refresh(videoCb);
    p_set_audio_sample(audioSampleCb);
    p_set_audio_sample_batch(audioBatchCb);
    p_set_input_poll(inputPollCb);
    p_set_input_state(inputStateCb);
    p_init();
    g_inited = true;
    LOGI("core init ok");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ng_emu_Native_loadRom(JNIEnv *env, jclass, jstring rom) {
    if (!g_inited) return -1;
    const char *r = env->GetStringUTFChars(rom, nullptr);
    g_romPath = r;
    env->ReleaseStringUTFChars(rom, r);

    retro_game_info gi{};
    gi.path = g_romPath.c_str();
    if (!p_load_game(&gi)) { LOGI("load_game failed"); return -2; }
    p_get_system_av_info(&g_av);
    if (p_get_mem_data) {
        g_sysram = (unsigned char *) p_get_mem_data(2);   // RETRO_MEMORY_SYSTEM_RAM
        g_sysramSize = p_get_mem_size ? p_get_mem_size(2) : 0;
        LOGI("system RAM %p size=%zu (base %06zX)", (void *) g_sysram, g_sysramSize, SYS_RAM_BASE);
    }
    for (unsigned i = 0; i < 2; i++) p_set_controller_port_device(i, RETRO_DEVICE_JOYPAD);
    g_loaded = true;
    LOGI("loaded, %.2ffps %.0fHz", g_av.timing.fps, g_av.timing.sample_rate);
    return (jint) (g_av.timing.sample_rate > 0 ? g_av.timing.sample_rate : 48000);
}

extern "C" JNIEXPORT void JNICALL Java_com_ng_emu_Native_start(JNIEnv *, jclass) {
    if (!g_loaded || g_running) return;
    g_running = true;
    g_thread = std::thread(runLoop);
}

extern "C" JNIEXPORT void JNICALL Java_com_ng_emu_Native_stop(JNIEnv *, jclass) {
    g_running = false;
    if (g_thread.joinable()) g_thread.join();
}

extern "C" JNIEXPORT void JNICALL Java_com_ng_emu_Native_reset(JNIEnv *, jclass) {
    if (!g_loaded) return;
    if (g_running) g_restartReq.store(1);
    else p_reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_setButton(JNIEnv *, jclass, jint port, jint id, jboolean pressed) {
    if (port < 0 || port > 3 || id < 0 || id > 15) return;
    if (g_netMode.load() && port == 0) port = g_padPort.load();
    uint32_t m = g_btn[port].load(std::memory_order_relaxed);
    if (pressed) m |= (1u << id); else m &= ~(1u << id);
    g_btn[port].store(m, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_netEnable(JNIEnv *, jclass, jint mode, jint localPort) {
    g_txFrame.store(0);
    g_rxLatest.store(0);
    g_lastRemote = 0;
    g_txLatest.store(0); g_txSent = 0;
    for (int i = 0; i < 30; i++) { g_rxFrame[i].store(0); g_rxMask[i] = 0; g_txRing[i].store(0); }
    g_padPort.store(localPort ? 1 : 0);
    g_netMode.store(mode);
    if (!mode) { g_btn[0].store(0); g_btn[1].store(0); }
    LOGI("net mode=%d pad=%d", mode, localPort ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_netRestart(JNIEnv *, jclass) {
    if (g_loaded) {
        int want = g_restartDone.load() + 1;
        if (g_running) {
            g_restartReq.store(1);
            for (int i = 0; i < 3000 && g_running; i++) {
                if (g_restartDone.load() >= want) break;
                usleep(1000);
            }
        } else {
            p_reset();
            g_restartDone.fetch_add(1);
        }
    }
    g_btn[0].store(0); g_btn[1].store(0);
    g_txFrame.store(0); g_txMask.store(0); g_txDirty.store(false);
    g_txLatest.store(0); g_txSent = 0;
    for (int i = 0; i < 30; i++) { g_rxFrame[i].store(0xFFFFFFFFu); g_txRing[i].store(0); }
    g_rxLatest.store(0);
    g_lastRemote = 0;
    LOGI("net restart");
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_netPause(JNIEnv *, jclass, jint on) {
    g_gamePause.store(on ? 1 : 0);
    if (on) g_ar.store(g_aw.load());   // 清音频环，恢复后不重播残音
    LOGI("pause=%d", on ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_gamePause(JNIEnv *, jclass, jint on) {
    g_gamePause.store(on ? 1 : 0);
    LOGI("game pause=%d", on ? 1 : 0);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ng_emu_Native_netPoll(JNIEnv *, jclass) {
    if (!g_netMode.load()) return -1;
    uint32_t latest = g_txLatest.load();
    if (g_txSent >= latest) return -1;
    uint32_t f = g_txSent + 1;
    uint32_t m = g_txRing[f % (unsigned) NET_RING].load();
    g_txSent = f;
    return (jlong) (((uint64_t) f << 32) | m);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_netDeliver(JNIEnv *, jclass, jint frame, jint mask) {
    uint32_t f = (uint32_t) frame;
    { unsigned i = f % (unsigned) NET_RING;
      g_rxFrame[i].store(f);
      g_rxMask[i] = (uint32_t) mask; }
    if (f > g_rxLatest.load()) g_rxLatest.store(f);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ng_emu_Native_netState(JNIEnv *, jclass) {
    return g_netMode.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_setNetDelay(JNIEnv *, jclass, jint d) {
    setNetDelay(d);
    LOGI("net delay = %d frames", d);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ng_emu_Native_readAudio(JNIEnv *env, jclass, jshortArray buf, jint frames) {
    static int16_t tmp[RING_PAIRS * 2];
    uint32_t r = g_ar.load(std::memory_order_relaxed);
    uint32_t w = g_aw.load(std::memory_order_acquire);
    uint32_t avail = w - r;
    if (avail > (uint32_t) frames) avail = (uint32_t) frames;
    if (!avail) return 0;
    for (uint32_t i = 0; i < avail; i++) {
        uint32_t i2 = ((r + i) % RING_PAIRS) * 2;
        tmp[i * 2] = g_ring[i2];
        tmp[i * 2 + 1] = g_ring[i2 + 1];
    }
    g_ar.store(r + avail, std::memory_order_release);
    env->SetShortArrayRegion(buf, 0, (jsize) (avail * 2), tmp);
    return (jint) avail;
}

extern "C" JNIEXPORT void JNICALL Java_com_ng_emu_Native_deinit(JNIEnv *, jclass) {
    g_running = false;
    if (g_thread.joinable()) g_thread.join();
    if (g_inited) {
        if (g_loaded) p_unload_game();
        p_deinit();
        g_inited = g_loaded = false;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ng_emu_Native_saveState(JNIEnv *env, jclass, jstring path) {
    if (!g_loaded || !p_serialize || !p_serialize_size) return -1;
    size_t n = p_serialize_size();
    if (!n) return -2;
    std::vector<unsigned char> buf(n);
    if (!p_serialize(buf.data(), n)) return -3;
    const char *p = env->GetStringUTFChars(path, nullptr);
    FILE *f = fopen(p, "wb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) return -4;
    fwrite(buf.data(), 1, n, f);
    fclose(f);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ng_emu_Native_loadState(JNIEnv *env, jclass, jstring path) {
    if (!g_loaded || !p_unserialize) return -1;
    const char *p = env->GetStringUTFChars(path, nullptr);
    FILE *f = fopen(p, "rb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) return -2;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<unsigned char> buf((size_t) n);
    size_t rd = fread(buf.data(), 1, (size_t) n, f);
    fclose(f);
    if ((long) rd != n) return -3;
    return p_unserialize(buf.data(), (size_t) n) ? 0 : -4;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ng_emu_Native_setCheats(JNIEnv *env, jclass, jstring text) {
    g_ncheats = 0;
    if (!text) return 0;
    const char *t = env->GetStringUTFChars(text, nullptr);
    std::string s(t);
    env->ReleaseStringUTFChars(text, t);
    size_t pos = 0;
    while (pos < s.size() && g_ncheats < 64) {
        size_t e = s.find('\n', pos);
        if (e == std::string::npos) e = s.size();
        std::string line = s.substr(pos, e - pos);
        pos = e + 1;
        // 去掉注释
        size_t hash = line.find_first_of("#;");
        if (hash != std::string::npos) line = line.substr(0, hash);
        unsigned long addr = 0, val = 0; int len = 1;
        int got = sscanf(line.c_str(), "%lx %lx %d", &addr, &val, &len);
        if (got >= 2) {
            if (len < 1) len = 1;
            if (len > 8) len = 8;
            CheatEntry &c = g_cheats[g_ncheats++];
            c.addr = (size_t) addr;
            c.len = len;
            for (int k = 0; k < len; k++) c.val[k] = (unsigned char) ((val >> (8 * (len - 1 - k))) & 0xFF);
        }
    }
    if (g_ncheats) applyCheats();
    return g_ncheats;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_setVideo(JNIEnv *, jclass, jint scaleMode, jint filter,
                               jfloat bright, jfloat contrast, jfloat sat) {
    g_scaleMode = scaleMode;
    g_filter = filter;
    g_bright = bright;
    g_contrast = contrast;
    g_sat = sat;
    g_texW = 0;  // 逼下一次重画（过滤参数变化）
}

extern "C" JNIEXPORT void JNICALL
Java_com_ng_emu_Native_setCoreVariable(JNIEnv *env, jclass, jstring key, jstring value) {
    const char *k = env->GetStringUTFChars(key, nullptr);
    const char *v = env->GetStringUTFChars(value, nullptr);
    g_vars[k] = v;
    if (std::string(k) == "fbneo-neogeo-mode") g_neomode = v;
    LOGI("core option %s = %s", k, v);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(value, v);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ng_emu_Native_getDriver(JNIEnv *env, jclass) {
    const std::string pre = "fbneo-dipswitch-";
    for (const auto &kv : g_varDef) {
        if (kv.first.compare(0, pre.size(), pre) == 0) {
            std::string rest = kv.first.substr(pre.size());
            size_t d = rest.find('-');
            if (d != std::string::npos) rest = rest.substr(0, d);
            return env->NewStringUTF(rest.c_str());
        }
    }
    return env->NewStringUTF("");
}

// 导出所有 DIP 开关项：每行 "key\t默认值\t候选1|候选2|..."
extern "C" JNIEXPORT jstring JNICALL
Java_com_ng_emu_Native_getDipList(JNIEnv *env, jclass) {
    const std::string pre = "fbneo-dipswitch-";
    std::string out;
    for (const auto &kv : g_varDef) {
        if (kv.first.compare(0, pre.size(), pre) != 0) continue;
        out += kv.first;
        out += '\t';
        auto it = g_vars.find(kv.first);
        out += (it == g_vars.end() ? kv.second : it->second);
        out += '\t';
        out += g_opts.count(kv.first) ? g_opts[kv.first] : kv.second;
        out += '\n';
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ng_emu_Native_getCoreVariable(JNIEnv *env, jclass, jstring key) {
    const char *k = env->GetStringUTFChars(key, nullptr);
    auto it = g_vars.find(k);
    jstring r = env->NewStringUTF(it == g_vars.end() ? "" : it->second.c_str());
    env->ReleaseStringUTFChars(key, k);
    return r;
}

extern "C" JNIEXPORT jfloat JNICALL Java_com_ng_emu_Native_getFps(JNIEnv *, jclass) {
    return g_fpsNow.load();
}
extern "C" JNIEXPORT jfloat JNICALL Java_com_ng_emu_Native_getTargetFps(JNIEnv *, jclass) {
    return (float) (g_av.timing.fps > 1.0 ? g_av.timing.fps : 60.0);
}
extern "C" JNIEXPORT jlong JNICALL Java_com_ng_emu_Native_getFrames(JNIEnv *, jclass) {
    return (jlong) g_frames.load();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ng_emu_Native_readMem(JNIEnv *env, jclass, jint addr, jint len) {
    unsigned char *p = nullptr;
    if (!resolveMem((size_t) (unsigned) addr, (size_t) len, &p)) return nullptr;
    jbyteArray r = env->NewByteArray(len);
    env->SetByteArrayRegion(r, 0, len, (const jbyte *) p);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ng_emu_Native_writeMem(JNIEnv *env, jclass, jint addr, jbyteArray data) {
    if (!data) return -2;
    jsize len = env->GetArrayLength(data);
    unsigned char *p = nullptr;
    if (!resolveMem((size_t) (unsigned) addr, (size_t) len, &p)) {
        LOGI("mem write %06zX 越界", (size_t) (unsigned) addr);
        return -1;
    }
    env->GetByteArrayRegion(data, 0, len, (jbyte *) p);
    LOGI("mem write %06zX [%d]", (size_t) (unsigned) addr, (int) len);
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_ng_emu_Native_getMemRanges(JNIEnv *env, jclass) {
    std::string s;
    char b[128];
    for (unsigned i = 0; i < g_ndesc; i++) {
        if (!g_desc[i].len) continue;
        snprintf(b, sizeof b, "%06zX-%06zX  %zu KB\n",
                 (size_t) g_desc[i].start,
                 (size_t) (g_desc[i].start + g_desc[i].len),
                 (size_t) (g_desc[i].len / 1024));
        s += b;
    }
    if (g_sysram && g_sysramSize)
        snprintf(b, sizeof b, "%06zX-%06zX  %zu KB  主 RAM\n",
                 SYS_RAM_BASE, SYS_RAM_BASE + g_sysramSize, g_sysramSize / 1024), s += b;
    if (s.empty()) s = "核心未提供内存";
    return env->NewStringUTF(s.c_str());
}
