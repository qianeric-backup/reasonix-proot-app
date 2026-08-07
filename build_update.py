# -*- coding: utf-8 -*-
import io

p = r'C:\Users\qianeric\Documents\resonix_project\resonix app\android-app\app\src\main\java\com\rxproot\app\MainActivity.java'
s = io.open(p, encoding='utf-8').read()

# 1. import（onActivityResult 需要）
old_imp = '''import android.app.AlertDialog;
import android.content.Intent;'''
new_imp = '''import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;'''
if old_imp in s and 'OpenableColumns' not in s:
    s = s.replace(old_imp, new_imp)
    print('imports 已加')
else:
    print('imports 跳过')

# 2. 菜单监听
old_l = '''        findViewById(R.id.menu_apikey).setOnClickListener(v -> { drawerLayout.closeDrawers(); showApiKeyConfigDialog(); });'''
new_l = '''        findViewById(R.id.menu_apikey).setOnClickListener(v -> { drawerLayout.closeDrawers(); showApiKeyConfigDialog(); });
        findViewById(R.id.menu_update).setOnClickListener(v -> { drawerLayout.closeDrawers(); showUpdateResonixDialog(); });'''
assert old_l in s, 'listener anchor'
s = s.replace(old_l, new_l)
print('监听已加')

# 3. 新增方法（在 showApiKeyConfigDialog 后插入）
anchor = '''    /** CLI 参数：显示 reasonix 当前配置 */'''
methods = '''    /** 更新 resonix：从手机选择新版文件，或恢复内置版本 */
    private void showUpdateResonixDialog() {
        new AlertDialog.Builder(this)
                .setTitle("更新 resonix")
                .setMessage("选择更新方式：\\n\\n"
                        + "1. 从手机选择新版 resonix 文件（推荐）：\\n"
                        + "   将新版二进制放到手机，点下方「选择文件」\\n\\n"
                        + "2. 恢复内置版本：\\n"
                        + "   从 APK 自带版本覆盖（用于误更新后还原）\\n\\n"
                        + "更新后会自动重启 Linux 环境。")
                .setPositiveButton("选择文件", (d, w) -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, REQ_UPDATE_RESONIX);
                    } catch (Exception e) {
                        Log.e(TAG, "open document failed", e);
                    }
                })
                .setNeutralButton("恢复内置", (d, w) -> restoreBundledResonix())
                .setNegativeButton("取消", null)
                .show();
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
            pushOutput("\\r\\n[已恢复内置 resonix，正在重启环境...]\\r\\n");
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
            pushOutput("\\r\\n[resonix 已更新（" + rx.length() + " 字节），正在重启环境...]\\r\\n");
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

'''
assert anchor in s, 'method anchor'
s = s.replace(anchor, methods + anchor)
print('方法已加')

# 4. 常量
old_c = '''    private static final String TAG = "ReasonixProot";'''
new_c = '''    private static final String TAG = "ReasonixProot";
    private static final int REQ_UPDATE_RESONIX = 200;'''
assert old_c in s
s = s.replace(old_c, new_c)
print('常量已加')

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('MainActivity 更新功能完成')
