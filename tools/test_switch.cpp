// 复现：切换 ROM 后黑屏
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <string>
#include <vector>

struct retro_variable { const char *key, *value; };
struct retro_message { const char *msg; unsigned frames; };
struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; };
struct retro_game_geometry { unsigned bw, bh, mw, mh; float aspect; };
struct retro_system_timing { double fps, sr; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };

typedef bool (*env_t)(unsigned, void *);
typedef void (*vid_t)(const void *, unsigned, unsigned, size_t);
typedef void (*aud_t)(int16_t, int16_t);
typedef size_t (*audb_t)(const int16_t *, size_t);
typedef void (*poll_t)(void);
typedef int16_t (*state_t)(unsigned, unsigned, unsigned, unsigned);

static std::string g_sys;
static int g_pix = -1;
static std::vector<uint16_t> g_frame;
static unsigned g_w = 0, g_h = 0;
static int g_vidCalls = 0;

static bool envCb(unsigned cmd, void *data) {
    switch (cmd & 0xFFFF) {
        case 10: g_pix = *(int *) data; printf("[env] pixel_format=%d\n", g_pix); return true;
        case 9:  *(const char **) data = g_sys.c_str(); return true;
        case 31: *(const char **) data = g_sys.c_str(); return true;
        case 30: *(const char **) data = g_sys.c_str(); return true;
        case 3:  *(bool *) data = true; return true;
        case 2:  *(bool *) data = true; return true;
        case 6: { auto *m = (retro_message *) data; if (m) printf("[core msg] %s\n", m->msg); return true; }
        case 15: { auto *v = (retro_variable *) data; if (v) v->value = nullptr; return false; }
        case 17: *(bool *) data = false; return true;
        case 39: *(unsigned *) data = 0; return true;
        case 52: *(unsigned *) data = 0; return true;
        case 51: return true;
        case 61: *(unsigned *) data = 4; return true;
        case 8: case 11: case 16: case 18: case 35: case 36: case 53: case 54: case 55: case 7: return true;
        default: return false;
    }
}

static void vidCb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data) return;
    g_vidCalls++;
    g_w = w; g_h = h;
    g_frame.resize((size_t) w * h);
    const uint8_t *s = (const uint8_t *) data;
    uint16_t *o = g_frame.data();
    for (unsigned y = 0; y < h; y++) {
        if (g_pix == 1) {
            const uint32_t *row = (const uint32_t *) (s + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                uint32_t c = row[x];
                o[y * w + x] = (uint16_t) (((c >> 8) & 0xF800) | ((c >> 5) & 0x07E0) | ((c >> 3) & 0x001F));
            }
        } else if (g_pix == 2) {
            memcpy(o + y * w, s + y * pitch, (size_t) w * 2);
        } else {
            const uint16_t *row = (const uint16_t *) (s + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                uint16_t c = row[x];
                unsigned g5 = (c >> 5) & 31;
                o[y * w + x] = (uint16_t) ((((c >> 10) & 31) << 11) | (((g5 << 1) | (g5 >> 4)) << 5) | (c & 31));
            }
        }
    }
}
static size_t audbCb(const int16_t *, size_t f) { return f; }
static void audCb(int16_t, int16_t) {}
static void pollCb(void) {}
static int16_t stateCb(unsigned, unsigned, unsigned, unsigned) { return 0; }

static void stats(const char *tag) {
    if (g_frame.empty()) { printf("%s: 无画面 (calls=%d)\n", tag, g_vidCalls); return; }
    double sum = 0; int nonBlack = 0;
    for (uint16_t c : g_frame) {
        int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
        int lum = (r * 255 / 31 + g * 255 / 63 + b * 255 / 31) / 3;
        sum += lum;
        if (lum > 8) nonBlack++;
    }
    printf("%s: %ux%u 平均亮度=%.1f 非黑像素=%.1f%% vidCalls=%d\n",
           tag, g_w, g_h, sum / g_frame.size(), 100.0 * nonBlack / g_frame.size(), g_vidCalls);
}

int main(int argc, char **argv) {
    if (argc < 4) { printf("usage: %s core romA [romB] sysdir\n", argv[0]); return 1; }
    void *h = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!h) { printf("dlopen: %s\n", dlerror()); return 1; }

    auto set_env = (void (*)(env_t)) dlsym(h, "retro_set_environment");
    auto set_vid = (void (*)(vid_t)) dlsym(h, "retro_set_video_refresh");
    auto set_aud = (void (*)(aud_t)) dlsym(h, "retro_set_audio_sample");
    auto set_audb = (void (*)(audb_t)) dlsym(h, "retro_set_audio_sample_batch");
    auto set_poll = (void (*)(poll_t)) dlsym(h, "retro_set_input_poll");
    auto set_state = (void (*)(state_t)) dlsym(h, "retro_set_input_state");
    auto p_init = (void (*)(void)) dlsym(h, "retro_init");
    auto p_deinit = (void (*)(void)) dlsym(h, "retro_deinit");
    auto load = (bool (*)(const retro_game_info *)) dlsym(h, "retro_load_game");
    auto unload = (void (*)(void)) dlsym(h, "retro_unload_game");
    auto run = (void (*)(void)) dlsym(h, "retro_run");

    set_env(envCb);
    set_vid(vidCb);
    set_aud(audCb);
    set_audb(audbCb);
    set_poll(pollCb);
    set_state(stateCb);

    g_sys = argc > 4 ? argv[4] : "/sdcard/neogeo";

    retro_game_info ga{};
    ga.path = argv[2];
    p_init();
    printf("load A %s -> %d\n", argv[2], (int) load(&ga));
    for (int i = 0; i < 300; i++) run();
    stats("A after 300");

    if (argc > 3) {
        printf("--- 切换 ---\n");
        unload();
        p_deinit();
        g_pix = -1;
        g_vidCalls = 0;
        g_w = g_h = 0;
        p_init();
        retro_game_info gb{};
        gb.path = argv[3];
        printf("load B %s -> %d\n", argv[3], (int) load(&gb));
        for (int i = 0; i < 300; i++) run();
        stats("B after 300");
    }
    printf("done\n");
    return 0;
}
