package com.rsxm.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * reasonix MCP 服务器配置管理（宿主侧直接读写当前项目根的 .mcp.json）。
 *
 * reasonix CLI 按当前项目根目录的 .mcp.json 发现 MCP 服务器，格式：
 * <pre>
 * {
 *   "mcpServers": {
 *     "本地服务": { "type": "stdio", "command": "node", "args": ["server.js"], "env": {"K": "V"} },
 *     "远程服务": { "type": "http", "url": "https://example.com/mcp", "headers": {"Authorization": "Bearer x"} }
 *   }
 * }
 * </pre>
 * 项目根 = /root/.rsxm-project 标记指定的目录（缺省 /root）。宿主侧把 guest 路径换算成
 * 真实存储路径后直接读写：proot rootfs 内路径加 rootfs 前缀；/sdcard → /storage/emulated/0；
 * /host-data → app 私有数据目录。chroot 模式下文件可能为 root 属主导致宿主直读直写失败，
 * 此时经 root 命令桥（execRootCommand）兜底。
 *
 * 说明：sse 类型在 reasonix 中与 http 同形态（url + headers），本面板归并为 http 选项，
 * 编辑已存在的 sse 条目时保留其原 type。
 */
public final class McpManager {
    private static final String TAG = "McpManager";

    /** 单个 MCP 服务器定义（UI 层可直接读改字段） */
    public static class ServerSpec {
        public String name;                 // mcpServers 键（服务器名）
        public String type = "stdio";       // stdio | http | sse
        public String command = "";         // stdio：可执行命令
        public String args = "";            // stdio：参数（空格分隔展示）
        public String env = "";             // stdio：环境变量 JSON 对象文本（可空）
        public String url = "";             // http/sse：服务地址
        public String headers = "";         // http/sse：请求头 JSON 对象文本（可空）
        public boolean autoStart = true;    // false = 仅手动启动（reasonix mcp start），默认随会话启动
    }

    private McpManager() {}

    /** guest 项目根目录（/root/.rsxm-project 标记内容，缺省 /root） */
    public static String projectDir(Context ctx) {
        try {
            File mark = new File(new File(new File(ctx.getFilesDir(), "rootfs"), "root"), ".rsxm-project");
            if (mark.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(mark.toPath()),
                        StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Exception ignored) {}
        return "/root";
    }

    /** guest 路径 → 宿主真实路径（/sdcard→共享存储、/host-data→app 私有目录、其余加 rootfs 前缀） */
    public static File hostPath(Context ctx, String guestPath) {
        if (guestPath.startsWith("/sdcard/"))
            return new File("/storage/emulated/0", guestPath.substring("/sdcard/".length()));
        if (guestPath.startsWith("/host-data/")) {
            File data = ctx.getFilesDir().getParentFile();
            return new File(data, guestPath.substring("/host-data/".length()));
        }
        return new File(new File(ctx.getFilesDir(), "rootfs"), guestPath);
    }

    /** 项目根 .mcp.json 对应的宿主文件 */
    public static File mcpFile(Context ctx) {
        return new File(hostPath(ctx, projectDir(ctx)), ".mcp.json");
    }

    /** 读取全部 MCP 服务器（宿主直读失败时经 root 桥兜底）；无配置/失败返回空列表 */
    public static List<ServerSpec> loadServers(Context ctx) {
        List<ServerSpec> out = new ArrayList<>();
        try {
            String text = readFileAny(ctx, mcpFile(ctx));
            if (text == null || text.trim().isEmpty()) return out;
            JSONObject root = new JSONObject(text);
            JSONObject servers = root.optJSONObject("mcpServers");
            if (servers == null) return out;
            Iterator<String> keys = servers.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject o = servers.optJSONObject(k);
                if (o != null) out.add(parse(k, o));
            }
        } catch (Exception e) {
            Log.w(TAG, "load mcp servers failed", e);
        }
        return out;
    }

    /** 保存全部 MCP 服务器；返回 null=成功，否则为错误消息（保留原文件 mcpServers 之外的顶层字段） */
    public static String saveServers(Context ctx, List<ServerSpec> list) {
        try {
            JSONObject servers = new JSONObject();
            for (ServerSpec s : list) servers.put(s.name, toJson(s));
            JSONObject root = new JSONObject();
            root.put("mcpServers", servers);
            try {
                String old = readFileAny(ctx, mcpFile(ctx));
                if (old != null) {
                    JSONObject or = new JSONObject(old);
                    Iterator<String> it = or.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (!k.equals("mcpServers")) root.put(k, or.get(k));
                    }
                }
            } catch (Exception ignored) {}
            return writeFileAny(ctx, mcpFile(ctx), root.toString(2) + "\n");
        } catch (Exception e) {
            return "生成 .mcp.json 失败: " + e.getMessage();
        }
    }

    /** spec → mcpServers 条目（省略默认值字段，配置文件保持精简可读） */
    public static JSONObject toJson(ServerSpec s) throws Exception {
        JSONObject o = new JSONObject();
        boolean http = "http".equals(s.type) || "sse".equals(s.type);
        o.put("type", s.type);
        if (http) {
            o.put("url", s.url);
            if (!s.headers.trim().isEmpty()) o.put("headers", new JSONObject(s.headers));
        } else {
            o.put("command", s.command);
            if (!s.args.trim().isEmpty()) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String a : s.args.trim().split("\\s+")) arr.put(a);
                o.put("args", arr);
            }
            if (!s.env.trim().isEmpty()) o.put("env", new JSONObject(s.env));
        }
        if (!s.autoStart) o.put("auto_start", false);
        return o;
    }

    /** mcpServers 条目 → spec */
    public static ServerSpec parse(String name, JSONObject o) {
        ServerSpec s = new ServerSpec();
        s.name = name;
        s.type = o.optString("type", "stdio");
        s.command = o.optString("command", "");
        org.json.JSONArray arr = o.optJSONArray("args");
        if (arr != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(arr.optString(i));
            }
            s.args = sb.toString();
        }
        JSONObject env = o.optJSONObject("env");
        s.env = env != null ? env.toString() : "";
        s.url = o.optString("url", "");
        JSONObject hd = o.optJSONObject("headers");
        s.headers = hd != null ? hd.toString() : "";
        s.autoStart = o.optBoolean("auto_start", true);
        return s;
    }

    /** 读文件：宿主直读，失败（chroot root 属主/权限）经 MainActivity root 命令桥兜底 */
    private static String readFileAny(Context ctx, File f) {
        try {
            if (f.exists() && f.canRead())
                return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        MainActivity act = MainActivity.current();
        if (act == null) return null;
        String out = act.execRootCommand("cat '" + sq(f.getAbsolutePath()) + "' 2>/dev/null", 8);
        return isExecFailure(out) ? null : out;
    }

    /** 写文件：宿主直写，失败经 root 桥引用式 heredoc 兜底；返回 null=成功 */
    private static String writeFileAny(Context ctx, File f, String content) {
        try {
            File parent = f.getParentFile();
            if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                java.nio.file.Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
                return null;
            }
        } catch (Exception ignored) {}
        // root 桥兜底：'MCP_EOF' 引用式 heredoc 不做变量展开；JSON 序列化不会产生独立的
        // MCP_EOF 行，结束标记安全
        MainActivity act = MainActivity.current();
        if (act == null) return "写入 .mcp.json 失败（无 root 兜底通道）";
        String cmd = "mkdir -p '" + sq(f.getParent()) + "' && cat > '" + sq(f.getAbsolutePath())
                + "' <<'MCP_EOF'\n" + content + "MCP_EOF\n";
        String out = act.execRootCommand(cmd, 10);
        return isExecFailure(out) ? "写入 .mcp.json 失败（root 桥返回错误）" : null;
    }

    /** root 桥执行结果是否为失败/超时（execRootCommand 约定的错误文本） */
    private static boolean isExecFailure(String out) {
        return out == null || out.startsWith("(超时") || out.startsWith("(执行失败") || out.startsWith("(root");
    }

    /** shell 单引号转义（root 桥命令拼接防注入/防路径含引号破坏命令） */
    private static String sq(String s) {
        return s.replace("'", "'\\''");
    }
}
