package com.ng.emu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity {
    static { System.loadLibrary("ngfe"); }

    private static final int REQ_ROM = 1, REQ_BIOS = 2, REQ_TREE = 3, REQ_CHEAT = 4, REQ_CORE = 5;

    private GLSurfaceView gl;
    private PadView pad;
    private File coreFile, systemDir, saveDir, romFile;
    private boolean loaded = false, coreInited = false;
    private AudioThread audio;
    private int sampleRate = 48000;
    private File logFile;
    private KeyConfig cfg;
    private boolean editLayout = false;
    private int vScale = 0, vFilter = 0, vBright = 100, vContrast = 100, vSat = 100;
    private TextView hud;
    private final Handler hudHandler = new Handler();
    private final Runnable hudTick = new Runnable() {
        public void run() {
            if (hud == null) return;
            float fps = Native.getFps(), tf = Native.getTargetFps();
            int pct = tf > 0 ? Math.round(fps / tf * 100f) : 0;
            hud.setText(String.format(Locale.US, "%.1f/%.2f fps  %d%%  %d帧",
                    fps, tf, pct, Native.getFrames()));
            hudHandler.postDelayed(this, 500);
        }
    };
    private static final String[] NEO_MODES = {"DIPSWITCH", "MVS_EUR", "MVS_USA", "MVS_JAP", "AES_EUR", "AES_JAP", "UNIBIOS"};
    private static final String[] NEO_LABELS = {"自动（跟随 DIP 开关）", "MVS 欧洲", "MVS 美国", "MVS 日本",
            "AES 欧洲", "AES 日本", "Universe BIOS"};

    // ---------------- 基板识别 ----------------
    private static final java.util.HashSet<String> BOARD_CPS1 = new java.util.HashSet<>(java.util.Arrays.asList(
            "3wonders", "1941", "area88", "avengers", "bionicc", "blktiger", "cawing", "cbasebal", "ckongs",
            "captcomm", "dino", "dinou", "dinohunt", "dynastyw", "ffight", "forgottn", "ghouls", "gng",
            "knights", "kod", "lastduel", "lwings", "mbombrd", "mercs", "mswu", "mtwins", "nemo", "pang3",
            "punisher", "qad", "qtono2", "sf2", "sf2ce", "sf2hf", "sf2m", "sf2rb", "sf2rb2", "strider",
            "tiga", "unsquad", "varth", "willow", "wof", "wofj"));
    private static final java.util.HashSet<String> BOARD_CPS2 = new java.util.HashSet<>(java.util.Arrays.asList(
            "1944", "19xx", "armwar", "avsp", "csclub", "cybots", "ddsom", "ddtod", "ddx", "dimahoo",
            "dstlk", "ecofghtr", "gigawing", "hsf2", "jyangoku", "megaman2", "mmatrix", "mpang", "msh",
            "mshvsf", "mvsc", "nwarr", "phoenix", "progear", "pzloop2", "ringdest", "rcmakaim", "sgemf",
            "spf2t", "ssf2", "ssf2t", "ssf2x", "ssf2xj", "techromn", "vhunt2", "vsav", "vsav2", "xmcota",
            "xmvsf", "qndream", "sgemfa", "spf2ta", "ssf2ta"));
    private static final String[] BOARD_NEO = {
            "2020bb", "3countb", "alpham2", "androdun", "aodk", "aof", "bakatono", "bangbead", "blazstar",
            "breakers", "bstars", "burningf", "crsword", "cthd2k3", "cyberlip", "eightman", "fatfur",
            "fatfury", "fightfev", "ganryu", "garou", "goalx3", "gowcaizr", "gtstrikr", "kabukikl",
            "knight", "kof", "kotm", "lastblad", "lbowling", "legendos", "lresort", "magdrop", "matrim",
            "miexchng", "mma", "moshougi", "mrd", "mslug", "msl", "mutnat", "nam1975", "ncombat",
            "neocup", "neodrift", "neomrdo", "ninjamas", "nitd", "panicbom", "pbobblen", "pgoal",
            "preisle2", "pulstar", "puzzledp", "ragnagrd", "rbff", "ridhero", "rmsodk", "roboarmy",
            "s1945", "samsho", "savagere", "sengoku", "sonicwi", "spinmast", "strhoop", "superspy",
            "svc", "tophuntr", "trally", "turfmast", "tws96", "twinsprk", "viewpoin", "wakuwak7",
            "wh1", "wh2", "whp", "wjammers", "zedblade", "zupapa"};

    static String boardLabel(String file) {
        String n = file.toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip")) n = n.substring(0, n.length() - 4);
        if (BOARD_CPS1.contains(n)) return "CPS1";
        if (BOARD_CPS2.contains(n)) return "CPS2";
        for (String p : BOARD_NEO) if (n.startsWith(p)) return "NEOGEO";
        return "";
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideUi();

        systemDir = new File(getExternalFilesDir(null), "system");
        saveDir = new File(getFilesDir(), "saves");
        systemDir.mkdirs();
        saveDir.mkdirs();

        logFile = new File(getFilesDir(), "emu.log");
        if (logFile.length() > 512 * 1024) logFile.delete();
        Logger.init(logFile);
        Logger.log("start model=" + android.os.Build.MODEL
                + " api=" + android.os.Build.VERSION.SDK_INT
                + " abi=" + android.os.Build.SUPPORTED_ABIS[0]
                + " sys=" + systemDir);

        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setRenderer(new Renderer());
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        gl.setPreserveEGLContextOnPause(true);

        pad = new PadView(this);
        cfg = KeyConfig.load(prefs());
        pad.preset(cfg);
        pad.setCustomLayout(prefs().getString("layout", null));
        pad.setListener(s1 -> prefs().edit().putString("layout", s1).apply());

        FrameLayout root = new FrameLayout(this);
        root.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));

        hud = new TextView(this);
        hud.setTextColor(0xFFFFFFFF);
        hud.setTextSize(11f);
        hud.setTypeface(Typeface.MONOSPACE);
        hud.setBackgroundColor(0x66000000);
        hud.setPadding(16, 4, 16, 4);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2);
        hlp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = 8;
        root.addView(hud, hlp);
        hud.setVisibility(prefs().getBoolean("hud", false) ? View.VISIBLE : View.GONE);

        setContentView(root);

        loadVideo();

        new Thread(() -> boot()).start();
    }

    private SharedPreferences prefs() { return getSharedPreferences("ng", MODE_PRIVATE); }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) hideUi();
    }

    private void hideUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---------------- 启动 ----------------
    private void boot() {
        coreFile = new File(getFilesDir(), "cores/libfbneo.so");
        if (!coreFile.exists() || coreFile.length() < (1 << 20)) {
            Logger.log("核心缺失，等待导入");
            toastMain("还没有模拟器核心：请选择 fbneo_libretro_android.so");
            runOnUiThread(this::importCore);
            return;
        }
        Native.setLogFile(logFile.getAbsolutePath());
        Logger.log("core file " + coreFile.length() + " bytes");
        if (!new File(systemDir, "neogeo.zip").exists()) {
            Logger.log("未导入 neogeo.zip（仅 NeoGeo 游戏需要）");
        }
        if (!initCore()) { toastMain("核心加载失败"); return; }

        // 启动时弹窗选 ROM（有记录则显示上次游戏，无则选）
        final String last = prefs().getString("rom", null);
        final File lastFile = last != null ? new File(last) : null;
        runOnUiThread(() -> {
            java.util.List<String> items = new java.util.ArrayList<>();
            if (lastFile != null && lastFile.exists()) items.add("继续上次：" + lastFile.getName());
            items.add("浏览游戏目录…");
            items.add("选择任意 ROM 文件…");
            new AlertDialog.Builder(this).setTitle("选择游戏")
                    .setItems(items.toArray(new String[0]), (d, w) -> {
                        if (w == 0) { startGame(lastFile); return; }
                        if (w == 1) listGames();
                        else pick(REQ_ROM);
                    })
                    .show();
        });
    }

    private boolean initCore() {
        if (coreInited) { Native.deinit(); coreInited = false; }
        int r = Native.init(coreFile.getAbsolutePath(), systemDir.getAbsolutePath(), saveDir.getAbsolutePath());
        coreInited = (r == 0);
        if (coreInited) applyCpuSpeed();
        Logger.log("Native.init -> " + r);
        return coreInited;
    }

    private void copyAsset(String name, File dst) throws Exception {
        InputStream in = getAssets().open(name);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    // ---------------- 菜单 ----------------
    private void handleMenu(String it) {
        if (it.startsWith("CPU 频率")) { cpuSpeed(); return; }
        switch (it) {
            case "游戏列表": listGames(); break;
            case "联机（蓝牙）": netMenu(); break;
            case "选择任意 ROM": pick(REQ_ROM); break;
            case "导入 BIOS/资源 (.zip)": importBios(); break;
            case "导入核心 (.so)": importCore(); break;
            case "存档": saveState(); break;
            case "读档": loadState(); break;
            case "加载金手指 (.txt)": pick(REQ_CHEAT); break;
            case "NeoGeo 模式": neoMode(); break;
            case "画面设置": videoSettings(); break;
            case "帧数显示：开":
            case "帧数显示：关": toggleHud(); break;
            case "键位设置": keySettings(); break;
            case "编辑布局": enterEdit(); break;
            case "恢复默认布局":
                pad.clearCustomLayout();
                prefs().edit().remove("layout").apply();
                toastMain("已恢复默认布局");
                break;
            case "查看日志": showLog(); break;
            case "系统信息 / BIOS": sysInfo(); break;
            case "内存编辑 [DEBUG]": memTool(); break;
            case "重置游戏": if (loaded) Native.reset(); break;
            default: finish();
        }
    }

    private void toggleHud() {
        boolean on = !prefs().getBoolean("hud", false);
        prefs().edit().putBoolean("hud", on).apply();
        hud.setVisibility(on ? View.VISIBLE : View.GONE);
        hudHandler.removeCallbacks(hudTick);
        if (on) hudHandler.post(hudTick);
        toastMain(on ? "帧数显示已开" : "帧数显示已关");
    }

    // ---------------- 内存编辑（仅 DEBUG 版） ----------------
    private void memTool() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 24, 40, 16);

        TextView ranges = new TextView(this);
        ranges.setText(Native.getMemRanges());
        ranges.setTypeface(Typeface.MONOSPACE);
        ranges.setTextSize(10f);

        final EditText addr = new EditText(this);
        addr.setHint("地址（十六进制）");
        addr.setText("100000");
        addr.setInputType(InputType.TYPE_CLASS_TEXT);

        final EditText data = new EditText(this);
        data.setHint("字节，如 63 或 01 02 FF");
        data.setInputType(InputType.TYPE_CLASS_TEXT);

        Button br = new Button(this);
        br.setText("读取 16 字节");
        Button bw = new Button(this);
        bw.setText("写入");

        final TextView out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(11f);

        box.addView(ranges);
        box.addView(addr);
        box.addView(data);
        box.addView(br);
        box.addView(bw);
        box.addView(out);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("内存编辑").setView(sv)
                .setNegativeButton("关闭", null).show();

        br.setOnClickListener(v -> {
            try {
                int a = (int) Long.parseLong(addr.getText().toString().trim().replace("0x", ""), 16);
                byte[] b = Native.readMem(a, 16);
                if (b == null) { out.setText("地址不在已映射范围内"); return; }
                StringBuilder sb = new StringBuilder(String.format(Locale.US, "%06X:", a));
                for (byte x : b) sb.append(String.format(Locale.US, " %02X", x));
                out.setText(sb.toString());
            } catch (Exception e) {
                out.setText("解析失败: " + e);
            }
        });

        bw.setOnClickListener(v -> {
            try {
                int a = (int) Long.parseLong(addr.getText().toString().trim().replace("0x", ""), 16);
                String[] parts = data.getText().toString().trim().split("\\s+");
                byte[] b = new byte[parts.length];
                for (int i = 0; i < parts.length; i++)
                    b[i] = (byte) Integer.parseInt(parts[i].replace("0x", ""), 16);
                int r = Native.writeMem(a, b);
                out.setText(r == 0 ? "写入成功 " + b.length + " 字节" : "写入失败 code=" + r);
            } catch (Exception e) {
                out.setText("解析失败: " + e);
            }
        });
    }

    private void enterEdit() {
        editLayout = true;
        pad.setEditMode(true);
        toastMain("拖动每个按键到合适位置，然后按返回键保存");
    }

    private void exitEdit() {
        editLayout = false;
        pad.setEditMode(false);
        prefs().edit().putString("layout", pad.currentLayout()).apply();
        toastMain("布局已保存");
    }

    @Override
    public void onBackPressed() {
        if (editLayout) { exitEdit(); return; }
        final List<String> items = new ArrayList<>();
        items.add("游戏列表");
        items.add("联机（蓝牙）");
        items.add("选择任意 ROM");
        items.add("导入 BIOS/资源 (.zip)");
        items.add("导入核心 (.so)");
        items.add("存档");
        items.add("读档");
        items.add("加载金手指 (.txt)");
        items.add("NeoGeo 模式");
        items.add("CPU 频率（" + prefs().getInt("cpuspeed", 100) + "%）");
        items.add("画面设置");
        items.add(prefs().getBoolean("hud", false) ? "帧数显示：开" : "帧数显示：关");
        items.add("键位设置");
        items.add("编辑布局");
        items.add("恢复默认布局");
        items.add("查看日志");
        items.add("系统信息 / BIOS");
        boolean dbg = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (dbg) items.add("内存编辑 [DEBUG]");
        items.add("重置游戏");
        items.add("退出");

        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, w) -> handleMenu(items.get(w)))
                .show();
    }

    // ---------------- NeoGeo 模式 ----------------
    private void neoMode() {
        String cur = prefs().getString("neomode", "UNIBIOS");
        int idx = 0;
        for (int i = 0; i < NEO_MODES.length; i++) if (NEO_MODES[i].equals(cur)) idx = i;
        new AlertDialog.Builder(this)
                .setTitle("NeoGeo 模式（换档后自动重载游戏）")
                .setSingleChoiceItems(NEO_LABELS, idx, (d, w) -> {
                    prefs().edit().putString("neomode", NEO_MODES[w]).apply();
                    d.dismiss();
                    applyNeoMode();
                    if (loaded && romFile != null) startGame(romFile);
                })
                .show();
    }

    private void applyNeoMode() {
        Native.setCoreVariable("fbneo-neogeo-mode", prefs().getString("neomode", "UNIBIOS"));
    }

    // ---------------- CPU 频率 ----------------
    private void cpuSpeed() {
        final int cur = prefs().getInt("cpuspeed", 100);
        final int[] speeds = {25, 50, 75, 90, 100, 110, 120, 150, 200, 300, 400};
        java.util.List<String> items = new ArrayList<>();
        int idx = -1;
        for (int i = 0; i < speeds.length; i++) {
            if (speeds[i] == cur) idx = i;
            items.add(speeds[i] + "%   68k ≈ "
                    + String.format(Locale.US, "%.1f", speeds[i] * 12.0 / 100) + " MHz");
        }
        items.add("自定义…");
        new AlertDialog.Builder(this)
                .setTitle("CPU 频率（当前 " + cur + "%）")
                .setSingleChoiceItems(items.toArray(new String[0]), idx, (d, w) -> {
                    d.dismiss();
                    if (w == speeds.length) customCpu();
                    else setCpu(speeds[w]);
                })
                .show();
    }

    private void customCpu() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(prefs().getInt("cpuspeed", 100)));
        new AlertDialog.Builder(this)
                .setTitle("CPU 频率 25–400 %")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int p = Integer.parseInt(et.getText().toString().trim());
                        if (p < 25) p = 25;
                        if (p > 400) p = 400;
                        setCpu(p);
                    } catch (Exception e) {
                        toastMain("输入无效");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setCpu(int p) {
        prefs().edit().putInt("cpuspeed", p).apply();
        applyCpuSpeed();
        toastMain("CPU 频率 " + p + "%");
        if (loaded && romFile != null) startGame(romFile);
    }

    private void applyCpuSpeed() {
        Native.setCoreVariable("fbneo-cpu-speed-adjust", prefs().getInt("cpuspeed", 100) + "%");
    }

    // ---------------- 系统信息 / BIOS ----------------
    private void sysInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("核心：").append(coreFile != null && coreFile.exists()
                ? (coreFile.length() / 1024 / 1024) + " MB" : "未导入").append('\n');
        String drv = Native.getDriver();
        sb.append("当前驱动：").append(drv.isEmpty() ? "(未载入游戏)" : drv).append('\n');
        sb.append("NeoGeo 模式：").append(prefs().getString("neomode", "UNIBIOS")).append('\n');
        sb.append("CPU 频率：").append(prefs().getInt("cpuspeed", 100)).append("%\n");
        sb.append("分辨率缩放：").append(vScale == 0 ? "适应窗口" : vScale == 1 ? "整数倍" : "拉伸").append('\n');
        sb.append("\nsystem 目录：\n").append(systemDir.getAbsolutePath()).append('\n');
        File[] fs = systemDir.listFiles();
        if (fs == null || fs.length == 0) sb.append("  (空)\n");
        else {
            java.util.Arrays.sort(fs);
            for (File f : fs) sb.append("  ").append(f.getName())
                    .append("   ").append(f.length() / 1024).append(" KB\n");
        }

        File neo = new File(systemDir, "neogeo.zip");
        if (!neo.exists()) {
            sb.append("\nneogeo.zip：未导入\n");
        } else {
            sb.append("\nneogeo.zip 里的 BIOS 文件：\n");
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(neo)) {
                int n = 0;
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    String nm = en.nextElement().getName().toLowerCase(Locale.ROOT);
                    if (nm.startsWith("sp") || nm.startsWith("aes")
                            || nm.startsWith("uni-bios") || nm.startsWith("uni_bios") || nm.contains("bios")) {
                        sb.append("  ").append(nm).append('\n');
                        n++;
                    }
                }
                if (n == 0) sb.append("  (没有识别到 BIOS 文件)\n");
            } catch (Exception e) {
                sb.append("  读取失败: ").append(e).append('\n');
            }
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setPadding(40, 30, 40, 30);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("系统信息 / BIOS")
                .setView(sv).setPositiveButton("关闭", null).show();
    }

    // ---------------- 联机（蓝牙 RFCOMM） ----------------
    private static final java.util.UUID NET_UUID =
            java.util.UUID.fromString("6f9a1b1e-3f6c-4a1e-9a53-1c9b2b6a7e21");
    private volatile boolean netRunning;
    private android.bluetooth.BluetoothSocket netSocket;
    private android.bluetooth.BluetoothServerSocket netServer;
    private Runnable pendingBt;

    private void netMenu() {
        String st = !netRunning ? "未连接" : (Native.netState() == 1 ? "主机（1P）" : "客户端（2P）");
        int d = prefs().getInt("netdelay", 4);
        String[] items = {"创建房间（我当 1P）", "加入房间（我当 2P）",
                          "联机延迟：" + d + " 帧", "断开联机"};
        new AlertDialog.Builder(this)
                .setTitle("联机（蓝牙）当前：" + st)
                .setItems(items, (d2, w) -> {
                    if (w == 0) createRoom();
                    else if (w == 1) joinRoom();
                    else if (w == 2) netDelayMenu();
                    else stopNet();
                }).show();
    }

    private void netDelayMenu() {
        final int cur = prefs().getInt("netdelay", 4);
        final int[] delays = {0, 2, 4, 6, 8, 12, 16, 20, 24, 30};
        String[] labels = new String[delays.length];
        int idx = 0;
        for (int i = 0; i < delays.length; i++) {
            labels[i] = delays[i] + " 帧" + (delays[i] == 0 ? "（最低延迟，易不同步）" : (delays[i] <= 4 ? "" : "（更稳）"));
            if (delays[i] == cur) idx = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("联机延迟（越高越稳，延迟越大越流畅）")
                .setSingleChoiceItems(labels, idx, (d, w) -> {
                    int d2 = delays[w];
                    prefs().edit().putInt("netdelay", d2).apply();
                    Native.setNetDelay(d2);
                    d.dismiss();
                    toastMain("联机延迟 " + d2 + " 帧" + (netRunning ? "（下一局生效）" : ""));
                }).show();
    }

    private void withBtPermission(Runnable r) {
        if (android.os.Build.VERSION.SDK_INT >= 31
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingBt = r;
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT",
                    "android.permission.BLUETOOTH_SCAN"}, 9001);
            return;
        }
        r.run();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (req == 9001) {
            Runnable r = pendingBt;
            pendingBt = null;
            if (g.length > 0 && g[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && r != null) r.run();
            else toastMain("需要蓝牙权限");
        }
    }

    private void createRoom() {
        withBtPermission(() -> {
            final android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) { toastMain("请先打开蓝牙"); return; }
            if (!loaded) { toastMain("先载入游戏再开房"); return; }
            stopNet();
            new Thread(() -> {
                try {
                    netServer = a.listenUsingRfcommWithServiceRecord("MobileNG", NET_UUID);
                    toastMain("等待对方加入…");
                    android.bluetooth.BluetoothSocket s = netServer.accept();
                    netServer.close();
                    netServer = null;
                    startNet(s, 1);
                } catch (Exception e) {
                    toastMain("开房失败: " + e.getMessage());
                }
            }).start();
        });
    }

    private void joinRoom() {
        withBtPermission(() -> {
            final android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) { toastMain("请先打开蓝牙"); return; }
            if (!loaded) { toastMain("先载入同一个游戏再加入"); return; }
            java.util.Set<android.bluetooth.BluetoothDevice> set = a.getBondedDevices();
            if (set == null || set.isEmpty()) { toastMain("没有已配对设备，请先在系统蓝牙设置里配对"); return; }
            final java.util.List<android.bluetooth.BluetoothDevice> list = new ArrayList<>(set);
            String[] names = new String[list.size()];
            for (int i = 0; i < list.size(); i++)
                names[i] = list.get(i).getName() + "  " + list.get(i).getAddress();
            new AlertDialog.Builder(this).setTitle("选择对方设备")
                    .setItems(names, (d, w) -> {
                        final android.bluetooth.BluetoothDevice dev = list.get(w);
                        new Thread(() -> {
                            try {
                                a.cancelDiscovery();
                                android.bluetooth.BluetoothSocket s = dev.createRfcommSocketToServiceRecord(NET_UUID);
                                s.connect();
                                startNet(s, 2);
                            } catch (Exception e) {
                                toastMain("连接失败: " + e.getMessage());
                            }
                        }).start();
                    }).show();
        });
    }

    private void startNet(android.bluetooth.BluetoothSocket s, int mode) {
        netSocket = s;
        netRunning = true;
        Native.setNetDelay(prefs().getInt("netdelay", 4));
        Native.netEnable(mode, mode == 1 ? 0 : 1);

        // 1) 配置签名校验：两端交换 neomode/cpuspeed/romCrc，不一致就拒绝
        final String myMode = prefs().getString("neomode", "UNIBIOS");
        final int myCpu = prefs().getInt("cpuspeed", 100);
        final String myRom = romFile != null ? fileCrc4K(romFile) : "no_rom";
        final java.io.DataOutputStream out; final java.io.DataInputStream in;
        try {
            out = new java.io.DataOutputStream(s.getOutputStream());
            in = new java.io.DataInputStream(s.getInputStream());
        } catch (Exception e) { netLost(e); return; }
        final String peerMode; final int peerCpu; final String peerRom;
        try {
            if (mode == 1) {  // 主机：先发后收
                out.writeUTF(myMode); out.writeInt(myCpu); out.writeUTF(myRom); out.flush();
                peerMode = in.readUTF(); peerCpu = in.readInt(); peerRom = in.readUTF();
            } else {          // 客户端：先收后发
                peerMode = in.readUTF(); peerCpu = in.readInt(); peerRom = in.readUTF();
                out.writeUTF(myMode); out.writeInt(myCpu); out.writeUTF(myRom); out.flush();
            }
        } catch (Exception e) { netLost(e); return; }

        final String mm = myMode, pr = myRom, pm = peerMode, rr = peerRom;
        runOnUiThread(() -> {
            if (!mm.equals(pm)) { toastMain("NeoGeo 模式不一致：本机 " + mm + " / 对端 " + pm + "，已断开"); netLost(new Exception("mode mismatch")); return; }
            if (myCpu != peerCpu) { toastMain("CPU 频率不一致：本机 " + myCpu + "% / 对端 " + peerCpu + "%，已断开"); netLost(new Exception("cpu mismatch")); return; }
            if (!pr.equals(rr)) { toastMain("ROM 不一致，已断开"); netLost(new Exception("rom mismatch")); return; }
            // 严格起跑栅栏：先暂停模拟，再复位归零帧号，再同时恢复
            Native.netPause(1);
            if (loaded) Native.netRestart();
            Native.netPause(0);
            toastMain((mode == 1 ? "联机已建立：你是 1P" : "联机已建立：你是 2P") + "，配置已校验，同步起跑");
        });

        // 2) 严格同步：发送帧 + 帧号，接收帧 + 帧号
        final java.io.DataOutputStream fout = out; final java.io.DataInputStream fin = in;
        new Thread(() -> {
            try {
                while (netRunning) {
                    long v = Native.netPoll();
                    if (v < 0) { Thread.sleep(1); continue; }
                    fout.writeInt((int) (v >> 32));
                    fout.writeInt((int) v);
                    fout.flush();
                }
            } catch (Exception e) { netLost(e); }
        }).start();
        new Thread(() -> {
            try {
                while (netRunning) {
                    int f = fin.readInt(), m = fin.readInt();
                    Native.netDeliver(f, m);
                }
            } catch (Exception e) { netLost(e); }
        }).start();
    }

    private void netLost(Exception e) {
        if (!netRunning) return;
        netRunning = false;
        Native.netEnable(0, 0);
        toastMain("联机断开: " + e.getMessage());
    }

    private void stopNet() {
        boolean was = netRunning;
        netRunning = false;
        Native.netEnable(0, 0);
        try { if (netSocket != null) netSocket.close(); } catch (Exception ignored) {}
        try { if (netServer != null) netServer.close(); } catch (Exception ignored) {}
        netSocket = null;
        netServer = null;
        if (was) toastMain("已断开联机");
    }


    private String fileCrc4K(File f) {
        if (!f.exists()) return "missing";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4096];
            int n = in.read(b);
            if (n <= 0) return "empty";
            java.util.zip.CRC32 c = new java.util.zip.CRC32();
            c.update(b, 0, n);
            return Long.toHexString(c.getValue());
        } catch (Exception e) { return "err"; }
    }

    // ---------------- 画面设置 ----------------
    private void loadVideo() {
        String s = prefs().getString("video", null);
        if (s != null) {
            try {
                String[] a = s.split(",");
                vScale = Integer.parseInt(a[0]);
                vFilter = Integer.parseInt(a[1]);
                vBright = Integer.parseInt(a[2]);
                vContrast = Integer.parseInt(a[3]);
                vSat = Integer.parseInt(a[4]);
            } catch (Exception ignored) {
            }
        }
        applyVideo();
    }

    private void applyVideo() {
        Native.setVideo(vScale, vFilter, vBright / 100f, vContrast / 100f, vSat / 100f);
        prefs().edit().putString("video",
                vScale + "," + vFilter + "," + vBright + "," + vContrast + "," + vSat).apply();
    }

    private void videoSettings() {
        final String[] scaleNames = {"适应窗口", "整数倍缩放", "拉伸铺满"};
        final String[] filterNames = {"平滑", "锐利", "扫描线"};
        final String[] items = {
                "缩放：" + scaleNames[vScale],
                "滤镜：" + filterNames[vFilter],
                "亮度：" + vBright + "%",
                "对比度：" + vContrast + "%",
                "饱和度：" + vSat + "%",
                "恢复默认"
        };
        new AlertDialog.Builder(this)
                .setTitle("画面设置")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            new AlertDialog.Builder(this).setTitle("缩放")
                                    .setSingleChoiceItems(scaleNames, vScale, (d2, k) -> {
                                        vScale = k; applyVideo(); d2.dismiss(); videoSettings();
                                    }).show();
                            break;
                        case 1:
                            new AlertDialog.Builder(this).setTitle("滤镜")
                                    .setSingleChoiceItems(filterNames, vFilter, (d2, k) -> {
                                        vFilter = k; applyVideo(); d2.dismiss(); videoSettings();
                                    }).show();
                            break;
                        case 2: slider("亮度", vBright, v -> { vBright = v; applyVideo(); }); break;
                        case 3: slider("对比度", vContrast, v -> { vContrast = v; applyVideo(); }); break;
                        case 4: slider("饱和度", vSat, v -> { vSat = v; applyVideo(); }); break;
                        default:
                            vScale = 0; vFilter = 0; vBright = 100; vContrast = 100; vSat = 100;
                            applyVideo();
                            break;
                    }
                })
                .setNeutralButton("重开菜单", (d, w) -> videoSettings())
                .show();
    }

    private void slider(String title, final int cur, final java.util.function.IntConsumer onSet) {
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 32, 48, 16);
        final android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(cur + "%");
        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(100);
        sb.setProgress(cur - 50);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar b, int p, boolean u) {
                tv.setText((p + 50) + "%");
            }
            public void onStartTrackingTouch(android.widget.SeekBar b) {}
            public void onStopTrackingTouch(android.widget.SeekBar b) {}
        });
        box.addView(tv);
        box.addView(sb);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    int v = sb.getProgress() + 50;
                    onSet.accept(v);
                    videoSettings();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 键位设置 ----------------
    private void keySettings() {
        final String[] items = {
                "屏幕 A → " + KeyConfig.name(cfg.face[0]),
                "屏幕 B → " + KeyConfig.name(cfg.face[1]),
                "屏幕 C → " + KeyConfig.name(cfg.face[2]),
                "屏幕 D → " + KeyConfig.name(cfg.face[3]),
                "START → " + KeyConfig.name(cfg.start),
                "COIN → " + KeyConfig.name(cfg.coin),
                "组合键 M1 → " + KeyConfig.maskString(cfg.macro[0]),
                "组合键 M2 → " + KeyConfig.maskString(cfg.macro[1]),
        };
        new AlertDialog.Builder(this)
                .setTitle("键位设置")
                .setItems(items, (d, w) -> {
                    if (w < 6) pickSingle(w);
                    else pickMacro(w - 6);
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    cfg = new KeyConfig();
                    cfg.save(prefs());
                    pad.preset(cfg);
                })
                .show();
    }

    private void pickSingle(final int slot) {
        int cur = slot < 4 ? cfg.face[slot] : (slot == 4 ? cfg.start : cfg.coin);
        new AlertDialog.Builder(this)
                .setTitle("选择键位")
                .setSingleChoiceItems(KeyConfig.NAMES, cur, (d, w) -> {
                    if (slot < 4) cfg.face[slot] = w;
                    else if (slot == 4) cfg.start = w;
                    else cfg.coin = w;
                    cfg.save(prefs());
                    pad.preset(cfg);
                    d.dismiss();
                    keySettings();
                })
                .show();
    }

    private void pickMacro(final int i) {
        final boolean[] checked = new boolean[16];
        for (int k = 0; k < 16; k++) checked[k] = (cfg.macro[i] & (1 << k)) != 0;
        new AlertDialog.Builder(this)
                .setTitle("组合键 M" + (i + 1) + "（可多选，同时按下）")
                .setMultiChoiceItems(KeyConfig.NAMES, checked, (d, w, isChecked) -> checked[w] = isChecked)
                .setPositiveButton("确定", (d, w) -> {
                    int mask = 0;
                    for (int k = 0; k < 16; k++) if (checked[k]) mask |= (1 << k);
                    cfg.macro[i] = mask;
                    cfg.save(prefs());
                    pad.preset(cfg);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 日志查看 ----------------
    private void showLog() {
        final TextView tv = new TextView(this);
        tv.setTextSize(9f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFF9BE89B);
        tv.setBackgroundColor(0xFF0D0D0D);
        tv.setPadding(24, 24, 24, 24);
        final ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        final Handler h = new Handler();
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            tv.setText(tailLog(48 * 1024));
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            h.postDelayed(tick[0], 800);
        };
        h.post(tick[0]);

        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(sv)
                .setPositiveButton("关闭", (d, w) -> h.removeCallbacks(tick[0]))
                .setNegativeButton("复制", (d, w) -> {
                    h.removeCallbacks(tick[0]);
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("log", tailLog(200 * 1024)));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("清空", (d, w) -> {
                    h.removeCallbacks(tick[0]);
                    if (logFile != null) logFile.delete();
                })
                .show();
    }

    private String tailLog(int max) {
        if (logFile == null || !logFile.exists()) return "(暂无日志)";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - max);
            raf.seek(start);
            byte[] b = new byte[(int) (len - start)];
            raf.readFully(b);
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "(读取日志失败: " + e + ")";
        }
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, req);
    }

    private void importBios() { pick(REQ_BIOS); }

    private void importCore() { pick(REQ_CORE); }

    // ---------------- 游戏列表（SAF 目录） ----------------
    private void listGames() {
        final String t = prefs().getString("tree", null);
        if (t == null) { pickTree(); return; }
        new Thread(() -> {
            final TreeMap<String, String> map = new TreeMap<>();
            try {
                Uri tree = Uri.parse(t);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                Cursor c = getContentResolver().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        String n = c.getString(1), id = c.getString(0);
                        if (n != null && id != null && n.toLowerCase(Locale.ROOT).endsWith(".zip"))
                            map.put(n, id);
                    }
                    c.close();
                }
            } catch (Exception e) {
                toastMain("读取目录失败: " + e);
                return;
            }
            if (map.isEmpty()) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("目录里没有 .zip")
                        .setPositiveButton("换个目录", (d, w) -> pickTree())
                        .setNegativeButton("取消", null).show());
                return;
            }
            final String[] names = map.keySet().toArray(new String[0]);
            final String[] labels = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                String b = boardLabel(names[i]);
                labels[i] = b.isEmpty() ? names[i] : names[i] + "   · " + b;
            }
            final String[] ids = new String[names.length];
            int k = 0;
            for (Map.Entry<String, String> e : map.entrySet()) ids[k++] = e.getValue();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("选择游戏")
                    .setItems(labels, (d, i) -> {
                        Uri tree = Uri.parse(prefs().getString("tree", null));
                        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, ids[i]);
                        playUri(doc, names[i]);
                    })
                    .setNeutralButton("换目录", (d, w) -> pickTree())
                    .show());
        }).start();
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    // ---------------- 结果分发 ----------------
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        Uri uri = data.getData();

        if (req == REQ_TREE && uri != null) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            prefs().edit().putString("tree", uri.toString()).apply();
            listGames();
            return;
        }
        if (uri == null) return;

        if (req == REQ_CORE) {
            new Thread(() -> {
                try {
                    File dst = new File(getFilesDir(), "cores/libfbneo.so");
                    dst.getParentFile().mkdirs();
                    InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(dst);
                    byte[] b = new byte[1 << 16];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    out.close();
                    in.close();
                    toastMain("核心已导入：" + (dst.length() / 1024 / 1024) + " MB");
                    new Thread(this::boot).start();
                } catch (Exception e) {
                    toastMain("导入核心失败: " + e);
                }
            }).start();
            return;
        }

        if (req == REQ_BIOS) {
            new Thread(() -> {
                try {
                    String nm = displayName(uri).toLowerCase(Locale.ROOT);
                    if (!nm.endsWith(".zip")) nm = "neogeo.zip";
                    File dst = new File(systemDir, nm);
                    InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(dst);
                    byte[] b = new byte[1 << 16];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    out.close();
                    in.close();
                    toastMain("已导入 " + dst.getName());
                    String r = prefs().getString("rom", null);
                    if (r != null && new File(r).exists()) startGame(new File(r));
                    else runOnUiThread(this::listGames);
                } catch (Exception e) {
                    toastMain("导入 BIOS 失败: " + e);
                }
            }).start();
            return;
        }

        if (req == REQ_CHEAT) {
            new Thread(() -> {
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    InputStream in = getContentResolver().openInputStream(uri);
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                    in.close();
                    String txt = bo.toString("UTF-8");
                    prefs().edit().putString("cheat", txt).apply();
                    int cnt = Native.setCheats(txt);
                    toastMain("金手指已载入 " + cnt + " 条");
                } catch (Exception e) {
                    toastMain("金手指读取失败: " + e);
                }
            }).start();
            return;
        }

        if (req == REQ_ROM) playUri(uri, displayName(uri));
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        if (s == null) return "rom.zip";
        if (s.contains("/")) s = s.substring(s.lastIndexOf('/') + 1);
        if (s.contains(":")) s = s.substring(s.lastIndexOf(':') + 1);
        return s;
    }

    private void playUri(Uri uri, String name) {
        new Thread(() -> {
            try {
                File dir = new File(getFilesDir(), "roms");
                dir.mkdirs();
                String n = name.toLowerCase(Locale.ROOT).trim();
                if (!n.endsWith(".zip")) n = n + ".zip";
                n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
                File f = new File(dir, n);
                InputStream in = getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[1 << 16];
                int c;
                while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
                out.close();
                in.close();
                prefs().edit().putString("rom", f.getAbsolutePath()).apply();
                startGame(f);
            } catch (Exception e) {
                toastMain("读取 ROM 失败: " + e);
            }
        }).start();
    }

    // ---------------- 运行 ----------------
    private void startGame(File f) {
        if (!netRunning) {
            stopNet();
        }
        stopAudio();
        if (loaded) {
            Native.stop();
            loaded = false;
        }
        if (!initCore()) { toastMain("核心加载失败"); return; }
        romFile = f;
        String board = boardLabel(f.getName());
        Logger.log("loadRom " + f.getName() + " (" + f.length() + ")" + (board.isEmpty() ? "" : " board=" + board));
        int sr = Native.loadRom(f.getAbsolutePath());
        Logger.log("loadRom -> " + sr);
        if (sr <= 0) {
            toastMain("载入失败 code=" + sr + "\n检查 ROM 是否匹配本核心、neogeo.zip 是否已导入");
            romFile = null;
            prefs().edit().remove("rom").apply();
            runOnUiThread(this::listGames);
            return;
        }
        String cheat = prefs().getString("cheat", null);
        if (cheat != null) Native.setCheats(cheat);
        sampleRate = sr;
        Native.start();
        startAudio();
        loaded = true;
        runOnUiThread(() -> gl.requestRender());
    }

    private void saveState() {
        if (!loaded) { toastMain("没有运行中的游戏"); return; }
        int r = Native.saveState(new File(saveDir, "slot1.sav").getAbsolutePath());
        toastMain(r == 0 ? "已存档 slot1" : "存档失败 code=" + r);
    }

    private void loadState() {
        if (!loaded) { toastMain("没有运行中的游戏"); return; }
        File f = new File(saveDir, "slot1.sav");
        if (!f.exists()) { toastMain("没有存档"); return; }
        int r = Native.loadState(f.getAbsolutePath());
        toastMain(r == 0 ? "已读档 slot1" : "读档失败 code=" + r);
    }

    private void startAudio() {
        stopAudio();
        audio = new AudioThread(sampleRate);
        audio.start();
    }

    private void stopAudio() {
        if (audio != null) {
            audio.run = false;
            try { audio.join(500); } catch (InterruptedException ignored) {}
            audio = null;
        }
    }

    private void toastMain(final String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onPause() {
        super.onPause();
        hudHandler.removeCallbacks(hudTick);
        gl.onPause();
        if (loaded) Native.stop();
        stopAudio();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideUi();
        hudHandler.removeCallbacks(hudTick);
        if (prefs().getBoolean("hud", false)) hudHandler.post(hudTick);
        gl.onResume();
        if (loaded) {
            Native.start();
            startAudio();
        }
    }

    @Override
    protected void onDestroy() {
        if (loaded) { Native.stop(); loaded = false; }
        if (coreInited) { Native.deinit(); coreInited = false; }
        stopAudio();
        super.onDestroy();
    }

    private class Renderer implements GLSurfaceView.Renderer {
        public void onSurfaceCreated(GL10 g, EGLConfig c) { Native.glInit(); }
        public void onSurfaceChanged(GL10 g, int w, int h) {}
        public void onDrawFrame(GL10 g) { Native.glDraw(gl.getWidth(), gl.getHeight()); }
    }

    private static class AudioThread extends Thread {
        volatile boolean run = true;
        private final int rate;

        AudioThread(int rate) { this.rate = rate; }

        @Override
        public void run() {
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min * 2, 32768);
            AudioTrack t;
            try {
                t = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                        size, AudioTrack.MODE_STREAM);
                t.play();
            } catch (Exception e) {
                return;
            }
            short[] buf = new short[4096];
            while (run) {
                int n = Native.readAudio(buf, buf.length / 2);
                if (n > 0) t.write(buf, 0, n * 2);
                else {
                    try { Thread.sleep(2); } catch (InterruptedException e) { break; }
                }
            }
            try { t.stop(); } catch (Exception ignored) {}
            t.release();
        }
    }
}
