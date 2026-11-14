package com.ng.emu;

import android.content.SharedPreferences;

/** 键位配置：屏幕按键 -> libretro 键位 id，外加两个组合键(位掩码) */
public class KeyConfig {
    public static final String[] NAMES = {
            "B", "Y", "SELECT", "START", "UP", "DOWN", "LEFT", "RIGHT",
            "A", "X", "L", "R", "L2", "R2", "L3", "R3"
    };

    public final int[] face = {0, 8, 1, 9};   // 屏幕 A B C D
    public int start = 3;
    public int coin = 2;
    public final int[] macro = {0, 0};        // 组合键位掩码，0=关

    public static KeyConfig load(SharedPreferences p) {
        KeyConfig k = new KeyConfig();
        String s = p.getString("keys", null);
        if (s == null) return k;
        try {
            String[] a = s.split(",");
            for (int i = 0; i < 4; i++) k.face[i] = Integer.parseInt(a[i]);
            k.start = Integer.parseInt(a[4]);
            k.coin = Integer.parseInt(a[5]);
            k.macro[0] = Integer.parseInt(a[6]);
            k.macro[1] = Integer.parseInt(a[7]);
        } catch (Exception ignored) {
        }
        return k;
    }

    public void save(SharedPreferences p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(face[i]).append(',');
        sb.append(start).append(',').append(coin).append(',')
                .append(macro[0]).append(',').append(macro[1]);
        p.edit().putString("keys", sb.toString()).apply();
    }

    public static String maskString(int mask) {
        if (mask == 0) return "关";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if ((mask & (1 << i)) != 0) {
                if (sb.length() > 0) sb.append('+');
                sb.append(NAMES[i]);
            }
        }
        return sb.toString();
    }

    public static String name(int id) {
        return id >= 0 && id < 16 ? NAMES[id] : ("?" + id);
    }
}
