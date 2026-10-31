package app.facet.kit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 澎湃 OS 4 的原生桌面 LSPosed 进不去，图标只能写进主题图标包 /data/system/theme/icons（一个 zip）。
 * 换主题会重新生成这个包，所以 system_server 里的 Hook 发现它变了就通知 {@link ThemeReceiver} 再写一次。
 *
 * 本版本在原实现上补了三点：
 * 1. 主题包不存在时（新装机 / 从未应用过图标主题）自动用 {@link #DEFAULT_ICONS} 播种基底，不再直接报错；
 * 2. 记录「写过哪些应用」和「每个应用的原图」（facet/written、facet/orig/&lt;pkg&gt;），
 *    取消自定义 / 恢复原图标后能从主题包里真正还原，而不是留下旧图标；
 * 3. 支持只写入单个应用（{@link #syncOne}）和只移除单个应用（{@link #removeOne}）。
 *
 * 换主题后的自动重写（force=false）只维护「上次已经写入过」的那批应用，
 * 不会把用户故意留在桌面默认的应用也写回去。
 */
final class ThemeIcons {
    static final String THEME_DIR = "/data/system/theme";
    static final String THEME_ICONS = THEME_DIR + "/icons";
    /** 系统自带的默认图标包（只有一个 transform_config.xml 的空壳），主题包缺失时用它当基底。 */
    static final String DEFAULT_ICONS = "/product/media/theme/default/icons";
    static final String ACTION_CHANGED = "app.facet.kit.THEME_ICONS_CHANGED";
    private static final String ICON_DIR = "res/drawable-xxhdpi/";
    private static final String STAMP = "facet/stamp";
    /** 写入记录：每行一个包名，表示主题包里哪些图标是本模块写的。 */
    private static final String WRITTEN = "facet/written";
    /** 原图备份：facet/orig/&lt;包名&gt;，用于还原。 */
    private static final String ORIG_DIR = "facet/orig/";

    private ThemeIcons() {}

    /** 全量同步：把当前所有自定义图标写进去（首页按钮 / 换主题后的自动重写）。 */
    static synchronized String sync(Context ctx, boolean force) {
        return run(ctx, force ? MODE_ALL : MODE_KEEP);
    }

    /** 只写入（或更新）一个应用，不动主题包里其它应用的条目。 */
    static synchronized String syncOne(Context ctx, String pkg) {
        return run(ctx, "one:" + pkg);
    }

    /** 从主题包里移除一个应用的自定义图标（恢复原图标时调用）。 */
    static synchronized String removeOne(Context ctx, String pkg) {
        return run(ctx, "remove:" + pkg);
    }

    private static final String MODE_ALL = "all";
    private static final String MODE_KEEP = "keep";

    private static String run(Context ctx, String mode) {
        File src = new File(ctx.getCacheDir(), "theme_icons_src");
        File out = new File(ctx.getCacheDir(), "theme_icons_new");
        try {
            Map<String, Bitmap> custom = customIcons(ctx);
            ensureBase();
            su("cp " + THEME_ICONS + " " + src.getAbsolutePath() + " && chmod 644 " + src.getAbsolutePath());

            Set<String> before = readWritten(src);      // 主题包里记录的（系统重建后可能为空）
            Set<String> known = new LinkedHashSet<>(before);
            if (known.isEmpty()) known.addAll(persistedWritten(ctx));   // 包被整体重建 → 用持久记录兜底
            Set<String> target = new LinkedHashSet<>();
            if (MODE_ALL.equals(mode)) {
                target.addAll(custom.keySet());
            } else if (MODE_KEEP.equals(mode)) {
                // 自动重写：优先恢复「上次写过」的那批；若连持久记录都没有（老版本升级），
                // 就把所有「桌面生效」的图标写一次，避免系统重建后彻底静默。
                if (known.isEmpty()) {
                    target.addAll(custom.keySet());
                } else {
                    for (String p : known) if (custom.containsKey(p)) target.add(p);
                }
                if (stampOf(ctx).equals(readStamp(src)) && before.equals(target)) {
                    return custom.isEmpty() ? "主题包里没有本模块写入的图标" : "桌面图标已是最新";
                }
            } else if (mode.startsWith("one:")) {
                target.addAll(known);
                String p = mode.substring(4);
                if (custom.containsKey(p)) target.add(p); else target.remove(p);
            } else if (mode.startsWith("remove:")) {
                target.addAll(known);
                target.remove(mode.substring(7));
            }

            int changed = rewrite(src, out, custom, target, stampOf(ctx));
            push(ctx, out);
            // 把这次实际写入的集合持久化：主题包被系统重建后仍能据此恢复
            Set<String> done = new LinkedHashSet<>();
            for (String p : target) if (custom.containsKey(p)) done.add(p);
            savePersistedWritten(ctx, done);
            if (changed == 0) return "没有需要变化的图标";
            return "已写入 " + changed + " 项并重启桌面";
        } catch (IOException e) {
            return "无法获取 Root（" + e.getMessage() + "）";
        } catch (Exception e) {
            return "写入失败：" + e.getMessage();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    /** 把生成的包替换到系统位置，并把桌面重启一次。 */
    private static void push(Context ctx, File out) throws Exception {
        String label = su("ls -Z " + THEME_ICONS + " | awk '{print $1}'").trim();
        su("cp " + out.getAbsolutePath() + " " + THEME_ICONS
                + " && chown system_theme:system_theme " + THEME_ICONS
                + " && chmod 755 " + THEME_ICONS
                + (label.startsWith("u:") ? " && chcon " + label + " " + THEME_ICONS : "")
                + " && (killall " + homePackage(ctx) + " || true)");
    }

    /**
     * 主题图标包不存在时（新装机、从未应用过图标主题、OTA 后）用系统默认包播种一个基底，
     * 否则后面的 cp / 重写全部无从谈起。
     */
    private static void ensureBase() throws Exception {
        try {
            su("test -f " + THEME_ICONS);
            return;
        } catch (Exception ignored) {
            // 不存在，继续播种
        }
        String label = su("ls -Z " + THEME_DIR + " | awk '{print $1}' | head -1").trim();
        su("cp " + DEFAULT_ICONS + " " + THEME_ICONS
                + " && chown system_theme:system_theme " + THEME_ICONS
                + " && chmod 755 " + THEME_ICONS
                + (label.startsWith("u:") ? " && (chcon " + label + " " + THEME_ICONS + " || true)" : ""));
    }

    static SharedPreferences prefs(Context ctx) {
        try {
            //noinspection deprecation
            return ctx.getSharedPreferences(Const.PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE);
        }
    }

    private static Map<String, Bitmap> customIcons(Context ctx) {
        Map<String, Bitmap> m = new HashMap<>();
        SharedPreferences p = prefs(ctx);
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(Const.ICON) || !(e.getValue() instanceof String)) continue;
            String key = k.substring(Const.ICON.length());
            if (key.contains("@")) continue;              // 主题包不分用户，分身用不上
            if (!hasDesktopIcon(p, key)) continue;        // 「仅通知/设置」的图标不写进桌面主题包
            byte[] d = Base64.decode((String) e.getValue(), Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
            if (b != null) m.put(key, b);
        }
        return m;
    }

    /** 该应用的自定义图标是否要出现在桌面上（NOTIF 模式只改通知/设置，不写主题包）。 */
    private static boolean hasDesktopIcon(SharedPreferences p, String pkg) {
        String mode = p.getString(Const.MODE + pkg, Const.MODE_ALL);
        return !Const.MODE_NOTIF.equals(mode);
    }

    /** 当前图标配置的摘要，写进包里当标记。 */
    private static String stampOf(Context ctx) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(Const.ICON) || k.startsWith(Const.MODE)) sorted.put(k, e.getValue());
        }
        return Integer.toHexString(sorted.hashCode());
    }

    private static String readStamp(File zip) {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry e = zf.getEntry(STAMP);
            return e == null ? "" : new String(readFully(zf.getInputStream(e)), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 包里记录的「本模块写过哪些应用」。 */
    private static Set<String> readWritten(File zip) {
        Set<String> s = new LinkedHashSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry e = zf.getEntry(WRITTEN);
            if (e == null) return s;
            for (String line : new String(readFully(zf.getInputStream(e)), "UTF-8").split("\n")) {
                if (!line.trim().isEmpty()) s.add(line.trim());
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    /**
     * 已写入记录在偏好里另存一份。
     * 主题包会被系统整体重建（换主题 / 深色模式切换 / 开机），包内 facet/written 会一起消失，
     * 只靠 zip 内的记录会导致「自动重写时不知道该写谁」，表现为图标掉了再也不会自己回来。
     */
    private static Set<String> persistedWritten(Context ctx) {
        Set<String> s = new LinkedHashSet<>();
        String v = prefs(ctx).getString(Const.WRITTEN, "");
        if (v == null) return s;
        for (String line : v.split("\n")) {
            if (!line.trim().isEmpty()) s.add(line.trim());
        }
        return s;
    }

    private static void savePersistedWritten(Context ctx, Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String p : set) sb.append(p).append('\n');
        prefs(ctx).edit().putString(Const.WRITTEN, sb.toString()).commit();
    }

    /**
     * 重写主题包。
     * target 里的应用：写自定义图标（首次写入时把原图存进 facet/orig/）。
     * 之前写过、但已不在 target 里的应用：用备份的原图还原；没有备份则直接删掉该条目。
     * 其余条目原样保留（含 STORED / DEFLATED 方式）。
     */
    private static int rewrite(File src, File out, Map<String, Bitmap> custom, Set<String> target, String stamp)
            throws Exception {
        LinkedHashMap<String, byte[]> data = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> method = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(src)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                data.put(e.getName(), readFully(zf.getInputStream(e)));
                method.put(e.getName(), e.getMethod());
            }
        }

        Set<String> before = new LinkedHashSet<>();
        String list = data.containsKey(WRITTEN) ? new String(data.get(WRITTEN), "UTF-8") : "";
        for (String s : list.split("\n")) if (!s.trim().isEmpty()) before.add(s.trim());

        Map<String, byte[]> backup = new HashMap<>();
        for (Map.Entry<String, byte[]> e : data.entrySet()) {
            if (e.getKey().startsWith(ORIG_DIR)) {
                backup.put(e.getKey().substring(ORIG_DIR.length()), e.getValue());
            }
        }

        LinkedHashMap<String, byte[]> outData = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> outMethod = new LinkedHashMap<>();
        Set<String> after = new LinkedHashSet<>();
        int changed = 0;
        int size = 256;

        for (Map.Entry<String, byte[]> e : data.entrySet()) {
            String name = e.getKey();
            if (STAMP.equals(name) || WRITTEN.equals(name) || name.startsWith(ORIG_DIR)) continue;
            byte[] bytes = e.getValue();
            int m = method.get(name);
            if (name.startsWith(ICON_DIR) && name.endsWith(".png")) {
                String pkg = name.substring(ICON_DIR.length(), name.length() - 4);
                Bitmap mask = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (mask != null) size = mask.getWidth();
                if (target.contains(pkg) && custom.containsKey(pkg) && mask != null) {
                    if (!before.contains(pkg)) backup.put(pkg, bytes);   // 首次写入：记住主题原来的图
                    bytes = png(themed(custom.get(pkg), mask));
                    m = ZipEntry.STORED;
                    after.add(pkg);
                    changed++;
                } else if (before.contains(pkg)) {
                    // 以前是模块写的，现在不该有了 → 还原或删除
                    byte[] orig = backup.get(pkg);
                    changed++;
                    if (orig != null) {
                        bytes = orig;
                        m = ZipEntry.STORED;
                    } else {
                        continue;   // 主题本来就没有这个图标，直接去掉条目
                    }
                }
            }
            outData.put(name, bytes);
            outMethod.put(name, m);
        }

        // target 里有、但包里还没有的应用：新增条目
        for (Map.Entry<String, Bitmap> c : custom.entrySet()) {
            String pkg = c.getKey();
            if (!target.contains(pkg) || after.contains(pkg)) continue;
            String name = ICON_DIR + pkg + ".png";
            outData.put(name, png(themed(c.getValue(), roundMask(size))));
            outMethod.put(name, ZipEntry.STORED);
            after.add(pkg);
            changed++;
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, byte[]> e : outData.entrySet()) {
                put(zos, e.getKey(), e.getValue(), outMethod.get(e.getKey()));
            }
            StringBuilder sb = new StringBuilder();
            for (String p : after) sb.append(p).append('\n');
            put(zos, STAMP, stamp.getBytes("UTF-8"), ZipEntry.STORED);
            put(zos, WRITTEN, sb.toString().getBytes("UTF-8"), ZipEntry.STORED);
            // 只保留仍在使用的原图备份
            for (String p : after) {
                byte[] b = backup.get(p);
                if (b != null) put(zos, ORIG_DIR + p, b, ZipEntry.STORED);
            }
        }
        return changed;
    }

    private static void put(ZipOutputStream zos, String name, byte[] data, int method) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(data.length);
            ne.setCompressedSize(data.length);
            ne.setCrc(crc.getValue());
        } else {
            ne.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(ne);
        zos.write(data);
        zos.closeEntry();
    }

    /** 白色底板 + 自定义图标，再按 mask 的透明度裁成主题形状。 */
    private static Bitmap themed(Bitmap icon, Bitmap mask) {
        int w = mask.getWidth(), h = mask.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(icon, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawBitmap(mask, 0, 0, p);
        return out;
    }

    private static Bitmap roundMask(int s) {
        Bitmap m = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(m);
        c.drawRoundRect(new RectF(0, 0, s, s), s * 0.225f, s * 0.225f, new Paint(Paint.ANTI_ALIAS_FLAG));
        return m;
    }

    private static byte[] png(Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    static String homePackage(Context ctx) {
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
            return ri.activityInfo.packageName;
        }
        return "com.miui.home";
    }

    /** 以 root 执行，失败（退出码非 0）抛异常，返回标准输出。 */
    static String su(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        String outText = new String(readFully(p.getInputStream()));
        String err = new String(readFully(p.getErrorStream())).trim();
        if (p.waitFor() != 0) {
            throw new Exception(err.isEmpty() ? "命令失败：" + cmd : err + "\n命令：" + cmd);
        }
        return outText;
    }

    static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}