package com.rxproot.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    private Process prootProcess;
    private OutputStream procIn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean environmentStarted = false;
    private DrawerLayout drawerLayout;
    private TextView tvStatus;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 终端 UI 就绪后再启动 Linux 环境，避免输出丢失
                if (!environmentStarted) {
                    environmentStarted = true;
                    new Thread(MainActivity.this::startEnvironment).start();
                }
            }
        });

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
            boolean on = sp.getBoolean("background_mode", false);
            sp.edit().putBoolean("background_mode", !on).apply();
            updateBgModeLabel();
            drawerLayout.closeDrawers();
            pushOutput("\r\n[后台运行模式 " + (!on ? "已开启" : "已关闭") + "："
                    + (!on ? "冻结前台操作，避免抢占其他应用（如微信）前台]" : "恢复正常前台行为]") + "\r\n");
        });
        updateBgModeLabel();
        findViewById(R.id.menu_root).setOnClickListener(v -> { drawerLayout.closeDrawers(); showRootDialog(); });

        requestStoragePermission();
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
        new AlertDialog.Builder(this)
                .setTitle("Root 权限")
                .setView(panel)
                .setPositiveButton("关闭", null)
                .show();
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

    /** 探测 su 路径 */
    private String findSuPath() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
                "/system/bin/.ext/.su", "/system/usr/we-need-root/su-backup"
        };
        for (String p : paths) {
            boolean ex = new File(p).exists();
            Log.d(TAG, "root probe: " + p + " exists=" + ex + " canRead=" + new File(p).canRead());
            if (ex) return p;
        }
        return null;
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
    private void updateBgModeLabel() {
        boolean on = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("background_mode", false);
        TextView tv = findViewById(R.id.menu_bgmode);
        if (tv != null) {
            tv.setText(on ? "后台运行模式：开" : "后台运行模式：关");
            tv.setTextColor(on ? 0xFF4CAF50 : 0xFFFFFFFF);
        }
    }

    /* ==================== 侧滑菜单功能 ==================== */

    /** dp 转 px */
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 深色输入框（纯黑观感统一） */
    private EditText createDarkEditText(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setHint(hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF707070);
        et.setBackgroundColor(0xFF1A1A1A);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        return et;
    }

    /** 深色按钮 */
    private Button createDarkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setBackgroundColor(0xFF262626);
        b.setAllCaps(false);
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
            runAdbInGuest("adb-dopair " + pp + " " + pc, resultView, statusLine);
        });
        panel.addView(pairBtn);
        new AlertDialog.Builder(this)
                .setTitle("ADB 无线调试")
                .setView(panel)
                .setNeutralButton("复制命令", (d, w) -> {
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
                })
                .show();
        // 操作逻辑优化：状态未知（首次/环境刚启动）时自动触发一次检测，免去手动点击
        if (status.contains("未知")) {
            statusLine.postDelayed(() -> runAdbInGuest("adb-autoconnect", resultView, statusLine), 600);
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
            if (procIn != null) {
                procIn.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                procIn.flush();
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
        new AlertDialog.Builder(this)
                .setTitle("API Key 配置")
                .setMessage("当前模型：deepseek-v4-flash（api.deepseek.com）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String key = input.getText().toString().trim();
                    if (key.isEmpty()) return;
                    try {
                        env.getParentFile().mkdirs();
                        java.nio.file.Files.write(env.toPath(),
                                ("DEEPSEEK_API_KEY=" + key + "\n").getBytes(StandardCharsets.UTF_8));
                        Log.d(TAG, "API key updated");
                        pushOutput("\r\n[API Key 已更新，正在重启环境...]\r\n");
                        restartEnvironment();
                    } catch (Exception e) {
                        Log.e(TAG, "save api key failed", e);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 更新 resonix：从手机选择新版文件，或恢复内置版本 */
    private void showUpdateResonixDialog() {
        EditText urlInput = createDarkEditText("reasonix 更新包链接 (.tgz)", InputType.TYPE_CLASS_TEXT);
        urlInput.setText(REASONIX_DEFAULT_URL);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, dp(8), pad, 0);
        TextView tip = createDarkTip("官方源：@reasonix/cli-linux-arm64（npm 平台包）\n"
                + "可修改下方链接更新到其它版本。\n"
                + "或从手机选择新版文件 / 恢复内置版本。");
        panel.addView(tip);
        panel.addView(urlInput);
        new AlertDialog.Builder(this)
                .setTitle("更新 resonix")
                .setView(panel)
                .setPositiveButton("网络更新", (d, w) -> {
                    String url = urlInput.getText().toString().trim();
                    if (!url.isEmpty()) updateFromNetwork(url);
                })
                .setNeutralButton("选择文件", (d, w) -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, REQ_UPDATE_RESONIX);
                    } catch (Exception e) {
                        Log.e(TAG, "open document failed", e);
                    }
                })
                .setNegativeButton("恢复内置", (d, w) -> restoreBundledResonix())
                .show();
    }

    /** 从网络下载 reasonix 更新包（tar.gz），解压提取二进制并覆盖 guest 内版本 */
    private void updateFromNetwork(String url) {
        new Thread(() -> {
            try {
                pushOutput("\r\n[正在下载 resonix 更新包...]\r\n");
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
                pushOutput("\r\n[resonix 已更新（" + rx.length() + " 字节），正在重启环境...]\r\n");
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
            pushOutput("\r\n[已恢复内置 resonix，正在重启环境...]\r\n");
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
            pushOutput("\r\n[resonix 已更新（" + rx.length() + " 字节），正在重启环境...]\r\n");
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
    protected void onResume() {
        super.onResume();
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
                new AlertDialog.Builder(this)
                        .setTitle("存储权限")
                        .setMessage("reasonix 需要\"所有文件访问\"权限才能读写手机存储的任意位置（文档、下载、非媒体文件等）。\n\n未授权时仅可访问公共媒体目录。")
                        .setPositiveButton("去授权", (d, w) -> openManageAllFilesSettings())
                        .setNegativeButton("暂不", null)
                        .show();
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
            if (prootProcess != null) {
                prootProcess.destroy();
                prootProcess = null;
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
        if (procIn == null || data == null) return;
        try {
            procIn.write(data.getBytes(StandardCharsets.UTF_8));
            procIn.flush();
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
        if (procIn == null || rows <= 0 || cols <= 0) return;
        try {
            String seq = "\u001b]50;" + rows + ";" + cols + "\u0007";
            procIn.write(seq.getBytes(StandardCharsets.UTF_8));
            procIn.flush();
        } catch (IOException e) {
            Log.w(TAG, "resize failed", e);
        }
    }

    // ------------------------------------------------------------------
    // 环境初始化与 proot 启动（后台线程）
    // ------------------------------------------------------------------

    private void startEnvironment() {
        Log.d(TAG, "startEnvironment: begin");
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
        Log.d(TAG, "reasonix config missing, showing API key dialog");
        ui.post(() -> showApiKeyDialog(rootfs, home, cfg, env));
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

    private void showApiKeyDialog(final File rootfs, final File home, final File cfg, final File env) {
        final EditText input = new EditText(this);
        input.setHint("sk-...（DeepSeek API Key）");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        new AlertDialog.Builder(this)
                .setTitle("配置 Reasonix API Key")
                .setMessage("首次使用需要 DeepSeek API Key（platform.deepseek.com 获取）。\n保存后自动进入 reasonix；也可稍后在终端运行 reasonix setup 修改。")
                .setView(input)
                .setPositiveButton("保存并启动", (d, w) -> {
                    String key = input.getText().toString().trim();
                    if (!key.isEmpty()) {
                        writeReasonixConfig(home, cfg, env, key);
                    } else {
                        Log.w(TAG, "empty API key, starting without config");
                    }
                })
                .setNegativeButton("跳过", (d, w) -> Log.d(TAG, "skipped API key dialog"))
                .setOnDismissListener(d -> safeStartProot())
                .show();
    }

    /** 写入 reasonix 配置（config.toml + .env，DeepSeek provider）；文件很小，同步执行 */
    private void writeReasonixConfig(File home, File cfg, File env, String apiKey) {
        try {
            home.mkdirs();
            String configToml = "default_model = \"deepseek-flash\"\n"
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
        // reasonix
        File rx = new File(rootfs, "usr/local/bin/reasonix");
        rx.getParentFile().mkdirs();
        extractAsset("usr/bin/reasonix", rx);
        rx.setExecutable(true, false);
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
        // SELinux 只允许 app 执行 APK native libs 目录（apk_data_file）里的 ELF，
        // 因此 proot、libtalloc、libandroid-shmem、loader 全部打包在 jniLibs，
        // 经 useLegacyPackaging 解压到 nativeLibraryDir 后从这里直接执行。
        // rootfs 内的 guest 二进制（busybox/pty-bridge/reasonix）由 proot 的
        // loader 机制读取装载，不走宿主 execve，天然绕过该限制。
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        String proot = nativeLibDir + "/proot.so";
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
        prootProcess = pb.start();
        procIn = prootProcess.getOutputStream();
        startRootPolling();   // 启动 root 命令桥轮询（guest root <cmd> → app su 执行）

        Thread reader = new Thread(() -> {
            try (InputStream in = prootProcess.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    pushOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.w(TAG, "reader ended", e);
            }
            pushOutput("\r\n[Linux 环境已退出]\r\n");
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

    /** 把文本追加到 xterm.js 终端（任意线程可调）；同时输出到 logcat 便于调试 */
    private void pushOutput(String text) {
        Log.d(TAG, "OUT> " + (text.length() > 200 ? text.substring(0, 200) : text));
        ui.post(() -> {
            if (webView == null) return;
            webView.evaluateJavascript("window.onTermData(" + jsQuote(text) + ")", null);
        });
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
        stopRootPolling();
        if (prootProcess != null) {
            prootProcess.destroy();     // pty-bridge 收到 SIGTERM 后会 kill 整个 guest 进程组
            prootProcess = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
