package com.rsxm.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.view.WindowManager;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import rikka.shizuku.Shizuku;

/**
 * Reasonix Proot —— 在 Android 上通过 proot 运行 Alpine Linux 环境，
 * 启动后自动进入 reasonix AI 编码助手（TUI）。
 *
 * 架构：
 *   WebView (xterm.js 终端)  <->  Java 管道  <->  pty-bridge（静态 musl，提供 PTY）
 *                                                          └─ proot -> Alpine -> entry.sh -> reasonix
 */
public class MainActivity extends Activity {

    private static final String TAG = "ReasonixProot";
    private static final int REQ_UPDATE_RESONIX = 200;
    private static final int REQ_SKILL_IMPORT = 201;
    private static final int REQ_CREATE_ENV_TEMPLATE = 202;
    private static final int REQ_IMPORT_ENV_TEMPLATE = 203;
    /** 官方更新源：@reasonix/cli-linux-arm64（npm 平台二进制包，npmmirror 国内镜像） */
    private static final String REASONIX_DEFAULT_URL =
            "https://registry.npmmirror.com/@reasonix/cli-linux-arm64/-/cli-linux-arm64-1.31.4.tgz";
    /**
     * 上下滑动调速档位：1~10（prefs 键 scroll_speed，默认 5）。
     * 档位越小滑动越慢：SCROLL_STEP = 档位换算的每页滑动像素数
     *  档位 1 → 500px/页（最慢，精细浏览）
     *  档位 5 → 100px/页（默认，翻看历史）
     *  档位 10 → 10px/页（最快，接近原版 8px/页）
     */
    public static final int SPEED_MIN = 1;
    public static final int SPEED_MAX = 10;
    public static final int SPEED_DEFAULT = 5;

    private WebView webView;
    // proot 进程与输入流静态持有：后台运行模式下与 Activity 生命周期解耦，
    // Activity 重建（系统回收/返回后重开）时无需重启环境，终端 I/O 可无缝续接。
    private static volatile Process sProotProcess;
    private static volatile OutputStream sProcIn;
    /** 当前活动实例：后台 reader 线程输出经它转发到活动终端（重建后指向新实例） */
    private static volatile MainActivity sCurrent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean environmentStarted = false;
    /** WebView 页面加载完成标记（onPageFinished 置位，超时未完成则重载页面） */
    private volatile boolean pageLoaded = false;
    /** 复用环境标记：后台模式开启时 Activity 重建复用运行中的 proot 环境 */
    private volatile boolean reuseEnv = false;
    /** 开发环境安装中标记：防止并发安装互相覆盖 guest 内 .env-done/.env-install.log */
    private volatile boolean devEnvInstalling = false;
    /** 正在安装的环境名称（null 表示无任务；面板重开时据此恢复禁用/提示状态） */
    private volatile String devEnvInstallingName = null;
    /** apk 日志进度模式：(x/N) Installing ... */
    private static final Pattern APK_PROGRESS = Pattern.compile("\\((\\d+)/(\\d+)\\)");
    private DrawerLayout drawerLayout;
    private TextView tvStatus;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 启动加速：无 Activity 过渡动画
        overridePendingTransition(0, 0);
        sCurrent = this;
        // 重新打开（Activity 重建）时清理上次残留的 proot/pty-bridge/reasonix 进程，
        // 避免双环境并存的 PTY 竞争导致 reasonix CLI 排版错乱。
        // 例外：后台运行模式下旧环境仍在运行 → 直接复用（进程/流静态持有，与 Activity
        // 解耦，终端 I/O 无缝续接），不清理不重启，避免 AI 会话在后台中断。
        boolean bgMode = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("background_mode", false);
        if (bgMode && sProotProcess != null && sProotProcess.isAlive()) {
            environmentStarted = true;
            reuseEnv = true;      // 复用环境：新 WebView 空白，需强制 reasonix 重绘 TUI
            startRootPolling();   // 复用环境：恢复 root 命令桥轮询（onDestroy 已停）
            Log.d(TAG, "background mode: reusing running proot environment");
        } else {
            killProotTree();
        }

        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        tvStatus = findViewById(R.id.tv_status);
        webView = findViewById(R.id.webview);
        // 二次开启黑屏修复：硬件渲染在部分设备出现 userfaultfd 卡死（logcat:
        // "userfaultfd: MOVE ioctl seems unsupported: Connection timed out"）导致
        // onPageFinished 不触发、xterm 不渲染 → 终端黑屏；改软件渲染绕过。
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // JS -> Java 桥（键盘输入）
        webView.addJavascriptInterface(this, "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                pageLoaded = true;
                // 页面(重)加载完成后注入当前滑动调速档位（WebView 重载后 JS 变量重置）
                applyScrollSpeed(getSharedPreferences("prefs", MODE_PRIVATE)
                        .getInt("scroll_speed", SPEED_DEFAULT));
                // 聚焦 WebView 触发 xterm 渲染，避免启动后需点击才显示 CLI 界面
                try { view.requestFocus(); } catch (Exception ignored) {}
                if (reuseEnv) {
                    // 复用环境（后台模式开启 + Activity 重建）：新 WebView 终端空白，
                    // reasonix 不感知新终端，强制重绘完整 TUI（微调列数触发 SIGWINCH 再恢复）
                    reuseEnv = false;
                    view.postDelayed(() -> {
                        try {
                            view.evaluateJavascript(
                                    "if(window.Android&&Android.resize){Android.resize(term.rows,Math.max(1,term.cols-1));"
                                            + "setTimeout(function(){Android.resize(term.rows,term.cols);},200);}", null);
                        } catch (Exception ignored) {}
                    }, 600);
                }
                if (!environmentStarted) {
                    environmentStarted = true;
                    new Thread(MainActivity.this::startEnvironment).start();
                }
            }
        });
        webView.loadUrl("file:///android_asset/web/index.html");

        // onPageFinished 超时重载：userfaultfd 渲染卡死时页面可能一直不完成，4s 后重载恢复
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!pageLoaded && webView != null) {
                Log.w(TAG, "onPageFinished 超时，重载 index.html");
                try { webView.reload(); } catch (Exception ignored) {}
            }
        }, 4000);

        // 兜底启动：环境启动不依赖 WebView 渲染。二次开启时 WebView 可能因渲染线程
        // 卡住（logcat: userfaultfd: MOVE ioctl seems unsupported: Connection timed out）
        // 导致 onPageFinished 不触发 → 环境永不启动 → 终端黑屏。
        // onPageFinished 正常触发会先启动环境（environmentStarted 置位），此处 4s 后跳过。
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!environmentStarted) {
                environmentStarted = true;
                Log.w(TAG, "onPageFinished 未触发（WebView 渲染异常），兜底启动环境");
                new Thread(MainActivity.this::startEnvironment).start();
            }
        }, 4000);

        // 标题栏菜单按钮：打开侧滑配置列表
        findViewById(R.id.btn_menu).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START, false));
        // 侧滑菜单功能
        findViewById(R.id.menu_adb).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showAdbDialog(); });
        findViewById(R.id.menu_apikey).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showApiKeyConfigDialog(); });
        findViewById(R.id.menu_ds2api).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showDs2ApiDialog(); });
        findViewById(R.id.menu_update).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showUpdateResonixDialog(); });
        findViewById(R.id.menu_bgmode).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences("prefs", MODE_PRIVATE);
            boolean on = !sp.getBoolean("background_mode", false);
            sp.edit().putBoolean("background_mode", on).apply();
            updateBgModeLabel();
            drawerLayout.closeDrawer(GravityCompat.START, false);
            // 注意：不向终端 pushOutput 提示文本——reasonix 在 alt screen 全屏自绘，
            // 插入的文本会污染 TUI 画面（错误 screen 状态）；状态由菜单标签显示。
            if (on) {
                startBackgroundService(true);
            } else {
                stopBackgroundService();
            }
        });
        updateBgModeLabel();
        findViewById(R.id.menu_yolo).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences("prefs", MODE_PRIVATE);
            boolean on = !sp.getBoolean("yolo_mode", true);
            sp.edit().putBoolean("yolo_mode", on).apply();
            syncYoloMark(on);
            updateYoloModeLabel();
            drawerLayout.closeDrawer(GravityCompat.START, false);
            // 立即生效：重启 reasonix 环境（reasonix 启动时 wrapper 读取新标记决定审批模式）。
            // 不 pushOutput（reasonix alt screen 自绘会污染 TUI）；状态由菜单标签与重启日志显示。
            restartEnvironment();
        });
        updateYoloModeLabel();
        // 升级安装后 rootfs 可能没有 YOLO 标记：以偏好为准补写（默认开启）
        syncYoloMark(getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("yolo_mode", true));
        // 上下滑动调速：SeekBar 档位 1~10 ⇄ 每页滑动像素数 SCROLL_STEP
        // 档位越小每页所需像素越多 → 滑动越慢（精细浏览）；档位越大越快。
        // 换算：SCROLL_STEP = 1000 / 档位（见 scrollStepForSpeed：
        //       档位 1 → 500px/页（最慢） 5 → 100px/页（默认） 10 → 10px/页（最快，接近原 8px/页）
        {
            final SeekBar sbSpeed = findViewById(R.id.sb_speed);
            final TextView tvSpeedValue = findViewById(R.id.tv_speed_value);
            int speed = getSharedPreferences("prefs", MODE_PRIVATE).getInt("scroll_speed", SPEED_DEFAULT);
            if (speed < SPEED_MIN) speed = SPEED_MIN;
            if (speed > SPEED_MAX) speed = SPEED_MAX;
            sbSpeed.setMax(SPEED_MAX - SPEED_MIN);
            sbSpeed.setProgress(speed - SPEED_MIN);
            updateSpeedLabel(speed);
            // 先注入当前档位（页面可能已加载；若未加载，onPageFinished 启动环境时也会注入）
            applyScrollSpeed(speed);
            sbSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    int speed = seekBar.getProgress() + SPEED_MIN;
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("scroll_speed", speed).apply();
                    applyScrollSpeed(speed);
                    updateSpeedLabel(speed);
                }
            });
        }
        findViewById(R.id.menu_root).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showRootDialog(); });
        findViewById(R.id.menu_keys).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showKeysDialog(); });
        findViewById(R.id.menu_skill).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showSkillInstallDialog(); });
        findViewById(R.id.menu_project).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showProjectDialog(); });
        findViewById(R.id.menu_sessions).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showSessionsDialog(); });
        findViewById(R.id.menu_dev).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showDevEnvDialog(); });

        // 全屏功能面板：返回按钮关闭（系统返回键同样生效）
        findViewById(R.id.panel_back).setOnClickListener(v -> hidePanel());

        // 测试/调试入口：am start -e force_reinstall true 模拟无 root 设备的自动修复流程
        if (getIntent().getBooleanExtra("force_reinstall", false)) {
            new Handler(Looper.getMainLooper()).postDelayed(this::promptReinstallForNativeLib, 3000);
        }

        requestStoragePermission();

        // 后台运行模式恢复：上次开启过（app 被系统回收后重新打开）→ 重新拉起保活服务，
        // 使进程不被系统回收，proot/reasonix 环境得以在后台继续运行。
        if (getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("background_mode", false)) {
            startBackgroundService(false);
        }
    }

    /* ==================== Root 权限（KernelSU/Magisk） ==================== */

    /** root 命令桥：guest 写 /root/.root-cmd → 此轮询执行 su → 结果写 /root/.root-out */
    private final Handler rootPoller = new Handler(Looper.getMainLooper());
    private final Runnable rootPollTask = new Runnable() {
        @Override
        public void run() {
            processRootCommandQueue();
            rootPoller.postDelayed(this, 1500);
        }
    };

    private void startRootPolling() {
        Log.d(TAG, "root polling started");
        rootPoller.removeCallbacks(rootPollTask);
        probeRootAndMark();   // 预检 root 并写 .root-ok（guest adb wrapper 据此直连 root 桥）
        rootPoller.postDelayed(rootPollTask, 1500);
    }

    private void stopRootPolling() {
        rootPoller.removeCallbacks(rootPollTask);
    }

    /** 处理 guest 的 root 命令队列（一次一个，串行；结果写回 .root-out 带 __DONE__ 标记） */
    private void processRootCommandQueue() {
        try {
            File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
            File cmdFile = new File(rootDir, ".root-cmd");
            File outFile = new File(rootDir, ".root-out");
            if (!cmdFile.exists()) return;
            String cmd = new String(java.nio.file.Files.readAllBytes(cmdFile.toPath()),
                    StandardCharsets.UTF_8).trim();
            cmdFile.delete();
            if (cmd.isEmpty()) return;
            Log.d(TAG, "root bridge cmd: " + cmd);
            new Thread(() -> {
                String r = execRootCommand(cmd, 25);
                try {
                    java.nio.file.Files.write(outFile.toPath(),
                            ((r == null ? "(root 执行失败，请检查授权)" : r) + "\n__DONE__")
                                    .getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    Log.w(TAG, "root out write failed", e);
                }
            }, "root-bridge").start();
        } catch (Exception e) {
            Log.w(TAG, "root queue failed", e);
        }
    }

    /** Root 权限对话框：检测状态 + 授权引导 + 测试 */
    private void showRootDialog() {
        String su = findSuPath();
        TextView status = new TextView(this);
        status.setTextSize(14);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView tip = createDarkTip(
                "reasonix 内可执行 root <命令> 获取手机 root 权限（如 root id、root 'pm list packages'）。\n"
                        + "首次执行会弹出 root 授权请求（KernelSU/Magisk），请允许。\n"
                        + "⚠ root 可完全控制系统，请勿执行未知命令。");
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        addV(panel, status, 0);
        addV(panel, tip, 8);
        Button testBtn = createDarkButton("测试 root（执行 id）");
        addV(panel, testBtn, 10);
        TextView result = createDarkResult();
        result.setText("（测试结果将显示在这里）");
        addV(panel, result, 8);

        // 运行模式切换：proot（默认）/ chroot（root 直入，SELinux 保持 enforcing 下 JVM 亦可用）
        final boolean chrootNow = isChrootMode();
        panel.addView(createDarkSectionTitle("运行模式"));
        TextView modeTip = createDarkTip(
                "运行模式：" + (chrootNow ? "chroot（root 直入）" : "proot（默认）") + "\n"
                        + "chroot 使用 root 直接 chroot 进环境（需 root 授权），SELinux 保持 enforcing 时\n"
                        + "JVM/安卓开发环境亦正常（proot 模式 enforcing 下不可用）；切换会重启 reasonix 环境。");
        modeTip.setTextColor(chrootNow ? 0xFF7FDB8A : 0xFFAAAAAA);
        modeTip.setPadding(0, dp(2), 0, dp(4));
        addV(panel, modeTip, 6);
        Button modeBtn = createDarkButton(chrootNow ? "切换回 proot 模式" : "切换为 chroot 模式（实验）");
        modeBtn.setOnClickListener(v -> {
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putString("run_mode", chrootNow ? "proot" : "chroot").apply();
            result.setText(chrootNow ? "已切换为 proot 模式，正在重启环境..." : "已切换为 chroot 模式，正在重启环境...");
            hidePanel();
            restartEnvironment();
        });
        // chroot 需要 root 授权：先置灰，root-check 检测通过后恢复；无 root 保持禁用并提示
        modeBtn.setEnabled(false);
        modeBtn.setTextColor(0xFF6A6A6A);
        modeBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1E1E1E));
        addV(panel, modeBtn, 8);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("ROOT", panel, null);
        // 检测状态（后台线程）
        new Thread(() -> {
            final String st;
            if (su == null) {
                st = "未检测到 root（未安装 KernelSU/Magisk）";
            } else {
                String r = execRootCommand("id", 5);
                st = (r != null && r.contains("uid=0"))
                        ? "已授权（" + su + "）：" + r.split("\n")[0]
                        : "检测到 " + su + "，但执行失败（请在弹窗授权后重试）";
            }
            runOnUiThread(() -> {
                status.setText(st);
                status.setTextColor(st.contains("已授权") ? 0xFF7FDB8A : (st.contains("未检测") ? 0xFF888888 : 0xFFFFD54F));
                // chroot 按钮：root 可用才恢复（chroot 需 su 授权）
                if (st.contains("已授权")) {
                    modeBtn.setEnabled(true);
                    modeBtn.setTextColor(0xFFFFFFFF);
                    modeBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF262626));
                } else {
                    modeTip.setText(modeTip.getText() + "\n⚠ chroot 需要 root 授权，当前未检测到可用 root，切换按钮不可用。");
                    modeTip.setTextColor(0xFFFF6B6B);
                }
            });
        }, "root-check").start();
        testBtn.setOnClickListener(v -> {
            result.setText("执行中...\nroot id");
            new Thread(() -> {
                String r = execRootCommand("id", 8);
                runOnUiThread(() -> {
                    result.setTextColor(r != null && r.contains("uid=0") ? 0xFF7FDB8A : 0xFFFF6E6E);
                    result.setText(r == null ? "(无输出或超时——请检查授权)" : r.trim());
                });
            }, "root-test").start();
        });
    }

    /** 探测 su 路径（含 KernelSU/Magisk；找不到则回退 PATH 中的 su） */
    private String findSuPath() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
                "/system/bin/.ext/.su", "/system/usr/we-need-root/su-backup",
                "/debug_ramdisk/su",              // KernelSU
                "/data/adb/ksu/bin/su",           // KernelSU
                "/data/adb/magisk/busybox/su"     // Magisk
        };
        for (String p : paths) {
            boolean ex = new File(p).exists();
            Log.d(TAG, "root probe: " + p + " exists=" + ex);
            if (ex) return p;
        }
        // 回退：直接使用 "su"（走 PATH，KernelSU/Magisk 通常已加入 PATH）
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            if (p.waitFor(3, TimeUnit.SECONDS)) {
                byte[] buf = new byte[256];
                int n = p.getInputStream().read(buf);
                String s = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                p.destroy();
                if (s.contains("uid=0")) {
                    Log.d(TAG, "root probe: PATH su works: " + s.trim());
                    return "su";
                }
            } else {
                p.destroy();
            }
        } catch (Exception e) {
            Log.w(TAG, "root probe: PATH su failed", e);
        }
        return null;
    }

    /** 预检 root 并写 /root/.root-ok 标记（guest 侧 adb wrapper 据此直连 root 命令桥，无需无线调试） */
    private void probeRootAndMark() {
        new Thread(() -> {
            String su = findSuPath();
            boolean ok = su != null;
            if (ok) {
                String r = execRootCommand("id", 5);
                ok = r != null && r.contains("uid=0");
            }
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                File okFile = new File(rootDir, ".root-ok");
                if (ok) {
                    java.nio.file.Files.write(okFile.toPath(), "ok\n".getBytes(StandardCharsets.UTF_8));
                    Log.d(TAG, "root precheck OK, wrote .root-ok");
                } else {
                    okFile.delete();
                    Log.d(TAG, "root precheck unavailable, removed .root-ok");
                }
            } catch (Exception e) {
                Log.w(TAG, "root mark failed", e);
            }
        }, "root-precheck").start();
    }

    /** 执行 root 命令（su -c），返回 stdout+stderr（失败返回 null） */
    private String execRootCommand(String cmd, int timeoutSec) {
        String su = findSuPath();
        if (su == null) return null;
        try {
            Process p = new ProcessBuilder(su, "-c", cmd)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy();
                return "(超时)";
            }
            byte[] out = new byte[8192];
            int n = p.getInputStream().read(out);
            return n > 0 ? new String(out, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            Log.w(TAG, "root exec failed: " + e);
            return null;
        }
    }

    /* ==================== 侧滑菜单功能 ==================== */

    // ------------------------------------------------------------------
    // 全屏功能面板（取代系统 AlertDialog 弹窗，页面式切换避免遮挡控件）
    // ------------------------------------------------------------------
    private Runnable panelOnClose;   // 面板关闭回调（hidePanel 时触发一次）

    /** 显示全屏功能面板：标题 + 内容视图；onClose 在面板关闭时回调（可空） */
    private void showPanel(String title, View content, Runnable onClose) {
        ((TextView) findViewById(R.id.panel_title)).setText(title);
        LinearLayout holder = findViewById(R.id.panel_content);
        holder.removeAllViews();
        holder.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panelOnClose = onClose;
        // 面板内键盘弹出不压缩布局/不自动滚动（adjustPan：窗口整体平移保持输入框可见，
        // 面板外层 ScrollView 不会因键盘把内容超高而自动滑动）；退出面板恢复终端 adjustResize。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        findViewById(R.id.panel_overlay).setVisibility(View.VISIBLE);
    }

    /** 关闭全屏功能面板 */
    private void hidePanel() {
        if (findViewById(R.id.panel_overlay).getVisibility() != View.VISIBLE) return;
        findViewById(R.id.panel_overlay).setVisibility(View.GONE);
        ((ViewGroup) findViewById(R.id.panel_content)).removeAllViews();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Runnable cb = panelOnClose;
        panelOnClose = null;
        if (cb != null) cb.run();
    }

    /** 返回键：面板可见时先关面板，不退出应用 */
    @Override
    public void onBackPressed() {
        if (findViewById(R.id.panel_overlay).getVisibility() == View.VISIBLE) {
            hidePanel();
            return;
        }
        super.onBackPressed();
    }

    private void updateBgModeLabel() {
        boolean on = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("background_mode", false);
        TextView tv = findViewById(R.id.menu_bgmode);
        if (tv != null) {
            tv.setText(on ? "后台运行：开" : "后台运行：关");
            tv.setTextColor(on ? 0xFF4CAF50 : 0xFFFFFFFF);
        }
    }

    /** YOLO 免审批模式标签：开启时 reasonix 完全跳过工具审批（--permission-mode bypassPermissions） */
    private void updateYoloModeLabel() {
        boolean on = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("yolo_mode", true);
        TextView tv = findViewById(R.id.menu_yolo);
        if (tv != null) {
            tv.setText(on ? "YOLO 免审批：开" : "YOLO 免审批：关");
            tv.setTextColor(on ? 0xFF4CAF50 : 0xFFFFFFFF);
        }
    }

    // ---- 上下滑动调速 ----

    /**
     * 档位(1~10) → 每页滑动像素数：SCROLL_STEP = 1000 / 档位。
     * 档位 1 → 500px/页（最慢，精细浏览）；档位 5 → 100px/页（默认）；
     * 档位 10 → 10px/页（最快，接近原固定 8px/页的翻动速度）。
     */
    private static int scrollStepForSpeed(int speed) {
        if (speed < SPEED_MIN) speed = SPEED_MIN;
        if (speed > SPEED_MAX) speed = SPEED_MAX;
        return 1000 / speed;
    }

    /** 把当前档位换算的 SCROLL_STEP 注入 xterm.js（JS 函数 setScrollStep 已暴露） */
    private void applyScrollSpeed(int speed) {
        int step = scrollStepForSpeed(speed);
        if (webView == null) return;
        ui.post(() -> {
            try {
                webView.evaluateJavascript("window.setScrollStep(" + step + ")", null);
            } catch (Exception ignored) {}
        });
    }

    /** 更新菜单里的档位显示文本 */
    private void updateSpeedLabel(int speed) {
        TextView tv = findViewById(R.id.tv_speed_value);
        if (tv != null) {
            tv.setText("当前：" + speed + " 档（滑动" + (speed >= 7 ? "较快" : speed <= 3 ? "较慢" : "适中") + "）");
        }
    }

    /** 同步 YOLO 开关到 rootfs 标记（/root/.rsxm-yolo），reasonix wrapper 每次启动时读取决定审批模式 */
    private void syncYoloMark(boolean on) {
        try {
            File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
            File mark = new File(rootDir, ".rsxm-yolo");
            if (on) {
                if (!mark.exists()) mark.createNewFile();
            } else {
                mark.delete();
            }
            Log.d(TAG, "yolo mark " + (on ? "created" : "removed"));
        } catch (Exception e) {
            Log.w(TAG, "sync yolo mark failed", e);
        }
    }

    /**
     * 启动后台保活前台服务（后台运行模式）。
     * requestNotifPerm=true（用户手动开启时）：顺带请求通知权限（Android 13+），
     * 保证常驻通知可见；onCreate 自动恢复时传 false，避免打扰。
     */
    private void startBackgroundService(boolean requestNotifPerm) {
        try {
            startForegroundService(new Intent(this, BackgroundService.class));
            Log.d(TAG, "background keep-alive service started");
        } catch (Exception e) {
            Log.w(TAG, "start background service failed", e);
        }
        if (requestNotifPerm && Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 201);
            } catch (Exception e) {
                Log.w(TAG, "request notification permission failed", e);
            }
        }
    }

    /** 停止后台保活前台服务（后台运行模式关闭） */
    private void stopBackgroundService() {
        try {
            stopService(new Intent(this, BackgroundService.class));
            Log.d(TAG, "background keep-alive service stopped");
        } catch (Exception e) {
            Log.w(TAG, "stop background service failed", e);
        }
    }

    /* ==================== 侧滑菜单功能 ==================== */

    /** dp 转 px */
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 安装 SKILL：skill 是 reasonix 的 AI 技能包（SKILL.md 规范格式），
     *  写入当前项目（reasonix 工作区）的 .reasonix/skills/<name>/SKILL.md，
     *  reasonix 按工作区加载：<workspace>/.reasonix/skills/（默认项目 /root）,
     *  安装后 reasonix 内 /skills reload 或重启环境即可显示 */
    private void showSkillInstallDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip(
                "SKILL 为 reasonix 技能包（SKILL.md），全局安装（所有项目共用，切换项目不影响）。\n"
                        + "格式：开头 YAML frontmatter，含 name 和 description。"));
        // ---- 新增区 ----
        panel.addView(createDarkSectionTitle("新增 SKILL"));
        skillNameInput = createDarkEditText("SKILL 名称（如 mytool，仅字母数字._-）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, skillNameInput, 6);
        skillContentInput = new EditText(this);
        skillContentInput.setHint("SKILL.md 内容（粘贴/导入，含 frontmatter）");
        skillContentInput.setTextColor(0xFFE0E0E0);
        skillContentInput.setHintTextColor(0xFF707070);
        skillContentInput.setTextSize(13);
        skillContentInput.setGravity(android.view.Gravity.TOP);
        skillContentInput.setSingleLine(false);
        skillContentInput.setMaxLines(Integer.MAX_VALUE);
        skillContentInput.setVerticalScrollBarEnabled(true);
        skillContentInput.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        skillContentInput.setBackgroundColor(0xFF1A1A1A);
        skillContentInput.setPadding(dp(10), dp(10), dp(10), dp(10));
        // 固定高度 + 内部滚动（二级滑动）：导入/粘贴大内容时内容区自己滚，不撑动整个功能页
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        contentLp.topMargin = dp(6);
        panel.addView(skillContentInput, contentLp);
        Button importBtn = createDarkButton("从手机文件导入 SKILL.md");
        importBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/*");
            try {
                startActivityForResult(i, REQ_SKILL_IMPORT);
            } catch (Exception e) {
                pushOutput("\r\n[无法打开文件选择器: " + e.getMessage() + "]\r\n");
            }
        });
        addV(panel, importBtn, 8);
        Button installBtn = createDarkButton("安装 SKILL");
        installBtn.setOnClickListener(v -> {
            String name = skillNameInput.getText().toString().trim();
            String content = skillContentInput.getText().toString();
            if (name.isEmpty() || content.isEmpty()) {
                pushOutput("\r\n[请填写 SKILL 名称和内容]\r\n");
                return;
            }
            if (!name.matches("[A-Za-z0-9_.-]+")) {
                pushOutput("\r\n[SKILL 名称仅允许字母、数字、_ . -]\r\n");
                return;
            }
            installSkill(name, content);
            // 列表刷新由 installSkill 完成回调触发（不再固定延时，避免与安装命令并发抢占 .adb-cmd/.adb-out）
        });
        addV(panel, installBtn, 8);
        // ---- 查找区 ----
        panel.addView(createDarkSectionTitle("查找"));
        final EditText searchInput = createDarkEditText("输入名称关键字过滤",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, searchInput, 6);
        // ---- 已装列表区（固定高度 + 二级滑动，列表变长不会导致整个功能页滚动）----
        panel.addView(createDarkSectionTitle("已安装 SKILL"));
        final ScrollView listScroll = new ScrollView(this);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listBox);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
        listLp.topMargin = dp(6);
        panel.addView(listScroll, listLp);
        addV(panel, createDarkTip("勾选 = 启用；取消勾选 = 禁用（reasonix 隐藏）。"), 8);
        // 刷新/查找联动
        skillRefreshRunnable = () -> loadSkillList(listBox, searchInput.getText().toString().trim());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { skillRefreshRunnable.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        skillRefreshRunnable.run();   // 初始加载列表
        showPanel("SKILL", panel, null);
    }

    /** SKILL 列表刷新任务（安装/删除/开关后调用；面板持有当前 listBox/filter） */
    private Runnable skillRefreshRunnable;

    /** SKILL 面板输入框引用（文件导入回调填充用；面板每次重建时重新赋值） */
    private EditText skillNameInput, skillContentInput;

    /** 全局 SKILL 根目录（Reasonix home: ~/.reasonix/skills）的宿主侧路径。
     *  SKILL 全局通用：所有项目共用（reasonix 每个项目都会加载 <Reasonix home>/skills/），
     *  切换项目不影响；固定路径在 rootfs 内，不依赖 guest 内 /sdcard 绑定。 */
    private File globalSkillsDir() {
        return new File(new File(new File(getFilesDir(), "rootfs"), "root"), ".reasonix/skills");
    }

    /** 解析 guest 读出的 disabled_skills 行（形如 disabled_skills = ["a", "b"]） */
    private void parseDisabled(String cfg, java.util.Set<String> disabled) {
        if (cfg == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([^\\]]*)\\]").matcher(cfg);
        if (m.find()) {
            for (String x : m.group(1).split(",")) {
                String n = x.trim().replace("\"", "").replace("'", "");
                if (!n.isEmpty()) disabled.add(n);
            }
        }
    }

    /** 异步加载已装 SKILL 列表与启用状态，渲染到容器（支持名称过滤） */
    private void loadSkillList(LinearLayout container, String filter) {
        container.removeAllViews();
        container.addView(createDarkTip("加载列表中..."));
        new Thread(() -> {
            try {
                final List<String> names = new ArrayList<>();
                final java.util.Set<String> disabled = new java.util.HashSet<>();
                // 全局 skills 目录（~/.reasonix/skills）：宿主侧列目录，与安装/删除一致，不依赖 guest /sdcard 绑定
                File[] dirs = globalSkillsDir().listFiles();
                if (dirs != null) {
                    for (File d : dirs) {
                        String n = d.getName();
                        if (d.isDirectory() && !n.startsWith(".")) names.add(n);
                    }
                }
                // disabled 状态：config.toml 在 /root/.reasonix/（Reasonix home），仍经 guest 读
                parseDisabled(executeInGuest(
                        "grep -A8 '\\[skills\\]' $HOME/.reasonix/config.toml 2>/dev/null | grep disabled_skills", 10),
                        disabled);
                runOnUiThread(() -> renderSkillList(container, names, disabled, filter));
            } catch (Exception e) {
                Log.e(TAG, "load skills failed", e);
                runOnUiThread(() -> {
                    container.removeAllViews();
                    container.addView(createDarkTip("（加载失败：" + e.getMessage() + "）"));
                });
            }
        }, "skill-load").start();
    }

    /** 渲染 SKILL 列表（每行：名称 + 启用勾选 + 删除） */
    private void renderSkillList(LinearLayout container, List<String> names,
                                 java.util.Set<String> disabled, String filter) {
        container.removeAllViews();
        boolean has = false;
        for (final String name : names) {
            if (filter != null && !filter.isEmpty() && !name.contains(filter)) continue;
            has = true;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextColor(0xFFE0E0E0);
            tv.setTextSize(14);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            CheckBox cb = new CheckBox(this);
            cb.setChecked(!disabled.contains(name));
            cb.setText("启用");
            cb.setTextColor(0xFFAAAAAA);
            cb.setOnCheckedChangeListener((b, checked) -> setSkillEnabled(name, checked));
            row.addView(cb);
            Button del = createDarkButton("删除");
            del.setOnClickListener(v -> {
                uninstallSkill(name);   // 列表刷新由 uninstallSkill 完成回调触发
            });
            row.addView(del);
            container.addView(row);
        }
        if (!has) container.addView(createDarkTip("（无匹配的已安装 SKILL）"));
    }

    /** 启用/禁用 SKILL：修改 guest 内 $HOME/.reasonix/config.toml 的 [skills] disabled_skills */
    private void setSkillEnabled(String name, boolean enable) {
        new Thread(() -> {
            try {
                String script = "import os, sys\n"
                        + "p = '/root/.reasonix/config.toml'\n"
                        + "name = sys.argv[1]\n"
                        + "disable = sys.argv[2] == '1'\n"
                        + "content = open(p).read() if os.path.exists(p) else ''\n"
                        + "lines = content.split('\\n')\n"
                        + "out = []\n"
                        + "found = False\n"
                        + "for l in lines:\n"
                        + "    if 'disabled_skills' in l and '[' in l:\n"
                        + "        found = True\n"
                        + "        arr = l[l.index('[')+1:l.rindex(']')]\n"
                        + "        names = [x.strip().strip('\\\"\\'').strip() for x in arr.split(',') if x.strip()]\n"
                        + "        if disable and name not in names: names.append(name)\n"
                        + "        if (not disable) and name in names: names.remove(name)\n"
                        + "        l = 'disabled_skills = [' + ', '.join('\\\"%s\\\"' % n for n in names) + ']'\n"
                        + "    out.append(l)\n"
                        + "if not found:\n"
                        + "    if '[skills]' in content:\n"
                        + "        idx = next(i for i,l in enumerate(out) if l.strip() == '[skills]')\n"
                        + "        j = idx + 1\n"
                        + "        while j < len(out) and not out[j].strip().startswith('['): j += 1\n"
                        + "        out.insert(j, 'disabled_skills = [' + ('\\\"%s\\\"' % name) + ']' if disable else 'disabled_skills = []')\n"
                        + "    else:\n"
                        + "        out += ['', '[skills]', 'disabled_skills = [' + ('\\\"%s\\\"' % name) + ']' if disable else 'disabled_skills = []']\n"
                        + "open(p, 'w').write('\\n'.join(out))\n"
                        + "print('OK ' + name + (' disabled' if disable else ' enabled'))\n";
                String b64 = Base64.encodeToString(script.getBytes("UTF-8"), Base64.NO_WRAP);
                String out = executeInGuest("echo " + b64 + " | base64 -d > /tmp/skill_toggle.py; "
                        + "python3 /tmp/skill_toggle.py " + name + " " + (enable ? "0" : "1"), 10);
                runOnUiThread(() -> pushOutput("\r\n[SKILL " + name + (enable ? " 已启用" : " 已禁用") + "]"
                        + (out == null ? "" : "\n" + out) + "\r\n"));
            } catch (Exception e) {
                Log.e(TAG, "set skill enabled failed", e);
                runOnUiThread(() -> pushOutput("\r\n[切换 SKILL 启用状态失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-toggle").start();
    }

    /** 卸载 SKILL：删除全局 SKILL 目录 ~/.reasonix/skills/<name>（所有项目共用，切换项目不影响） */
    private void uninstallSkill(String name) {
        new Thread(() -> {
            try {
                String out;
                File dir = new File(globalSkillsDir(), name);
                boolean ok = deleteRecursive(dir);
                StringBuilder sb = new StringBuilder(ok ? "UNINSTALLED_OK" : "(宿主删除失败)");
                File[] left = globalSkillsDir().listFiles();
                if (left != null) {
                    for (File f : left) {
                        if (f.isDirectory() && !f.getName().startsWith(".")) sb.append('\n').append(f.getName());
                    }
                }
                out = sb.toString();
                String msg = "\r\n[卸载 SKILL: " + name + "]\r\n"
                        + ((out != null && out.contains("UNINSTALLED_OK"))
                            ? "已删除。剩余 SKILL：\n" + out.replace("UNINSTALLED_OK", "").trim()
                            : (out == null ? "(无响应)" : out)) + "\r\n";
                runOnUiThread(() -> pushOutput(msg));
                runOnUiThread(() -> { if (skillRefreshRunnable != null) skillRefreshRunnable.run(); });
            } catch (Exception e) {
                Log.e(TAG, "uninstall skill failed", e);
                runOnUiThread(() -> pushOutput("\r\n[卸载 SKILL 失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-uninstall").start();
    }

    /** 回退路径：经 guest 服务循环写入全局 SKILL（base64 避免转义/引号问题） */
    private String guestInstallSkill(String name, String content) {
        String b64 = android.util.Base64.encodeToString(
                content.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        String cmd = "SK=\"/root/.reasonix/skills\"; "
                + "mkdir -p \"$SK/" + name + "\""
                + " && echo " + b64 + " | base64 -d > \"$SK/" + name + "/SKILL.md\""
                + " && chmod 644 \"$SK/" + name + "/SKILL.md\""
                + " && echo INSTALLED_OK && ls -la \"$SK/" + name + "/SKILL.md\"";
        return executeInGuest(cmd, 12);
    }

    /** 安装 SKILL：写入全局 SKILL 目录 ~/.reasonix/skills/<name>/SKILL.md（Reasonix home skills，
     *  所有项目共用，切换项目不影响；reasonix 内 /skills reload 或重启后显示）。
     *  宿主侧写入 rootfs（稳定，不依赖 guest /sdcard 绑定）；宿主写入失败回退 guest 命令。 */
    private void installSkill(String name, String content) {
        new Thread(() -> {
            try {
                String out;
                File dir = new File(globalSkillsDir(), name);
                try {
                    if (dir.mkdirs() || dir.isDirectory()) {
                        java.nio.file.Files.write(new File(dir, "SKILL.md").toPath(),
                                content.getBytes(StandardCharsets.UTF_8));
                        out = "INSTALLED_OK\n" + new File(dir, "SKILL.md").getAbsolutePath();
                    } else {
                        out = guestInstallSkill(name, content);   // 宿主创建失败回退
                    }
                } catch (Exception e2) {
                    Log.w(TAG, "host skill write failed, fallback guest", e2);
                    out = guestInstallSkill(name, content);
                }
                String msg = "\r\n[安装 SKILL: " + name + "]\r\n"
                        + (out == null ? "(无响应)" : out)
                        + "\r\n[完成。SKILL 为全局安装（所有项目共用），reasonix 内 /skills reload 或重启应用环境后显示]\r\n";
                runOnUiThread(() -> pushOutput(msg));
                runOnUiThread(() -> { if (skillRefreshRunnable != null) skillRefreshRunnable.run(); });
            } catch (Exception e) {
                Log.e(TAG, "install skill failed", e);
                runOnUiThread(() -> pushOutput("\r\n[安装 SKILL 失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-install").start();
    }

    /** 解析 SKILL.md frontmatter 中的 name（开头 --- 到下一个 --- 之间的 name: 行），无则返回 null */
    private String parseSkillName(String content) {
        if (content == null) return null;
        String c = content.trim();
        if (!c.startsWith("---")) return null;
        int end = c.indexOf("\n---", 3);
        if (end < 0) return null;
        String fm = c.substring(3, end);
        for (String l : fm.split("\n")) {
            String t = l.trim();
            if (t.startsWith("name:")) {
                String n = t.substring(5).trim().replace("\"", "").replace("'", "").trim();
                if (!n.isEmpty()) return n;
            }
        }
        return null;
    }

    /** 从手机文件导入 SKILL.md（SAF 免存储权限）：读取内容 → 解析 frontmatter name →
     *  填入表单（名称+内容）由用户确认，安装仍由「安装 SKILL」按钮触发 */
    private void importSkillFromUri(Uri uri) {
        new Thread(() -> {
            try {
                String content;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        runOnUiThread(() -> pushOutput("\r\n[导入失败: 无法读取所选文件]\r\n"));
                        return;
                    }
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    content = sb.toString();
                }
                if (content == null || content.trim().isEmpty()) {
                    runOnUiThread(() -> pushOutput("\r\n[导入失败: 文件为空]\r\n"));
                    return;
                }
                final String name = parseSkillName(content);
                final String finalContent = content;
                runOnUiThread(() -> {
                    if (skillNameInput != null) skillNameInput.setText(name == null ? "" : name);
                    if (skillContentInput != null) skillContentInput.setText(finalContent);
                    pushOutput("\r\n[已导入" + (name != null ? " " + name : "") + "，检查后点「安装 SKILL」完成安装]\r\n");
                });
            } catch (Exception e) {
                Log.e(TAG, "import skill failed", e);
                runOnUiThread(() -> pushOutput("\r\n[导入 SKILL 文件失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-import").start();
    }

    /* ==================== 项目位置 ==================== */

    /** 项目：reasonix 工作目录（会话/记忆按项目隔离），可建在内部或手机目录（/sdcard），切换后重启生效 */
    private void showProjectDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip(
                "项目为 reasonix 工作目录（会话/记忆按项目隔离）。\n"
                        + "「进入」切换（重启生效）；「删除」移除。可建内部或手机目录（文件管理器可见）。"));
        final TextView curView = new TextView(this);
        curView.setTextColor(0xFF4CAF50);
        curView.setTextSize(14);
        curView.setTypeface(null, android.graphics.Typeface.BOLD);
        curView.setPadding(dp(2), dp(8), dp(2), dp(4));
        addV(panel, curView, 6);
        // 项目列表（内置 + 手机分组渲染，每行 名称/进入/删除；固定高度 + 二级滑动）
        panel.addView(createDarkSectionTitle("项目列表"));
        final ScrollView listScroll = new ScrollView(this);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listBox);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(290));
        listLp.topMargin = dp(6);
        panel.addView(listScroll, listLp);
        // 新增区
        panel.addView(createDarkSectionTitle("新建项目"));
        final EditText newInput = createDarkEditText("新项目名称（如 myapp，字母数字._-）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, newInput, 6);
        Button newSdcardBtn = createDarkButton("在手机目录创建并进入");
        newSdcardBtn.setOnClickListener(v -> {
            String name = newInput.getText().toString().trim();
            if (checkProjectName(name)) createProject(name, true, curView, listBox);
        });
        addV(panel, newSdcardBtn, 8);
        Button newInnerBtn = createDarkButton("在内部创建并进入");
        newInnerBtn.setOnClickListener(v -> {
            String name = newInput.getText().toString().trim();
            if (checkProjectName(name)) createProject(name, false, curView, listBox);
        });
        addV(panel, newInnerBtn, 8);
        Button defaultBtn = createDarkButton("恢复默认（/root）");
        defaultBtn.setOnClickListener(v -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                new File(rootDir, ".rsxm-project").delete();
                pushOutput("\r\n[已恢复默认项目 /root，重启后生效]\r\n");
                loadProjectList(curView, listBox);
            } catch (Exception e) {
                Log.e(TAG, "reset project failed", e);
            }
        });
        addV(panel, defaultBtn, 8);
        Button refreshBtn = createDarkButton("刷新项目列表");
        refreshBtn.setOnClickListener(v -> loadProjectList(curView, listBox));
        addV(panel, refreshBtn, 8);
        loadProjectList(curView, listBox);
        showPanel("项目", panel, null);
    }

    /** 会话面板：列出 ~/.reasonix/projects/<项目>/sessions/ 下的历史会话（聊天记录），
     *  按项目分组显示（时间 + 预览）。点击「继续」写 /root/.rsxm-resume 标记并重启环境，
     *  entry.sh wrapper 用 `--resume <jsonl路径>` 恢复该会话；「删除」移入 sessions/.trash 回收站；
     *  「新建会话」清除恢复标记后重启，进入全新会话。 */
    private void showSessionsDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip(
                "reasonix 会话（聊天记录）按项目保存在 ~/.reasonix/projects/<项目>/sessions/，"
                        + "退出 reasonix 即自动保存。\n"
                        + "「继续」恢复该会话聊天（重启环境生效）；「删除」移入回收站；「新建会话」开始全新对话。"));
        panel.addView(createDarkSectionTitle("历史会话"));
        final ScrollView listScroll = new ScrollView(this);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listBox);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        listLp.topMargin = dp(6);
        panel.addView(listScroll, listLp);
        Button newBtn = createDarkButton("新建会话（清除恢复标记，重启进入全新会话）");
        newBtn.setOnClickListener(v -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                new File(rootDir, ".rsxm-resume").delete();
                pushOutput("\r\n[已清除会话恢复标记，重启后开始全新会话]\r\n");
                restartEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "new session failed", e);
            }
        });
        addV(panel, newBtn, 8);
        Button refreshBtn = createDarkButton("刷新会话列表");
        refreshBtn.setOnClickListener(v -> loadSessions(listBox));
        addV(panel, refreshBtn, 8);
        loadSessions(listBox);
        showPanel("会话", panel, null);
    }

    /** 宿主侧遍历 rootfs 的 ~/.reasonix/projects 下各项目 sessions 目录中的 .jsonl 会话文件
     *  （排除回收站、恢复分支、隐藏文件），按项目分组渲染：每行「时间 + 预览」+ 继续/删除按钮。 */
    private void loadSessions(LinearLayout container) {
        container.removeAllViews();
        container.addView(createDarkTip("加载中..."));
        new Thread(() -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                File projectsDir = new File(new File(rootDir, ".reasonix"), "projects");
                final java.util.List<String[]> groups = new java.util.ArrayList<>(); // {项目标签, jsonl绝对路径, 时间, 预览}
                if (projectsDir.isDirectory()) {
                    File[] pds = projectsDir.listFiles(File::isDirectory);
                    if (pds != null) {
                        java.util.Arrays.sort(pds, java.util.Comparator.comparing(File::getName));
                        for (File pd : pds) {
                            File sessions = new File(pd, "sessions");
                            File[] files = sessions.listFiles((d, n) ->
                                    n.endsWith(".jsonl") && !n.startsWith(".")
                                            && !n.endsWith(".events.jsonl") && !n.endsWith(".conflicts.jsonl")
                                            && !n.endsWith(".recovery.json") && !n.endsWith(".recovery")
                                            && !n.contains(".lease."));
                            if (files == null || files.length == 0) continue;
                            java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified).reversed());
                            String label = projectLabel(pd.getName());
                            for (File f : files) {
                                groups.add(new String[]{label, f.getAbsolutePath(),
                                        fmtSessionTime(f.lastModified()), sessionPreview(f)});
                            }
                        }
                    }
                }
                runOnUiThread(() -> {
                    container.removeAllViews();
                    if (groups.isEmpty()) {
                        container.addView(createDarkTip("（暂无历史会话，reasonix 会话会自动保存到这里）"));
                        return;
                    }
                    String lastProj = null;
                    for (String[] g : groups) {
                        if (!g[0].equals(lastProj)) {
                            lastProj = g[0];
                            TextView t = new TextView(this);
                            t.setText("项目 " + g[0]);
                            t.setTextColor(0xFF7FDB8A);
                            t.setTextSize(13);
                            t.setTypeface(null, android.graphics.Typeface.BOLD);
                            t.setPadding(0, dp(10), 0, dp(2));
                            container.addView(t);
                        }
                        container.addView(sessionRow(g, container));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "load sessions failed", e);
                runOnUiThread(() -> {
                    container.removeAllViews();
                    container.addView(createDarkTip("（加载失败：" + e.getMessage() + "）"));
                });
            }
        }, "session-load").start();
    }

    /** 会话行：时间 + 预览 + 「继续」「删除」 */
    private View sessionRow(String[] g, LinearLayout container) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(0xFF141414);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(4);
        row.setLayoutParams(rp);
        TextView info = new TextView(this);
        info.setText(g[2] + "\n" + g[3]);
        info.setTextColor(0xFFE0E0E0);
        info.setTextSize(13);
        info.setLineSpacing(0, 1.1f);
        row.addView(info);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button cont = createDarkButton("继续");
        cont.setOnClickListener(v -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                // guest 内路径：rootDir（宿主 rootfs/root）对应 guest /root
                String abs = new File(g[1]).getAbsolutePath();
                String rel = abs.substring(rootDir.getAbsolutePath().length());
                String guestPath = "/root" + rel;
                java.nio.file.Files.write(new File(rootDir, ".rsxm-resume").toPath(),
                        guestPath.getBytes(StandardCharsets.UTF_8));
                pushOutput("\r\n[恢复会话: " + g[0] + " " + g[2] + "]\r\n");
                restartEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "resume session failed", e);
                pushOutput("\r\n[恢复失败: " + e.getMessage() + "]\r\n");
            }
        });
        Button del = createDarkButton("删除");
        del.setOnClickListener(v -> {
            final File f = new File(g[1]);
            // 后台线程执行：chroot 模式会话为 root 属主 600，renameTo 必失败且 root 桥 su 可能耗时数秒，不能阻塞 UI
            new Thread(() -> {
                try {
                    File trash = new File(f.getParentFile(), ".trash");
                    trash.mkdirs();
                    boolean ok = f.renameTo(new File(trash, f.getName()));
                    if (!ok) {
                        execRootCommand("mkdir -p '" + sq(trash.getAbsolutePath()) + "' && mv '"
                                + sq(f.getAbsolutePath()) + "' '" + sq(trash.getAbsolutePath()) + "/' 2>&1", 10);
                        ok = !f.exists();
                    }
                    final boolean r = ok;
                    runOnUiThread(() -> {
                        pushOutput("\r\n[会话" + (r ? "已移入回收站" : "删除失败") + ": " + g[2] + "]\r\n");
                        loadSessions(container);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "delete session failed", e);
                }
            }, "session-del").start();
        });
        btns.addView(cont);
        btns.addView(del);
        row.addView(btns);
        return row;
    }

    /** 项目 key 目录名 → 可读项目路径（reasonix key = 路径转义：\\→-、冒号去掉、字母小写；
     *  仅解码已知前缀，其余原样显示） */
    private static String projectLabel(String key) {
        String k = key.toLowerCase(java.util.Locale.US);
        if (k.startsWith("-sdcard-reasonixprojects-"))
            return "/sdcard/ReasonixProjects/" + key.substring("-sdcard-ReasonixProjects-".length());
        if (k.startsWith("-root-"))
            return "/root/" + key.substring("-root-".length());
        if (k.equals("-root")) return "/root";
        return key;
    }

    /** 会话时间：文件最后修改时间 → "MM-dd HH:mm" */
    private static String fmtSessionTime(long t) {
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .format(java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()));
    }

    /** shell 单引号转义（root 桥命令拼接防注入/防路径含引号破坏命令） */
    private static String sq(String s) {
        return s.replace("'", "'\\''");
    }

    /** 会话预览：jsonl 第一条 user 消息文本（截断 50 字符）。
     *  chroot 模式下会话为 root 属主 600，宿主直读失败时经 root 桥 chmod 后重读。 */
    private String sessionPreview(File f) {
        byte[] head = readSessionHead(f);
        if (head == null) return "(读取失败，无 root 权限)";
        String text = new String(head, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.startsWith("{")) continue;
            try {
                org.json.JSONObject o = new org.json.JSONObject(line);
                if ("user".equals(o.optString("role"))) {
                    Object c = o.opt("content");
                    String s = (c instanceof String) ? (String) c : String.valueOf(c);
                    s = s.replaceAll("\\s+", " ").trim();
                    if (s.length() > 50) s = s.substring(0, 50) + "…";
                    return s.isEmpty() ? "(空)" : s;
                }
            } catch (Exception ignore) { }
        }
        return "(无文本内容)";
    }

    /** 读会话文件头部（前 64KB）；宿主直读失败（chroot 模式 root 属主 600）→ root 桥 chmod 后重读 */
    private byte[] readSessionHead(File f) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n = in.read(buf);
                byte[] out = new byte[Math.max(n, 0)];
                if (n > 0) System.arraycopy(buf, 0, out, 0, n);
                return out;
            } catch (Exception e) {
                if (attempt == 0) {
                    execRootCommand("chmod a+r '" + sq(f.getAbsolutePath()) + "' 2>/dev/null", 8);
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    /** 加载项目列表（内置 /root 与手机 /sdcard/ReasonixProjects），渲染到容器 */
    private void loadProjectList(TextView curView, LinearLayout container) {
        container.removeAllViews();
        container.addView(createDarkTip("加载中..."));
        new Thread(() -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                String cur = "默认（/root）";
                File mark = new File(rootDir, ".rsxm-project");
                if (mark.exists()) {
                    String s = new String(java.nio.file.Files.readAllBytes(mark.toPath()),
                            StandardCharsets.UTF_8).trim();
                    if (!s.isEmpty()) cur = s;
                }
                // 内置项目 = /root/ 下用户创建的目录（~/.reasonix/projects/ 是 reasonix 自动生成的
                // 项目元数据/会话目录，手机项目也会触发，不再混入列表）
                String out = executeInGuest(
                        "echo [内部]:; ls -d /root/*/ 2>/dev/null | sed 's|/root/||; s|/$||';", 10);
                final List<String> inner = new ArrayList<>();
                final List<String> sdcard = new ArrayList<>();
                if (out != null) {
                    boolean inInner = false;
                    for (String l : out.split("\n")) {
                        String t = l.trim();
                        if (t.equals("[内部]:")) { inInner = true; continue; }
                        if (inInner && !t.isEmpty() && !t.contains("error")) inner.add(t);
                    }
                }
                // 手机目录：宿主侧直接读真存储（chroot 内写 /sdcard 会被 mount namespace 隔离，
                // 宿主文件管理器不可见；宿主 File API 读写才是文件管理器可见的目录）
                File sdcardProj = new File("/storage/emulated/0/ReasonixProjects");
                if (sdcardProj.isDirectory()) {
                    String[] list = sdcardProj.list();
                    if (list != null) {
                        for (String n : list) {
                            if (!n.startsWith(".")) sdcard.add(n);
                        }
                    }
                }
                final String c = cur;
                runOnUiThread(() -> {
                    curView.setText("当前项目：" + c);
                    renderProjectList(container, inner, sdcard, c, curView, container);
                });
            } catch (Exception e) {
                Log.e(TAG, "load projects failed", e);
                runOnUiThread(() -> {
                    container.removeAllViews();
                    container.addView(createDarkTip("（加载失败：" + e.getMessage() + "）"));
                });
            }
        }, "project-load").start();
    }

    /** 渲染项目列表：内置/手机分组，每行 名称 + 进入 + 删除 */
    private void renderProjectList(LinearLayout container, List<String> inner, List<String> sdcard,
                                   String cur, TextView curView, LinearLayout listBox) {
        container.removeAllViews();
        addProjectGroup(container, "内置目录（/root/）", inner, "/root", cur, curView, listBox);
        addProjectGroup(container, "手机目录（/sdcard/ReasonixProjects/）", sdcard, "/sdcard/ReasonixProjects", cur, curView, listBox);
        if (inner.isEmpty() && sdcard.isEmpty()) container.addView(createDarkTip("（暂无项目，可新建）"));
    }

    private void addProjectGroup(LinearLayout container, String title, List<String> names,
                                 String base, String cur, TextView curView, LinearLayout listBox) {
        if (names.isEmpty()) return;
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFAAAAAA);
        t.setTextSize(12);
        t.setPadding(0, dp(10), 0, dp(2));
        container.addView(t);
        for (final String name : names) {
            final String path = base + "/" + name;
            final boolean isCur = path.equals(cur);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView tv = new TextView(this);
            tv.setText(name + (isCur ? "（当前）" : ""));
            tv.setTextColor(isCur ? 0xFF4CAF50 : 0xFFE0E0E0);
            tv.setTextSize(14);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button go = createDarkButton("进入");
            go.setOnClickListener(v -> switchProject(path, curView, listBox));
            row.addView(go);
            Button del = createDarkButton("删除");
            del.setOnClickListener(v -> deleteProject(path, curView, listBox));
            row.addView(del);
            container.addView(row);
        }
    }

    /** 删除项目目录；若是当前项目则同时清除切换标记 */
    private void deleteProject(String path, TextView curView, LinearLayout listBox) {
        new Thread(() -> {
            try {
                boolean ok;
                if (path.startsWith("/sdcard/")) {
                    // 手机目录走宿主侧（文件管理器可见）
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    ok = deleteRecursive(new File("/storage/emulated/0/ReasonixProjects", name));
                } else {
                    String out = executeInGuest("rm -rf " + path + " && echo DELETED_OK", 10);
                    ok = out != null && out.contains("DELETED_OK");
                }
                if (!ok) {
                    runOnUiThread(() -> pushOutput("\r\n[删除项目失败]\r\n"));
                    return;
                }
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                File mark = new File(rootDir, ".rsxm-project");
                if (mark.exists()) {
                    String s = new String(java.nio.file.Files.readAllBytes(mark.toPath()),
                            StandardCharsets.UTF_8).trim();
                    if (s.equals(path)) mark.delete();   // 删的是当前项目，回默认
                }
                runOnUiThread(() -> {
                    pushOutput("\r\n[已删除项目 " + path + "]\r\n");
                    loadProjectList(curView, listBox);
                });
            } catch (Exception e) {
                Log.e(TAG, "delete project failed", e);
                runOnUiThread(() -> pushOutput("\r\n[删除项目失败: " + e.getMessage() + "]\r\n"));
            }
        }, "project-del").start();
    }

    /** 递归删除目录/文件（宿主侧手机项目用） */
    private boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        return f.delete();
    }

    /** 校验新项目名称（目录名安全） */
    private boolean checkProjectName(String name) {
        if (name.isEmpty()) {
            pushOutput("\r\n[请输入项目名称]\r\n");
            return false;
        }
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            pushOutput("\r\n[项目名称仅允许字母、数字、_ . -]\r\n");
            return false;
        }
        return true;
    }

    /** 新建项目：手机目录(/sdcard/ReasonixProjects/<name>)或内部(/root/<name>)，创建后切换并重启 */
    private void createProject(String name, boolean inSdcard, TextView curView, LinearLayout listBox) {
        String path = inSdcard
                ? "/sdcard/ReasonixProjects/" + name
                : "/root/" + name;
        new Thread(() -> {
            try {
                // 手机目录走宿主侧（文件管理器可见；chroot 内写 /sdcard 被 mount namespace 隔离）；
                // 内置目录走 guest（rootfs 内）
                boolean ok;
                if (inSdcard) {
                    // mkdirs 失败不阻断（reasonix wrapper 启动时对标记目录 mkdir -p 兜底，
                    // guest 内可用；宿主可见性在权限正常时由这里保证）
                    try {
                        new File("/storage/emulated/0/ReasonixProjects", name).mkdirs();
                    } catch (Exception ignored) {
                    }
                    ok = true;
                } else {
                    String out = executeInGuest("mkdir -p " + path + " && echo MKDIR_OK", 10);
                    ok = out != null && out.contains("MKDIR_OK");
                }
                if (!ok) {
                    runOnUiThread(() -> pushOutput("\r\n[创建项目目录失败]\r\n"));
                    return;
                }
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                java.nio.file.Files.write(new File(rootDir, ".rsxm-project").toPath(),
                        (path + "\n").getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    pushOutput("\r\n[已创建项目 " + path + "，正在重启 reasonix...]\r\n");
                    restartEnvironment();
                    refreshAfterEnvRestart(curView, listBox);
                });
            } catch (Exception e) {
                Log.e(TAG, "create project failed", e);
                runOnUiThread(() -> pushOutput("\r\n[创建项目失败: " + e.getMessage() + "]\r\n"));
            }
        }, "project-create").start();
    }

    /** 刷新当前项目与项目列表显示（内部 + 手机目录） */
    /** 切换项目：guest 内创建目录 + 写标记 + 重启环境（reasonix 从新项目目录启动） */
    private void switchProject(String path, TextView curView, LinearLayout listBox) {
        new Thread(() -> {
            try {
                // 手机目录走宿主侧（文件管理器可见），内置走 guest
                boolean ok;
                if (path.startsWith("/sdcard/")) {
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    // 宿主 mkdirs（文件管理器可见）；失败不阻断切换——reasonix wrapper 启动时
                    // 会对标记目录 mkdir -p（guest 内 /sdcard 绑定可见宿主目录），目录存在性由它兜底
                    try {
                        new File("/storage/emulated/0/ReasonixProjects", name).mkdirs();
                    } catch (Exception ignored) {
                    }
                    ok = true;
                } else {
                    String out = executeInGuest("mkdir -p " + path + " && echo MKDIR_OK", 10);
                    ok = out != null && out.contains("MKDIR_OK");
                }
                if (!ok) {
                    runOnUiThread(() -> pushOutput("\r\n[创建项目目录失败]\r\n"));
                    return;
                }
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                java.nio.file.Files.write(new File(rootDir, ".rsxm-project").toPath(),
                        (path + "\n").getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    pushOutput("\r\n[已切换到项目 " + path + "，正在重启 reasonix...]\r\n");
                    restartEnvironment();
                    refreshAfterEnvRestart(curView, listBox);
                });
            } catch (Exception e) {
                Log.e(TAG, "switch project failed", e);
                runOnUiThread(() -> pushOutput("\r\n[切换项目失败: " + e.getMessage() + "]\r\n"));
            }
        }, "project-switch").start();
    }

    /** 环境重启后延迟刷新项目列表（重启期间 guest 服务不可用，等环境起来再读） */
    private void refreshAfterEnvRestart(TextView curView, LinearLayout listBox) {
        curView.postDelayed(() -> loadProjectList(curView, listBox), 4000);
    }

    /* ==================== 开发环境 ==================== */

    /** 开发环境：一键安装常用开发环境（Alpine 包管理器，后台安装 + 面板内实时进度） */
    private void showDevEnvDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        panel.addView(createDarkTip(
                "一键安装开发环境（Alpine 包，需联网），进度实时显示。\n"
                        + "大环境（Android/Go）耗时较长。"));

        // chroot 检测：Android 开发依赖 chroot 运行模式（JVM 需 root 域，proot + SELinux
        // enforcing 下 mprotect RWX 被拒无法启动）；未开启 chroot 时置灰并提示。
        // Python/Node/Go/C-C++/通用工具不依赖 chroot，保持可用。
        final boolean chrootOn = isChrootMode();
        final TextView chrootWarn = new TextView(this);
        chrootWarn.setText(chrootOn ? ""
                : "⚠ 未开启 chroot：Android 开发已置灰（JVM 需 root 域）。\nROOT 面板可切换为 chroot。");
        chrootWarn.setTextColor(chrootOn ? 0xFFAAAAAA : 0xFFFF6B6B);
        chrootWarn.setTextSize(13);
        chrootWarn.setLineSpacing(0, 1.2f);
        chrootWarn.setMaxLines(2);
        chrootWarn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chrootWarn.setPadding(0, dp(6), 0, dp(2));
        // 固定高度：警告出现/消失/精简不引起功能页内容上下移动
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        warnLp.topMargin = dp(6);
        panel.addView(chrootWarn, warnLp);

        // 安装进度区（固化显示且高度固定：空闲/安装中/完成时输出内容变化不引起功能页上下调整）
        LinearLayout progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(0, dp(12), 0, dp(8));
        progressBox.setVisibility(View.VISIBLE);
        TextView progressTitle = new TextView(this);
        progressTitle.setText("安装进度");
        progressTitle.setTextColor(0xFFAAAAAA);
        progressTitle.setTextSize(14);
        progressTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        TextView progressText = new TextView(this);
        progressText.setText("空闲：点列表中的「安装」开始，进度实时显示于此。");
        progressText.setTextColor(0xFFAAAAAA);
        progressText.setTextSize(12);
        progressText.setPadding(0, dp(4), 0, 0);
        progressText.setMaxLines(2);   // 固定行数，输出变化不改变高度
        progressText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        progressBox.addView(progressTitle);
        progressBox.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        progressBox.addView(progressText);
        panel.addView(progressBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        // envs 第 4 列：是否依赖 chroot 运行模式。实测仅 Android 开发依赖
        // （JVM 需 root 域 execmem，proot + SELinux enforcing 下 mprotect RWX 被拒无法启动）；
        // Python/Node/Go/C-C++/通用工具 安装与运行均不依赖。
        // envs 第 5 列：关键命令（安装前检测半装：数据库标记已装但文件缺失 → 自动清理重装）
        String[][] builtin = {
                {"Android 开发", "openjdk17-jdk gradle android-tools",
                        "JDK17 + Gradle + adb/fastboot（安卓应用构建）", "1", "java gradle adb"},
                {"Python 开发", "python3 py3-pip", "Python3 + pip", "0", "python3 pip3"},
                {"Node.js", "nodejs npm", "Node.js + npm", "0", "node npm"},
                {"Go 开发", "go", "Go 语言工具链", "0", "go"},
                {"C/C++ 开发", "gcc g++ make musl-dev", "GCC/G++ + Make + 头文件", "0", "gcc g++ make"},
                {"通用工具", "git vim curl wget zip unzip", "Git/Vim/curl/wget 等", "0", "git vim curl wget zip unzip"},
        };
        // 合并模板导入的自定义环境（持久化于本地偏好，重启 app 保留）
        java.util.List<String[]> envListAll = new java.util.ArrayList<>();
        Collections.addAll(envListAll, builtin);
        envListAll.addAll(loadCustomEnvs());
        final String[][] envs = envListAll.toArray(new String[0][]);
        // ---- 模板区：下载模板（.rsxmenv）+ 导入模板 ----
        panel.addView(createDarkSectionTitle("自定义模板"));
        LinearLayout tplRow = new LinearLayout(this);
        tplRow.setOrientation(LinearLayout.HORIZONTAL);
        Button dlBtn = createDarkButton("下载模板");
        dlBtn.setOnClickListener(v -> downloadEnvTemplate());
        Button impBtn = createDarkButton("导入模板");
        impBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_IMPORT_ENV_TEMPLATE);
            } catch (Exception e) {
                pushOutput("\r\n[无法打开文件选择器: " + e.getMessage() + "]\r\n");
            }
        });
        tplRow.addView(dlBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tplRow.addView(impBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams tplRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tplRowLp.topMargin = dp(6);
        panel.addView(tplRow, tplRowLp);
        addV(panel, createDarkTip("模板（.rsxmenv）可自定义环境：下载模板 → 配置 apk 包 → 导入后即可安装。\n"
                + "chroot=1 的环境需先在 ROOT 面板开启 chroot 模式。"), 8);
        // ---- 已安装列表（固定高度 + 二级滑动；每行 名称/状态/删除）----
        panel.addView(createDarkSectionTitle("已安装环境"));
        final ScrollView envScroll = new ScrollView(this);
        final LinearLayout envList = new LinearLayout(this);
        envList.setOrientation(LinearLayout.VERTICAL);
        envScroll.addView(envList);
        LinearLayout.LayoutParams envLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        envLp.topMargin = dp(6);
        panel.addView(envScroll, envLp);
        loadInstalledEnvs(envList, envs, progressBox, progressTitle, progressBar, progressText);
        // 面板重开时若仍有安装任务在后台进行：恢复显示进度区
        String installing = devEnvInstallingName;
        if (installing != null) {
            progressBox.setVisibility(View.VISIBLE);
            progressTitle.setText(installing + " 安装中...");
            progressTitle.setTextColor(0xFFFFFFFF);
            progressBar.setIndeterminate(true);
            progressBar.setProgress(0);
            progressText.setText("安装进行中，请稍候...");
        }
        showPanel("开发环境", panel, null);
    }

    /** 开发环境模板（.rsxmenv）默认内容：下载模板时写入，用户编辑后导入添加自定义环境 */
    private static final String ENV_TEMPLATE_CONTENT =
            "# RSXM 开发环境模板（.rsxmenv）\n"
            + "# 配置后保存，在「开发环境」面板点「导入模板」选择此文件即可添加自定义环境\n"
            + "# name：环境名（显示用）\n"
            + "# packages：apk 包列表（空格分隔）\n"
            + "# description：描述\n"
            + "# chroot：1=依赖 chroot 运行模式，0=不依赖\n"
            + "# key_commands：关键命令（空格分隔，用于已装检测）\n"
            + "name=myenv\n"
            + "packages=git curl\n"
            + "description=我的自定义开发环境\n"
            + "chroot=0\n"
            + "key_commands=git curl\n";

    /** 读取持久化的自定义环境（模板导入，SharedPreferences JSON），返回 {name, packages, desc, chroot, keyCmds}[] */
    private List<String[]> loadCustomEnvs() {
        List<String[]> out = new ArrayList<>();
        try {
            String raw = getSharedPreferences("prefs", MODE_PRIVATE).getString("custom_dev_envs", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{
                        o.optString("name", ""),
                        o.optString("packages", ""),
                        o.optString("description", ""),
                        o.optString("chroot", "0"),
                        o.optString("keyCmds", "")});
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 持久化自定义环境列表 */
    private void saveCustomEnvs(List<String[]> envs) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String[] e : envs) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", e[0]);
                o.put("packages", e[1]);
                o.put("description", e[2]);
                o.put("chroot", e[3]);
                o.put("keyCmds", e[4]);
                arr.put(o);
            }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("custom_dev_envs", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 解析 .rsxmenv 模板文本：返回 {name, packages, desc, chroot, keyCmds}，缺 name/packages 返回 null */
    private String[] parseEnvTemplate(String content) {
        String name = "", packages = "", desc = "", chroot = "0", cmds = "";
        for (String l : content.split("\n")) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq < 0) continue;
            String k = t.substring(0, eq).trim();
            String v = t.substring(eq + 1).trim();
            if (k.equals("name")) name = v;
            else if (k.equals("packages")) packages = v;
            else if (k.equals("description")) desc = v;
            else if (k.equals("chroot")) chroot = v.equals("1") ? "1" : "0";
            else if (k.equals("key_commands")) cmds = v;
        }
        if (name.isEmpty() || packages.isEmpty()) return null;
        if (cmds.isEmpty()) cmds = packages.split(" ")[0];   // 缺省用第一个包作为关键命令
        return new String[]{name, packages, desc, chroot, cmds};
    }

    /** 下载开发环境模板：系统保存选择器（SAF），默认文件名 dev-env-template.rsxmenv */
    private void downloadEnvTemplate() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        i.putExtra(Intent.EXTRA_TITLE, "dev-env-template.rsxmenv");
        try {
            startActivityForResult(i, REQ_CREATE_ENV_TEMPLATE);
        } catch (Exception e) {
            pushOutput("\r\n[无法打开保存选择器: " + e.getMessage() + "]\r\n");
        }
    }

    /** 导入开发环境模板：读取 .rsxmenv → 解析 → 持久化（同名覆盖）→ 重建面板显示新环境 */
    private void importEnvTemplate(Uri uri) {
        new Thread(() -> {
            try {
                String content;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        runOnUiThread(() -> pushOutput("\r\n[导入失败: 无法读取所选文件]\r\n"));
                        return;
                    }
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    content = sb.toString();
                }
                final String[] env = parseEnvTemplate(content);
                if (env == null) {
                    runOnUiThread(() -> pushOutput("\r\n[模板无效：缺少 name 或 packages 字段]\r\n"));
                    return;
                }
                List<String[]> customs = loadCustomEnvs();
                boolean replaced = false;
                for (int i = 0; i < customs.size(); i++) {
                    if (customs.get(i)[0].equals(env[0])) { customs.set(i, env); replaced = true; }
                }
                if (!replaced) customs.add(env);
                saveCustomEnvs(customs);
                final boolean replacedFinal = replaced;
                runOnUiThread(() -> {
                    pushOutput("\r\n[已导入环境模板 " + env[0]
                            + (replacedFinal ? "（同名已覆盖）" : "") + "，可点「安装」开始安装]\r\n");
                    hidePanel();
                    showDevEnvDialog();   // 重建面板，列表显示新环境
                });
            } catch (Exception e) {
                Log.e(TAG, "import env template failed", e);
                runOnUiThread(() -> pushOutput("\r\n[导入模板失败: " + e.getMessage() + "]\r\n"));
            }
        }, "env-template-import").start();
    }

    /** 安装开发环境：guest 内 nohup 后台 apk add（防服务循环 20s timeout 杀掉长安装），
     *  轮询状态文件报告结果，并解析 apk 日志 (x/N) 实时刷新面板进度条 */
    private void installDevEnv(String name, String packages, String keyCmds,
                               View progressBox, TextView progressTitle, ProgressBar progressBar,
                               TextView progressText, LinearLayout envList, String[][] envs) {
        if (devEnvInstalling) {
            runOnUiThread(() -> pushOutput("\r\n[开发环境] 已有安装任务进行中，请等待完成\r\n"));
            return;
        }
        devEnvInstalling = true;
        devEnvInstallingName = name;
        pushOutput("\r\n[开发环境] 开始安装 " + name + "（后台进行，进度见面板，可继续使用终端）...\r\n");
        runOnUiThread(() -> {
            progressBox.setVisibility(View.VISIBLE);
            progressTitle.setText(name + " 安装中...");
            progressTitle.setTextColor(0xFFFFFFFF);
            progressBar.setIndeterminate(true);
            progressBar.setProgress(0);
            progressText.setText("正在更新软件源索引...");
        });
        new Thread(() -> {
            try {
                // 安装脚本经 base64 写入 guest（服务循环用 sh -c "$CMD" 执行，命令内不能含双引号），
                // 再 nohup 后台执行，避免 20s timeout 杀掉长安装。
                // 半装自动修复：关键命令缺失（apk 数据库标记已装但文件中断缺失，如 libjli.so/二进制丢失）
                // 时先 apk del 清理残留再全新安装，避免 apk add 因"已装"直接跳过导致环境不可用。
                String script = "#!/bin/sh\n"
                        + "# 等待其他 apk 操作完成（防并发 apk 数据库锁冲突：Unable to lock database, 退出码 99）\n"
                        + "i=0\n"
                        + "while [ -f /root/.env-lock ] && [ $i -lt 90 ]; do sleep 1; i=$((i+1)); done\n"
                        + "touch /root/.env-lock\n"
                        + "trap 'rm -f /root/.env-lock' EXIT\n"
                        + "apk update > /root/.env-install.log 2>&1\n"
                        + "echo \"--- 安装 " + name + " ---\" >> /root/.env-install.log\n"
                        + "NEED=0\n"
                        + "for c in " + keyCmds + "; do command -v $c >/dev/null 2>&1 || NEED=1; done\n"
                        + "if [ $NEED = 1 ]; then\n"
                        + "  echo \"[检测到安装不完整，清理残留后重新安装]\" >> /root/.env-install.log\n"
                        + "  apk del " + packages + " >> /root/.env-install.log 2>&1\n"
                        + "fi\n"
                        + "apk add --no-cache " + packages + " >> /root/.env-install.log 2>&1\n"
                        + "echo INSTALL_DONE_$? > /root/.env-done\n";
                String b64 = Base64.encodeToString(script.getBytes("UTF-8"), Base64.NO_WRAP);
                executeInGuest("rm -f /root/.env-done /root/.env-install.log; "
                        + "echo " + b64 + " | base64 -d > /root/.env-install.sh; chmod +x /root/.env-install.sh; "
                        + "nohup sh /root/.env-install.sh > /dev/null 2>&1 & echo STARTED", 8);
                long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(3000);
                    // 读取日志尾部解析进度（fetch 阶段不定进度，(x/N) 阶段百分比）
                    String log = executeInGuest("tail -80 /root/.env-install.log 2>/dev/null", 6);
                    if (log != null) {
                        Matcher m = APK_PROGRESS.matcher(log);
                        int done = -1, total = -1;
                        while (m.find()) {
                            done = Integer.parseInt(m.group(1));
                            total = Integer.parseInt(m.group(2));
                        }
                        final int fDone = done, fTotal = total;
                        runOnUiThread(() -> {
                            if (fDone > 0 && fTotal > 0) {
                                int pct = Math.min(99, fDone * 100 / fTotal);
                                progressBar.setIndeterminate(false);
                                progressBar.setProgress(pct);
                                progressText.setText("正在安装 " + fDone + "/" + fTotal + "（" + pct + "%）");
                            } else if (log.contains("fetch")) {
                                progressBar.setIndeterminate(true);
                                progressText.setText("正在下载软件包...");
                            } else {
                                progressBar.setIndeterminate(true);
                                progressText.setText("正在更新软件源索引...");
                            }
                        });
                    }
                    String st = executeInGuest("cat /root/.env-done 2>/dev/null", 6);
                    if (st != null && st.contains("INSTALL_DONE_")) {
                        final String code = st.replaceAll("[^0-9]", "").trim();
                        String doneLog = executeInGuest("tail -4 /root/.env-install.log 2>/dev/null", 6);
                        final String tail = doneLog == null ? "" : doneLog;
                        final boolean ok = code.equals("0");
                        String msg = "\r\n[开发环境] " + name + " 安装完成（apk 退出码 " + code + "）\n"
                                + tail + "\r\n"
                                + (ok ? "[完成] 可直接在终端使用 " + name + " 环境\r\n"
                                : "[失败] 请检查网络（aliyun 源）后重试\r\n");
                        runOnUiThread(() -> {
                            progressTitle.setText(name + (ok ? " 安装完成" : " 安装失败"));
                            progressTitle.setTextColor(ok ? 0xFF7FDB8A : 0xFFFF6B6B);
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(100);
                            // 进度条下只显示简单状态，不回显安装日志（tail 已固化省略无意义）
                            progressText.setText(ok ? "完成（退出码 0）" : "失败（apk 退出码 " + code + "）");
                            loadInstalledEnvs(envList, envs, progressBox, progressTitle, progressBar, progressText);
                            pushOutput(msg);
                        });
                        return;
                    }
                }
                runOnUiThread(() -> {
                    progressTitle.setText(name + " 安装超时");
                    progressTitle.setTextColor(0xFFFFD54F);
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    progressText.setText("超过 15 分钟未完成，请检查网络后重试");
                    loadInstalledEnvs(envList, envs, progressBox, progressTitle, progressBar, progressText);
                    pushOutput("\r\n[开发环境] " + name + " 安装超时（15 分钟），请检查网络后重试\r\n");
                });
            } catch (Exception e) {
                Log.e(TAG, "install dev env failed", e);
                runOnUiThread(() -> {
                    progressTitle.setText(name + " 安装失败");
                    progressTitle.setTextColor(0xFFFF6B6B);
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    progressText.setText("异常：" + e.getMessage());
                    loadInstalledEnvs(envList, envs, progressBox, progressTitle, progressBar, progressText);
                    pushOutput("\r\n[开发环境] 安装失败: " + e.getMessage() + "\r\n");
                });
            } finally {
                devEnvInstalling = false;
                devEnvInstallingName = null;
            }
        }, "dev-env").start();
    }

    /** 检测各开发环境是否已安装（关键命令存在），渲染到已安装列表 */
    private void loadInstalledEnvs(LinearLayout container, String[][] envs, View progressBox,
                                   TextView progressTitle, ProgressBar progressBar, TextView progressText) {
        container.removeAllViews();
        container.addView(createDarkTip("检测中..."));
        new Thread(() -> {
            try {
                // 标签动态生成：内置+自定义环境都可能超过 6 个（A-Z 后接 a-z，最多 52 个）
                char[] tags = new char[envs.length];
                for (int i = 0; i < envs.length; i++) tags[i] = (char)(i < 26 ? 'A' + i : 'a' + (i - 26));
                StringBuilder cmd = new StringBuilder("ok(){ command -v $1 >/dev/null 2>&1 && echo -n 1 || echo -n 0; }; ");
                for (int i = 0; i < envs.length && i < tags.length; i++) {
                    cmd.append("echo ").append(tags[i]).append(":$(ok ")
                            .append(envs[i][4].replace(" ", ")$(ok ")).append("); ");
                }
                String out = executeInGuest(cmd.toString(), 15);
                final boolean[] installed = new boolean[envs.length];
                final boolean[] partial = new boolean[envs.length];
                if (out != null) {
                    for (int i = 0; i < envs.length && i < tags.length; i++) {
                        String line = null;
                        for (String l : out.split("\n")) {
                            if (l.startsWith(tags[i] + ":")) { line = l.substring(2).trim(); break; }
                        }
                        if (line == null) continue;
                        int count = 0;
                        for (int k = 0; k < line.length(); k++) if (line.charAt(k) == '1') count++;
                        int total = envs[i][4].split(" ").length;
                        installed[i] = count == total;
                        partial[i] = count > 0 && count < total;
                    }
                }
                final boolean[] fInstalled = installed, fPartial = partial;
                runOnUiThread(() -> renderInstalledEnvs(container, envs, fInstalled, fPartial,
                        progressBox, progressTitle, progressBar, progressText));
            } catch (Exception e) {
                Log.e(TAG, "load installed envs failed", e);
            }
        }, "env-installed-load").start();
    }

    /** 渲染环境列表（每行：名称 + 状态 + 未安装时的「安装」按钮 + 已装时的「删除」按钮） */
    private void renderInstalledEnvs(LinearLayout container, String[][] envs,
                                     boolean[] installed, boolean[] partial,
                                     View progressBox, TextView progressTitle,
                                     ProgressBar progressBar, TextView progressText) {
        container.removeAllViews();
        // 自定义环境名集合（模板导入）：未安装时额外提供「移除」按钮，可彻底剔除模板条目
        java.util.Set<String> customNames = new java.util.HashSet<>();
        for (String[] ce : loadCustomEnvs()) customNames.add(ce[0]);
        for (int i = 0; i < envs.length; i++) {
            final String[] e = envs[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView tv = new TextView(this);
            String st = installed[i] ? "已安装" : (partial[i] ? "部分安装" : "未安装");
            tv.setText(e[0] + "：" + st);
            tv.setTextColor(installed[i] ? 0xFF7FDB8A : (partial[i] ? 0xFFFFD54F : 0xFF888888));
            tv.setTextSize(13);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // 未安装/部分 → 安装（重装）按钮；chroot 依赖环境（Android 开发等，e[3]=="1"）
            // 在非 chroot 模式不可用（JVM 需 root 域）→ 置灰；已安装/部分 → 删除按钮
            if (!installed[i]) {
                final boolean needChroot = e[3].equals("1") && !isChrootMode();
                final String label = needChroot ? "需 chroot" : (partial[i] ? "重装" : "安装");
                Button inst = createDarkButton(label);
                if (needChroot) {
                    inst.setEnabled(false);
                    inst.setTextColor(0xFF6A6A6A);
                    inst.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1E1E1E));
                } else {
                    inst.setOnClickListener(v -> installDevEnv(e[0], e[1], e[4],
                            progressBox, progressTitle, progressBar, progressText, container, envs));
                }
                row.addView(inst);
                if (customNames.contains(e[0])) {
                    Button rm = createDarkButton("移除");
                    rm.setOnClickListener(v -> removeCustomEnv(e[0]));
                    row.addView(rm);
                }
            }
            if (installed[i] || partial[i]) {
                Button del = createDarkButton("删除");
                del.setOnClickListener(v -> uninstallDevEnv(e[0], e[1], container, envs,
                        progressBox, progressTitle, progressBar, progressText));
                row.addView(del);
            }
            container.addView(row);
        }
    }

    /** 移除自定义环境模板：从持久化列表剔除并重建面板（已装的 apk 请先点「删除」） */
    private void removeCustomEnv(String name) {
        List<String[]> customs = loadCustomEnvs();
        boolean removed = false;
        for (int i = 0; i < customs.size(); i++) {
            if (customs.get(i)[0].equals(name)) { customs.remove(i); removed = true; break; }
        }
        if (!removed) return;
        saveCustomEnvs(customs);
        pushOutput("\r\n[已移除自定义环境 " + name + "]\r\n");
        hidePanel();
        showDevEnvDialog();
    }

    /** 删除开发环境：guest 内 nohup apk del，完成后刷新已安装列表 */
    private void uninstallDevEnv(String name, String packages, LinearLayout container, String[][] envs,
                                 View progressBox, TextView progressTitle, ProgressBar progressBar, TextView progressText) {
        if (devEnvInstalling) {
            runOnUiThread(() -> pushOutput("\r\n[开发环境] 有安装/删除任务进行中，请等待完成\r\n"));
            return;
        }
        devEnvInstalling = true;
        devEnvInstallingName = name + " 删除";
        pushOutput("\r\n[开发环境] 开始删除 " + name + "（后台进行）...\r\n");
        new Thread(() -> {
            try {
                String script = "#!/bin/sh\n"
                        + "# 等待其他 apk 操作完成（防并发 apk 数据库锁冲突）\n"
                        + "i=0\n"
                        + "while [ -f /root/.env-lock ] && [ $i -lt 90 ]; do sleep 1; i=$((i+1)); done\n"
                        + "touch /root/.env-lock\n"
                        + "trap 'rm -f /root/.env-lock' EXIT\n"
                        + "echo \"--- 删除 " + name + " ---\" > /root/.env-install.log\n"
                        + "apk del " + packages + " >> /root/.env-install.log 2>&1\n"
                        + "echo INSTALL_DONE_$? > /root/.env-done\n";
                String b64 = Base64.encodeToString(script.getBytes("UTF-8"), Base64.NO_WRAP);
                executeInGuest("rm -f /root/.env-done /root/.env-install.log; "
                        + "echo " + b64 + " | base64 -d > /root/.env-install.sh; chmod +x /root/.env-install.sh; "
                        + "nohup sh /root/.env-install.sh > /dev/null 2>&1 & echo STARTED", 8);
                long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(3000);
                    String st = executeInGuest("cat /root/.env-done 2>/dev/null", 6);
                    if (st != null && st.contains("INSTALL_DONE_")) {
                        runOnUiThread(() -> {
                            pushOutput("\r\n[开发环境] " + name + " 已删除\r\n");
                            loadInstalledEnvs(container, envs, progressBox, progressTitle, progressBar, progressText);
                        });
                        return;
                    }
                }
                runOnUiThread(() -> pushOutput("\r\n[开发环境] " + name + " 删除超时\r\n"));
            } catch (Exception e) {
                Log.e(TAG, "uninstall dev env failed", e);
            } finally {
                devEnvInstalling = false;
                devEnvInstallingName = null;
            }
        }, "dev-env-del").start();
    }

    /** 深色输入框（原生 Material 下划线风格，背景透明保持纯黑） */
    private EditText createDarkEditText(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setHint(hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF707070);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        // 统一块状深灰底（与 SKILL 内容输入框一致），替代 Material 下划线，深色面板更清晰
        et.setBackgroundColor(0xFF1A1A1A);
        return et;
    }

    /** 原生风格按钮（平台 Theme.Material 下自带圆角/波纹，仅覆 tint 为黑灰保持纯黑）。
     *  统一最小高度与内边距：全宽主按钮与行内小按钮观感一致 */
    private Button createDarkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setAllCaps(false);
        // 原生涟漪保留（colorControlHighlight），底色改为黑灰
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF262626));
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    /** 深色说明文字（12sp 灰，带行距） */
    private TextView createDarkTip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFAAAAAA);
        tv.setTextSize(12);
        tv.setLineSpacing(0, 1.3f);
        return tv;
    }

    /** 深色区块标题（14sp 白 bold，与说明文字层级区分，用于功能页分组） */
    private TextView createDarkSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(6), 0, dp(2));
        return tv;
    }

    /** 添加子视图并统一顶部间距（功能页排版：元素间固定留白；负值/0 无上边距） */
    private void addV(LinearLayout panel, View v, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topDp > 0) lp.topMargin = dp(topDp);
        panel.addView(v, lp);
    }

    /** 深色代码结果区 */
    private TextView createDarkResult() {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFF7FDB8A);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setBackgroundColor(0xFF101010);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        // 固定最小高度 + 行数限制：结果回显出现/更新不引起功能页内容上下移动（长输出省略，完整在终端）
        tv.setMinHeight(dp(48));
        tv.setMaxLines(4);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    /** ADB 无线调试：显示局域网 IP 与连接指引 */
    private void showAdbDialog() {
        String ip = getLocalIpAddress();
        final String fip = (ip == null) ? "<IP>" : ip;
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String savedPairPort = prefs.getString("adb_pair_port", "");
        String savedPairCode = prefs.getString("adb_pair_code", "");
        String status = readAdbStatus();
        // 输入面板：配对端口 / 配对码（连接端口由 app 自动扫描，无需填写）
        EditText pairPort = createDarkEditText("配对端口（无线调试界面显示，如 37000）", InputType.TYPE_CLASS_NUMBER);
        if (!savedPairPort.isEmpty()) pairPort.setText(savedPairPort);
        EditText pairCode = createDarkEditText("配对码（6 位数字）", InputType.TYPE_CLASS_NUMBER);
        if (savedPairCode.length() == 6) pairCode.setText(savedPairCode);
        TextView tip = createDarkTip("本机 IP：" + ip + "\n"
                + "手机「开发者选项 → 无线调试」开启后：首次填配对端口+配对码点「配对并连接」；\n"
                + "之后点「自动连接」免配对直连；连接后 reasonix 内可直接 adb shell / adb install。");
        // 独立状态行（执行后自动刷新）
        TextView statusLine = new TextView(this);
        statusLine.setText("连接状态：" + status);
        statusLine.setTextColor(status.contains("已连接") ? 0xFF7FDB8A : (status.contains("需配对") ? 0xFFFFD54F : 0xFFCCCCCC));
        statusLine.setTextSize(13);
        statusLine.setTypeface(null, android.graphics.Typeface.BOLD);
        int pad = dp(16);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, dp(8), pad, dp(12));
        addV(panel, statusLine, 0);
        addV(panel, tip, 8);
        panel.addView(createDarkSectionTitle("配对信息"));
        addV(panel, pairPort, 6);
        addV(panel, pairCode, 8);
        // 执行结果区（adb 输出实时显示，不依赖 reasonix 会话）
        TextView resultView = createDarkResult();
        resultView.setText("（执行结果将显示在这里）");
        addV(panel, resultView, 8);
        // 自动连接按钮：app 直接驱动 guest 内 adb-autoconnect（扫描 30000-49999 并 connect）
        Button autoBtn = createDarkButton("自动连接（免配对直连）");
        autoBtn.setOnClickListener(v -> {
            saveAdbPrefs(prefs, pairPort, pairCode);
            runAdbInGuest("adb-autoconnect", resultView, statusLine);
        });
        addV(panel, autoBtn, 8);
        // 配对并连接按钮：app 执行配对 + 自动扫描连接端口 + 连接（全程 app 处理）
        Button pairBtn = createDarkButton("配对并连接");
        pairBtn.setOnClickListener(v -> {
            saveAdbPrefs(prefs, pairPort, pairCode);
            String pp = pairPort.getText().toString().trim();
            String pc = pairCode.getText().toString().trim();
            if (pp.isEmpty() || pc.isEmpty()) {
                resultView.setTextColor(0xFFFF6E6E);
                resultView.setText("请先在手机上开启「无线调试」，抄下配对端口和 6 位配对码后填写。");
                return;
            }
            runAdbInGuest("adb-dopair " + pp + " " + pc, resultView, statusLine);
        });
        addV(panel, pairBtn, 8);
        // 复制命令按钮（原对话框 neutral 按钮 → 面板内按钮）
        Button copyBtn = createDarkButton("复制命令");
        copyBtn.setOnClickListener(v -> {
            saveAdbPrefs(prefs, pairPort, pairCode);
            String cmd = buildAdbCommands(fip,
                    pairPort.getText().toString().trim(),
                    pairCode.getText().toString().trim());
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("adb", cmd));
            } catch (Exception e) {
                Log.w(TAG, "clipboard failed", e);
            }
        });
        addV(panel, copyBtn, 8);
        // Shizuku 支持：依赖 Shizuku 的 adb 权限持久化（替代原「后台保活」；Shizuku 服务常驻，
        // 本应用关闭后仍可经 Shizuku 执行 adb 命令）
        panel.addView(createDarkSectionTitle("Shizuku 持久化"));
        final TextView szStatus = new TextView(this);
        szStatus.setTextSize(13);
        szStatus.setPadding(dp(2), dp(2), dp(2), dp(4));
        final boolean szOn = shizukuAvailable();
        szStatus.setText("Shizuku：" + (szOn ? "已授权（adb 权限可用）" : "未授权/未安装"));
        szStatus.setTextColor(szOn ? 0xFF4CAF50 : 0xFFFFD54F);
        addV(panel, szStatus, 6);
        addV(panel, createDarkTip(
                "Shizuku 授权后 adb 命令以其权限执行，关闭本应用仍可用（替代后台保活）。"), 6);
        Button szBtn = createDarkButton(szOn
                ? "通过 Shizuku 持久化 adb（启动 adb server）" : "Shizuku 授权（打开授权页）");
        szBtn.setOnClickListener(v -> {
            if (shizukuAvailable()) {
                resultView.setTextColor(0xFFFFD54F);
                resultView.setText("通过 Shizuku 启动 adb server（持久化）...\n");
                new Thread(() -> {
                    String out = execViaShizuku("adb start-server 2>&1; adb devices 2>&1", 30);
                    runOnUiThread(() -> {
                        resultView.setTextColor(0xFF7FDB8A);
                        resultView.setText("Shizuku adb server 已启动（Shizuku 保持，持久化）：\n" + out);
                        szStatus.setText("Shizuku：已授权（adb 权限可用）");
                        szStatus.setTextColor(0xFF4CAF50);
                    });
                }, "shizuku-adb").start();
            } else {
                requestShizukuPermission();
            }
        });
        addV(panel, szBtn, 8);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("ADB 调试", panel, this::stopAdbStatusRefresh);
        // 状态实时刷新：面板打开期间每 4 秒用 adb devices 检查真实连接（防快照过期）
        startAdbStatusRefresh(statusLine);
        // 操作逻辑优化：状态未知（首次/环境刚启动）时自动触发一次检测，免去手动点击
        if (status.contains("未知")) {
            statusLine.postDelayed(() -> runAdbInGuest("adb-autoconnect", resultView, statusLine), 600);
        }
    }

    /** ADB 状态定时刷新：面板打开期间每 4 秒检查 adb devices 真实状态（防快照过期显示不符） */
    private final Handler adbStatusRefresher = new Handler(Looper.getMainLooper());
    private Runnable adbStatusTask;

    private void startAdbStatusRefresh(TextView statusLine) {
        stopAdbStatusRefresh();
        adbStatusTask = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    String st;
                    boolean wifiOff = false;
                    // 系统无线调试开关检测（经 root 桥读系统设置，不依赖 adb 连接）
                    try {
                        String wd = execRootCommand("settings get global adb_wifi_enabled", 4);
                        if (wd != null && wd.trim().equals("0")) wifiOff = true;
                    } catch (Exception ignored) {
                    }
                    if (wifiOff) {
                        st = "系统无线调试未开启（请到 设置→开发者选项→无线调试 打开）";
                    } else {
                        try {
                            String out = executeInGuest("adb devices 2>&1", 6);
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("([\\d.]+:\\d+)\\s+device(\\s|$)")
                                    .matcher(out == null ? "" : out);
                            if (m.find()) {
                                st = "已连接 " + m.group(1);
                            } else if (out != null && out.contains("unauthorized")) {
                                st = "需授权（手机弹窗点允许）";
                            } else if (out != null && out.contains("offline")) {
                                st = "设备离线（offline）";
                            } else {
                                st = "未连接（点自动连接或配对）";
                            }
                        } catch (Exception e) {
                            st = "检测失败";
                        }
                    }
                    final String statusText = st;   // final 副本供 lambda 捕获
                    runOnUiThread(() -> {
                        if (statusLine != null) statusLine.setText("连接状态：" + statusText);
                        adbStatusRefresher.postDelayed(this, 4000);
                    });
                }, "adb-status").start();
            }
        };
        adbStatusRefresher.postDelayed(adbStatusTask, 1200);
    }

    private void stopAdbStatusRefresh() {
        if (adbStatusTask != null) {
            adbStatusRefresher.removeCallbacks(adbStatusTask);
            adbStatusTask = null;
        }
    }

    /** 尝试 root 修复缺失的 native 库：从 APK 提取 so 到 nativeLibraryDir，并修复 SELinux 上下文（apk_data_file 域才可执行） */
    private boolean tryRepairNativeLib(String nativeLibDir, String apkPath) {        if (findSuPath() == null) return false;   // 无 root 无法 chcon，跳过
        File tmp = new File(getFilesDir(), "tmpnative");
        tmp.mkdirs();
        boolean any = false;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath)) {
            String prefix = "lib/arm64-v8a/";
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith(prefix) && n.endsWith(".so")) {
                    String name = n.substring(prefix.length());
                    File out = new File(tmp, name);
                    try (java.io.InputStream in = zf.getInputStream(e);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int c;
                        while ((c = in.read(buf)) > 0) fos.write(buf, 0, c);
                    }
                    String r = execRootCommand("mkdir -p " + nativeLibDir
                            + " && cp " + out.getAbsolutePath() + " " + nativeLibDir + "/" + name
                            + " && chmod 755 " + nativeLibDir + "/" + name
                            + " && chcon u:object_r:apk_data_file:s0 " + nativeLibDir + "/" + name
                            + " && echo REPAIR_OK", 30);
                    if (r != null && r.contains("REPAIR_OK")) {
                        Log.d(TAG, "native lib repaired: " + name);
                        any = true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "repair native lib failed", e);
        }
        return any && new File(nativeLibDir, "proot.so").exists();
    }

    /** 无 root 修复 native 库：自动打开系统安装器引导覆盖安装本 APK
     *  （覆盖安装后系统重新解压 native lib）。
     *  注意：Android 安全机制要求无 root 覆盖安装必须用户确认一次（静默自更新仅系统应用可用），
     *  因此自动打开安装器后用户点「更新」即可，无需找 APK/手动选择。首次需先允许"安装未知应用" */
    private void promptReinstallForNativeLib() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                pushOutput("\r\n[native 库缺失。需要覆盖安装本应用来修复，请先允许「安装未知应用」]\r\n");
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            // 复制 APK 到 cacheDir/apk/ 并打开系统安装器（覆盖安装 → 系统重新解压 native lib）
            File apkDir = new File(getCacheDir(), "apk");
            apkDir.mkdirs();
            File apk = new File(apkDir, "reinstall.apk");
            try (java.io.InputStream in = new java.io.FileInputStream(getApplicationInfo().sourceDir);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(apk)) {
                byte[] buf = new byte[65536];
                int c;
                while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pushOutput("\r\n[native 库缺失。已自动打开安装器，请点「更新/安装」覆盖安装本应用（系统将重新解压所需文件）]\r\n");
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "prompt reinstall failed", e);
            pushOutput("\r\n[启动失败] native 库未解压：请卸载后重新安装本 APK（或电脑端用 adb install --no-streaming）\r\n");
        }
    }

    /** Shizuku 可用性：binder 存活且已授权（adb/root 权限） */
    private boolean shizukuAvailable() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.w(TAG, "shizuku check failed", e);
            return false;
        }
    }

    /** 经 Shizuku 权限执行 shell 命令（adb 权限；Shizuku 服务常驻 → 持久化，不依赖本应用存活） */
    private String execViaShizuku(String cmd, int timeoutSec) {
        try {
            Process p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            try (InputStream in = p.getInputStream()) {
                while (System.currentTimeMillis() < deadline) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            Log.w(TAG, "shizuku exec failed", e);
            return null;
        }
    }

    /** 请求 Shizuku 授权（打开 Shizuku Manager 授权页）；未安装时给出提示 */
    private void requestShizukuPermission() {
        try {
            Intent intent = new Intent("moe.shizuku.manager.intent.action.REQUEST_PERMISSION");
            intent.setPackage("moe.shizuku.manager");
            intent.putExtra("moe.shizuku.manager.intent.extra.APP_UID", getApplicationInfo().uid);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "request shizuku permission failed", e);
            pushOutput("\r\n[Shizuku Manager 未安装，请先安装 Shizuku（GitHub: RikkaApps/Shizuku）后再授权]\r\n");
        }
    }

    /** 在 guest 内直接执行 adb 命令（经 adb 服务，不依赖 reasonix/AI 会话），结果实时显示 */
    private void runAdbInGuest(String cmd, TextView resultView, TextView statusLine) {
        final String fcmd = cmd;
        resultView.setTextColor(0xFFFFD54F);
        resultView.setText("执行中...\n" + fcmd.trim());
        new Thread(() -> {
            String out = executeInGuest(fcmd, 150);
            runOnUiThread(() -> {
                resultView.setTextColor(out.contains("error") || out.contains("超时")
                        ? 0xFFFF6E6E : 0xFF7FDB8A);
                resultView.setText(out);
                if (statusLine != null) {
                    String st = readAdbStatus();
                    statusLine.setText("连接状态：" + st);
                    statusLine.setTextColor(st.contains("已连接") ? 0xFF7FDB8A
                            : (st.contains("需配对") ? 0xFFFFD54F : 0xFFCCCCCC));
                }
            });
        }, "adb-exec").start();
    }

    /** 写入命令到 guest adb 服务并轮询结果（.adb-cmd → 执行 → .adb-out 含 __DONE__ 标记）。
     *  全程持有 adbCmdLock 串行执行：多条并发命令共用同一个 .adb-cmd/.adb-out 文件，
     *  并发写入会互相覆盖导致命令丢失/读到错误输出（如安装 SKILL 后立刻刷新列表）。 */
    private final Object adbCmdLock = new Object();
    private String executeInGuest(String cmd, int timeoutSec) {
        synchronized (adbCmdLock) {
            try {
                File root = new File(new File(getFilesDir(), "rootfs"), "root");
                File cmdFile = new File(root, ".adb-cmd");
                File outFile = new File(root, ".adb-out");
                outFile.delete();
                java.nio.file.Files.write(cmdFile.toPath(), cmd.getBytes(StandardCharsets.UTF_8));
                long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(300);
                    if (outFile.exists()) {
                        String out = new String(java.nio.file.Files.readAllBytes(outFile.toPath()),
                                StandardCharsets.UTF_8);
                        if (out.contains("__DONE__")) {
                            outFile.delete();
                            String r = out.replace("__DONE__", "").trim();
                            Log.d(TAG, "adb out: " + r);
                            return r.isEmpty() ? "(无输出)" : r;
                        }
                    }
                }
                return "(执行超时 " + timeoutSec + " 秒)";
            } catch (Exception e) {
                Log.w(TAG, "executeInGuest failed", e);
                return "(执行失败: " + e + ")";
            }
        }
    }

    /** 保存 ADB 配对信息（下次打开自动填充） */
    private void saveAdbPrefs(SharedPreferences prefs, EditText pairPort, EditText pairCode) {
        prefs.edit()
                .putString("adb_pair_port", pairPort.getText().toString().trim())
                .putString("adb_pair_code", pairCode.getText().toString().trim())
                .apply();
    }

    /** 读取 guest 内 adb 连接状态（/root/.adb_status，由 adb-autoconnect 写入） */
    private String readAdbStatus() {
        try {
            File f = new File(new File(new File(getFilesDir(), "rootfs"), "root"), ".adb_status");
            if (f.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    if (s.startsWith("connected")) return "已连接 " + s.substring("connected ".length());
                    if (s.equals("unauthorized")) return "已连接但未授权（请在手机弹窗点允许）";
                    if (s.equals("offline")) return "设备离线（offline）";
                    if (s.equals("need_pair")) return "需配对：无线调试已开启但未信任本设备（填写配对端口+配对码配对）";
                    if (s.equals("no_port")) return "未发现无线调试端口（请确认已开启）";
                    if (s.equals("no_adb")) return "adb 未就绪（android-tools 后台安装中，请稍后重试）";
                    if (s.equals("no_ip")) return "未获取本机 IP（请连接 Wi-Fi）";
                    return s;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "readAdbStatus failed", e);
        }
        return "未知（首次启动后自动检测）";
    }

    /** 生成 adb 配对/连接命令（由 app 驱动 guest 内 adb-dopair 完成配对+自动扫描连接端口） */
    private String buildAdbCommands(String ip, String pairPort, String pairCode) {
        StringBuilder sb = new StringBuilder();
        if (!pairPort.isEmpty() && !pairCode.isEmpty()) {
            sb.append("adb-dopair ").append(pairPort).append(' ').append(pairCode).append('\n');
        }
        return sb.toString();
    }

    /** 向 guest 终端发送命令（用户需已退到 shell；reasonix 会话内无效） */
    private void sendToTerminal(String cmd) {
        try {
            if (sProcIn != null) {
                sProcIn.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                sProcIn.flush();
                pushOutput("\r\n[已发送 adb 命令到终端]\r\n");
                Log.d(TAG, "sent to terminal: " + cmd.replace("\n", " ; "));
            } else {
                pushOutput("\r\n[终端未就绪]\r\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "send to terminal failed", e);
        }
    }

    /** 获取本机局域网 IPv4 地址（遍历网络接口） */
    private String getLocalIpAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    byte[] b = addr.getAddress();
                    if (b.length == 4 && (b[0] & 0xff) != 0) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getLocalIpAddress failed", e);
        }
        return null;
    }

    /** API Key 配置：读取/修改 reasonix 的 DEEPSEEK_API_KEY */
    /** 解析 config.toml 当前 default_model（provider name，无则默认 deepseek-flash） */
    private String parseDefaultModel(File conf) {
        if (conf != null && conf.exists()) {
            try {
                for (String l : new String(java.nio.file.Files.readAllBytes(conf.toPath()),
                        StandardCharsets.UTF_8).split("\n")) {
                    String t = l.trim();
                    if (t.startsWith("default_model")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("default_model\\s*=\\s*\"([^\"]+)\"").matcher(t);
                        if (m.find()) return m.group(1);
                    }
                }
            } catch (Exception ignored) {}
        }
        return "deepseek-flash";
    }

    /** 解析 config.toml [[providers]] 块：返回 {providerName, model} 列表 */
    private List<String[]> parseProviders(File conf) {
        List<String[]> list = new ArrayList<>();
        if (conf != null && conf.exists()) {
            try {
                String name = null, model = null;
                for (String l : new String(java.nio.file.Files.readAllBytes(conf.toPath()),
                        StandardCharsets.UTF_8).split("\n")) {
                    String t = l.trim();
                    if (t.startsWith("[[providers]]")) {
                        if (name != null && model != null) list.add(new String[]{name, model});
                        name = null; model = null;
                    } else if (name == null && t.matches("name\\s*=.*")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(t);
                        if (m.find()) name = m.group(1);
                    } else if (model == null && t.matches("model\\s*=.*")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^model\\s*=\\s*\"([^\"]+)\"").matcher(t);
                        if (m.find()) model = m.group(1);
                    }
                }
                if (name != null && model != null) list.add(new String[]{name, model});
            } catch (Exception ignored) {}
        }
        return list;
    }

    /** Provider 配置信息（对应 config.toml 的一个 [[providers]] 块） */
    private static class ProviderInfo {
        String name;      // provider 名（default_model 引用）
        String kind;      // anthropic | openai
        String baseUrl;   // API 端点
        String apiKeyEnv; // .env 中存储密钥的变量名
        String model;     // 默认模型（default / model 字段）
        List<String> models;   // models=[...] 列表（可选；为空时联网拉取或手工填）
        ProviderInfo(String name, String kind, String baseUrl, String apiKeyEnv, String model) {
            this(name, kind, baseUrl, apiKeyEnv, model, null);
        }
        ProviderInfo(String name, String kind, String baseUrl, String apiKeyEnv, String model, List<String> models) {
            this.name = name; this.kind = kind; this.baseUrl = baseUrl;
            this.apiKeyEnv = apiKeyEnv; this.model = model; this.models = models;
        }
    }

    /** 解析 config.toml 全部 [[providers]] 块，返回含 name/kind/base_url/api_key_env/model/models 的完整信息 */
    private List<ProviderInfo> parseProviderInfos(File conf) {
        List<ProviderInfo> list = new ArrayList<>();
        if (conf != null && conf.exists()) {
            try {
                String name = null, kind = null, baseUrl = null, apiKeyEnv = null, model = null;
                List<String> models = null;
                for (String l : new String(java.nio.file.Files.readAllBytes(conf.toPath()),
                        StandardCharsets.UTF_8).split("\n")) {
                    String t = l.trim();
                    if (t.startsWith("[[providers]]")) {
                        if (name != null) list.add(new ProviderInfo(name, kind, baseUrl, apiKeyEnv, model, models));
                        name = null; kind = null; baseUrl = null; apiKeyEnv = null; model = null; models = null;
                        continue;
                    }
                    if (t.startsWith("[")) continue;   // 其他 section 块跳过
                    java.util.regex.Matcher m;
                    if (name == null && (m = java.util.regex.Pattern.compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        name = m.group(1);
                    } else if (kind == null && (m = java.util.regex.Pattern.compile("^kind\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        kind = m.group(1);
                    } else if (baseUrl == null && (m = java.util.regex.Pattern.compile("^base_url\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        baseUrl = m.group(1);
                    } else if (apiKeyEnv == null && (m = java.util.regex.Pattern.compile("^api_key_env\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        apiKeyEnv = m.group(1);
                    } else if (model == null && (m = java.util.regex.Pattern.compile("^(?:default|model)\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        model = m.group(1);
                    } else if (models == null && t.startsWith("models")) {
                        // models = [ "a", "b", ... ]
                        java.util.regex.Matcher ms = java.util.regex.Pattern
                                .compile("models\\s*=\\s*\\[(.*)\\]", java.util.regex.Pattern.DOTALL).matcher(t);
                        if (ms.find()) {
                            models = new ArrayList<>();
                            java.util.regex.Matcher item = java.util.regex.Pattern
                                    .compile("\"([^\"]+)\"").matcher(ms.group(1));
                            while (item.find()) models.add(item.group(1));
                        }
                    }
                }
                if (name != null) list.add(new ProviderInfo(name, kind, baseUrl, apiKeyEnv, model, models));
            } catch (Exception ignored) {}
        }
        return list;
    }

    /** 改写 config.toml 的 default_model（provider name），返回是否成功 */
    private boolean setDefaultModel(File conf, String providerName) {
        try {
            if (conf == null || !conf.exists()) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8));
            boolean ok = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("default_model")) {
                    lines.set(i, "default_model = \"" + providerName + "\"");
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                // TOML 顶层键必须在所有 [section] 之前：找不到 default_model 时插到第一个 section 前，无 section 追加末尾
                int insert = lines.size();
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().startsWith("[")) { insert = i; break; }
                }
                lines.add(insert, "default_model = \"" + providerName + "\"");
            }
            java.nio.file.Files.write(conf.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "set default model failed", e);
            return false;
        }
    }

    /** 读取 .env 中指定变量的值（无则返回空串） */
    private String readApiKeyFromEnv(File env, String varName) {
        if (env == null || !env.exists() || varName == null || varName.isEmpty()) return "";
        try {
            for (String line : new String(java.nio.file.Files.readAllBytes(env.toPath()),
                    StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.startsWith(varName + "=")) {
                    return t.substring(varName.length() + 1).trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 加载 provider 的可选模型到模型 Spinner：静态 models 优先，否则联网拉取，失败兜底静态表。
     *  供切换 provider 与点击「刷新模型列表」复用。 */
    private void loadModelsForProvider(final ProviderInfo p, final File env,
                                       final ArrayAdapter<String> modelAdapter, final Spinner modelSpinner) {
        if (p == null) return;
        // 静态 models 已配：直接填充（无需联网）
        if (p.models != null && !p.models.isEmpty()) {
            final List<String> list = p.models;
            runOnUiThread(() -> {
                modelAdapter.clear();
                for (String s : list) modelAdapter.add(s);
                modelAdapter.notifyDataSetChanged();
                int idx = 0;
                if (p.model != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).equals(p.model)) { idx = i; break; }
                    }
                }
                modelSpinner.setSelection(idx);
            });
            return;
        }
        // 无静态列表：异步联网拉取；失败不兜底（保持空列表，不显示任何模型）
        new Thread(() -> {
            String key = readApiKeyFromEnv(env, p.apiKeyEnv);
            List<String> fetched = fetchModelsFromProvider(p.baseUrl, key, p.kind);
            final List<String> finalList = (fetched != null) ? fetched : new ArrayList<String>();
            runOnUiThread(() -> {
                modelAdapter.clear();
                for (String s : finalList) modelAdapter.add(s);
                modelAdapter.notifyDataSetChanged();
                int idx = 0;
                if (p.model != null) {
                    for (int i = 0; i < finalList.size(); i++) {
                        if (finalList.get(i).equals(p.model)) { idx = i; break; }
                    }
                }
                modelSpinner.setSelection(idx);
            });
        }, "rx-load-models").start();
    }

    /** 改写 config.toml 中指定 provider 块的 default 字段（默认模型名），返回是否成功 */
    private boolean setProviderDefaultModel(File conf, String providerName, String modelName) {
        try {
            if (conf == null || !conf.exists() || providerName == null || modelName == null) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8));
            boolean inTarget = false, found = false;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("[[providers]]")) {
                    inTarget = false;
                } else if (t.startsWith("[")) {
                    continue;
                } else if (inTarget && t.startsWith("name =")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(t);
                    if (m.find() && m.group(1).equals(providerName)) inTarget = true;
                }
                if (inTarget && t.startsWith("default =")) {
                    lines.set(i, "default     = \"" + modelName + "\"");
                    found = true;
                    break;
                }
            }
            if (!found) {
                // 未找到 default 字段：在目标 provider 块内追加（定位到该块 api_key_env 行后）
                int insertAfter = -1;
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).trim();
                    if (t.startsWith("[[providers]]")) {
                        insertAfter = -1;
                    } else if (t.startsWith("[")) {
                        continue;
                    } else if (t.startsWith("name =")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(t);
                        if (m.find() && m.group(1).equals(providerName)) insertAfter = i;
                    } else if (insertAfter >= 0) {
                        insertAfter = i;
                    }
                }
                if (insertAfter >= 0) {
                    lines.add(insertAfter + 1, "default     = \"" + modelName + "\"");
                    found = true;
                }
            }
            if (found) {
                java.nio.file.Files.write(conf.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "set provider default model failed", e);
            return false;
        }
    }

    /** 联网拉取可选模型列表：GET {base_url}/models（OpenAI 兼容），解析 data[].id；失败返回 null。
     *  若 base_url 命中已知厂商域名且拉取失败，返回内置静态模型表兜底（保证面板仍可选）。 */
    private List<String> fetchModelsFromProvider(String baseUrl, String apiKey, String kind) {
        List<String> models = new ArrayList<>();
        if (baseUrl == null || baseUrl.isEmpty()) return null;
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!url.endsWith("/models")) url += "/models";
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code == 200) {
                java.io.InputStream in = conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                String body = sb.toString();
                // data: [ {"id":"model-a","object":"model",...}, ... ]
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                while (m.find()) models.add(m.group(1));
                if (!models.isEmpty()) return models;
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch models failed: " + url, e);
        }
        // 不要兜底：无法联网/无 /models 时返回 null（UI 保持空列表，不显示任何模型）
        return null;
    }


    /** 写入/更新 .env 变量：保留已有其他变量，替换同名旧值，追加新变量 */
    private void upsertEnvVariable(File env, String varName, String value) {
        try {
            if (env == null) return;
            env.getParentFile().mkdirs();
            List<String> lines = env.exists()
                    ? new ArrayList<>(java.nio.file.Files.readAllLines(env.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith(varName + "=") ||
                        lines.get(i).trim().equals(varName)) {
                    lines.set(i, varName + "=" + value);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) lines.add(varName + "=" + value);
            java.nio.file.Files.write(env.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "upsert env variable failed", e);
        }
    }

    /** 追加一个 [[providers]] 块到 config.toml（末尾），返回是否成功 */
    private boolean addProviderToConfig(File conf, ProviderInfo p) {
        try {
            if (conf == null) return false;
            conf.getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("\n[[providers]]\n");
            sb.append("name        = \"").append(p.name).append("\"\n");
            sb.append("kind        = \"").append(p.kind == null || p.kind.isEmpty() ? "openai" : p.kind).append("\"\n");
            sb.append("base_url    = \"").append(p.baseUrl).append("\"\n");
            sb.append("model       = \"").append(p.model).append("\"\n");
            sb.append("api_key_env = \"").append(p.apiKeyEnv).append("\"\n");
            java.nio.file.Files.write(conf.toPath(),
                    ((conf.exists() ? "\n" : "") + sb.toString()).getBytes(StandardCharsets.UTF_8),
                    conf.exists()
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "add provider to config failed", e);
            return false;
        }
    }

    /** 从 config.toml 移除指定 provider 块（name 匹配），返回是否删除成功 */
    private boolean removeProviderFromConfig(File conf, ProviderInfo p) {
        try {
            if (conf == null || !conf.exists()) return false;
            if (p == null || p.name == null) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8));
            List<Integer> dropIdx = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (!t.startsWith("[[providers]]")) continue;
                // 块边界：下一个 [[providers]] 或任何 [section]（含 [x] 单括号开头）
                int j = i;
                boolean target = false, hasName = false;
                while (j < lines.size()) {
                    String tj = lines.get(j).trim();
                    if (j > i && (tj.startsWith("[[providers]]") || tj.startsWith("["))) {
                        break;   // 到达块尾
                    }
                    if (!hasName && tj.startsWith("name")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("^name\s*=\s*\"([^\"]+)\"").matcher(tj);
                        if (m.find() && m.group(1).equals(p.name)) target = true;
                        hasName = true;
                    }
                    j++;
                }
                if (target) {
                    for (int k = i; k < j; k++) dropIdx.add(k);
                }
                i = j - 1;
            }
            if (dropIdx.isEmpty()) return false;
            java.util.Set<Integer> dropSet = new java.util.HashSet<>(dropIdx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (!dropSet.contains(i)) {
                    sb.append(lines.get(i));
                    if (i < lines.size() - 1) sb.append("\n");
                }
            }
            java.nio.file.Files.write(conf.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "remove provider from config failed", e);
            return false;
        }
    }

    /** 从 .env 删除指定变量行，返回是否成功 */
    private boolean removeEnvVariable(File env, String varName) {
        try {
            if (env == null || !env.exists() || varName == null || varName.isEmpty()) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(env.toPath(), StandardCharsets.UTF_8));
            boolean removed = false;
            List<String> out = new ArrayList<>();
            for (String l : lines) {
                String t = l.trim();
                if (t.startsWith(varName + "=") || t.equals(varName)) { removed = true; continue; }
                out.add(l);
            }
            java.nio.file.Files.write(env.toPath(), String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return removed;
        } catch (Exception e) {
            Log.e(TAG, "remove env variable failed", e);
            return false;
        }
    }

    private void showApiKeyConfigDialog() {
        File rootfs = new File(getFilesDir(), "rootfs");
        File env = new File(new File(rootfs, "root/.reasonix"), ".env");
        File conf = new File(new File(rootfs, "root/.reasonix"), "config.toml");
        // Provider 列表：解析 config.toml [[providers]]（含 deepseek 及其他 AI）
        final List<ProviderInfo> providers = parseProviderInfos(conf);
        if (providers.isEmpty()) {
            // 解析不到（config.toml 未生成/被删）时用内置 DeepSeek 两档兜底
            providers.add(new ProviderInfo("deepseek-flash", "anthropic",
                    "https://api.deepseek.com/anthropic", "DEEPSEEK_API_KEY", "deepseek-v4-flash"));
            providers.add(new ProviderInfo("deepseek-pro", "anthropic",
                    "https://api.deepseek.com/anthropic", "DEEPSEEK_API_KEY", "deepseek-v4-pro"));
        }
        final String curModel = parseDefaultModel(conf);
        int curIdx = 0;
        List<String> display = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            ProviderInfo p = providers.get(i);
            display.add(p.name + "（" + (p.baseUrl != null ? p.baseUrl : "") + "）");
            if (p.name.equals(curModel)) curIdx = i;
        }
        // API Key 输入框：随选中的 provider 切换 hint（api_key_env 变量名）与已存值
        EditText input = createDarkEditText("粘贴 API Key",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip("选择 AI Provider 并填写其 API Key（支持 DeepSeek 及其他任意 OpenAI/Anthropic 兼容服务，"
                + "如 Kimi、GLM、MiniMax、OpenRouter 等。可在「+ 新增 Provider」里配置自定义端点）。切换后保存重启生效。"));
        // Provider 选择
        panel.addView(createDarkSectionTitle("选择 Provider"));
        final Spinner providerSpinner = new Spinner(this);
        // 深色适配：选中项白字、块状深灰底（与输入框一致），下拉项白字
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, display) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setBackgroundColor(0xFF1A1A1A);
                return tv;
            }
        };
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(spinnerAdapter);
        providerSpinner.setSelection(curIdx);
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        spinnerLp.topMargin = dp(6);
        panel.addView(providerSpinner, spinnerLp);
        // API Key：填写所选 provider 的密钥（api_key_env 变量），保存后写入 .env
        panel.addView(createDarkSectionTitle("API Key"));
        addV(panel, input, 6);
        // 默认模型：来自 provider 块 models 列表，或联网拉取（失败不兜底，保持空列表）
        panel.addView(createDarkSectionTitle("默认模型"));
        final Spinner modelSpinner = new Spinner(this);
        final ArrayAdapter<String> modelAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, new ArrayList<String>()) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setBackgroundColor(0xFF1A1A1A);
                return tv;
            }
        };
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        modelLp.topMargin = dp(6);
        panel.addView(modelSpinner, modelLp);
        // 刷新按钮：重新联网拉取该 provider 的模型列表
        Button refreshModelBtn = createDarkButton("刷新模型列表");
        refreshModelBtn.setOnClickListener(v -> {
            int sel = providerSpinner.getSelectedItemPosition();
            if (sel < 0 || sel >= providers.size()) return;
            ProviderInfo p = providers.get(sel);
            // 异步拉取，避免阻塞 UI
            new Thread(() -> {
                String key = readApiKeyFromEnv(env, p.apiKeyEnv);
                List<String> fetched = fetchModelsFromProvider(p.baseUrl, key, p.kind);
                // 不要兜底：拉取失败/无 /models 时保持空列表（UI 不显示任何模型）
                final List<String> finalList = (fetched != null) ? fetched : new ArrayList<String>();
                runOnUiThread(() -> {
                    modelAdapter.clear();
                    for (String s : finalList) modelAdapter.add(s);
                    modelAdapter.notifyDataSetChanged();
                    // 尝试选中当前默认模型
                    int idx = 0;
                    if (p.model != null) {
                        for (int i = 0; i < finalList.size(); i++) {
                            if (finalList.get(i).equals(p.model)) { idx = i; break; }
                        }
                    }
                    modelSpinner.setSelection(idx);
                });
            }, "rx-fetch-models").start();
        });
        addV(panel, refreshModelBtn, 4);
        // 切换 Provider：更新 hint（api_key_env 变量名）与已存值回显
        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int pos, long id) {
                if (pos >= 0 && pos < providers.size()) {
                    ProviderInfo p = providers.get(pos);
                    String envName = (p.apiKeyEnv != null && !p.apiKeyEnv.isEmpty()) ? p.apiKeyEnv : "API_KEY";
                    input.setHint("粘贴 " + envName + "（" + p.name + "）");
                    String saved = readApiKeyFromEnv(env, envName);
                    input.setText(saved);
                    input.setSelection(saved.length());
                    // 加载该 provider 的模型列表（静态 models 优先，否则联网拉取，失败兜底）
                    loadModelsForProvider(p, env, modelAdapter, modelSpinner);
                }
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        Button saveBtn = createDarkButton("保存");
        saveBtn.setOnClickListener(v -> {
            int sel = providerSpinner.getSelectedItemPosition();
            if (sel < 0 || sel >= providers.size()) return;
            final ProviderInfo p = providers.get(sel);
            String key = input.getText().toString().trim();
            if (key.isEmpty()) {
                pushOutput("\r\n[请填写 " + (p.apiKeyEnv != null ? p.apiKeyEnv : "API") + " API Key]\r\n");
                return;
            }
            try {
                // 写入 .env 对应变量（保留其他 provider 的 key）
                upsertEnvVariable(env, p.apiKeyEnv, key);
                // 同时写两层默认：顶层 default_model=provider 名 + provider 块 default=用户选中的模型
                boolean modelOk = setDefaultModel(conf, p.name);
                int mSel = modelSpinner.getSelectedItemPosition();
                String chosenModel = mSel >= 0 ? (String) modelSpinner.getItemAtPosition(mSel) : null;
                if (chosenModel != null && !chosenModel.isEmpty()) {
                    modelOk = setProviderDefaultModel(conf, p.name, chosenModel) || modelOk;
                }
                Log.d(TAG, "API key + provider updated: " + p.name + " env=" + p.apiKeyEnv + " modelOk=" + modelOk);
                hidePanel();
                pushOutput("\r\n[" + p.name + " API Key 已更新" + (modelOk ? "，已切换默认模型 " + (chosenModel != null ? chosenModel : p.name) : "，但默认模型写入失败（config.toml 未生成？）")
                        + "，正在重启环境...]\r\n");
                restartEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "save api key failed", e);
            }
        });
        addV(panel, saveBtn, 10);
        // 新增 Provider 独立按钮：点击直接进入子表单（不再混入 Spinner 选项，避免歧义）
        Button addBtn = createDarkButton("+ 新增 Provider…");
        addBtn.setOnClickListener(v -> showAddProviderForm(providers, env, conf));
        addV(panel, addBtn, 6);
        // 删除 Provider 独立按钮：移除当前选中的 provider（config.toml 对应块 + .env 变量）
        Button delBtn = createDarkButton("删除当前 Provider…");
        delBtn.setTextColor(0xFFFF6B6B);  // 红色警示
        delBtn.setOnClickListener(v -> {
            int sel = providerSpinner.getSelectedItemPosition();
            if (sel < 0 || sel >= providers.size()) return;
            final ProviderInfo p = providers.get(sel);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("删除 Provider")
                    .setMessage("确定删除「" + p.name + "」（" + p.baseUrl + "）？\n"
                            + "将从 config.toml 移除该 provider 块并删除 .env 中对应 API Key 变量。"
                            + (curModel.equals(p.name) ? "\n\n注意：这是当前默认 provider，删除后需重新选择。"
                            : ""))
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                        if (removeProviderFromConfig(conf, p) && removeEnvVariable(env, p.apiKeyEnv)) {
                            providers.remove(sel);
                            pushOutput("\r\n[Provider 已删除：" + p.name + "]\r\n");
                            if (curModel.equals(p.name)) {
                                // 删除的是当前默认：重置 default_model 为剩余的第一个（无则留空）
                                boolean ok = false;
                                if (!providers.isEmpty()) ok = setDefaultModel(conf, providers.get(0).name);
                                pushOutput(ok ? "\r\n[默认 Provider 已切换为 " + providers.get(0).name + "]\r\n"
                                        : "\r\n[config.toml 无剩余 provider（需重启后重新配置）]\r\n");
                            }
                            showApiKeyConfigDialog();  // 重开面板刷新列表
                        } else {
                            pushOutput("\r\n[Provider 删除失败（config.toml 或 .env 写入异常）]\r\n");
                        }
                    })
                    .show();
        });
        addV(panel, delBtn, 6);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("API Key", panel, null);
    }

    /** 「+ 新增 Provider」子表单：填写 name/kind/base_url/model/api_key_env 并保存到 config.toml */
    private void showAddProviderForm(final List<ProviderInfo> providers, final File env, final File conf) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip("新增 AI Provider（OpenAI 或 Anthropic 兼容端点）。"
                + "填写后保存到 config.toml，随后在上一页选择该 Provider 填入 API Key。"));
        panel.addView(createDarkSectionTitle("Provider 名称"));
        final EditText nameInput = createDarkEditText("如 my-ai / kimi / glm（default_model 引用名）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, nameInput, 4);
        panel.addView(createDarkSectionTitle("kind（协议）"));
        final android.widget.EditText kindInput = createDarkEditText("anthropic 或 openai（默认 openai）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, kindInput, 4);
        panel.addView(createDarkSectionTitle("base_url（API 端点）"));
        final EditText urlInput = createDarkEditText("如 https://api.moonshot.cn/v1（OpenAI 兼容）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, urlInput, 4);
        panel.addView(createDarkSectionTitle("默认模型"));
        final Spinner modelSpinner = new Spinner(this);
        final ArrayAdapter<String> modelAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, new ArrayList<String>()) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setBackgroundColor(0xFF1A1A1A);
                return tv;
            }
        };
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        modelLp.topMargin = dp(6);
        panel.addView(modelSpinner, modelLp);
        panel.addView(createDarkSectionTitle("api_key_env（.env 变量名）"));
        final EditText envInput = createDarkEditText("如 KIMI_API_KEY（大写字母数字下划线）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, envInput, 4);
        // 「拉取模型」：读 base_url + .env 中该 api_key_env 的 key（可为空→静态表兜底）联网列出可选模型
        Button pullBtn = createDarkButton("拉取模型");
        pullBtn.setOnClickListener(v -> {
            String baseUrl = urlInput.getText().toString().trim();
            String envVar = envInput.getText().toString().trim();
            String kind = kindInput.getText().toString().trim();
            if (baseUrl.isEmpty() || envVar.isEmpty()) {
                pushOutput("\r\n[请先填写 base_url 与 api_key_env，再拉取模型]\r\n");
                return;
            }
            new Thread(() -> {
                String key = readApiKeyFromEnv(env, envVar);
                List<String> fetched = fetchModelsFromProvider(baseUrl, key, kind);
                // 不要兜底：拉取失败/无 /models 时保持空列表（不显示任何模型）
                final List<String> finalList = (fetched != null) ? fetched : new ArrayList<String>();
                runOnUiThread(() -> {
                    modelAdapter.clear();
                    for (String s : finalList) modelAdapter.add(s);
                    modelAdapter.notifyDataSetChanged();
                    modelSpinner.setSelection(0);
                });
            }, "rx-pull-models").start();
        });
        addV(panel, pullBtn, 4);
        Button okBtn = createDarkButton("保存 Provider");
        okBtn.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String baseUrl = urlInput.getText().toString().trim();
            String envVar = envInput.getText().toString().trim();
            String kind = kindInput.getText().toString().trim();
            // 默认模型：优先取 Spinner 选中项；未拉取时以 provider 名为默认（reasonix 以 provider 名解析）
            String model = modelSpinner.getSelectedItem() != null
                    ? modelSpinner.getSelectedItem().toString().trim() : "";
            if (name.isEmpty() || baseUrl.isEmpty() || envVar.isEmpty()) {
                pushOutput("\r\n[请填写 Provider 名称、base_url 与 api_key_env]\r\n");
                return;
            }
            if (model.isEmpty()) model = name;
            ProviderInfo np = new ProviderInfo(name, kind, baseUrl, envVar, model);
            // 把拉取到的模型列表一并写入 provider 块（主面板可直接选中，无需再联网）
            np.models = new ArrayList<>();
            for (int i = 0; i < modelAdapter.getCount(); i++) {
                String s = modelAdapter.getItem(i);
                if (s != null && !s.isEmpty()) np.models.add(s);
            }
            if (addProviderToConfig(conf, np)) {
                upsertEnvVariable(env, envVar, "");   // 预建空变量占位（用户回上一页填 key）
                providers.add(np);
                pushOutput("\r\n[Provider 已添加：" + name + "（" + baseUrl + "），回到上一页选择并填写 API Key]\r\n");
                // 重开面板（选中新 provider）
                showApiKeyConfigDialog();
            } else {
                pushOutput("\r\n[Provider 写入 config.toml 失败]\r\n");
            }
        });
        addV(panel, okBtn, 10);
        showPanel("新增 Provider", panel, null);
    }

    /** 从 Go 二进制提取模块版本（buildinfo：`mod\t...\tvX.Y.Z`，位于文件尾部） */
    private String extractReasonixVersion(File bin) {
        if (bin == null || !bin.exists()) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(bin, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - (2L * 1024 * 1024));   // Go buildinfo 距文件尾可达 1.5MB+，读 2MB
            raf.seek(start);
            byte[] tail = new byte[(int) (len - start)];
            raf.readFully(tail);
            String s = new String(tail, StandardCharsets.ISO_8859_1);
            int idx = s.indexOf("mod\t");
            if (idx >= 0) {
                String mod = s.substring(idx, Math.min(s.length(), idx + 150));
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("mod\\t\\S+\\tv([\\d.]+[\\w.-]*)").matcher(mod);
                if (m.find()) return "v" + m.group(1);
            }
        } catch (Exception e) {
            Log.w(TAG, "extract version failed", e);
        }
        return null;
    }

    /** DS2API 网关面板：应用内嵌 WebView 打开 http://127.0.0.1:5001/admin/ 管理页
     *  （无需额外安装 DS2API App；若服务未启动则显示提示）。 */
    private void showDs2ApiDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, dp(8), pad, dp(12));
        panel.addView(createDarkTip("DS2API 网关：本地 OpenAI/Claude 兼容 API 中转服务（已内置）。\n"
                + "应用启动时会在 Linux 环境中自动后台运行 DS2API 服务（127.0.0.1:5001），\n"
                + "下方为管理页面（初始管理密钥 rsxm-ds2api-admin，首次保存配置后持久化到 /root/ds2api/config.json）。\n"
                + "若检测到旧版 DS2API App 已占用 5001 端口，内置服务不重复启动，面板仍打开旧服务。"));

        // 服务控制：启动 / 停止（经 guest .adb-cmd 桥执行，操作后自动刷新管理页）
        // WebView 声明在下方，用 holder 数组跨作用域引用（final 局部变量限制）
        final WebView[] ds2WebBox = new WebView[1];
        final TextView ds2Status = new TextView(this);
        ds2Status.setTextSize(13);
        ds2Status.setPadding(dp(2), dp(4), dp(2), dp(4));
        ds2Status.setText("（检测中...）");
        addV(panel, ds2Status, 8);
        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        Button startBtn = createDarkButton("启动 DS2API");
        Button stopBtn = createDarkButton("停止 DS2API");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.rightMargin = dp(8);
        ctrlRow.addView(startBtn, btnLp);
        ctrlRow.addView(stopBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addV(panel, ctrlRow, 6);

        // 检测服务状态（面板打开时与每次操作后刷新）
        Runnable refreshDs2Status = () -> new Thread(() -> {
            String st = executeInGuest(
                    "pgrep -x ds2api >/dev/null 2>&1 && echo RUNNING || echo STOPPED", 6);
            runOnUiThread(() -> {
                boolean running = st.contains("RUNNING");
                ds2Status.setText("服务状态：" + (running ? "运行中" : "已停止"));
                ds2Status.setTextColor(running ? 0xFF4CAF50 : 0xFFFF6E6E);
            });
        }, "ds2-status").start();
        refreshDs2Status.run();

        // 启动：guest 内后台拉起内置服务（与 entry.sh 启动段同参数）
        startBtn.setOnClickListener(v -> {
            startBtn.setEnabled(false);
            ds2Status.setText("正在启动 DS2API...");
            ds2Status.setTextColor(0xFFFFD54F);
            new Thread(() -> {
                String out = executeInGuest(
                        // 先强清残留（含优雅关闭中未退出的进程），确保干净启动
                        "mkdir -p /root/ds2api && pkill -9 -x ds2api 2>/dev/null; sleep 0.5; "
                        + "if pgrep -x ds2api >/dev/null 2>&1; then echo ALREADY_RUNNING; else "
                        + "cd /root/ds2api && export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
                        + "TERM=xterm-256color LANG=C.UTF-8 TMPDIR=/tmp TMP=/tmp "
                        + "NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost "
                        + "PORT=5001 DS2API_ADMIN_KEY=rsxm-ds2api-admin DS2API_STATIC_ADMIN_DIR=/usr/local/ds2api/static/admin DS2API_CONFIG_PATH=/root/ds2api/config.json && "
                        + "nohup /usr/local/ds2api/ds2api >/root/ds2api/ds2api.log 2>&1 & echo STARTED; fi", 8);
                runOnUiThread(() -> {
                    boolean ok = out.contains("STARTED") || out.contains("ALREADY_RUNNING");
                    ds2Status.setText(ok ? "已启动（管理台 http://127.0.0.1:5001/admin）"
                            : "启动失败：" + out);
                    ds2Status.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF6E6E);
                    startBtn.setEnabled(true);
                    if (ok) ds2WebBox[0].reload();
                });
            }, "ds2-start").start();
        });

        // 停止：杀掉内置服务进程（环境重启后 entry.sh 会自动再拉起）
        stopBtn.setOnClickListener(v -> {
            stopBtn.setEnabled(false);
            ds2Status.setText("正在停止 DS2API...");
            ds2Status.setTextColor(0xFFFFD54F);
            new Thread(() -> {
                // SIGTERM 优雅关闭；ds2api 主进程对 SIGTERM 做优雅退出（srv.Shutdown 最多 10s+等活跃连接），
                // 因此 pkill 发信号后立即返回不代表已退出——先等 1s，仍存活则 SIGKILL 强杀兜底，
                // 最后用 pgrep 确认真正退出才算 STOPPED（否则面板显示"已停止"但进程还在优雅关闭中）。
                String out = executeInGuest(
                        "pkill -x ds2api 2>/dev/null; sleep 1; "
                        + "if pgrep -x ds2api >/dev/null 2>&1; then pkill -9 -x ds2api 2>/dev/null; sleep 0.5; fi; "
                        + "if pgrep -x ds2api >/dev/null 2>&1; then echo STILL_RUNNING; else echo STOPPED; fi", 15);
                runOnUiThread(() -> {
                    boolean stopped = out.contains("STOPPED");
                    ds2Status.setText(stopped ? "已停止"
                            : (out.contains("STILL_RUNNING") ? "停止失败：进程仍在运行（SIGKILL 后仍存活）"
                            : "停止失败：" + out));
                    ds2Status.setTextColor(0xFFFF6E6E);
                    stopBtn.setEnabled(true);
                    if (stopped) ds2WebBox[0].reload();
                });
            }, "ds2-stop").start();
        });

        // 内嵌 WebView 加载 DS2API 管理页
        WebView ds2Web = new WebView(this);
        ds2WebBox[0] = ds2Web;   // 供上方按钮线程跨作用域引用（reload）
        WebSettings ws = ds2Web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ds2Web.setBackgroundColor(0xFF000000);
        ds2Web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // 服务未启动时给出友好提示（不显示 error 页）
                view.loadDataWithBaseURL(null,
                        "<html><body style='background:#111;color:#aaa;font-family:sans-serif;padding:20px'>"
                                + "<h3 style='color:#f77'>DS2API 服务未运行</h3>"
                                + "<p>请先安装并启动 DS2API App（127.0.0.1:5001）。</p></body></html>",
                        "text/html", "utf-8", null);
            }
        });
        ds2Web.loadUrl("http://127.0.0.1:5001/admin/");
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420));
        webLp.topMargin = dp(8);
        panel.addView(ds2Web, webLp);

        // 打开系统浏览器按钮（备用）
        Button openBtn = createDarkButton("在系统浏览器打开");
        openBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:5001/admin/")));
            } catch (Exception e) {
                Log.w(TAG, "open ds2api browser failed", e);
            }
        });
        addV(panel, openBtn, 12);

        showPanel("DS2API 网关", panel, null);
    }

    /** 发送按键序列到 reasonix 终端（原样写入 stdin，不追加换行）：
     *  复用 write 通道（sProcIn），点击快捷键条目即把对应按键注入当前会话。 */
    private void sendKeySeq(String seq) {
        try {
            if (sProcIn != null) {
                sProcIn.write(seq.getBytes(StandardCharsets.UTF_8));
                sProcIn.flush();
                Log.d(TAG, "key seq sent: " + seq.replace("\u001b", "ESC").replace("\r", "CR"));
            } else {
                pushOutput("\r\n[终端未就绪]\r\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "send key seq failed", e);
        }
    }

    /** 快捷键面板：列出 reasonix 常用按键，点击条目即发送对应按键序列到终端
     *  （对照官方文档 docs/CLI-REFERENCE.md 的 Keybindings 章节）。 */
    private void showKeysDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, dp(8), pad, dp(12));

        // 分组：编辑 / 导航与历史 / 会话控制 / 编辑门（code mode）
        final String[][] editKeys = {
                {"Enter", "提交提示词", "\r"},
                {"Ctrl+A", "跳到行首", "\u0001"},
                {"Ctrl+E", "跳到行尾", "\u0005"},
                {"Ctrl+W", "删除光标前一个词", "\u0017"},
                {"Ctrl+U", "清空整个输入缓冲", "\u0015"},
        };
        final String[][] navKeys = {
                {"↑", "上翻对话历史（与滑动一致）", "\u001b[A"},
                {"↓", "下翻对话历史（与滑动一致）", "\u001b[B"},
                {"PgUp", "整页上翻", "\u001b[5~"},
                {"PgDn", "整页下翻", "\u001b[6~"},
                {"End", "跳到最新一行", "\u001b[F"},
        };
        final String[][] sessionKeys = {
                {"Tab", "完成 @ 提及 / 补全斜杠命令", "\t"},
                {"Shift+Tab", "编辑门：切换 review ↔ AUTO", "\u001b[Z"},
                {"Esc", "关闭选择器 · 中止当前模型回合", "\u001b"},
                {"Ctrl+C", "中止当前模型回合", "\u0003"},
        };
        final String[][] gateKeys = {
                {"y", "接受待处理编辑（review 弹窗）", "y"},
                {"n", "丢弃待处理编辑（review 弹窗）", "n"},
                {"Shift+Tab", "切换 review ↔ AUTO（跨会话记忆）", "\u001b[Z"},
                {"u", "撤销最后一次自动应用批次（5s 横幅内）", "u"},
        };

        // 通用键盘行：按键 + 说明，点击发送
        Runnable addGroup = null;
        java.util.function.BiConsumer<String, String[][]> addGroupFn = (title, keys) -> {
            panel.addView(createDarkSectionTitle(title));
            for (String[] k : keys) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(2), dp(5), dp(2), dp(5));
                TextView keyTv = new TextView(this);
                keyTv.setText(k[0]);
                keyTv.setTextColor(0xFF58A6FF);
                keyTv.setTextSize(14);
                keyTv.setTypeface(android.graphics.Typeface.MONOSPACE);
                keyTv.setBackgroundColor(0xFF1E1E1E);
                keyTv.setPadding(dp(8), dp(3), dp(8), dp(3));
                LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                keyLp.rightMargin = dp(10);
                row.addView(keyTv, keyLp);
                TextView descTv = new TextView(this);
                descTv.setText(k[1]);
                descTv.setTextColor(0xFFCCCCCC);
                descTv.setTextSize(13);
                descTv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(descTv);
                row.setOnClickListener(v -> {
                    sendKeySeq(k[2]);
                    // 震动反馈：瞬时短震（硬件支持时），让点击可感知
                    try {
                        android.os.Vibrator vb = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                        if (vb != null && vb.hasVibrator()) vb.vibrate(25);
                    } catch (Exception ignored) {}
                });
                // 长按连发：按住持续发送（翻页/上下翻连续滚动用）
                row.setOnLongClickListener(v -> {
                    final Runnable[] r = new Runnable[1];
                    r[0] = new Runnable() {
                        @Override
                        public void run() {
                            sendKeySeq(k[2]);
                            ui.postDelayed(r[0], 180);
                        }
                    };
                    r[0].run();
                    row.setOnTouchListener((view, ev) -> {
                        if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                                || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                            ui.removeCallbacks(r[0]);
                            row.setOnTouchListener(null);
                        }
                        return false;
                    });
                    return true;
                });
                panel.addView(row);
            }
        };
        addGroupFn.accept("编辑", editKeys);
        addGroupFn.accept("导航与历史", navKeys);
        addGroupFn.accept("会话控制", sessionKeys);
        addGroupFn.accept("编辑门（code mode）", gateKeys);
        addV(panel, createDarkTip("点击任意按键即发送到 reasonix 终端（面板保持打开，可连续点按；长按可连发，翻页/上下翻建议长按）。"
                + "发送的是按键序列而非文本命令，reasonix 会话与 shell 均可响应。"
                + "终端内手动滑动也已改为 ↑/↓ 滚动对话历史（与滚轮语义一致）。"), 10);

        showPanel("快捷键", panel, null);
    }

    /** 更新 reasonix：从手机选择新版文件，或恢复内置版本 */
    private void showUpdateResonixDialog() {
        EditText urlInput = createDarkEditText("reasonix 更新包链接 (.tgz)", InputType.TYPE_CLASS_TEXT);
        urlInput.setText(REASONIX_DEFAULT_URL);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, dp(8), pad, dp(12));
        // 当前版本：优先显示记录的 npm 版本（.npm-version），否则从 Go buildinfo 提取
        String npmVer = null;
        File vf = new File(new File(new File(getFilesDir(), "rootfs/root"), ".reasonix"), ".npm-version");
        if (vf.exists()) {
            try {
                npmVer = new String(java.nio.file.Files.readAllBytes(vf.toPath()), StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {}
        }
        String ver = (npmVer != null && !npmVer.isEmpty()) ? "v" + npmVer
                : extractReasonixVersion(new File(new File(getFilesDir(), "rootfs/usr/local/bin"), "reasonix"));
        TextView verView = new TextView(this);
        verView.setText("当前版本：" + (ver != null ? ver : "未知（内置 1.31.4）"));
        verView.setTextColor(0xFF7FDB8A);
        verView.setTextSize(14);
        verView.setTypeface(null, android.graphics.Typeface.BOLD);
        addV(panel, verView, 0);
        // 异步查询最新版本并自动填入最新下载链接
        final String curVer = ver;
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL("https://registry.npmmirror.com/@reasonix/cli-linux-arm64/latest");
                byte[] buf = new byte[8192];
                int n;
                StringBuilder sb = new StringBuilder();
                try (java.io.InputStream in = u.openStream()) {
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                java.util.regex.Matcher mv = java.util.regex.Pattern
                        .compile("\"version\"\\s*:\\s*\"([\\d.]+)\"").matcher(sb.toString());
                if (mv.find()) {
                    final String latest = mv.group(1);
                    runOnUiThread(() -> {
                        verView.setText("当前版本：" + (curVer != null ? curVer : "未知")
                                + "　最新版本：v" + latest);
                        urlInput.setText("https://registry.npmmirror.com/@reasonix/cli-linux-arm64/-/cli-linux-arm64-"
                                + latest + ".tgz");
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "query latest version failed", e);
            }
        }, "rx-ver-check").start();
        TextView tip = createDarkTip("官方源 @reasonix/cli-linux-arm64（npm 平台包）。\n"
                + "可改链接更新、选文件更新或恢复内置版本。");
        addV(panel, tip, 8);
        panel.addView(createDarkSectionTitle("更新方式"));
        addV(panel, urlInput, 6);
        Button netBtn = createDarkButton("网络更新");
        netBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) {
                hidePanel();
                updateFromNetwork(url);
            }
        });
        addV(panel, netBtn, 8);
        Button fileBtn = createDarkButton("选择文件");
        fileBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_UPDATE_RESONIX);
            } catch (Exception e) {
                Log.e(TAG, "open document failed", e);
            }
        });
        addV(panel, fileBtn, 8);
        Button restoreBtn = createDarkButton("恢复内置");
        restoreBtn.setOnClickListener(v -> {
            hidePanel();
            restoreBundledResonix();
        });
        addV(panel, restoreBtn, 8);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("Reasonix 更新", panel, null);
    }

    /** 从网络下载 reasonix 更新包（tar.gz），解压提取二进制并覆盖 guest 内版本 */
    private void updateFromNetwork(String url) {
        new Thread(() -> {
            try {
                pushOutput("\r\n[正在下载 reasonix 更新包...]\r\n");
                File tmp = new File(getCacheDir(), "reasonix-update.tgz");
                long total = 0;
                try (InputStream in = new URL(url).openStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                }
                Log.d(TAG, "downloaded " + total + " bytes");
                pushOutput("\r\n[下载完成 (" + (total / 1024 / 1024) + " MB)，正在解压...]\r\n");
                File rootfs = new File(getFilesDir(), "rootfs");
                File rx = new File(new File(rootfs, "usr/local/bin"), "reasonix");
                rx.getParentFile().mkdirs();
                try (GZIPInputStream gz = new GZIPInputStream(new FileInputStream(tmp));
                     FileOutputStream out = new FileOutputStream(rx)) {
                    extractTarMember(gz, out, "package/bin/reasonix");
                }
                rx.setExecutable(true, false);
                tmp.delete();
                // 记录 npm 版本号（buildinfo 是 git pseudo-version，显示友好版本用）
                try {
                    java.util.regex.Matcher mv = java.util.regex.Pattern
                            .compile("cli-linux-arm64-(\\d+\\.\\d+\\.\\d+)").matcher(url);
                    if (mv.find()) {
                        writeNpmVersion(mv.group(1));
                    }
                } catch (Exception ignored) {}
                pushOutput("\r\n[reasonix 已更新（" + rx.length() + " 字节），正在重启环境...]\r\n");
                restartEnvironment();
            } catch (Exception e) {
                pushOutput("\r\n[更新失败] " + e + "\r\n");
                Log.e(TAG, "network update failed", e);
            }
        }, "rx-update").start();
    }

    /** 从 tar 流中提取指定成员（tar 512 字节块格式；gzip 已解压） */
    private void extractTarMember(InputStream in, OutputStream out, String targetName) throws IOException {
        byte[] header = new byte[512];
        boolean found = false;
        while (true) {
            int read = readFully(in, header);
            if (read < 512) break;              // 结束
            if (allZero(header)) break;         // 两个空块结束
            String name = new String(header, 0, 100, StandardCharsets.UTF_8).replace("\0", "").trim();
            long size = 0;
            String sizeStr = new String(header, 124, 12, StandardCharsets.US_ASCII).trim();
            try {
                size = Long.parseLong(sizeStr, 8);
            } catch (Exception ignored) {}
            if (name.equals(targetName)) {
                byte[] data = new byte[(int) size];
                readFully(in, data);
                out.write(data);
                found = true;
                break;
            } else {
                // 跳过数据块（512 对齐）
                long skip = (size + 511) / 512 * 512;
                skipFully(in, skip);
            }
        }
        if (!found) throw new IOException("tar 内未找到 " + targetName);
    }

    private int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int c = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (c < 0) break;
            remaining -= c;
        }
    }

    private boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    /** 从 APK assets 恢复内置 reasonix */
    private void restoreBundledResonix() {
        try {
            File rootfs = new File(getFilesDir(), "rootfs");
            File rx = new File(new File(rootfs, "usr/local/bin"), "reasonix");
            rx.getParentFile().mkdirs();
            extractAsset("usr/bin/reasonix", rx);
            rx.setExecutable(true, false);
            // 重置版本标记为内置版（避免 UI 仍显示之前网络更新过的旧版本号）
            writeNpmVersion("1.31.4");
            Log.d(TAG, "reasonix restored from bundle");
            pushOutput("\r\n[已恢复内置 reasonix，正在重启环境...]\r\n");
            restartEnvironment();
        } catch (Exception e) {
            Log.e(TAG, "restore reasonix failed", e);
        }
    }

    /** 写入 reasonix npm 版本标记（rootfs/.reasonix/.npm-version，UI 显示友好版本用） */
    private void writeNpmVersion(String ver) {
        try {
            File vf = new File(new File(new File(getFilesDir(), "rootfs/root"), ".reasonix"), ".npm-version");
            vf.getParentFile().mkdirs();
            java.nio.file.Files.write(vf.toPath(), ver.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "write npm-version failed", e);
        }
    }

    /** 从手机存储复制新版 reasonix 到 guest */
    private void applyReasonixUpdate(Uri uri) {
        try {
            File rootfs = new File(getFilesDir(), "rootfs");
            File rx = new File(new File(rootfs, "usr/local/bin"), "reasonix");
            rx.getParentFile().mkdirs();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    pushOutput("\r\n[更新失败: 无法读取所选文件]\r\n");
                    return;
                }
                try (OutputStream out = new FileOutputStream(rx)) {
                    byte[] buf = new byte[65536];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    Log.d(TAG, "reasonix updated, size=" + total);
                }
            }
            rx.setExecutable(true, false);
            pushOutput("\r\n[reasonix 已更新（" + rx.length() + " 字节），正在重启环境...]\r\n");
            restartEnvironment();
        } catch (Exception e) {
            Log.e(TAG, "apply reasonix update failed", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UPDATE_RESONIX && resultCode == RESULT_OK && data != null && data.getData() != null) {
            applyReasonixUpdate(data.getData());
        } else if (requestCode == REQ_SKILL_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importSkillFromUri(data.getData());
        } else if (requestCode == REQ_CREATE_ENV_TEMPLATE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                if (os != null) {
                    os.write(ENV_TEMPLATE_CONTENT.getBytes(StandardCharsets.UTF_8));
                    pushOutput("\r\n[模板已保存（.rsxmenv），编辑后可在开发环境面板「导入模板」导入]\r\n");
                } else {
                    pushOutput("\r\n[保存模板失败: 无法写入所选位置]\r\n");
                }
            } catch (Exception e) {
                Log.e(TAG, "save env template failed", e);
                pushOutput("\r\n[保存模板失败: " + e.getMessage() + "]\r\n");
            }
        } else if (requestCode == REQ_IMPORT_ENV_TEMPLATE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importEnvTemplate(data.getData());
        }
    }

    /** 请求运行时存储权限（媒体文件；Android 13+ 用 READ_MEDIA_*） */
    private void requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO",
                        "android.permission.READ_MEDIA_AUDIO"}, 100);
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, 100);
            }
        } catch (Exception e) {
            Log.w(TAG, "permission request failed", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sWebActive = false;   // 退后台：WebView JS 暂停，终端输出改缓存（避免堆积阻塞 reasonix）
    }

    protected void onResume() {
        super.onResume();
        flushPendingOutput();   // 前台恢复：冲刷后台期间缓存的终端输出
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        // 后台运行模式：冻结一切自动前台操作（弹窗/重启），避免抢占其他应用前台
        // （adb-open-moments-summary.md 坑 4：守护界面抢回前台导致 UI 自动化窗口期过短）
        if (prefs.getBoolean("background_mode", false)) {
            return;
        }

        // API 30+ 的"所有文件访问"（MANAGE_EXTERNAL_STORAGE）：让 reasonix 能读写
        // /sdcard 任意位置（文档、下载、非媒体等）。首次启动引导授权；检测到授权状态
        // 从"未授权"变为"已授权"时自动重启 Linux 环境使 FUSE 权限生效。
        if (Build.VERSION.SDK_INT >= 30) {
            boolean managed = Environment.isExternalStorageManager();
            boolean wasManaged = prefs.getBoolean("storage_managed", false);
            prefs.edit().putBoolean("storage_managed", managed).apply();
            if (managed && !wasManaged) {
                pushOutput("\r\n[存储权限已生效，正在重启 Linux 环境...]\r\n");
                restartEnvironment();
            } else if (!managed && !prefs.getBoolean("storage_guided", false)) {
                prefs.edit().putBoolean("storage_guided", true).apply();
                // 全屏面板引导（取代系统弹窗，避免遮挡控件）
                LinearLayout panel = new LinearLayout(this);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setPadding(dp(16), dp(8), dp(16), dp(12));
                panel.addView(createDarkTip("reasonix 需要\"所有文件访问\"权限才能读写手机存储的任意位置"
                        + "（文档、下载、非媒体文件等）。\n\n未授权时仅可访问公共媒体目录。"));
                Button grantBtn = createDarkButton("去授权");
                grantBtn.setOnClickListener(v -> {
                    hidePanel();
                    openManageAllFilesSettings();
                });
                addV(panel, grantBtn, 12);
                Button laterBtn = createDarkButton("暂不");
                laterBtn.setOnClickListener(v -> hidePanel());
                addV(panel, laterBtn, 8);
                showPanel("存储权限", panel, null);
            }
        }
    }

    /** 打开"所有文件访问"系统设置页 */
    private void openManageAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                Log.w(TAG, "cannot open manage-all-files settings", e2);
            }
        }
    }

    /** 杀掉 proot 进程并重启整个 Linux 环境（MANAGE_EXTERNAL_STORAGE 授权后调用，使 FUSE 权限生效） */
    private synchronized void restartEnvironment() {
        if (!environmentStarted) return;   // 环境尚未启动，无需重启（正常流程会启动）
        new Thread(() -> {
            try {
                killProotTree();
                startEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "restart failed", e);
            }
        }, "env-restart").start();
    }

    /** 杀掉 proot 及其残留的 guest 进程（pty-bridge/reasonix 是 proot 子进程，
     *  proot 被杀后若不清除会残留成孤儿，导致多套环境并存） */
    private void killProotTree() {
        try {
            if (sProotProcess != null) {
                sProotProcess.destroy();
                sProotProcess = null;
                sProcIn = null;
            }
            // 1) 先杀 app 同 uid 的进程（proot 模式；按进程名精确匹配，不会误伤其他应用）
            Process p = new ProcessBuilder("sh", "-c",
                    "for pid in $(ps -A -o PID,ARGS 2>/dev/null | grep -E 'proot.so|pty-bridge|reasonix|entry.sh' | awk '{print $1}'); " +
                    "do kill -9 $pid 2>/dev/null; done")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            // 2) chroot 模式进程是 su(root) 启动的（app 无权杀 root 进程，force-stop 也不杀），
            //    需用 su pkill 清理，否则旧环境残留导致切换目录/重启不生效
            try {
                String su = findSuPath();
                if (su != null) {
                    Process k = new ProcessBuilder(su, "-c",
                            "pkill -9 -f 'reasonix.bin' 2>/dev/null; "
                                    + "pkill -9 -f 'pty-bridge' 2>/dev/null; "
                                    + "pkill -9 -f 'entry.sh' 2>/dev/null; "
                                    + "pkill -9 -f 'chroot /data/user' 2>/dev/null; "
                                    + "pkill -9 -f 'proot.so' 2>/dev/null; true")
                            .redirectErrorStream(true).start();
                    if (!k.waitFor(3, TimeUnit.SECONDS)) k.destroy();
                }
            } catch (Exception ignored) {
            }
            // 3) chroot 模式：清理 bind mount（su 进程被强杀时脚本内 umount 可能未执行，
            //    残留挂载会占用 rootfs/dev 等目录，影响下次启动）
            try {
                String su = findSuPath();
                if (su != null) {
                    File rootfs = new File(new File(getFilesDir(), "rootfs"), "");
                    String r = rootfs.getAbsolutePath();
                    Process u = new ProcessBuilder(su, "-c",
                            "umount " + r + "/dev/pts 2>/dev/null; umount " + r + "/dev 2>/dev/null; "
                                    + "umount " + r + "/proc 2>/dev/null; umount " + r + "/sys 2>/dev/null; "
                                    + "umount " + r + "/sdcard 2>/dev/null")
                            .redirectErrorStream(true).start();
                    if (!u.waitFor(3, TimeUnit.SECONDS)) u.destroy();
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "kill tree failed", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // READ_MEDIA 等运行时权限授权结果；MANAGE_EXTERNAL_STORAGE 的授权状态
        // 在 onResume() 中通过 Environment.isExternalStorageManager() 检测。
        Log.d(TAG, "permission result code=" + code);
    }

    /** 由 xterm.js 调用：把终端按键输入写入子进程 stdin（经 pty-bridge 转发到 PTY） */
    @JavascriptInterface
    public void write(String data) {
        if (sProcIn == null || data == null) return;
        try {
            sProcIn.write(data.getBytes(StandardCharsets.UTF_8));
            sProcIn.flush();
        } catch (IOException e) {
            Log.w(TAG, "write failed", e);
        }
    }

    /** 由 xterm.js 在页面加载完成后调用（握手用） */
    @JavascriptInterface
    public void onReady() {
        // 环境启动由 onPageFinished 触发
    }

    /**
     * 由 xterm.js 上报终端状态（调试/验证用）：{cols, rows, viewportY, length}
     */
    @JavascriptInterface
    public void reportState(String state) {
        Log.d(TAG, "STATE> " + state);
    }

    /**
     * 由 xterm.js 在终端自适应尺寸后调用：把窗口大小（行/列）同步给 PTY，
     * 通过 pty-bridge 的带外序列 \u001b]50;ROWS;COLS\u0007 实现。
     */
    @JavascriptInterface
    public void resize(int rows, int cols) {
        if (sProcIn == null || rows <= 0 || cols <= 0) return;
        try {
            String seq = "\u001b]50;" + rows + ";" + cols + "\u0007";
            sProcIn.write(seq.getBytes(StandardCharsets.UTF_8));
            sProcIn.flush();
        } catch (IOException e) {
            Log.w(TAG, "resize failed", e);
        }
    }

    // ------------------------------------------------------------------
    // 环境初始化与 proot 启动（后台线程）
    // ------------------------------------------------------------------

    /** 当前运行模式："chroot"（root 直入）或 "proot"（默认） */
    private boolean isChrootMode() {
        return "chroot".equals(getSharedPreferences("prefs", MODE_PRIVATE).getString("run_mode", "proot"));
    }

    private void startEnvironment() {
        Log.d(TAG, "startEnvironment: begin");
        // 注：不自动修改 SELinux（setenforce 0）。SELinux 由系统/用户管理保持 enforcing；
        // 安卓开发环境（JVM）在 proot 模式 enforcing 下不可用，可用 root 面板切换 chroot 模式
        // （ksu/root 域，enforcing 下 JVM 正常）。
        try {
            File files = getFilesDir();
            File rootfs = new File(files, "rootfs");
            if (!new File(files, ".installed").exists()) {
                setupEnvironment(files, rootfs);
            } else {
                Log.d(TAG, "environment already installed, refreshing runtime assets");
                refreshAssets(files, rootfs);
            }
            writeAdbIpFile(rootfs);
            ensureReasonixConfig(rootfs);
        } catch (Exception e) {
            Log.e(TAG, "startEnvironment failed", e);
            pushOutput("\r\n[初始化失败] " + e + "\r\n");
        }
    }

    /** root 可用性检测：su 实际可执行且返回 uid=0（KernelSU/Magisk 已授权给本应用才可执行） */
    private boolean isRootAvailable() {
        try {
            String r = execRootCommand("id", 5);
            return r != null && r.contains("uid=0");
        } catch (Exception e) {
            Log.w(TAG, "root availability check failed: " + e);
            return false;
        }
    }

    /** 启动 proot（包装 IOException，供回调/lambda 使用） */
    private void safeStartProot() {
        try {
            startProot(getFilesDir(), new File(getFilesDir(), "rootfs"));
        } catch (IOException e) {
            Log.e(TAG, "startProot failed", e);
            pushOutput("\r\n[启动失败] " + e + "\r\n");
        }
    }

    /**
     * 首次使用快捷配置：检查 guest 内 ~/.reasonix/config.toml 与 .env；
     * 缺失时弹出 API Key 输入对话框，保存后写入配置再启动 reasonix。
     */
    private void ensureReasonixConfig(File rootfs) {
        File home = new File(rootfs, "root/.reasonix");
        File cfg = new File(home, "config.toml");
        File env = new File(home, ".env");
        // reasonix 1.16+ 默认 [sandbox] bash = "enforce" 需要 bubblewrap（bwrap），
        // Android proot 环境无 bwrap 且 user namespace 被禁用 → 所有 shell 命令被拒。
        // 预置 ~/.config/reasonix/config.toml 将 bash 沙箱关闭。
        ensureSandboxDisabled(rootfs);
        if (cfg.exists() && env.exists()) {
            Log.d(TAG, "reasonix config already present, skipping dialog");
            safeStartProot();
            return;
        }
        // 全新安装不再弹出 API Key 配置页面：直接启动环境。
        // 用户可随时通过侧滑菜单「API Key 配置」面板填写 Key。
        Log.d(TAG, "reasonix config missing, starting without API key dialog");
        safeStartProot();
    }

    /** 确保 reasonix 的 bash 沙箱关闭（Android 无 bubblewrap，enforce 会拒绝所有 shell 命令） */
    private void ensureSandboxDisabled(File rootfs) {
        try {
            // 注意：reasonix 的配置路径是 ~/.reasonix/config.toml（实测 reasonix config 命令输出
            // "cli_metrics = ... (~/.reasonix/config.toml)"），不是 ~/.config/reasonix/！
            File conf = new File(new File(rootfs, "root/.reasonix"), "config.toml");
            conf.getParentFile().mkdirs();
            String content = conf.exists()
                    ? new String(java.nio.file.Files.readAllBytes(conf.toPath()), StandardCharsets.UTF_8)
                    : "";
            // 显式配置 [desktop] telemetry = false（非默认值 → reasonix 视为已确认遥测，消除"Accept and continue?"询问）
            if (content.contains("telemetry = false") || content.contains("telemetry = %v")
                    || content.matches("(?ms).*^\\s*telemetry\\s*=\\s*true.*")) {
                content = content.replaceAll("(?m)^\\s*telemetry\\s*=\\s*true\\s*#.*$", "telemetry = false")
                        .replaceAll("(?m)^\\s*telemetry\\s*=\\s*%v.*$", "telemetry = false");
                if (!content.contains("[desktop]")) {
                    content += "\n[desktop]\ntelemetry = false\n";
                }
                java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "reasonix telemetry set false");
            }
            // 确保 AI 系统提示含 adb/root 能力说明（reasonix 探测不到但实际可用，注入提示让 AI 知道）。
            // 注意：reasonix 默认模板含 `# system_prompt = ...` 注释，contains 判断会被注释误伤，须匹配非注释键。
            if (!java.util.regex.Pattern.compile("(?m)^\\s*system_prompt\\s*=").matcher(content).find()) {
                content += "\nsystem_prompt = \"\"\"\n"
                        + "环境能力说明（按需使用，不要编造）：\n"
                        + "1. 本应用内置 adb 无线调试：可直接执行 adb 命令（先 adb devices 查看连接状态，已连接时可用 adb shell / adb install 等）。\n"
                        + "2. 本应用内置手机 root 权限桥：执行 `root <命令>` 以 root 权限运行宿主手机命令（如 root id、root 'pm list packages'）。\n"
                        + "\"\"\"\n";
                java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "reasonix system_prompt injected");
            }
            if (content.contains("bash = \"off\"")) {
                Log.d(TAG, "reasonix sandbox already disabled");
                return;
            }
            if (content.contains("bash =")) {
                content = content.replaceAll("bash\\s*=\\s*\"[a-z]*\"", "bash = \"off\"");
            } else {
                content += "\n[sandbox]\nbash = \"off\"\n";
            }
            java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "reasonix sandbox bash disabled: " + conf.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "failed to disable reasonix sandbox", e);
        }
    }

    /** 写入 reasonix 配置（config.toml + .env，DeepSeek provider）；文件很小，同步执行 */
    private void writeReasonixConfig(File home, File cfg, File env, String apiKey) {
        try {
            home.mkdirs();
            String configToml = "default_model = \"deepseek-flash\"\n"
                    + "\n"
                    + "[desktop]\n"
                    + "telemetry = false\n"
                    + "\n"
                    + "system_prompt = \"\"\"\n"
                    + "环境能力说明（按需使用，不要编造）：\n"
                    + "1. 本应用内置 adb 无线调试：可直接执行 adb 命令（先 adb devices 查看连接状态，已连接时可用 adb shell / adb install 等）。\n"
                    + "2. 本应用内置手机 root 权限桥：执行 `root <命令>` 以 root 权限运行宿主手机命令（如 root id、root 'pm list packages'）。\n"
                    + "\"\"\"\n"
                    + "\n"
                    + "[[providers]]\n"
                    + "name        = \"deepseek-flash\"\n"
                    + "kind        = \"openai\"\n"
                    + "base_url    = \"https://api.deepseek.com\"\n"
                    + "model       = \"deepseek-v4-flash\"\n"
                    + "api_key_env = \"DEEPSEEK_API_KEY\"\n"
                    + "\n"
                    + "[sandbox]\n"
                    + "bash = \"off\"\n";
            try (FileOutputStream fo = new FileOutputStream(cfg)) {
                fo.write(configToml.getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream fo = new FileOutputStream(env)) {
                fo.write(("DEEPSEEK_API_KEY=" + apiKey + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Log.d(TAG, "reasonix config written: " + cfg.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "failed to write reasonix config", e);
        }
    }

    /** 覆盖安装后刷新可更新的 assets（entry.sh/reasonix/pty-bridge 随 APK 版本更新） */
    private void refreshAssets(File files, File rootfs) throws IOException {
        // reasonix：仅在不存在时复制（保留用户通过网络/文件更新过的版本，不被内置版覆盖）
        File rx = new File(rootfs, "usr/local/bin/reasonix");
        if (!rx.exists()) {
            rx.getParentFile().mkdirs();
            extractAsset("usr/bin/reasonix", rx);
            rx.setExecutable(true, false);
            Log.d(TAG, "reasonix deployed from bundle (first time)");
        } else {
            Log.d(TAG, "reasonix exists, keep current version");
        }
        // pty-bridge：先删除再写入（旧环境进程可能仍 exec 着该文件，
        // 直接覆盖(O_TRUNC)报 ETXTBSY；unlink 正在执行的 inode 合法）
        File bridge = new File(rootfs, "usr/bin/pty-bridge");
        bridge.getParentFile().mkdirs();
        bridge.delete();
        extractAsset("usr/bin/pty-bridge", bridge);
        bridge.setExecutable(true, false);
        // entry.sh
        File entry = new File(rootfs, "root/entry.sh");
        extractAsset("root/entry.sh", entry);
        entry.setExecutable(true, false);
        // DS2API 网关（内置上游 AGPL-3.0 服务端，见 assets/ds2api/README-upstream.md）：
        // 覆盖刷新整个 ds2api 目录（删除再解压，保证升级后二进制/WebUI 与 APK 一致）
        File ds2Dir = new File(rootfs, "usr/local/ds2api");
        deleteRecursive(ds2Dir);
        File ds2Bundle = new File(files, "ds2api-bundle.tgz");
        extractAsset("ds2api/ds2api-bundle.tgz", ds2Bundle);
        File ds2Root = new File(rootfs, "usr/local");
        ds2Root.mkdirs();
        runCmd("/system/bin/tar", "-xzf", ds2Bundle.getAbsolutePath(), "-C", ds2Root.getAbsolutePath());
        ds2Bundle.delete();
        Log.d(TAG, "runtime assets refreshed");
    }

    /** ADB 无线调试持久化：把本机局域网 IP 写入 guest 持久文件，供 entry.sh 自动重连使用 */
    private void writeAdbIpFile(File rootfs) {
        try {
            String ip = getLocalIpAddress();
            if (ip == null) {
                Log.w(TAG, "no local IP, skip adb_ip");
                return;
            }
            File f = new File(rootfs, "root/.adb_ip");
            java.nio.file.Files.write(f.toPath(), ip.getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "adb_ip written: " + ip);
        } catch (Exception e) {
            Log.w(TAG, "writeAdbIpFile failed", e);
        }
    }

    /** 首次启动：解压 Alpine rootfs、部署 proot/pty-bridge/reasonix/启动脚本 */
    private void setupEnvironment(File files, File rootfs) throws IOException {
        pushOutput("首次启动，正在解压 Linux 环境（约 30 秒）...\r\n");
        rootfs.mkdirs();

        // 1. 解压 Alpine rootfs（使用系统 toybox tar，自动处理符号链接）
        //    注意：asset 名不能以 .gz 结尾（AGP 打包时会自动解压改名），
        //    内容本身是 gzip 压缩的 tar，改名为 rootfs.tar 保留。
        File tarFile = new File(files, "rootfs.tar");
        extractAsset("rootfs.tar", tarFile);
        runCmd("/system/bin/tar", "-xzf", tarFile.getAbsolutePath(), "-C", rootfs.getAbsolutePath());
        tarFile.delete();

        // 2. rootfs 解压完成（proot 与依赖库打包在 APK native libs 中，无需解压）

        // 3. reasonix、pty-bridge 部署进 rootfs（/usr/local/bin 与 /usr/bin）
        File rx = new File(rootfs, "usr/local/bin/reasonix");
        rx.getParentFile().mkdirs();
        extractAsset("usr/bin/reasonix", rx);
        rx.setExecutable(true, false);
        // 记录内置版本号（新装环境 UI「更新 Reasonix」面板显示内置 1.31.4）
        writeNpmVersion("1.31.4");
        File bridge = new File(rootfs, "usr/bin/pty-bridge");
        bridge.delete();   // 防残留 exec 导致 ETXTBSY（unlink 正在执行的 inode 合法）
        extractAsset("usr/bin/pty-bridge", bridge);
        bridge.setExecutable(true, false);

        // 4. 启动脚本
        File entry = new File(rootfs, "root/entry.sh");
        extractAsset("root/entry.sh", entry);
        entry.setExecutable(true, false);

        // 4.5 DS2API 网关（内置上游 AGPL-3.0 服务端）：解压 ds2api-bundle.tgz 到 /usr/local/ds2api
        //     （bundle 内含 ds2api 二进制 + static WebUI + LICENSE + README.MD）
        File ds2Bundle = new File(files, "ds2api-bundle.tgz");
        extractAsset("ds2api/ds2api-bundle.tgz", ds2Bundle);
        File ds2Root = new File(rootfs, "usr/local");
        ds2Root.mkdirs();
        runCmd("/system/bin/tar", "-xzf", ds2Bundle.getAbsolutePath(), "-C", ds2Root.getAbsolutePath());
        ds2Bundle.delete();

        // 5. DNS 配置
        File resolv = new File(rootfs, "etc/resolv.conf");
        if (!resolv.exists()) {
            try (FileOutputStream fo = new FileOutputStream(resolv)) {
                fo.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\n".getBytes(StandardCharsets.UTF_8));
            }
        }

        new File(files, ".installed").createNewFile();
        pushOutput("环境就绪。\r\n");
    }

    /** 启动 proot（从 nativeLibraryDir 执行）-> Alpine -> pty-bridge(PTY) -> entry.sh -> reasonix */
    /** 清理历史孤儿 guest 进程：多代环境重启/force-stop 后 proot 被杀，guest 内
     *  entry.sh/pty-bridge 变孤儿（PPID=1）累积，多套并存导致 adb 服务循环(.adb-cmd/.adb-out)
     *  多实例竞争、reasonix 会话冲突。环境启动前用真实 root 强制清理（仅当非后台复用场景）。 */
    private void cleanupOrphanGuests() {
        try {
            String su = findSuPath();
            if (su != null) {
                Process p = new ProcessBuilder(su, "-c",
                        "pkill -9 -f 'entry.sh' 2>/dev/null; "
                                + "pkill -9 -f 'pty-bridge' 2>/dev/null; "
                                + "pkill -9 -f 'proot.so' 2>/dev/null; "
                                + "pkill -9 -f 'reasonix.bin' 2>/dev/null; true")
                        .redirectErrorStream(true).start();
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            }
        } catch (Exception e) {
            Log.w(TAG, "cleanup orphan guests failed", e);
        }
    }

    /** 项目元数据目录权限归一：proot/chroot 运行模式切换后 projects 下项目目录 owner 不一致
     *  （chroot 创建的为 root 属主 700，proot 下 guest root=app uid 无 CAP_FOWNER 无法 chmod，
     *   reasonix 创建 sessions 报 permission denied）。环境启动前用真实 root 统一放开读写权限。 */
    private void normalizeProjectPerms() {
        try {
            File rootfs = new File(getFilesDir(), "rootfs");
            String r = rootfs.getAbsolutePath();
            String su = findSuPath();
            if (su != null) {
                Process p = new ProcessBuilder(su, "-c",
                        "chmod -R a+rwx " + r + "/root/.reasonix/projects/ 2>/dev/null")
                        .redirectErrorStream(true).start();
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            }
        } catch (Exception e) {
            Log.w(TAG, "normalize project perms failed", e);
        }
    }

    private void startProot(File files, File rootfs) throws IOException {
        // 防重复启动：环境已在运行时直接跳过（onCreate 已 kill 旧环境，此为双保险；
        // 后台运行模式复用场景 environmentStarted=true 已挡在启动前）
        if (sProotProcess != null && sProotProcess.isAlive()) {
            Log.d(TAG, "proot already running, skip start");
            return;
        }
        // 启动前清理孤儿 guest 进程 + 归一项目元数据权限（真实 root）
        cleanupOrphanGuests();
        normalizeProjectPerms();
        // chroot 模式：root 直接 chroot 进 rootfs（无 ptrace/seccomp 层，进程为 ksu(root) 域，
        // enforcing 下 JVM/apk 均正常，无需 setenforce 0）。无 root 时回退 proot。
        if (isChrootMode()) {
            String su = findSuPath();
            if (su == null) {
                Log.w(TAG, "chroot requires root, fallback to proot");
                getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("run_mode", "proot").apply();
            } else {
                startChroot(su, rootfs);
                return;
            }
        }
        // SELinux 只允许 app 执行 APK native libs 目录（apk_data_file）里的 ELF，
        // 因此 proot、libtalloc、libandroid-shmem、loader 全部打包在 jniLibs，
        // 经 useLegacyPackaging 解压到 nativeLibraryDir 后从这里直接执行。
        // rootfs 内的 guest 二进制（busybox/pty-bridge/reasonix）由 proot 的
        // loader 机制读取装载，不走宿主 execve，天然绕过该限制。
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        String proot = nativeLibDir + "/proot.so";
        // 自检：native 库缺失多为安装时解压失败（部分厂商安装器/流式安装不解压 lib）。
        // 有 root 时自动从 APK 提取并修复（复制 + chcon 回 apk_data_file 域，否则 SELinux 拒绝执行）
        if (!new File(proot).exists()) {
            boolean repaired = tryRepairNativeLib(nativeLibDir, getApplicationInfo().sourceDir);
            if (!repaired) {
                // 首次失败多为 KernelSU/Magisk 授权弹窗尚未确认（授权前 su 对应用不可见）。
                // 等待授权：su 可见后立即重试；若 su 一直不可见（无 root 设备）快速退出，
                // 避免长时间阻塞启动
                for (int i = 0; i < 6 && !repaired; i++) {
                    try { Thread.sleep(6000); } catch (InterruptedException e) { break; }
                    if (findSuPath() == null) break;   // 无 root，退出重试
                    Log.d(TAG, "su now visible, retry native lib repair");
                    repaired = tryRepairNativeLib(nativeLibDir, getApplicationInfo().sourceDir);
                }
            }
            if (repaired) {
                Log.d(TAG, "native lib auto-repaired, continue start");
                pushOutput("\r\n[native 库缺失，已通过 root 自动修复，正在启动...]\r\n");
            } else {
                // 无 root：首次引导覆盖安装；若已引导过一次仍缺失（如 vivo/iQOO 安装器
                // 不解压 native lib），不再重复弹窗（防无限循环），给出明确处理指引
                SharedPreferences p = getSharedPreferences("prefs", MODE_PRIVATE);
                if (!p.getBoolean("reinstall_prompted", false)) {
                    p.edit().putBoolean("reinstall_prompted", true).apply();
                    promptReinstallForNativeLib();
                } else {
                    pushOutput("\r\n[native 库缺失：已尝试覆盖安装但未生效（该手机安装器可能不解压 native 库）。\r\n"
                            + "请用电脑 adb install --no-streaming 安装本 APK，或用有 root 的设备自动修复]\r\n");
                }
                return;
            }
        } else {
            // 启动正常：清除重装引导标记
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit().putBoolean("reinstall_prompted", false).apply();
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(proot);
        cmd.add("-0");                                   // 伪装 root（Alpine 文件属主为 root）
        cmd.add("-r"); cmd.add(rootfs.getAbsolutePath()); // 新根目录
        cmd.add("-b"); cmd.add("/dev");                  // 绑定宿主设备（PTY 需要 /dev/ptmx）
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/storage/emulated/0:/sdcard"); // 手机共享存储
        // 沙箱增强：绑定宿主 app 私有数据目录（可读写）与宿主只读系统分区（放宽读访问）
        cmd.add("-b"); cmd.add("/data/data/" + getPackageName() + ":/host-data");
        String ownAndroidData = "/storage/emulated/0/Android/data/" + getPackageName();
        if (new File(ownAndroidData).exists()) {
            cmd.add("-b"); cmd.add(ownAndroidData + ":/sdcard/Android/data/" + getPackageName());
        }
        String[] hostRo = {"/system", "/product", "/apex"};
        for (String h : hostRo) {
            if (new File(h).exists()) {
                cmd.add("-b"); cmd.add(h + ":/host" + h);
            }
        }
        cmd.add("-w"); cmd.add("/root");                 // 初始工作目录
        cmd.add("/bin/sh"); cmd.add("-c"); cmd.add("/usr/bin/pty-bridge /bin/sh /root/entry.sh");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // proot 需要 execve 它的 loader；loader 放在 nativeLibraryDir（apk_data_file，
        // 允许 exec）。PROOT_LOADER 让 proot 直接使用外部 loader（不提取到 TMPDIR，
        // 提取位置若在 app_data_file 会被 SELinux 拒绝执行）。
        // PROOT_TMP_DIR 指向可写目录（proot 的宿主侧临时文件），filesDir 可写。
        pb.environment().put("PROOT_LOADER", nativeLibDir + "/loader.so");
        pb.environment().put("TMPDIR", nativeLibDir);
        pb.environment().put("PROOT_TMP_DIR", files.getAbsolutePath());
        sProotProcess = pb.start();
        sProcIn = sProotProcess.getOutputStream();
        startRootPolling();   // 启动 root 命令桥轮询（guest root <cmd> → app su 执行）
        startEnvReader(sProotProcess);
    }

    /**
     * chroot 模式启动：su(root) 直接 chroot 进 rootfs。
     * 绑定宿主 /dev（ptmx 保留宿主 SELinux 类型）+ 挂 devpts（pty 终端）+ proc/sys，
     * chroot 运行 pty-bridge → entry.sh，退出后清理挂载。
     * chroot 进程为 ksu(root) 域：JVM(mprotect RWX)/apk(link) 在 enforcing 下均可，
     * 保持 SELinux 开启（无需 setenforce 0）。
     */
    private void startChroot(String su, File rootfs) throws IOException {
        String r = rootfs.getAbsolutePath();
        // 绑定宿主 /dev + devpts + proc + sys + 手机存储 /storage/emulated/0 → /sdcard
        // （不绑 /sdcard 时 chroot 内 /sdcard 是 rootfs 内的空目录，手机目录创建的文件
        //   在文件管理器看不到——与 proot 的 -b /storage/emulated/0:/sdcard 对齐）
        String script = "mkdir -p " + r + "/dev/pts " + r + "/proc " + r + "/sys " + r + "/sdcard; "
                + "mount --bind /dev " + r + "/dev 2>/dev/null; "
                + "mount -t devpts -o gid=5,mode=620 devpts " + r + "/dev/pts 2>/dev/null; "
                + "mount --bind /proc " + r + "/proc 2>/dev/null; "
                + "mount --bind /sys " + r + "/sys 2>/dev/null; "
                + "mount --bind /storage/emulated/0 " + r + "/sdcard 2>/dev/null; "
                + "chroot " + r + " /usr/bin/pty-bridge /bin/sh /root/entry.sh; RC=$?; "
                + "umount " + r + "/dev/pts 2>/dev/null; umount " + r + "/dev 2>/dev/null; "
                + "umount " + r + "/proc 2>/dev/null; umount " + r + "/sys 2>/dev/null; "
                + "umount " + r + "/sdcard 2>/dev/null; exit $RC";
        ProcessBuilder pb = new ProcessBuilder(su, "-c", script);
        pb.redirectErrorStream(true);
        pb.environment().put("RSXM_CHROOT", "1");
        Log.d(TAG, "chroot started via " + su);
        sProotProcess = pb.start();
        sProcIn = sProotProcess.getOutputStream();
        startRootPolling();
        startEnvReader(sProotProcess);
    }

    /** 启动环境输出 reader：把进程 stdout 转发到终端（proot/chroot 共用） */
    private void startEnvReader(Process p) {
        // reader 绑定启动时刻的进程（局部捕获），避免重启环境后读到新进程的流
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    pushOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.w(TAG, "reader ended", e);
            }
            // 环境被主动替换/重启（killProotTree → 新进程）时不输出误导性退出消息
            if (sProotProcess == p) {
                pushOutput("\r\n[Linux 环境已退出]\r\n");
            }
        }, "env-reader");
        reader.setDaemon(true);
        reader.start();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private void extractAsset(String assetPath, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /** 执行命令并把输出转发到终端；非零退出码抛异常 */
    private void runCmd(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                pushOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        if (code != 0) {
            throw new IOException("命令失败(exit=" + code + "): " + String.join(" ", cmd));
        }
    }

    /** 把文本追加到 xterm.js 终端（任意线程可调）；同时输出到 logcat 便于调试。
     *  输出转发到当前活动实例 sCurrent：后台 reader 线程在 Activity 重建后
     *  仍能把环境输出送到新实例的终端；无活动实例时直接丢弃（避免旧实例被
     *  daemon reader 长期持有无法 GC）。 */
    /** 后台期间缓存终端输出（WebView JS 后台暂停，直接 evaluateJavascript 会堆积阻塞
     *  → PTY 缓冲满 → reasonix 写阻塞超时 → 一直 retrying）；前台恢复时一次冲刷。
     *  上限 4MB：AI 长回复/长输出在后台期间不被截断；超出仍清空兜底防无限增长。 */
    private static final StringBuilder sPendingOutput = new StringBuilder();
    private static final int PENDING_OUTPUT_CAP = 4 * 1024 * 1024;   // 4MB 保护
    private static final int FLUSH_CHUNK = 512 * 1024;               // 冲刷分块，防 WebView 卡顿
    private static volatile boolean sWebActive = true;

    private void pushOutput(String text) {
        Log.d(TAG, "OUT> " + (text.length() > 200 ? text.substring(0, 200) : text));
        final MainActivity target = sCurrent;
        if (target == null) return;
        if (!sWebActive) {
            synchronized (sPendingOutput) {
                if (sPendingOutput.length() > PENDING_OUTPUT_CAP) sPendingOutput.setLength(0);
                sPendingOutput.append(text);
            }
            return;
        }
        target.ui.post(() -> {
            if (target.webView == null) return;
            target.webView.evaluateJavascript("window.onTermData(" + jsQuote(text) + ")", null);
        });
    }

    /** 前台恢复：把后台期间缓存的终端输出分块冲刷到 WebView（避免大字符串单次注入卡顿） */
    private void flushPendingOutput() {
        sWebActive = true;
        final String batch;
        synchronized (sPendingOutput) {
            if (sPendingOutput.length() == 0) return;
            batch = sPendingOutput.toString();
            sPendingOutput.setLength(0);
        }
        if (webView == null) return;
        final Handler h = new Handler(Looper.getMainLooper());
        for (int i = 0; i < batch.length(); i += FLUSH_CHUNK) {
            final String part = batch.substring(i, Math.min(batch.length(), i + FLUSH_CHUNK));
            h.postDelayed(() -> webView.evaluateJavascript("window.onTermData(" + jsQuote(part) + ")", null),
                    (i / FLUSH_CHUNK) * 80L);
        }
    }

    /** 生成安全的 JS 字符串字面量 */
    private static String jsQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sCurrent == this) sCurrent = null;
        stopRootPolling();
        // 后台运行模式：环境与 Activity 生命周期解耦，退出 Activity 不杀 proot
        // （由前台服务保活继续后台运行，重新打开时 onCreate 复用环境与终端 I/O）；
        // 关闭模式时照旧清理。
        boolean bgMode = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("background_mode", false);
        if (!bgMode && sProotProcess != null) {
            sProotProcess.destroy();     // pty-bridge 收到 SIGTERM 后会 kill 整个 guest 进程组
            sProotProcess = null;
            sProcIn = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
