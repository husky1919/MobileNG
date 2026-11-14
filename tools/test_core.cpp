// 无界面测试：直接 dlopen FBNeo 核心，加载 romset，跑若干帧并导出画面
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

static std::string g_sys, g_save;
static int g_pix = 0;
static std::vector<uint16_t> g_frame;
static unsigned g_w = 0, g_h = 0;

static bool envCb(unsigned cmd, void *data) {
    switch (cmd & 0xFFFF) {
        case 10: g_pix = *(int *) data; printf("[env] pixel_format=%d\n", g_pix); return true;
        case 9:  *(const char **) data = g_sys.c_str(); printf("[env] system_dir=%s\n", g_sys.c_str()); return true;
        case 31: *(const char **) data = g_save.c_str(); return true;
        case 30: *(const char **) data = g_sys.c_str(); return true;
        case 3:  *(bool *) data = true; return true;
        case 2:  *(bool *) data = true; return true;
        case 6: { auto *m = (retro_message *) data; if (m) printf("[core msg] %s\n", m->msg); return true; }
        case 15: { auto *v = (retro_variable *) data;
            if (v) { v->value = nullptr;
                if (strstr(v->key, "neogeo")) printf("[getvar] %s\n", v->key); }
            return false; }
        case 17: *(bool *) data = false; return true;
        case 39: *(unsigned *) data = 0; return true;
        case 52: *(unsigned *) data = 0; return true;
        case 51: return true;
        case 61: *(unsigned *) data = 4; return true;
        case 16: {
            auto *v = (retro_variable *) data;
            if (v) for (; v->key; v++) printf("[opt] %s = %s\n", v->key, v->value ? v->value : "(null)");
            return true;
        }
        case 8: case 11: case 18: case 35: case 53: case 54: case 55: case 7: return true;
        default: printf("[env] cmd=%u\n", cmd & 0xFFFF); return false;
    }
}

static void vidCb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data) return;
    static bool said = false;
    if (!said) { printf("[video] %ux%u pitch=%zu\n", w, h, pitch); said = true; }
    if (w != g_w || h != g_h) { g_w = w; g_h = h; g_frame.assign((size_t) w * h, 0); }
    const uint8_t *s = (const uint8_t *) data;
    for (unsigned y = 0; y < h; y++) {
        uint16_t *o = g_frame.data() + (size_t) y * w;
        if (g_pix == 1) {
            const uint32_t *row = (const uint32_t *) (s + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                uint32_t c = row[x];
                o[x] = (uint16_t) (((c >> 8) & 0xF800) | ((c >> 5) & 0x07E0) | ((c >> 3) & 0x001F));
            }
        } else if (g_pix == 2) {
            memcpy(o, s + y * pitch, (size_t) w * 2);
        } else {
            const uint16_t *row = (const uint16_t *) (s + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                uint16_t c = row[x];
                unsigned g5 = (c >> 5) & 31;
                o[x] = (uint16_t) ((((c >> 10) & 31) << 11) | (((g5 << 1) | (g5 >> 4)) << 5) | (c & 31));
            }
        }
    }
}
static size_t audCb(const int16_t *, size_t f) { return f; }
static void aud1Cb(int16_t, int16_t) {}
static void pollCb(void) {}
static int g_tick = 0;
static int16_t stateCb(unsigned port, unsigned dev, unsigned idx, unsigned id) {
    if (port != 0 || idx != 0) return 0;
    if (id == 2) return (g_tick >= 300 && g_tick < 320);   // COIN
    if (id == 3) return (g_tick >= 340 && g_tick < 360);   // START
    if (id == 8) return (g_tick >= 380 && g_tick < 400);   // A
    return 0;
}

static void dumpPPM(const char *path) {
    FILE *f = fopen(path, "wb");
    if (!f) { printf("ppm write failed\n"); return; }
    fprintf(f, "P6 %u %u 255\n", g_w, g_h);
    for (unsigned i = 0; i < g_w * g_h; i++) {
        uint16_t c = g_frame[i];
        unsigned char px[3] = { (unsigned char) (((c >> 11) & 31) * 255 / 31),
                                (unsigned char) (((c >> 5) & 63) * 255 / 63),
                                (unsigned char) ((c & 31) * 255 / 31) };
        fwrite(px, 1, 3, f);
    }
    fclose(f);
    printf("[ok] wrote %s (%ux%u)\n", path, g_w, g_h);
}

int main(int argc, char **argv) {
    const char *core = argc > 1 ? argv[1] : "core.so";
    const char *rom = argc > 2 ? argv[2] : "rom.zip";
    g_sys = argc > 3 ? argv[3] : ".";
    g_save = g_sys;

    void *h = dlopen(core, RTLD_NOW | RTLD_GLOBAL);
    if (!h) { printf("dlopen: %s\n", dlerror()); return 1; }

#define F(n) auto p_##n = (decltype(&n)) dlsym(h, "retro_" #n); if (!p_##n) { printf("no retro_%s\n", #n); return 2; }
    typedef bool (*e_t)(env_t); typedef void (*v_t)(vid_t); typedef void (*a_t)(aud_t);
    typedef size_t (*ab_t)(audb_t); typedef void (*p_t)(poll_t); typedef int16_t (*s_t)(state_t);
    auto set_env = (void (*)(env_t)) dlsym(h, "retro_set_environment");
    auto set_vid = (void (*)(vid_t)) dlsym(h, "retro_set_video_refresh");
    auto set_aud = (void (*)(aud_t)) dlsym(h, "retro_set_audio_sample");
    auto set_audb = (void (*)(audb_t)) dlsym(h, "retro_set_audio_sample_batch");
    auto set_poll = (void (*)(poll_t)) dlsym(h, "retro_set_input_poll");
    auto set_state = (void (*)(state_t)) dlsym(h, "retro_set_input_state");
    auto init = (void (*)(void)) dlsym(h, "retro_init");
    auto load = (bool (*)(const retro_game_info *)) dlsym(h, "retro_load_game");
    auto av = (void (*)(retro_system_av_info *)) dlsym(h, "retro_get_system_av_info");
    auto run = (void (*)(void)) dlsym(h, "retro_run");
    if (!set_env || !set_vid || !init || !load || !run) { printf("missing symbols\n"); return 2; }

    set_env(envCb);
    set_vid(vidCb);
    if (set_aud) set_aud(aud1Cb);
    if (set_audb) set_audb(audCb);
    if (set_poll) set_poll(pollCb);
    if (set_state) set_state(stateCb);
    init();

    retro_game_info gi{};
    gi.path = rom;
    printf("[load] %s\n", rom);
    if (!load(&gi)) { printf("[fail] retro_load_game returned false\n"); return 3; }

    {
        auto gd = (void *(*)(unsigned)) dlsym(h, "retro_get_memory_data");
        auto gs = (size_t (*)(unsigned)) dlsym(h, "retro_get_memory_size");
        if (gd && gs) printf("[mem] sysram=%p size=%zu sram=%p/%zu\n",
                             gd(2), gs(2), gd(0), gs(0));
    }
    retro_system_av_info a{};
    if (av) { av(&a); printf("[av] %.2ffps %.0fHz %ux%u\n", a.timing.fps, a.timing.sr, a.geometry.bw, a.geometry.bh); }

    for (int i = 0; i < 1800; i++) {
        g_tick = i; run();
        if (i % 300 == 299) { char b[64]; snprintf(b, sizeof b, "/sdcard/neogeo/f%d.ppm", i); dumpPPM(b); }
    }
    if (g_w) dumpPPM("/sdcard/neogeo/test_frame.ppm");
    else printf("[warn] no video frame\n");
    printf("[done] ok\n");
    return 0;
}
