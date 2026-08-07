package com.rxproot.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        findViewById(R.id.menu_cli).setOnClickListener(v -> { drawerLayout.closeDrawers(); showCliParamsDialog(); });

        requestStoragePermission();
    }

    /* ==================== 侧滑菜单功能 ==================== */

    /** ADB 无线调试：显示局域网 IP 与连接指引 */
    private void showAdbDialog() {
        String ip = getLocalIpAddress();
        StringBuilder msg = new StringBuilder();
        if (ip != null) {
            msg.append("本机局域网 IP：").append(ip).append("\n\n");
        } else {
            msg.append("未获取到局域网 IP（请连接 Wi-Fi）。\n\n");
        }
        msg.append("在电脑上执行：\n")
           .append("  adb connect ").append(ip != null ? ip : "<IP>").append(":5555\n\n")
           .append("前提：本机已开启「无线调试」（开发者选项 -> 无线调试），\n")
           .append("或用数据线执行过一次 adb tcpip 5555。\n\n")
           .append("Android 11+ 也可直接打开无线调试设置页配对。");
        new AlertDialog.Builder(this)
                .setTitle("ADB 无线调试")
                .setMessage(msg)
                .setPositiveButton("打开无线调试设置", (d, w) -> {
                    try {
                        startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"));
                    } catch (Exception e) {
                        Log.w(TAG, "cannot open wireless debugging settings", e);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
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
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setHint("粘贴 DeepSeek API Key");
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

    /** CLI 参数：显示 reasonix 当前配置 */
    private void showCliParamsDialog() {
        File rootfs = new File(getFilesDir(), "rootfs");
        File home = new File(rootfs, "root/.reasonix");
        StringBuilder sb = new StringBuilder();
        File cfg = new File(home, "config.toml");
        if (cfg.exists()) {
            sb.append("--- config.toml ---\n");
            try {
                sb.append(new String(java.nio.file.Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) { sb.append("(读取失败)\n"); }
        }
        File env = new File(home, ".env");
        if (env.exists()) {
            sb.append("\n--- .env ---\n");
            try {
                for (String line : new String(java.nio.file.Files.readAllBytes(env.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    if (line.startsWith("DEEPSEEK_API_KEY=")) {
                        String k = line.substring("DEEPSEEK_API_KEY=".length());
                        sb.append("DEEPSEEK_API_KEY=")
                          .append(k.length() > 8 ? k.substring(0, 4) + "****" + k.substring(k.length() - 4) : "****")
                          .append("\n");
                    } else {
                        sb.append(line).append("\n");
                    }
                }
            } catch (Exception e) { sb.append("(读取失败)\n"); }
        }
        new AlertDialog.Builder(this)
                .setTitle("CLI 参数")
                .setMessage(sb.length() > 0 ? sb.toString() : "(环境尚未初始化，首次启动后生成)")
                .setPositiveButton("关闭", null)
                .show();
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
                fo.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\n".getBytes(StandardCharsets.UTF_8));
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
