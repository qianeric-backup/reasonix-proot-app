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
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
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
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
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
    /** 官方更新源：@reasonix/cli-linux-arm64（npm 平台二进制包，npmmirror 国内镜像） */
    private static final String REASONIX_DEFAULT_URL =
            "https://registry.npmmirror.com/@reasonix/cli-linux-arm64/-/cli-linux-arm64-1.21.1.tgz";

    private WebView webView;
    // proot 进程与输入流静态持有：后台运行模式下与 Activity 生命周期解耦，
    // Activity 重建（系统回收/返回后重开）时无需重启环境，终端 I/O 可无缝续接。
    private static volatile Process sProotProcess;
    private static volatile OutputStream sProcIn;
    /** 当前活动实例：后台 reader 线程输出经它转发到活动终端（重建后指向新实例） */
    private static volatile MainActivity sCurrent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean environmentStarted = false;
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

        // 标题栏菜单按钮：打开侧滑配置列表
        findViewById(R.id.btn_menu).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        // 侧滑菜单功能
        findViewById(R.id.menu_adb).setOnClickListener(v -> { drawerLayout.closeDrawers(); showAdbDialog(); });
        findViewById(R.id.menu_apikey).setOnClickListener(v -> { drawerLayout.closeDrawers(); showApiKeyConfigDialog(); });
        findViewById(R.id.menu_update).setOnClickListener(v -> { drawerLayout.closeDrawers(); showUpdateResonixDialog(); });
        findViewById(R.id.menu_bgmode).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences("prefs", MODE_PRIVATE);
            boolean on = !sp.getBoolean("background_mode", false);
            sp.edit().putBoolean("background_mode", on).apply();
            updateBgModeLabel();
            drawerLayout.closeDrawers();
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
            drawerLayout.closeDrawers();
            // 立即生效：重启 reasonix 环境（reasonix 启动时 wrapper 读取新标记决定审批模式）。
            // 不 pushOutput（reasonix alt screen 自绘会污染 TUI）；状态由菜单标签与重启日志显示。
            restartEnvironment();
        });
        updateYoloModeLabel();
        // 升级安装后 rootfs 可能没有 YOLO 标记：以偏好为准补写（默认开启）
        syncYoloMark(getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("yolo_mode", true));
        findViewById(R.id.menu_root).setOnClickListener(v -> { drawerLayout.closeDrawers(); showRootDialog(); });
        findViewById(R.id.menu_skill).setOnClickListener(v -> { drawerLayout.closeDrawers(); showSkillInstallDialog(); });
        findViewById(R.id.menu_project).setOnClickListener(v -> { drawerLayout.closeDrawers(); showProjectDialog(); });
        findViewById(R.id.menu_dev).setOnClickListener(v -> { drawerLayout.closeDrawers(); showDevEnvDialog(); });

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
                "本应用内置 root 命令桥：AI（reasonix）内可直接执行 root <命令> 获取手机 root 权限。\n\n"
                        + "使用方式（reasonix 终端里）：\n"
                        + "  root id                  # 查看 root 身份\n"
                        + "  root 'pm list packages'  # 例：列出应用\n\n"
                        + "首次执行会弹出 root 授权请求（KernelSU/Magisk），请允许。\n"
                        + "⚠ 请勿随意执行未知命令，root 权限可完全控制系统。");
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        panel.addView(status);
        panel.addView(tip);
        Button testBtn = createDarkButton("测试 root（执行 id）");
        panel.addView(testBtn);
        TextView result = createDarkResult();
        result.setText("（测试结果将显示在这里）");
        panel.addView(result);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("Root 权限", panel, null);
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
        findViewById(R.id.panel_overlay).setVisibility(View.VISIBLE);
    }

    /** 关闭全屏功能面板 */
    private void hidePanel() {
        if (findViewById(R.id.panel_overlay).getVisibility() != View.VISIBLE) return;
        findViewById(R.id.panel_overlay).setVisibility(View.GONE);
        ((ViewGroup) findViewById(R.id.panel_content)).removeAllViews();
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
            tv.setText(on ? "后台运行模式：开" : "后台运行模式：关");
            tv.setTextColor(on ? 0xFF4CAF50 : 0xFFFFFFFF);
        }
    }

    /** YOLO 免审批模式标签：开启时 reasonix 完全跳过工具审批（--permission-mode bypassPermissions） */
    private void updateYoloModeLabel() {
        boolean on = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("yolo_mode", true);
        TextView tv = findViewById(R.id.menu_yolo);
        if (tv != null) {
            tv.setText(on ? "YOLO 免审批模式：开" : "YOLO 免审批模式：关");
            tv.setTextColor(on ? 0xFF4CAF50 : 0xFFFFFFFF);
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

    /** 在 API Key 面板内嵌充值方式指引（内置在应用内，含打开充值页按钮） */
    private void addRechargeGuide(LinearLayout panel) {
        TextView title = new TextView(this);
        title.setText("充值方式（DeepSeek 开放平台）");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(13);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(2), dp(14), dp(2), dp(6));
        panel.addView(title);
        panel.addView(createDarkTip(
                "1. 手机/电脑浏览器打开 platform.deepseek.com 并登录\n"
                        + "2. 左侧菜单点「充值」（费用与充值）\n"
                        + "3. 输入金额（最低 ¥10），支持支付宝/微信支付\n"
                        + "4. 到账后即可使用；新用户有赠送额度，可先体验\n"
                        + "5. API Key 在「API Keys」页面创建后填入上方输入框"));
        Button openBtn = createDarkButton("打开充值页");
        openBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://platform.deepseek.com/top_up")));
            } catch (Exception e) {
                Log.w(TAG, "open recharge page failed", e);
            }
        });
        panel.addView(openBtn);
    }

    /** 安装 SKILL：skill 是 reasonix 的 AI 技能包（SKILL.md 规范格式），
     *  写入 ~/.reasonix/skills/<name>/SKILL.md，reasonix 启动时自动加载，
     *  对话内可用 /skill enable <name> 启用 */
    private void showSkillInstallDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        panel.addView(createDarkTip(
                "SKILL 是 reasonix 的 AI 技能包（SKILL.md 格式），安装后 reasonix 启动自动加载，"
                        + "对话里可用 /skill enable <名称> 启用；不需要时可在此卸载。\n"
                        + "格式要求：开头必须有 YAML frontmatter，含 name 和 description 两行。"));
        final EditText nameInput = createDarkEditText("SKILL 名称（如 mytool，仅字母数字._-）",
                InputType.TYPE_CLASS_TEXT);
        panel.addView(nameInput);
        final EditText contentInput = new EditText(this);
        contentInput.setHint("SKILL.md 内容（粘贴，含 frontmatter）");
        contentInput.setTextColor(0xFFE0E0E0);
        contentInput.setHintTextColor(0xFF666666);
        contentInput.setTextSize(13);
        contentInput.setGravity(android.view.Gravity.TOP);
        contentInput.setSingleLine(false);
        contentInput.setMinLines(8);
        contentInput.setBackgroundColor(0xFF1A1A1A);
        contentInput.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.addView(contentInput);
        Button installBtn = createDarkButton("安装 SKILL");
        installBtn.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String content = contentInput.getText().toString();
            if (name.isEmpty() || content.isEmpty()) {
                pushOutput("\r\n[请填写 SKILL 名称和内容]\r\n");
                return;
            }
            if (!name.matches("[A-Za-z0-9_.-]+")) {
                pushOutput("\r\n[SKILL 名称仅允许字母、数字、_ . -]\r\n");
                return;
            }
            installSkill(name, content);
        });
        panel.addView(installBtn);
        Button listBtn = createDarkButton("查看已安装");
        listBtn.setOnClickListener(v -> new Thread(() -> {
            try {
                String out = executeInGuest(
                        "ls ~/.reasonix/skills 2>&1; echo ---; ls ~/.reasonix/skills/*/SKILL.md 2>&1", 12);
                String msg = "\r\n[已安装 SKILL]\r\n" + (out == null ? "(无输出)" : out) + "\r\n";
                runOnUiThread(() -> pushOutput(msg));
            } catch (Exception e) {
                Log.e(TAG, "list skills failed", e);
            }
        }, "skill-list").start());
        panel.addView(listBtn);
        panel.addView(createDarkTip("卸载：填写要删除的 SKILL 名称后点「卸载 SKILL」"));
        final EditText uninstallInput = createDarkEditText("卸载 SKILL 名称（如 mytool）",
                InputType.TYPE_CLASS_TEXT);
        panel.addView(uninstallInput);
        Button uninstallBtn = createDarkButton("卸载 SKILL");
        uninstallBtn.setOnClickListener(v -> {
            String name = uninstallInput.getText().toString().trim();
            if (name.isEmpty()) {
                pushOutput("\r\n[请输入要卸载的 SKILL 名称]\r\n");
                return;
            }
            if (!name.matches("[A-Za-z0-9_.-]+")) {
                pushOutput("\r\n[SKILL 名称仅允许字母、数字、_ . -]\r\n");
                return;
            }
            uninstallSkill(name);
        });
        panel.addView(uninstallBtn);
        showPanel("skill功能", panel, null);
    }

    /** 卸载 SKILL：删除 ~/.reasonix/skills/<name> 目录 */
    private void uninstallSkill(String name) {
        new Thread(() -> {
            try {
                String out = executeInGuest(
                        "rm -rf ~/.reasonix/skills/" + name
                                + " && echo UNINSTALLED_OK && ls ~/.reasonix/skills/ 2>&1", 10);
                String msg = "\r\n[卸载 SKILL: " + name + "]\r\n"
                        + ((out != null && out.contains("UNINSTALLED_OK"))
                            ? "已删除。剩余 SKILL：\n" + out.replace("UNINSTALLED_OK", "").trim()
                            : (out == null ? "(无响应)" : out)) + "\r\n";
                runOnUiThread(() -> pushOutput(msg));
            } catch (Exception e) {
                Log.e(TAG, "uninstall skill failed", e);
                runOnUiThread(() -> pushOutput("\r\n[卸载 SKILL 失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-uninstall").start();
    }

    /** 安装 SKILL 文件：内容 base64 编码后经 guest 服务循环写入（避免转义/引号问题） */
    private void installSkill(String name, String content) {
        new Thread(() -> {
            try {
                String b64 = android.util.Base64.encodeToString(
                        content.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                String cmd = "mkdir -p ~/.reasonix/skills/" + name
                        + " && echo " + b64 + " | base64 -d > ~/.reasonix/skills/" + name + "/SKILL.md"
                        + " && chmod 644 ~/.reasonix/skills/" + name + "/SKILL.md"
                        + " && echo INSTALLED_OK && ls -la ~/.reasonix/skills/" + name + "/SKILL.md";
                String out = executeInGuest(cmd, 12);
                String msg = "\r\n[安装 SKILL: " + name + "]\r\n"
                        + (out == null ? "(无响应)" : out)
                        + "\r\n[完成。重启应用环境或 reasonix 内 /skill enable " + name + " 启用]\r\n";
                runOnUiThread(() -> pushOutput(msg));
            } catch (Exception e) {
                Log.e(TAG, "install skill failed", e);
                runOnUiThread(() -> pushOutput("\r\n[安装 SKILL 失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-install").start();
    }

    /* ==================== 项目位置 ==================== */

    /** 项目：reasonix 工作目录（会话/记忆按项目隔离），可建在内部或手机目录（/sdcard），切换后重启生效 */
    private void showProjectDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        panel.addView(createDarkTip(
                "项目是 reasonix 的工作目录，AI 会话、记忆、历史按项目隔离。\n"
                        + "可新建在内部（rootfs）或手机目录（/sdcard/ReasonixProjects，文件管理器可见）。\n"
                        + "切换/新建后自动重启 reasonix 进入该项目（当前会话会结束）。"));
        final TextView curView = new TextView(this);
        curView.setTextColor(0xFF4CAF50);
        curView.setTextSize(14);
        curView.setTypeface(null, android.graphics.Typeface.BOLD);
        curView.setPadding(dp(2), dp(8), dp(2), dp(4));
        panel.addView(curView);
        final TextView listView = new TextView(this);
        listView.setTextColor(0xFFCCCCCC);
        listView.setTextSize(12);
        listView.setPadding(dp(2), dp(4), dp(2), dp(8));
        panel.addView(listView);
        panel.addView(createDarkTip("新建项目：输入名称后选择创建位置"));
        final EditText newInput = createDarkEditText("新项目名称（如 myapp，字母数字._-）",
                InputType.TYPE_CLASS_TEXT);
        panel.addView(newInput);
        Button newSdcardBtn = createDarkButton("在手机目录创建并进入");
        newSdcardBtn.setOnClickListener(v -> {
            String name = newInput.getText().toString().trim();
            if (!checkProjectName(name)) return;
            createProject(name, true);
        });
        panel.addView(newSdcardBtn);
        Button newInnerBtn = createDarkButton("在内部创建并进入");
        newInnerBtn.setOnClickListener(v -> {
            String name = newInput.getText().toString().trim();
            if (!checkProjectName(name)) return;
            createProject(name, false);
        });
        panel.addView(newInnerBtn);
        panel.addView(createDarkTip("切换：也可直接输入完整路径（如 /root/xxx 或 /sdcard/ReasonixProjects/xxx）"));
        final EditText pathInput = createDarkEditText("项目路径（如 /root/myapp）", InputType.TYPE_CLASS_TEXT);
        panel.addView(pathInput);
        Button switchBtn = createDarkButton("切换到该路径并重启");
        switchBtn.setOnClickListener(v -> {
            String path = pathInput.getText().toString().trim();
            if (path.isEmpty()) {
                pushOutput("\r\n[请输入项目路径]\r\n");
                return;
            }
            if (!path.startsWith("/")) path = "/root/" + path;
            if (!path.matches("[/A-Za-z0-9_.-]+")) {
                pushOutput("\r\n[路径仅允许字母、数字、_ . - /]\r\n");
                return;
            }
            switchProject(path);
        });
        panel.addView(switchBtn);
        Button defaultBtn = createDarkButton("恢复默认（/root）");
        defaultBtn.setOnClickListener(v -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                new File(rootDir, ".rsxm-project").delete();
                pushOutput("\r\n[已恢复默认项目 /root，重启后生效]\r\n");
                refreshProjectViews(curView, listView);
            } catch (Exception e) {
                Log.e(TAG, "reset project failed", e);
            }
        });
        panel.addView(defaultBtn);
        Button refreshBtn = createDarkButton("刷新项目列表");
        refreshBtn.setOnClickListener(v -> refreshProjectViews(curView, listView));
        panel.addView(refreshBtn);
        refreshProjectViews(curView, listView);
        showPanel("项目", panel, null);
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
    private void createProject(String name, boolean inSdcard) {
        String path = inSdcard
                ? "/sdcard/ReasonixProjects/" + name
                : "/root/" + name;
        new Thread(() -> {
            try {
                String out = executeInGuest("mkdir -p " + path + " && echo MKDIR_OK", 10);
                if (out == null || !out.contains("MKDIR_OK")) {
                    runOnUiThread(() -> pushOutput(
                            "\r\n[创建项目目录失败: " + (out == null ? "无响应" : out) + "]\r\n"));
                    return;
                }
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                java.nio.file.Files.write(new File(rootDir, ".rsxm-project").toPath(),
                        (path + "\n").getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    pushOutput("\r\n[已创建项目 " + path + "，正在重启 reasonix...]\r\n");
                    restartEnvironment();
                });
            } catch (Exception e) {
                Log.e(TAG, "create project failed", e);
                runOnUiThread(() -> pushOutput("\r\n[创建项目失败: " + e.getMessage() + "]\r\n"));
            }
        }, "project-create").start();
    }

    /** 刷新当前项目与项目列表显示（内部 + 手机目录） */
    private void refreshProjectViews(TextView curView, TextView listView) {
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
                String out = executeInGuest(
                        "echo [内部]:; ls ~/.reasonix/projects/ 2>&1;"
                                + " echo [手机目录 /sdcard/ReasonixProjects]:;"
                                + " ls /sdcard/ReasonixProjects/ 2>&1", 10);
                String list = (out == null || out.trim().isEmpty()) ? "（无）" : out.trim();
                final String c = cur, l = list;
                runOnUiThread(() -> {
                    curView.setText("当前项目：" + c);
                    listView.setText("已有项目：\n" + l);
                });
            } catch (Exception e) {
                Log.e(TAG, "refresh projects failed", e);
            }
        }, "project-list").start();
    }

    /** 切换项目：guest 内创建目录 + 写标记 + 重启环境（reasonix 从新项目目录启动） */
    private void switchProject(String path) {
        new Thread(() -> {
            try {
                String out = executeInGuest("mkdir -p " + path + " && echo MKDIR_OK", 10);
                if (out == null || !out.contains("MKDIR_OK")) {
                    runOnUiThread(() -> pushOutput(
                            "\r\n[创建项目目录失败: " + (out == null ? "无响应" : out) + "]\r\n"));
                    return;
                }
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                java.nio.file.Files.write(new File(rootDir, ".rsxm-project").toPath(),
                        (path + "\n").getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    pushOutput("\r\n[已切换到项目 " + path + "，正在重启 reasonix...]\r\n");
                    restartEnvironment();
                });
            } catch (Exception e) {
                Log.e(TAG, "switch project failed", e);
                runOnUiThread(() -> pushOutput("\r\n[切换项目失败: " + e.getMessage() + "]\r\n"));
            }
        }, "project-switch").start();
    }

    /* ==================== 开发环境 ==================== */

    /** 开发环境：一键安装常用开发环境（Alpine 包管理器，后台安装 + 面板内实时进度） */
    private void showDevEnvDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        panel.addView(createDarkTip(
                "一键安装常用开发环境（基于 Alpine 包管理器，需联网）。\n"
                        + "点击后后台自动安装，进度实时显示在下方；大环境（Android/Go）耗时较长。"));

        // 安装进度区（初始隐藏，点击安装后显示；安装期间禁用所有按钮防止并发覆盖状态文件）
        LinearLayout progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(0, dp(12), 0, dp(12));
        progressBox.setVisibility(View.GONE);
        TextView progressTitle = new TextView(this);
        progressTitle.setTextColor(0xFFFFFFFF);
        progressTitle.setTextSize(14);
        progressTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        TextView progressText = new TextView(this);
        progressText.setTextColor(0xFFAAAAAA);
        progressText.setTextSize(12);
        progressText.setPadding(0, dp(4), 0, 0);
        progressBox.addView(progressTitle);
        progressBox.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        progressBox.addView(progressText);
        panel.addView(progressBox);

        String[][] envs = {
                {"Android 开发", "openjdk17-jdk gradle android-tools",
                        "JDK17 + Gradle + adb/fastboot（安卓应用构建）"},
                {"Python 开发", "python3 py3-pip", "Python3 + pip"},
                {"Node.js", "nodejs npm", "Node.js + npm"},
                {"Go 开发", "go", "Go 语言工具链"},
                {"C/C++ 开发", "gcc g++ make musl-dev", "GCC/G++ + Make + 头文件"},
                {"通用工具", "git vim curl wget zip unzip", "Git/Vim/curl/wget 等"},
        };
        final Button[] buttons = new Button[envs.length];
        for (int i = 0; i < envs.length; i++) {
            final String[] e = envs[i];
            Button b = createDarkButton(e[0] + "  ｜  " + e[2]);
            b.setOnClickListener(v -> installDevEnv(e[0], e[1], b, buttons,
                    progressBox, progressTitle, progressBar, progressText));
            panel.addView(b);
            buttons[i] = b;
        }
        // 面板重开时若仍有安装任务在后台进行：恢复显示进度区并禁用全部按钮
        String installing = devEnvInstallingName;
        if (installing != null) {
            progressBox.setVisibility(View.VISIBLE);
            progressTitle.setText(installing + " 安装中...");
            progressTitle.setTextColor(0xFFFFFFFF);
            progressBar.setIndeterminate(true);
            progressBar.setProgress(0);
            progressText.setText("安装进行中，请稍候...");
            for (Button b : buttons) b.setEnabled(false);
        }
        showPanel("开发环境", panel, null);
    }

    /** 安装开发环境：guest 内 nohup 后台 apk add（防服务循环 20s timeout 杀掉长安装），
     *  轮询状态文件报告结果，并解析 apk 日志 (x/N) 实时刷新面板进度条 */
    private void installDevEnv(String name, String packages, Button btn, Button[] allBtns,
                               View progressBox, TextView progressTitle, ProgressBar progressBar,
                               TextView progressText) {
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
            for (Button b : allBtns) b.setEnabled(false);
            btn.setText(name + " 安装中...");
        });
        new Thread(() -> {
            try {
                // 安装脚本经 base64 写入 guest（服务循环用 sh -c "$CMD" 执行，命令内不能含双引号），
                // 再 nohup 后台执行，避免 20s timeout 杀掉长安装
                String script = "#!/bin/sh\n"
                        + "apk update > /root/.env-install.log 2>&1\n"
                        + "echo \"--- 安装 " + name + " ---\" >> /root/.env-install.log\n"
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
                            progressText.setText((ok ? "完成（退出码 0）" : "失败（apk 退出码 " + code + "）")
                                    + "：" + tail.trim());
                            btn.setText(name + (ok ? " ✓ 已安装" : "（失败，可重试）"));
                            for (Button b : allBtns) b.setEnabled(true);
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
                    btn.setText(name + "（超时，可重试）");
                    for (Button b : allBtns) b.setEnabled(true);
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
                    btn.setText(name + "（失败，可重试）");
                    for (Button b : allBtns) b.setEnabled(true);
                    pushOutput("\r\n[开发环境] 安装失败: " + e.getMessage() + "\r\n");
                });
            } finally {
                devEnvInstalling = false;
                devEnvInstallingName = null;
            }
        }, "dev-env").start();
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
        return et;
    }

    /** 原生风格按钮（平台 Theme.Material 下自带圆角/波纹，仅覆 tint 为黑灰保持纯黑） */
    private Button createDarkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setAllCaps(false);
        // 原生涟漪保留（colorControlHighlight），底色改为黑灰
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF262626));
        return b;
    }

    /** 深色说明文字 */
    private TextView createDarkTip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFAAAAAA);
        tv.setTextSize(12);
        return tv;
    }

    /** 深色代码结果区 */
    private TextView createDarkResult() {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFF7FDB8A);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setBackgroundColor(0xFF101010);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
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
        TextView tip = createDarkTip("本机 IP：" + ip + "\n\n"
                + "1. 手机：设置 -> 开发者选项 -> 无线调试 -> 打开，抄下配对码与配对端口\n"
                + "2. 首次使用：填配对端口+配对码，点「配对并连接」（配对后自动扫描连接端口并直连）\n"
                + "3. 之后免配对：点「自动连接」直接扫描连接（无需任何输入）\n"
                + "4. 连接成功后，reasonix（AI）里可直接执行 adb shell / adb install 等\n\n"
                + "连接由本应用处理，AI 会话无需关心配对/端口。");
        // 独立状态行（执行后自动刷新）
        TextView statusLine = new TextView(this);
        statusLine.setText("连接状态：" + status);
        statusLine.setTextColor(status.contains("已连接") ? 0xFF7FDB8A : (status.contains("需配对") ? 0xFFFFD54F : 0xFFCCCCCC));
        statusLine.setTextSize(13);
        statusLine.setTypeface(null, android.graphics.Typeface.BOLD);
        int pad = dp(16);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, dp(8), pad, 0);
        panel.addView(statusLine);
        panel.addView(tip);
        panel.addView(pairPort);
        panel.addView(pairCode);
        // 执行结果区（adb 输出实时显示，不依赖 reasonix 会话）
        TextView resultView = createDarkResult();
        resultView.setText("（执行结果将显示在这里）");
        panel.addView(resultView);
        // 自动连接按钮：app 直接驱动 guest 内 adb-autoconnect（扫描 30000-49999 并 connect）
        Button autoBtn = createDarkButton("自动连接（免配对直连）");
        autoBtn.setOnClickListener(v -> {
            saveAdbPrefs(prefs, pairPort, pairCode);
            startAdbKeepAlive();   // 后台保活：滑动关闭应用后 adb 连接保持
            runAdbInGuest("adb-autoconnect", resultView, statusLine);
        });
        panel.addView(autoBtn);
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
            startAdbKeepAlive();   // 后台保活：滑动关闭应用后 adb 连接保持
            runAdbInGuest("adb-dopair " + pp + " " + pc, resultView, statusLine);
        });
        panel.addView(pairBtn);
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
        panel.addView(copyBtn);
        // ADB 后台保活状态与控制（前台服务：滑动关闭应用后连接保持）
        final TextView kaStatus = new TextView(this);
        kaStatus.setTextColor(0xFF4CAF50);
        kaStatus.setTextSize(13);
        kaStatus.setPadding(dp(2), dp(10), dp(2), dp(4));
        kaStatus.setText("后台保活：" + (getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("adb_keepalive", false)
                ? "开启（关闭应用后连接保持）" : "未开启（连接成功后自动开启）"));
        panel.addView(kaStatus);
        panel.addView(createDarkTip(
                "保活提示：请勿用「一键清理/强制停止」关闭应用，否则前台服务被系统终止、adb 连接会断开；"
                        + "从最近任务划掉应用不影响连接。"));
        Button stopKaBtn = createDarkButton("停止 ADB 后台保活");
        stopKaBtn.setOnClickListener(v -> {
            stopAdbKeepAlive();
            kaStatus.setText("后台保活：未开启");
            pushOutput("\r\n[已停止 ADB 后台保活，滑动关闭应用后连接将断开]\r\n");
        });
        panel.addView(stopKaBtn);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("ADB 无线调试", panel, this::stopAdbStatusRefresh);
        // 状态实时刷新：面板打开期间每 4 秒用 adb devices 检查真实连接（防快照过期）
        startAdbStatusRefresh(statusLine);
        // 操作逻辑优化：状态未知（首次/环境刚启动）时自动触发一次检测，免去手动点击
        if (status.contains("未知")) {
            statusLine.postDelayed(() -> runAdbInGuest("adb-autoconnect", resultView, statusLine), 600);
        }
        // 已连接则确保后台保活开启（防面板未操作时连接状态不触发保活）
        if (readAdbStatus().contains("已连接")) startAdbKeepAlive();
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
                        runOnUiThread(() -> stopAdbKeepAlive());   // 无线调试关闭，保活无意义
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

    /** 启动 ADB 后台保活（前台服务）：滑动关闭应用后进程不被回收，adb 连接保持 */
    private void startAdbKeepAlive() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("adb_keepalive", true).apply();
        try {
            Intent i = new Intent(this, AdbKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Log.d(TAG, "adb keepalive service started");
        } catch (Exception e) {
            Log.w(TAG, "start adb keepalive failed", e);
        }
    }

    /** 停止 ADB 后台保活 */
    private void stopAdbKeepAlive() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("adb_keepalive", false).apply();
        try {
            stopService(new Intent(this, AdbKeepAliveService.class));
            Log.d(TAG, "adb keepalive service stopped");
        } catch (Exception e) {
            Log.w(TAG, "stop adb keepalive failed", e);
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
                    if (st.contains("已连接")) startAdbKeepAlive();  // 连接成功自动开启后台保活
                }
            });
        }, "adb-exec").start();
    }

    /** 写入命令到 guest adb 服务并轮询结果（.adb-cmd → 执行 → .adb-out 含 __DONE__ 标记） */
    private String executeInGuest(String cmd, int timeoutSec) {
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
    private void showApiKeyConfigDialog() {
        File rootfs = new File(getFilesDir(), "rootfs");
        File env = new File(new File(rootfs, "root/.reasonix"), ".env");
        String current = "";
        if (env.exists()) {
            try {
                for (String line : new String(java.nio.file.Files.readAllBytes(env.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    if (line.startsWith("DEEPSEEK_API_KEY=")) {
                        current = line.substring("DEEPSEEK_API_KEY=".length()).trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        EditText input = createDarkEditText("粘贴 DeepSeek API Key",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (!current.isEmpty()) input.setText(current);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        panel.addView(createDarkTip("当前模型：deepseek-v4-flash（api.deepseek.com）"));
        panel.addView(input);
        addRechargeGuide(panel);
        Button saveBtn = createDarkButton("保存");
        saveBtn.setOnClickListener(v -> {
            String key = input.getText().toString().trim();
            if (key.isEmpty()) return;
            try {
                env.getParentFile().mkdirs();
                java.nio.file.Files.write(env.toPath(),
                        ("DEEPSEEK_API_KEY=" + key + "\n").getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "API key updated");
                hidePanel();
                pushOutput("\r\n[API Key 已更新，正在重启环境...]\r\n");
                restartEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "save api key failed", e);
            }
        });
        panel.addView(saveBtn);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("API Key 配置", panel, null);
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

    /** 更新 reasonix：从手机选择新版文件，或恢复内置版本 */
    private void showUpdateResonixDialog() {
        EditText urlInput = createDarkEditText("reasonix 更新包链接 (.tgz)", InputType.TYPE_CLASS_TEXT);
        urlInput.setText(REASONIX_DEFAULT_URL);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, dp(8), pad, 0);
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
        verView.setText("当前版本：" + (ver != null ? ver : "未知（内置 1.20.0）"));
        verView.setTextColor(0xFF7FDB8A);
        verView.setTextSize(14);
        verView.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(verView);
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
        TextView tip = createDarkTip("官方源：@reasonix/cli-linux-arm64（npm 平台包）\n"
                + "可修改下方链接更新到其它版本。\n"
                + "或从手机选择新版文件 / 恢复内置版本。");
        panel.addView(tip);
        panel.addView(urlInput);
        Button netBtn = createDarkButton("网络更新");
        netBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) {
                hidePanel();
                updateFromNetwork(url);
            }
        });
        panel.addView(netBtn);
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
        panel.addView(fileBtn);
        Button restoreBtn = createDarkButton("恢复内置");
        restoreBtn.setOnClickListener(v -> {
            hidePanel();
            restoreBundledResonix();
        });
        panel.addView(restoreBtn);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("更新 reasonix", panel, null);
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
                        java.nio.file.Files.write(new File(rootfs, "root/.reasonix/.npm-version").toPath(),
                                mv.group(1).getBytes(StandardCharsets.UTF_8));
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
            Log.d(TAG, "reasonix restored from bundle");
            pushOutput("\r\n[已恢复内置 reasonix，正在重启环境...]\r\n");
            restartEnvironment();
        } catch (Exception e) {
            Log.e(TAG, "restore reasonix failed", e);
        }
    }

    /** 从手机存储复制新版 reasonix 到 guest */
    private void applyReasonixUpdate(Uri uri) {
        try {
            File rootfs = new File(getFilesDir(), "rootfs");
            File rx = new File(new File(rootfs, "usr/local/bin"), "reasonix");
            rx.getParentFile().mkdirs();
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(rx)) {
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                Log.d(TAG, "reasonix updated, size=" + total);
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
        // 系统无线调试开关联动：已关闭时停止保活（避免"保持中"通知误导）
        new Thread(() -> {
            try {
                String wd = execRootCommand("settings get global adb_wifi_enabled", 4);
                if (wd != null && wd.trim().equals("0")
                        && getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("adb_keepalive", false)) {
                    runOnUiThread(() -> {
                        stopAdbKeepAlive();
                        pushOutput("\r\n[检测到系统无线调试已关闭，已停止 ADB 后台保活]\r\n");
                    });
                }
            } catch (Exception ignored) {
            }
        }, "adb-wifi-check").start();
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
                panel.setPadding(dp(16), dp(8), dp(16), 0);
                panel.addView(createDarkTip("reasonix 需要\"所有文件访问\"权限才能读写手机存储的任意位置"
                        + "（文档、下载、非媒体文件等）。\n\n未授权时仅可访问公共媒体目录。"));
                Button grantBtn = createDarkButton("去授权");
                grantBtn.setOnClickListener(v -> {
                    hidePanel();
                    openManageAllFilesSettings();
                });
                panel.addView(grantBtn);
                Button laterBtn = createDarkButton("暂不");
                laterBtn.setOnClickListener(v -> hidePanel());
                panel.addView(laterBtn);
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
            // 同 uid 下 kill 同 uid 进程是允许的；按进程名精确匹配，不会误伤其他应用
            Process p = new ProcessBuilder("sh", "-c",
                    "for pid in $(ps -A -o PID,NAME 2>/dev/null | grep -E 'proot.so|pty-bridge|reasonix' | awk '{print $1}'); " +
                    "do kill -9 $pid 2>/dev/null; done")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
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

    private void startEnvironment() {
        Log.d(TAG, "startEnvironment: begin");
        applySelinuxWorkaround();   // SELinux 适配：JVM/apk 需要 execmem/link 权限（见方法注释）
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

    /** SELinux 适配：untrusted_app 默认无 execmem/execmod/link 权限，导致
     *  JVM(mprotect RWX) 启动失败（"Failed to mark memory page as executable"）
     *  与 apk 硬链接安装失败（avc denied { link }）。
     *  KernelSU/Magisk root 下 setenforce 0 后两者均恢复正常；无 root 时静默跳过。
     *  重启后 SELinux 恢复 enforcing，故每次环境启动时执行。 */
    private void applySelinuxWorkaround() {
        try {
            String su = findSuPath();
            if (su == null) return;
            Process p = new ProcessBuilder(su, "-c", "setenforce 0 2>/dev/null; getenforce")
                    .redirectErrorStream(true).start();
            if (p.waitFor(5, TimeUnit.SECONDS)) {
                byte[] out = new byte[128];
                int n = p.getInputStream().read(out);
                String s = n > 0 ? new String(out, 0, n, StandardCharsets.UTF_8).trim() : "";
                Log.d(TAG, "SELinux workaround: " + s);
            } else {
                p.destroy();
                Log.w(TAG, "setenforce timeout");
            }
        } catch (Exception e) {
            Log.w(TAG, "setenforce failed: " + e);
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
        // 用户可随时通过侧滑菜单「API Key 配置」面板填写 Key（面板内置充值指引）。
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
        // pty-bridge
        File bridge = new File(rootfs, "usr/bin/pty-bridge");
        bridge.getParentFile().mkdirs();
        extractAsset("usr/bin/pty-bridge", bridge);
        bridge.setExecutable(true, false);
        // entry.sh
        File entry = new File(rootfs, "root/entry.sh");
        extractAsset("root/entry.sh", entry);
        entry.setExecutable(true, false);
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
        File bridge = new File(rootfs, "usr/bin/pty-bridge");
        extractAsset("usr/bin/pty-bridge", bridge);
        bridge.setExecutable(true, false);

        // 4. 启动脚本
        File entry = new File(rootfs, "root/entry.sh");
        extractAsset("root/entry.sh", entry);
        entry.setExecutable(true, false);

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
    private void startProot(File files, File rootfs) throws IOException {
        // 防重复启动：环境已在运行时直接跳过（onCreate 已 kill 旧环境，此为双保险；
        // 后台运行模式复用场景 environmentStarted=true 已挡在启动前）
        if (sProotProcess != null && sProotProcess.isAlive()) {
            Log.d(TAG, "proot already running, skip start");
            return;
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

        // reader 绑定启动时刻的进程（局部捕获），避免重启环境后读到新进程的流
        final Process p = sProotProcess;
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
        }, "proot-reader");
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
