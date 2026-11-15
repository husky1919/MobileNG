package com.ng.emu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * 虚拟手柄。支持：
 *  - 自动布局（按键塞进 4:3 画面两侧黑边）
 *  - 自定义位置（编辑模式拖动，归一化保存，换分辨率不跑位）
 *  - 改键位 / 组合键（KeyConfig）
 */
public class PadView extends View {

    private static final String[] LABEL = {"A", "B", "C", "D"};
    // 元素索引：0=摇杆 1~4=A B C D 5~6=M1 M2 7=START 8=COIN
    private static final int N = 9;

    public interface Listener { void onLayoutChanged(String layout); }
    private Runnable pauseListener;
    public void setPauseListener(Runnable r) { pauseListener = r; }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private KeyConfig cfg = new KeyConfig();
    private Listener listener;

    private float unit;
    private float dpCx, dpCy, dpR;
    private final float[] bx = new float[4], by = new float[4];
    private final float[] mx = new float[2], my = new float[2];
    private float mR;
    private float btnR;
    private final RectF[] rect = {new RectF(), new RectF()};
    private final RectF pauseRect = new RectF();

    private final float[] ex = new float[N], ey = new float[N];
    private final float[] nx = new float[N], ny = new float[N];
    private boolean custom = false;

    private boolean edit = false;
    private int dragIdx = -1;
    private float dragOffX, dragOffY;

    private int dpPointer = -1;
    private final boolean[] dpState = new boolean[4];
    private final SparseIntArray pointerBtn = new SparseIntArray();

    public PadView(Context c) {
        super(c);
        fill.setColor(0x44FFFFFF);
        stroke.setColor(0x88FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        text.setColor(0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        editStroke.setColor(0xFFFFC24D);
        editStroke.setStyle(Paint.Style.STROKE);
        editStroke.setStrokeWidth(4f);
    }

    public void preset(KeyConfig c) { cfg = c; invalidate(); }

    public void setListener(Listener l) { listener = l; }

    public void setEditMode(boolean on) {
        edit = on;
        dragIdx = -1;
        if (on) releaseAll();
        invalidate();
    }

    public boolean isEditMode() { return edit; }

    // ---------- 布局：归一化保存 ----------
    private void collect() {
        ex[0] = dpCx; ey[0] = dpCy;
        for (int i = 0; i < 4; i++) { ex[1 + i] = bx[i]; ey[1 + i] = by[i]; }
        ex[5] = mx[0]; ey[5] = my[0];
        ex[6] = mx[1]; ey[6] = my[1];
        ex[7] = rect[0].centerX(); ey[7] = rect[0].centerY();
        ex[8] = rect[1].centerX(); ey[8] = rect[1].centerY();
    }

    private void apply() {
        dpCx = ex[0]; dpCy = ey[0];
        for (int i = 0; i < 4; i++) { bx[i] = ex[1 + i]; by[i] = ey[1 + i]; }
        mx[0] = ex[5]; my[0] = ey[5];
        mx[1] = ex[6]; my[1] = ey[6];
        moveRect(rect[0], ex[7], ey[7]);
        moveRect(rect[1], ex[8], ey[8]);
    }

    private void moveRect(RectF r, float cx, float cy) {
        float hw = r.width() / 2f, hh = r.height() / 2f;
        r.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    public String currentLayout() {
        collect();
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < N; i++) {
            if (i > 0) sb.append(',');
            sb.append(Math.round(ex[i] * 1000f / w)).append(',').append(Math.round(ey[i] * 1000f / h));
        }
        return sb.toString();
    }

    public void setCustomLayout(String s) {
        if (s == null) return;
        String[] a = s.split(",");
        if (a.length < N * 2) return;
        try {
            for (int i = 0; i < N; i++) {
                nx[i] = Integer.parseInt(a[i * 2]) / 1000f;
                ny[i] = Integer.parseInt(a[i * 2 + 1]) / 1000f;
            }
            custom = true;
            if (getWidth() > 0) relayout(getWidth(), getHeight());
            invalidate();
        } catch (Exception ignored) {
        }
    }

    public void clearCustomLayout() {
        custom = false;
        if (getWidth() > 0) relayout(getWidth(), getHeight());
        invalidate();
    }

    // ---------- 自动布局 ----------
    private void bottomLayout(int w, int h, float u) {
        dpR = u * 2.4f;
        dpCx = dpR + u * 0.5f;
        dpCy = h - dpR - u * 0.5f;

        btnR = u * 0.95f;
        for (int i = 0; i < 4; i++) {
            bx[i] = w - u * (1.6f + (3 - i) * 2.1f);
            by[i] = h - u * 2.6f;
        }
        mR = btnR * 0.75f;
        mx[0] = u * 2.2f; my[0] = u * 1.1f;
        mx[1] = u * 4.6f; my[1] = u * 1.1f;

        rect[0].set(w - u * 6.4f, u * 0.4f, w - u * 3.4f, u * 1.6f);
        rect[1].set(w - u * 3.1f, u * 0.4f, w - u * 0.3f, u * 1.6f);
        pauseRect.set(u * 0.3f, u * 0.4f, u * 2.3f, u * 1.6f);
    }

    private void sideLayout(int w, int h, float u, float bar) {
        dpR = Math.min(u * 2.3f, bar / 2f - u * 0.2f);
        dpCx = bar / 2f;
        dpCy = h / 2f + u * 0.7f;

        float rx = w - bar / 2f;
        btnR = Math.min(u * 0.95f, bar / 2f - u * 0.3f);
        float gx = Math.min(btnR + u * 0.2f, bar / 2f - btnR * 0.7f);
        float gy = btnR + u * 0.3f;

        bx[0] = rx - gx; by[0] = dpCy - gy;
        bx[1] = rx + gx; by[1] = dpCy - gy;
        bx[2] = rx - gx; by[2] = dpCy + gy;
        bx[3] = rx + gx; by[3] = dpCy + gy;

        mR = btnR * 0.68f;
        mx[0] = rx - gx; my[0] = u * 0.85f;
        mx[1] = rx + gx; my[1] = u * 0.85f;

        float y1 = my[0] + mR + btnR * 0.75f;
        float hh = btnR * 0.5f;
        rect[0].set(rx - gx - btnR, y1 - hh, rx - gx + btnR, y1 + hh);
        rect[1].set(rx + gx - btnR, y1 - hh, rx + gx + btnR, y1 + hh);
        float pw = bar * 0.7f;
        pauseRect.set(bar / 2f - pw / 2f, u * 0.5f, bar / 2f + pw / 2f, u * 0.5f + btnR * 1.1f);
    }

    private void relayout(int w, int h) {
        unit = h / 10f;
        float contentW = h * 4f / 3f;
        float bar = (w - contentW) / 2f;
        if (bar >= unit * 3.2f) sideLayout(w, h, unit, bar);
        else bottomLayout(w, h, unit);

        if (custom) {
            for (int i = 0; i < N; i++) { ex[i] = nx[i] * w; ey[i] = ny[i] * h; }
            apply();
        }
        text.setTextSize(btnR * 0.9f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        relayout(w, h);
    }

    // ---------- 绘制 ----------
    @Override
    protected void onDraw(Canvas c) {
        c.drawCircle(dpCx, dpCy, dpR, fill);
        c.drawCircle(dpCx, dpCy, dpR, stroke);
        c.drawLine(dpCx - dpR * 0.7f, dpCy, dpCx + dpR * 0.7f, dpCy, stroke);
        c.drawLine(dpCx, dpCy - dpR * 0.7f, dpCx, dpCy + dpR * 0.7f, stroke);

        Paint t2 = new Paint(text);
        t2.setTextSize(btnR * 0.7f);

        for (int i = 0; i < 4; i++) {
            c.drawCircle(bx[i], by[i], btnR, fill);
            c.drawCircle(bx[i], by[i], btnR, stroke);
            c.drawText(LABEL[i], bx[i], by[i] + btnR * 0.32f, text);
        }

        for (int i = 0; i < 2; i++) {
            Paint f = new Paint(fill);
            f.setColor(cfg.macro[i] == 0 ? 0x22FFFFFF : 0x66FFD27F);
            c.drawCircle(mx[i], my[i], mR, f);
            c.drawCircle(mx[i], my[i], mR, stroke);
            c.drawText("M" + (i + 1), mx[i], my[i] + mR * 0.32f, t2);
        }

        for (int i = 0; i < 2; i++) {
            c.drawRoundRect(rect[i], btnR * 0.35f, btnR * 0.35f, fill);
            c.drawRoundRect(rect[i], btnR * 0.35f, btnR * 0.35f, stroke);
            c.drawText(i == 0 ? "START" : "COIN",
                    rect[i].centerX(), rect[i].centerY() + btnR * 0.28f, text);
        }

        c.drawRoundRect(pauseRect, btnR * 0.35f, btnR * 0.35f, fill);
        c.drawRoundRect(pauseRect, btnR * 0.35f, btnR * 0.35f, stroke);
        c.drawText("❚❚", pauseRect.centerX(), pauseRect.centerY() + btnR * 0.28f, text);

        if (edit) {
            c.drawRoundRect(pauseRect, btnR * 0.35f, btnR * 0.35f, editStroke);
            c.drawCircle(dpCx, dpCy, dpR + 6, editStroke);
            for (int i = 0; i < 4; i++) c.drawCircle(bx[i], by[i], btnR + 6, editStroke);
            for (int i = 0; i < 2; i++) c.drawCircle(mx[i], my[i], mR + 6, editStroke);
            for (int i = 0; i < 2; i++) c.drawRoundRect(rect[i], btnR * 0.35f, btnR * 0.35f, editStroke);
            Paint hint = new Paint(text);
            hint.setColor(0xFFFFC24D);
            hint.setTextSize(unit * 0.7f);
            c.drawText("编辑模式：拖动按钮，返回键退出", getWidth() / 2f, unit * 1.4f, hint);
        }
    }

    // ---------- 输入 ----------
    private void press(int id, boolean on) {
        if (id >= 0 && id < 16) Native.setButton(0, id, on);
    }

    private void pressMask(int mask, boolean on) {
        for (int i = 0; i < 16; i++) if ((mask & (1 << i)) != 0) press(i, on);
    }

    private float dist(float x, float y, float cx, float cy) {
        return (float) Math.hypot(x - cx, y - cy);
    }

    private void dpad(float x, float y) {
        float dx = (x - dpCx) / dpR, dy = (y - dpCy) / dpR;
        setDp(0, 4, dy < -0.35f);
        setDp(1, 5, dy > 0.35f);
        setDp(2, 6, dx < -0.35f);
        setDp(3, 7, dx > 0.35f);
        invalidate();
    }

    private void setDp(int k, int id, boolean on) {
        if (dpState[k] != on) {
            dpState[k] = on;
            press(id, on);
        }
    }

    private void clearDpad() {
        for (int k = 0; k < 4; k++) setDp(k, 4 + k, false);
        dpPointer = -1;
        invalidate();
    }

    private int hit(float x, float y) {
        for (int i = 0; i < 4; i++)
            if (dist(x, y, bx[i], by[i]) <= btnR * 1.25f) return i;
        for (int i = 0; i < 2; i++)
            if (dist(x, y, mx[i], my[i]) <= mR * 1.3f) return 6 + i;
        if (rect[0].contains(x, y)) return 4;
        if (rect[1].contains(x, y)) return 5;
        if (pauseRect.contains(x, y)) return 9;
        return -1;
    }

    private void pressIndex(int idx, boolean on) {
        if (idx < 0) return;
        if (idx < 4) press(cfg.face[idx], on);
        else if (idx == 4) press(cfg.start, on);
        else if (idx == 5) press(cfg.coin, on);
        else pressMask(cfg.macro[idx - 6], on);
    }

    private boolean inside(int idx, float x, float y) {
        if (idx < 4) return dist(x, y, bx[idx], by[idx]) <= btnR * 1.35f;
        if (idx < 6) return rect[idx - 4].contains(x, y);
        return dist(x, y, mx[idx - 6], my[idx - 6]) <= mR * 1.4f;
    }

    // ---------- 编辑模式：拖动 ----------
    private int nearest(float x, float y) {
        for (int i = 0; i < 4; i++)
            if (dist(x, y, bx[i], by[i]) <= btnR * 1.35f) return 1 + i;
        for (int i = 0; i < 2; i++)
            if (dist(x, y, mx[i], my[i]) <= mR * 1.45f) return 5 + i;
        if (rect[0].contains(x, y)) return 7;
        if (rect[1].contains(x, y)) return 8;
        if (dist(x, y, dpCx, dpCy) <= dpR * 1.3f) return 0;
        return -1;
    }

    private void drag(float x, float y) {
        float px = x + dragOffX, py = y + dragOffY;
        px = Math.max(0, Math.min(getWidth(), px));
        py = Math.max(0, Math.min(getHeight(), py));
        ex[dragIdx] = px;
        ey[dragIdx] = py;
        apply();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (edit) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    collect();
                    int i = nearest(e.getX(), e.getY());
                    if (i >= 0) {
                        dragIdx = i;
                        dragOffX = ex[i] - e.getX();
                        dragOffY = ey[i] - e.getY();
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    if (dragIdx >= 0) drag(e.getX(), e.getY());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragIdx >= 0) {
                        dragIdx = -1;
                        if (listener != null) listener.onLayoutChanged(currentLayout());
                    }
                    break;
            }
            return true;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(e.getPointerId(e.getActionIndex()), e.getX(e.getActionIndex()), e.getY(e.getActionIndex()));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++)
                    move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(e.getPointerId(e.getActionIndex()));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        return true;
    }

    private void down(int id, float x, float y) {
        if (dpPointer < 0 && dist(x, y, dpCx, dpCy) <= dpR * 1.35f) {
            dpPointer = id;
            dpad(x, y);
            return;
        }
        int h = hit(x, y);
        if (h == 9) {
            if (pauseListener != null) pauseListener.run();
            return;
        }
        if (h >= 0) {
            pointerBtn.put(id, h);
            pressIndex(h, true);
            invalidate();
        }
    }

    private void move(int id, float x, float y) {
        if (id == dpPointer) { dpad(x, y); return; }
        int idx = pointerBtn.get(id, -1);
        if (idx < 0) return;
        if (!inside(idx, x, y)) {
            pressIndex(idx, false);
            pointerBtn.delete(id);
            invalidate();
        }
    }

    private void up(int id) {
        if (id == dpPointer) { clearDpad(); return; }
        int idx = pointerBtn.get(id, -1);
        if (idx >= 0) {
            pressIndex(idx, false);
            pointerBtn.delete(id);
            invalidate();
        }
    }

    private void releaseAll() {
        for (int i = 0; i < pointerBtn.size(); i++) pressIndex(pointerBtn.valueAt(i), false);
        pointerBtn.clear();
        clearDpad();
    }
}
