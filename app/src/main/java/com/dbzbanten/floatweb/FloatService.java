package com.dbzbanten.floatweb;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class FloatService extends Service {

    private static final String DEFAULT_URL =
            "https://script.google.com/macros/s/AKfycbwUvjDgI4hQOYv8mp3B5BjutAf-hz_dd3-e7DxEjZJokb0hjDQnV4V-17BOfyiieFet/exec";
    private static final String PREFS_NAME = "dbzfloat_prefs";
    private static final String KEY_LAST_URL = "last_url";

    private static final int MIN_WIDTH_DP = 200;
    private static final int MIN_HEIGHT_DP = 300;
    private static final int BUBBLE_SIZE_DP = 60;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private View root;
    private LinearLayout mainContent;
    private LinearLayout body;
    private FrameLayout handleBox;
    private WebView webView;
    private EditText urlInput;
    private TextView title;
    private Button defaultBtn;
    private Button closeBtn;
    private Button autoBtn;
    private Button bubbleBtn;

    private boolean minimized = false;
    private boolean autoRefresh = false;
    private int autoInterval = 10;
    private Handler handler;
    private Runnable autoRunnable;

    private int ow, oh;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "dbzfloat", "Floating", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(channel);

                Notification notif = new Notification.Builder(this, "dbzfloat")
                        .setContentTitle("DbzFloat")
                        .setContentText("Floating WebView aktif")
                        .setSmallIcon(android.R.drawable.ic_menu_view)
                        .build();
                startForeground(1, notif);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (root != null) return START_STICKY;

        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            root = buildUI();

            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            int flagsWin = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

            lp = new WindowManager.LayoutParams(
                    dp(340), dp(520), type, flagsWin, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 30;
            lp.y = 80;
            ow = lp.width;
            oh = lp.height;
            wm.addView(root, lp);
        } catch (Throwable t) {
            Toast.makeText(this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAuto();
        close();
        if (webView != null) {
            try { webView.destroy(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private View buildUI() {
        LinearLayout container = new LinearLayout(this);
        mainContent = container;
        container.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0D1117);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), 0xFFFF9800);
        container.setBackground(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF1A1A1E);
        header.setPadding(dp(12), dp(8), dp(6), dp(8));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        container.addView(header, headerLp);

        title = new TextView(this);
        title.setText("DbzFloat [Tap = PASTE URL]");
        title.setTextColor(0xFFFF9800);
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        Button minBtn = makeBtn("−", 0xFF666666);
        header.addView(minBtn, new LinearLayout.LayoutParams(dp(36), dp(30)));

        defaultBtn = makeBtn("D", 0xFFFF6600);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(36), dp(30));
        dLp.leftMargin = dp(4);
        header.addView(defaultBtn, dLp);

        closeBtn = makeBtn("X", 0xFFCC3333);
        LinearLayout.LayoutParams xLp = new LinearLayout.LayoutParams(dp(36), dp(30));
        xLp.leftMargin = dp(4);
        header.addView(closeBtn, xLp);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(12));
        container.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView label = new TextView(this);
        label.setText("MASUKKAN URL (Google Script / Web)");
        label.setTextColor(0xFF888888);
        label.setTextSize(11);
        body.addView(label);

        urlInput = new EditText(this);
        urlInput.setHint(DEFAULT_URL);
        urlInput.setHintTextColor(0xFF666666);
        urlInput.setTextColor(0xFFFFFFFF);
        urlInput.setTextSize(13);
        urlInput.setSingleLine(true);
        urlInput.setBackgroundColor(0xFF1A1A1E);
        urlInput.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(6);
        body.addView(urlInput, inputLp);

        FocusToggle toggle = new FocusToggle(this);
        urlInput.setOnTouchListener(toggle);
        urlInput.setOnFocusChangeListener(toggle);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(10), 0, dp(8));

        Button loadBtn = makeBtn("LOAD URL", 0xFFFF9800);
        loadBtn.setTextColor(0xFF000000);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        loadLp.rightMargin = dp(6);
        btnRow.addView(loadBtn, loadLp);

        autoBtn = makeBtn("10s", 0xFF333334);
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        autoLp.rightMargin = dp(6);
        btnRow.addView(autoBtn, autoLp);

        Button refreshBtn = makeBtn("REFRESH", 0xFFCCCCCC);
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        btnRow.addView(refreshBtn, refLp);

        body.addView(btnRow);

        TextView webviewLabel = new TextView(this);
        webviewLabel.setText("WEBVIEW");
        webviewLabel.setTextColor(0xFF888888);
        webviewLabel.setTextSize(11);
        body.addView(webviewLabel);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setBackgroundColor(0xFF000000);
        LinearLayout.LayoutParams wvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        wvLp.topMargin = dp(6);
        body.addView(webView, wvLp);

        loadSavedUrl();

        minBtn.setOnClickListener(v -> toggleBubble());

        defaultBtn.setOnClickListener(v -> {
            urlInput.setText(DEFAULT_URL);
            webView.loadUrl(DEFAULT_URL);
            Toast.makeText(this, "Default URL dimuat", Toast.LENGTH_SHORT).show();
        });

        closeBtn.setOnClickListener(v -> close());

        loadBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString();
            if (TextUtils.isEmpty(url)) return;
            webView.loadUrl(url);
            Prefs.set(this, url);
        });

        refreshBtn.setOnClickListener(v -> {
            stopAuto();
            if (webView != null) webView.reload();
        });

        autoBtn.setOnClickListener(v -> showIntervalDialog());

        title.setOnClickListener(v -> pasteFromClipboard());

        FrameLayout frameRoot = new FrameLayout(this);
        FrameLayout.LayoutParams frameLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        frameRoot.addView(container, frameLp);

        handleBox = new FrameLayout(this);
        frameRoot.addView(handleBox, frameLp);

        View moveHandle = new View(this);
        moveHandle.setBackgroundColor(0x33FFAA00);
        FrameLayout.LayoutParams moveLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        moveLp.gravity = Gravity.BOTTOM | Gravity.START;
        handleBox.addView(moveHandle, moveLp);
        moveHandle.setOnTouchListener(new MoveListener(this));

        View resizeHandle = new View(this);
        resizeHandle.setBackgroundColor(0x33FFFFFF);
        FrameLayout.LayoutParams resizeLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        resizeLp.gravity = Gravity.BOTTOM | Gravity.END;
        handleBox.addView(resizeHandle, resizeLp);
        resizeHandle.setOnTouchListener(new ResizeListener(this));

        bubbleBtn = new Button(this);
        bubbleBtn.setText("DBZ");
        bubbleBtn.setTextColor(0xFFFFFFFF);
        bubbleBtn.setTextSize(12);
        bubbleBtn.setAllCaps(false);
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(0xFFFF6700);
        bubbleBtn.setBackground(bubbleBg);
        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        frameRoot.addView(bubbleBtn, bubbleLp);
        bubbleBtn.setVisibility(View.GONE);
        bubbleBtn.setOnTouchListener(new BubbleListener(this));

        return frameRoot;
    }

    private Button makeBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(12);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void loadSavedUrl() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String url = sp.getString(KEY_LAST_URL, "");
        if (!TextUtils.isEmpty(url)) {
            urlInput.setText(url);
            webView.loadUrl(url);
        } else {
            urlInput.setText(DEFAULT_URL);
            webView.loadUrl(DEFAULT_URL);
        }
    }

    private void close() {
        stopAuto();
        if (root != null && wm != null) {
            try { wm.removeView(root); } catch (Throwable ignored) {}
            root = null;
        }
        try { stopForeground(true); } catch (Throwable ignored) {}
    }

    public void applyInterval(int seconds) {
        autoInterval = seconds;
        autoRefresh = true;
        if (autoBtn != null) autoBtn.setText(seconds + "s");
        scheduleAuto();
        Toast.makeText(this, "Auto refresh ON (" + seconds + "s)", Toast.LENGTH_SHORT).show();
    }

    private void scheduleAuto() {
        if (!autoRefresh) return;
        if (handler == null) handler = new Handler();
        if (autoRunnable == null) {
            autoRunnable = () -> {
                if (autoRefresh && webView != null) {
                    webView.reload();
                    scheduleAuto();
                }
            };
        }
        handler.removeCallbacks(autoRunnable);
        handler.postDelayed(autoRunnable, autoInterval * 1000L);
    }

    private void stopAuto() {
        autoRefresh = false;
        if (handler != null && autoRunnable != null) handler.removeCallbacks(autoRunnable);
    }

    public void showIntervalDialog() {
        final int[] options = {5, 10, 15, 30, 60};
        String[] labels = {"5 detik", "10 detik", "15 detik", "30 detik", "60 detik"};

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pilih Interval Auto Refresh")
                .setItems(labels, (d, which) -> applyInterval(options[which]))
                .setNegativeButton("Batal", null)
                .create();

        if (dialog.getWindow() != null) {
            WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
            attrs.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            dialog.getWindow().setAttributes(attrs);
        }
        dialog.show();
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).getText();
            if (text != null && urlInput != null) {
                urlInput.setText(text.toString());
                Toast.makeText(this, "Pasted: " + text, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show();
    }

    private void toggleBubble() {
        if (minimized) {
            minimized = false;
            lp.width = ow;
            lp.height = oh;
            mainContent.setVisibility(View.VISIBLE);
            handleBox.setVisibility(View.VISIBLE);
            bubbleBtn.setVisibility(View.GONE);
        } else {
            minimized = true;
            ow = lp.width;
            oh = lp.height;
            int size = dp(BUBBLE_SIZE_DP);
            lp.width = size;
            lp.height = size;
            mainContent.setVisibility(View.GONE);
            handleBox.setVisibility(View.GONE);
            bubbleBtn.setVisibility(View.VISIBLE);
        }
        wm.updateViewLayout(root, lp);
    }

    // ================== INNER CLASSES ==================

    static class BubbleListener implements View.OnTouchListener {
        private final FloatService s;
        private float startX, startY;
        private int startParamX, startParamY;

        BubbleListener(FloatService service) { this.s = service; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    startParamX = s.lp.x; startParamY = s.lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    s.lp.x = startParamX + (int)(e.getRawX() - startX);
                    s.lp.y = startParamY + (int)(e.getRawY() - startY);
                    s.wm.updateViewLayout(s.root, s.lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(e.getRawX() - startX);
                    float dy = Math.abs(e.getRawY() - startY);
                    if (dx < 10 && dy < 10) s.toggleBubble();
                    return true;
            }
            return false;
        }
    }

    static class MoveListener implements View.OnTouchListener {
        private final FloatService s;
        private float startX, startY;
        private int startParamX, startParamY;

        MoveListener(FloatService service) { this.s = service; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    startParamX = s.lp.x; startParamY = s.lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    s.lp.x = startParamX + (int)(e.getRawX() - startX);
                    s.lp.y = startParamY + (int)(e.getRawY() - startY);
                    s.wm.updateViewLayout(s.root, s.lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        }
    }

    static class ResizeListener implements View.OnTouchListener {
        private final FloatService s;
        private float startX, startY;
        private int startW, startH;

        ResizeListener(FloatService service) { this.s = service; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    startW = s.lp.width; startH = s.lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int newW = startW + (int)(e.getRawX() - startX);
                    int newH = startH + (int)(e.getRawY() - startY);
                    newW = Math.max(newW, s.dp(MIN_WIDTH_DP));
                    newH = Math.max(newH, s.dp(MIN_HEIGHT_DP));
                    s.lp.width = newW; s.lp.height = newH;
                    s.wm.updateViewLayout(s.root, s.lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        }
    }

    static class FocusToggle implements View.OnTouchListener, View.OnFocusChangeListener {
        private final FloatService s;
        FocusToggle(FloatService service) { this.s = service; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                s.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                s.wm.updateViewLayout(s.root, s.lp);
            }
            return false;
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (!hasFocus) {
                s.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                s.wm.updateViewLayout(s.root, s.lp);
            }
        }
    }
}