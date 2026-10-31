package app.facet.kit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int ICON_PX = 256;

    private SharedPreferences sp;
    private boolean prefsShared;
    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private AppAdapter adapter;
    private TextView status;
    private String query = "";

    // 编辑对话框状态
    private String editPkg;
    private AlertDialog progressDialog;
    private Set<String> editFolds = new java.util.LinkedHashSet<>();
    private SharedPreferences state;
    private ImageView editPreview;
    private String editIcon;      // 新选的图标（base64），null 表示未改动
    private boolean editIconCleared;

    /** 模块被 LSPosed 加载后会被 Hook 成返回 true。 */
    public static boolean isModuleActive() {
        return false;
    }

    static final class AppItem {
        String pkg;      // 真正的包名
        String key;      // 配置的键：包名，分身是 包名@用户ID
        int userId;
        String label;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            sp = getSharedPreferences(Const.PREFS, Context.MODE_WORLD_READABLE);
            prefsShared = true;
        } catch (SecurityException e) {
            sp = getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE);
            prefsShared = false;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, 0);

        status = new TextView(this);
        status.setTextSize(13);
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("重启桌面", v -> restart(homePackage())), weight());
        buttons.addView(button("重启系统界面", v -> restart("com.android.systemui")), weight());
        buttons.addView(button("检查更新", v -> checkUpdate(true)), weight());
        root.addView(buttons);
        // 澎湃 OS 4 的原生桌面 LSPosed 进不去，图标只能写进主题图标包；换主题后由 ThemeReceiver 自动重写
        root.addView(button("全部自定义图标写入桌面（澎湃 OS 4；换主题后会自动重写）", v -> syncThemeIcons()));

        EditText search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filter();
            }
        });
        root.addView(search);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showEditor(shown.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        state = getSharedPreferences("state", Context.MODE_PRIVATE);
        //noinspection ResultOfMethodCallIgnored
        new java.io.File(getCacheDir(), "update.apk").delete();   // 上次更新留下的安装包
        IntentFilter f = new IntentFilter(Updater.ACTION_INSTALL_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(installReceiver, f);
        }
        updateStatus();
        loadApps();
        // 每 12 小时静默检查一次，只有发现新版本才会打扰
        if (System.currentTimeMillis() - state.getLong("last_check", 0) > 12L * 3600 * 1000) {
            checkUpdate(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(installReceiver);
        if (progressDialog != null) progressDialog.dismiss();
    }

    // ---------------------------------------------------------------- 更新

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void checkUpdate(boolean manual) {
        if (manual) toast("正在检查更新…");
        new Thread(() -> {
            try {
                Updater.Release r = Updater.fetchLatest();
                state.edit().putLong("last_check", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (Updater.isNewer(r.tag, versionName())) {
                        showUpdateDialog(r);
                    } else if (manual) {
                        toast("已是最新版本 v" + versionName());
                    }
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> toast("检查更新失败：" + e.getMessage()
                        + "\n（GitHub 需要能访问外网）"));
            }
        }).start();
    }

    private void showUpdateDialog(Updater.Release r) {
        String notes = r.notes.length() > 800 ? r.notes.substring(0, 800) + "…" : r.notes;
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + r.tag)
                .setMessage("当前 v" + versionName() + (notes.isEmpty() ? "" : "\n\n" + notes))
                .setPositiveButton("下载并安装", (d, w) -> startDownload(r))
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void startDownload(Updater.Release r) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("请先允许本应用安装应用，授权后再点一次检查更新");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        TextView msg = new TextView(this);
        msg.setPadding(dp(24), dp(16), dp(24), dp(8));
        msg.setText("准备下载…");
        progressDialog = new AlertDialog.Builder(this).setTitle("正在更新 " + r.tag)
                .setView(msg).setCancelable(false).show();
        new Thread(() -> {
            try {
                java.io.File apk = Updater.download(this, r, (done, total) -> runOnUiThread(() ->
                        msg.setText(total > 0
                                ? String.format(Locale.ROOT, "已下载 %d%%（%.1f / %.1f MB）",
                                done * 100 / total, done / 1048576f, total / 1048576f)
                                : String.format(Locale.ROOT, "已下载 %.1f MB", done / 1048576f))));
                runOnUiThread(() -> msg.setText("正在交给系统安装…"));
                Updater.install(this, apk);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    toast("更新失败：" + e.getMessage());
                });
            }
        }).start();
    }

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation") Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            if (progressDialog != null) progressDialog.dismiss();
            if (status != PackageInstaller.STATUS_SUCCESS) {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                toast("安装失败：" + (m == null ? "状态 " + status : m));
            }
        }
    };

    /** 打开应用时补一次同步。
     *  MIUI 会冻结后台进程，system_server 发来的「主题包变了」广播可能一直没被执行，
     *  导致图标掉了之后再也回不来；这里在每次启动进程后兜底一次。 */
    private static boolean resumeSynced;

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeSynced) return;
        resumeSynced = true;
        new Thread(() -> {
            try {
                ThemeIcons.sync(MainActivity.this, false);
            } catch (Throwable ignored) {
                // 补同步失败不影响界面
            }
        }).start();
    }

    private void updateStatus() {
        int count = 0;
        Set<String> pkgs = new HashSet<>();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(Const.NAME)) pkgs.add(k.substring(Const.NAME.length()));
            if (k.startsWith(Const.ICON)) pkgs.add(k.substring(Const.ICON.length()));
            if (k.startsWith(Const.FOLD) && !foldOf(k.substring(Const.FOLD.length())).isEmpty()) pkgs.add(k.substring(Const.FOLD.length()));
            if (k.startsWith(Const.COUNTRY)) pkgs.add(k.substring(Const.COUNTRY.length()));
            if (k.startsWith(Const.MAKER)) pkgs.add(k.substring(Const.MAKER.length()));
        }
        count = pkgs.size();
        String s;
        if (!isModuleActive()) {
            s = "⚠ 模块未激活：请在 LSPosed 里启用本模块并勾选作用域，然后重启手机。";
        } else if (!prefsShared) {
            s = "⚠ 配置无法共享给系统（LSPosed 版本过旧？）";
        } else {
            s = "✓ 模块已激活，已自定义 " + count + " 个应用。修改后点下面按钮让桌面/通知生效。";
        }
        status.setText(s + "\n版本 v" + versionName() + " · GitHub: " + Updater.REPO);
    }

    private static final String[][] SYSTEM_NOTIFIERS = {
            {"android", "Android 系统（LSPosed / Vector 通知）"},
            {"com.android.shell", "Shell（adb / 调试通知）"},
    };

    private void loadApps() {
        new Thread(() -> {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            PackageManager pm = getPackageManager();
            Set<String> seen = new HashSet<>();
            List<AppItem> items = new ArrayList<>();
            for (UserHandle u : la.getProfiles()) {
                int uid = userIdOf(u);
                List<LauncherActivityInfo> list;
                try {
                    list = la.getActivityList(null, u);
                } catch (Exception e) {
                    continue;
                }
                for (LauncherActivityInfo info : list) {
                    String pkg = info.getComponentName().getPackageName();
                    String key = uid == 0 ? pkg : pkg + "@" + uid;
                    if (Const.SELF.equals(pkg) || !seen.add(key)) continue;
                    AppItem it = new AppItem();
                    it.pkg = pkg;
                    it.userId = uid;
                    it.key = key;
                    it.label = String.valueOf(pm.getUserBadgedLabel(info.getLabel(), u));
                    it.icon = info.getBadgedIcon(0);
                    items.add(it);
                }
            }
            // 没有桌面图标、但会发通知的系统包（比如 LSPosed / Vector 的「已加载」通知是以 android 的名义发的）
            for (String[] sys : SYSTEM_NOTIFIERS) {
                if (!seen.add(sys[0])) continue;
                try {
                    AppItem it = new AppItem();
                    it.pkg = sys[0];
                    it.userId = 0;
                    it.key = sys[0];
                    it.label = sys[1];
                    it.icon = pm.getApplicationIcon(sys[0]);
                    items.add(it);
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> {
                all.clear();
                all.addAll(items);
                filter();
            });
        }).start();
    }

    private static int userIdOf(UserHandle u) {
        try {
            return (Integer) UserHandle.class.getMethod("getIdentifier").invoke(u);
        } catch (Exception e) {
            return u.hashCode();
        }
    }

    /** 该应用已设置折叠的通知渠道（逗号分隔）；旧版本留下的布尔值当作没设置。 */
    private String foldOf(String key) {
        Object v = sp.getAll().get(Const.FOLD + key);
        return v instanceof String ? (String) v : "";
    }

    private String foldSummary() {
        return editFolds.isEmpty() ? "折叠的通知渠道：无（所有通知照常提醒）"
                : "折叠的通知渠道：" + android.text.TextUtils.join("、", editFolds);
    }

    /** 用 root 读系统里这个应用已有的通知渠道，让你勾选要折叠哪几个。 */
    private void chooseChannels(AppItem item, TextView foldView) {
        toast("正在读取通知渠道…");
        new Thread(() -> {
            List<String[]> channels = new ArrayList<>();
            String error = null;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "dumpsys notification --noredact"});
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                java.util.regex.Pattern app = java.util.regex.Pattern.compile("AppSettings: (\\S+) \\((\\d+)\\)");
                java.util.regex.Pattern ch = java.util.regex.Pattern.compile(
                        "NotificationChannel\\{mId='([^']*)', mName=(.*?), mDescription=");
                boolean in = false;
                Set<String> seenIds = new HashSet<>();
                String line;
                while ((line = r.readLine()) != null) {
                    java.util.regex.Matcher m = app.matcher(line);
                    if (m.find()) {
                        in = item.pkg.equals(m.group(1)) && Long.parseLong(m.group(2)) / 100000 == item.userId;
                        continue;
                    }
                    if (!in) continue;
                    java.util.regex.Matcher c = ch.matcher(line);
                    if (c.find() && !line.contains("mDeleted=true") && seenIds.add(c.group(1))) {
                        channels.add(new String[]{c.group(1), c.group(2)});
                    }
                }
                p.waitFor();
            } catch (Exception e) {
                error = "需要 Root 才能读取通知渠道（" + e.getMessage() + "）";
            }
            String err = error;
            runOnUiThread(() -> {
                if (err != null) { toast(err); return; }
                if (channels.isEmpty()) { toast("没有找到这个应用的通知渠道（它可能还没发过通知）"); return; }
                String[] labels = new String[channels.size()];
                boolean[] checked = new boolean[channels.size()];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = channels.get(i)[1] + "  (" + channels.get(i)[0] + ")";
                    checked[i] = editFolds.contains(channels.get(i)[0]);
                }
                new AlertDialog.Builder(this)
                        .setTitle("折叠哪些通知渠道？")
                        .setMultiChoiceItems(labels, checked, (d, which, on) -> {
                            String id = channels.get(which)[0];
                            if (on) editFolds.add(id); else editFolds.remove(id);
                        })
                        .setPositiveButton("确定", (d, w) -> foldView.setText(foldSummary()))
                        .setNegativeButton("取消", (d, w) -> { })
                        .show();
            });
        }).start();
    }

    private boolean isCustomized(String pkg) {
        return sp.contains(Const.NAME + pkg) || sp.contains(Const.ICON + pkg) || !foldOf(pkg).isEmpty()
                || sp.contains(Const.COUNTRY + pkg) || sp.contains(Const.MAKER + pkg);
    }

    private void filter() {
        shown.clear();
        for (AppItem it : all) {
            if (query.isEmpty() || it.pkg.toLowerCase(Locale.ROOT).contains(query)
                    || it.label.toLowerCase(Locale.ROOT).contains(query)
                    || sp.getString(Const.NAME + it.key, "").toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(it);
            }
        }
        Collections.sort(shown, (a, b) -> {
            boolean ca = isCustomized(a.key), cb = isCustomized(b.key);
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- 编辑

    private void showEditor(AppItem item) {
        editPkg = item.pkg;
        editIcon = null;
        editIconCleared = false;
        // 记录打开弹窗时的初始值：保存时只有图标/范围变了才写主题包，避免每次保存都重启桌面
        final String initIcon = sp.getString(Const.ICON + item.key, null);
        final String initMode = sp.getString(Const.MODE + item.key, Const.MODE_ALL);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        TextView pkgView = new TextView(this);
        pkgView.setText("原名：" + item.label + "\n包名：" + item.pkg + (item.userId != 0 ? "\n分身用户：" + item.userId : ""));
        pkgView.setTextSize(12);
        box.addView(pkgView);

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("新名称（留空 = 用原名）");
        name.setText(sp.getString(Const.NAME + item.key, ""));
        box.addView(name);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.setPadding(0, dp(12), 0, 0);
        editPreview = new ImageView(this);
        String cur = sp.getString(Const.ICON + item.key, null);
        Bitmap curBmp = decode(cur);
        if (curBmp != null) editPreview.setImageBitmap(curBmp); else editPreview.setImageDrawable(item.icon);
        iconRow.addView(editPreview, new LinearLayout.LayoutParams(dp(64), dp(64)));
        Button pick = button("选择图片", v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选择图标图片"), REQ_PICK);
        });
        Button reset = button("恢复原图标", v -> {
            editIcon = null;
            editIconCleared = true;
            editPreview.setImageDrawable(item.icon);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(12);
        iconRow.addView(pick, lp);
        iconRow.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        box.addView(iconRow);

        // 替换范围：全部 / 仅桌面 / 仅通知等；选好范围后点「保存」会自动应用
        final String[] modeLabels = {"全部（桌面 + 通知等）", "仅桌面", "仅通知 / 设置 / 最近任务"};
        final String[] modeValues = {Const.MODE_ALL, Const.MODE_DESK, Const.MODE_NOTIF};
        Spinner modeSpin = new Spinner(this);
        modeSpin.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modeLabels));
        String curMode = sp.getString(Const.MODE + item.key, Const.MODE_ALL);
        for (int mi = 0; mi < modeValues.length; mi++) {
            if (modeValues[mi].equals(curMode)) modeSpin.setSelection(mi);
        }
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(0, dp(8), 0, 0);
        modeRow.addView(modeSpin, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(modeRow);

        CheckBox shape = new CheckBox(this);
        shape.setText("套用当前主题的图标形状");
        shape.setChecked(sp.getBoolean(Const.SHAPE + item.key, true));
        box.addView(shape);

        // 只折叠指定的通知渠道（比如常驻的「后台连接」），别的渠道（新消息等）照常提醒
        editFolds = new java.util.LinkedHashSet<>();
        for (String c : foldOf(item.key).split(",")) {
            if (!c.trim().isEmpty()) editFolds.add(c.trim());
        }
        TextView foldView = new TextView(this);
        foldView.setPadding(0, dp(8), 0, 0);
        foldView.setText(foldSummary());
        box.addView(foldView);
        box.addView(button("选择要折叠进「更多通知」的通知渠道", v -> chooseChannels(item, foldView)));

        // 只改这个应用自己读到的 SIM / 网络国家码；需要在 LSPosed 里把它加进作用域
        EditText country = new EditText(this);
        country.setSingleLine(true);
        country.setHint("伪装国家码，如 us（留空 = 不改）");
        country.setText(sp.getString(Const.COUNTRY + item.key, ""));
        box.addView(country);

        EditText maker = new EditText(this);
        maker.setSingleLine(true);
        maker.setHint("伪装厂商，如 samsung（留空 = 不改）");
        maker.setText(sp.getString(Const.MAKER + item.key, ""));
        box.addView(maker);

        new AlertDialog.Builder(this)
                .setTitle("自定义 " + item.label)
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String cc = country.getText().toString().trim().toLowerCase(Locale.ROOT);
                    if (!cc.isEmpty() && !cc.matches("[a-z]{2}")) {
                        toast("国家码要是两位字母，比如 us、hk、gb；其它设置未保存");
                        return;
                    }
                    SharedPreferences.Editor e = sp.edit();
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) e.remove(Const.NAME + item.key); else e.putString(Const.NAME + item.key, n);
                    if (editIcon != null) e.putString(Const.ICON + item.key, editIcon);
                    else if (editIconCleared) e.remove(Const.ICON + item.key);
                    e.putBoolean(Const.SHAPE + item.key, shape.isChecked());
                    if (editFolds.isEmpty()) e.remove(Const.FOLD + item.key);
                    else e.putString(Const.FOLD + item.key, android.text.TextUtils.join(",", editFolds));
                    if (cc.isEmpty()) e.remove(Const.COUNTRY + item.key); else e.putString(Const.COUNTRY + item.key, cc);
                    String mk = maker.getText().toString().trim();
                    if (mk.isEmpty()) e.remove(Const.MAKER + item.key); else e.putString(Const.MAKER + item.key, mk);
                    // 顺带把「替换范围」也存下来
                    String pickedMode = modeValues[modeSpin.getSelectedItemPosition()];
                    if (Const.MODE_ALL.equals(pickedMode)) e.remove(Const.MODE + item.key);
                    else e.putString(Const.MODE + item.key, pickedMode);
                    e.commit();

                    boolean desk = !Const.MODE_NOTIF.equals(pickedMode);
                    // 只提示需要重启的情况；不需要重启的就说"修改成功"；设置类位置需重启系统界面
                    String tip = desk ? "已应用，桌面已重启（系统界面需重启）" : "修改成功（系统界面需重启）";

                    boolean iconChanged = !String.valueOf(initIcon)
                            .equals(String.valueOf(sp.getString(Const.ICON + item.key, null)));
                    boolean modeChanged = !pickedMode.equals(initMode);

                    if (editIconCleared && !item.key.contains("@")) {
                        // 恢复原图标：主题包里可能还留着上次写进去的图标，一并还原
                        applyIcon(item, Const.MODE_NOTIF, "已恢复，桌面已重启（系统界面需重启）");
                    } else if (!item.key.contains("@") && sp.contains(Const.ICON + item.key)
                            && (iconChanged || modeChanged)) {
                        applyIcon(item, pickedMode, tip);
                    } else {
                        toast(tip);
                    }
                    if (!cc.isEmpty() || !mk.isEmpty()) {
                        toast("国家码 / 厂商要在 LSPosed 里把该应用加进作用域，并强行停止它后重新打开才生效");
                    }
                    updateStatus();
                    filter();
                })
                .setNeutralButton("全部恢复", (d, w) -> {
                    sp.edit().remove(Const.NAME + item.key).remove(Const.ICON + item.key)
                            .remove(Const.SHAPE + item.key).remove(Const.FOLD + item.key)
                            .remove(Const.COUNTRY + item.key).remove(Const.MAKER + item.key)
                            .remove(Const.MODE + item.key).commit();
                    if (!item.key.contains("@")) {
                        new Thread(() -> ThemeIcons.removeOne(MainActivity.this, item.key)).start();
                    }
                    toast("已恢复默认");
                    updateStatus();
                    filter();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按范围把图标落到实际位置；完成后用 prefix 作为提示前缀。 */
    private void applyIcon(AppItem item, String mode, String prefix) {
        boolean miuiHome = "com.miui.home".equals(ThemeIcons.homePackage(this));
        if (!miuiHome) {
            // 非澎湃 / 非 OS4：桌面由 Hook 负责，需要手动重启才能看到效果
            toast("已应用，请重启桌面与系统界面");
            return;
        }
        new Thread(() -> {
            String r = Const.MODE_NOTIF.equals(mode)
                    ? ThemeIcons.removeOne(this, item.key)   // 仅通知 → 把桌面那条撤掉
                    : ThemeIcons.syncOne(this, item.key);    // 全部 / 仅桌面 → 写主题包
            // 成功时只说状态；没有实际改动时不要误报"已重启"；失败时把原因原样给出来
            boolean ok = r.contains("已写入") || r.contains("已还原") || r.contains("已清除");
            boolean noop = r.contains("没有需要") || r.contains("已是最新");
            runOnUiThread(() -> toast(noop ? "修改成功" : ok ? prefix : r));
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Bitmap b = loadSquare(data.getData());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            editIcon = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            editIconCleared = false;
            if (editPreview != null) editPreview.setImageBitmap(b);
        } catch (Exception e) {
            toast("读取图片失败：" + e.getMessage());
        }
    }

    private Bitmap loadSquare(Uri uri) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
        Bitmap b = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int min = Math.min(info.getSize().getWidth(), info.getSize().getHeight());
            int sample = 1;
            while (min / (sample * 2) >= ICON_PX) sample *= 2;
            decoder.setTargetSampleSize(sample);
        });
        int size = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - size) / 2, (b.getHeight() - size) / 2, size, size);
        return Bitmap.createScaledBitmap(sq, ICON_PX, ICON_PX, true);
    }

    private static Bitmap decode(String b64) {
        if (b64 == null) return null;
        try {
            byte[] d = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(d, 0, d.length);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 重启

    /** 当前默认桌面的包名：小米是 com.miui.home，moto 是 com.motorola.launcher3。 */
    private String homePackage() {
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
            return ri.activityInfo.packageName;
        }
        return "com.miui.home";
    }

    private void restart(String pkg) {
        new Thread(() -> {
            String msg;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "killall " + pkg});
                String err = new String(readFully(p.getErrorStream())).trim();
                int code = p.waitFor();
                if (code == 0) {
                    msg = "已重启 " + pkg;
                } else if (err.contains("no process")) {
                    msg = pkg + " 当前没有在运行，无需重启";
                } else {
                    msg = "重启失败（退出码 " + code + "）" + (err.isEmpty() ? "" : "：" + err);
                }
            } catch (java.io.IOException e) {
                // su 根本启动不了：没授权，或者设备没有 Root
                msg = "无法获取 Root（" + e.getMessage() + "）\n"
                        + "请在 KernelSU / Magisk 的超级用户列表里给「" + getString(R.string.app_name) + "」授权后再试";
            } catch (Exception e) {
                msg = "重启失败：" + e;
            }
            String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
    }

    // ---------------------------------------------------------------- 主题图标包（澎湃 OS 4 桌面）

    /** 手动写入：不管包里是不是已经是这版配置，都重写一次并重启桌面。 */
    private void syncThemeIcons() {
        toast("正在写入桌面图标…");
        new Thread(() -> {
            String m = ThemeIcons.sync(this, true);
            runOnUiThread(() -> toast(m));
        }).start();
    }

    private static byte[] readFully(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ---------------------------------------------------------------- UI 工具

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                ImageView iv = new ImageView(MainActivity.this);
                row.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(12), 0, 0, 0);
                TextView t1 = new TextView(MainActivity.this);
                t1.setTextSize(16);
                TextView t2 = new TextView(MainActivity.this);
                t2.setTextSize(12);
                t2.setAlpha(0.7f);
                texts.addView(t1);
                texts.addView(t2);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            } else {
                row = (LinearLayout) convert;
            }
            AppItem it = shown.get(pos);
            ImageView iv = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            TextView t1 = (TextView) texts.getChildAt(0);
            TextView t2 = (TextView) texts.getChildAt(1);
            Bitmap custom = decode(sp.getString(Const.ICON + it.key, null));
            if (custom != null) iv.setImageBitmap(custom); else iv.setImageDrawable(it.icon);
            String alias = sp.getString(Const.NAME + it.key, null);
            t1.setText(alias != null ? alias + "  ←  " + it.label : it.label);
            t2.setText(it.pkg + (it.userId != 0 ? " · 分身" : "") + (custom != null ? " · 已换图标" : "")
                    + (foldOf(it.key).isEmpty() ? "" : " · 折叠：" + foldOf(it.key))
                    + (sp.contains(Const.COUNTRY + it.key) ? " · 国家：" + sp.getString(Const.COUNTRY + it.key, "") : "")
                    + (sp.contains(Const.MAKER + it.key) ? " · 厂商：" + sp.getString(Const.MAKER + it.key, "") : ""));
            return row;
        }
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
