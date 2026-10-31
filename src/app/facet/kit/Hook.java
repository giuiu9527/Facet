package app.facet.kit;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "Facet: ";
    private static final int SYSUI_ICON_PX = 192;

    private static XSharedPreferences prefs;
    private static boolean frameworkHooked;
    private static volatile boolean loaded;
    private static long lastCheck;

    private static volatile Map<String, String> names = Collections.emptyMap();
    private static volatile Map<String, String> icons = Collections.emptyMap();
    private static volatile Map<String, Boolean> shapes = Collections.emptyMap();
    private static volatile Map<String, Set<String>> folds = Collections.emptyMap();
    private static volatile Map<String, Integer> tokens = Collections.emptyMap();
    private static volatile Map<String, String> countries = Collections.emptyMap();
    private static volatile Map<String, String> makers = Collections.emptyMap();

    /** 本进程所属应用的配置键（第一次回调的包就是进程自己），国家码伪装只看它。 */
    private static String procKey;
    private static boolean countryHooked;
    private static boolean launcherAppsHooked;

    private static final Map<String, Bitmap> rawCache = new ConcurrentHashMap<>();
    private static final Map<String, Bitmap> styledCache = new ConcurrentHashMap<>();
    private static boolean launcherHooked;
    private static boolean systemUIHooked;
    private static final Map<String, String> origLabels = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> bypass = new ThreadLocal<>();

    /** 当前进程包名 / 是否桌面进程：用来判断自定义图标该不该出现在这个位置。 */
    private static volatile String procPkg = "";
    private static volatile boolean procIsLauncher;
    /** 每个应用的替换范围：t_<包名> → all / desk / notif，缺省视为 all。 */
    private static volatile Map<String, String> modes = Collections.emptyMap();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (Const.SELF.equals(lp.packageName)) {
            XposedHelpers.findAndHookMethod(MainActivity.class.getName(), lp.classLoader,
                    "isModuleActive", XC_MethodReplacementTrue.INSTANCE);
            return;
        }
        // 系统框架（system_server）只挂 LauncherApps 一处，别的通用 Hook 不进这个进程，降低卡开机的风险
        if ("android".equals(lp.packageName)) {
            synchronized (Hook.class) {
                if (prefs == null) prefs = new XSharedPreferences(Const.SELF, Const.PREFS);
                if (!launcherAppsHooked) {
                    launcherAppsHooked = true;
                    hookLauncherAppsService(lp.classLoader);
                    watchThemeIcons();
                }
            }
            return;
        }
        synchronized (Hook.class) {
            procPkg = lp.packageName;
            procIsLauncher = LAUNCHERS.contains(lp.packageName);
            if (!frameworkHooked) {
                frameworkHooked = true;
                prefs = new XSharedPreferences(Const.SELF, Const.PREFS);
                hookFramework();
                XposedBridge.log(TAG + "framework hooks installed in " + lp.processName);
            }
            if (!countryHooked) {
                countryHooked = true;
                procKey = key(lp.packageName, userOf(lp.appInfo));
                hookCountry();
                applyMaker();
            }
        }
        // 同一进程可能多次回调（插件等其它 ClassLoader），只在能找到目标类的那次挂钩
        if (!"com.miui.home".equals(lp.packageName) && !launcherHooked
                && XposedHelpers.findClassIfExists("com.android.launcher3.icons.IconProvider", lp.classLoader) != null) {
            launcherHooked = true;
            hookLauncher3(lp.classLoader);
        }
        if ("com.miui.home".equals(lp.packageName) && !launcherHooked
                && XposedHelpers.findClassIfExists("com.miui.home.icon.IconProvider", lp.classLoader) != null) {
            launcherHooked = true;
            hookLauncher(lp.classLoader);
        } else if ("com.android.systemui".equals(lp.packageName) && !systemUIHooked
                && XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.ExpandedNotification", lp.classLoader) != null) {
            systemUIHooked = true;
            hookSystemUI(lp.classLoader);
        }
    }

    // ---------------------------------------------------------------- 配置

    private static void refresh() {
        long now = SystemClock.uptimeMillis();
        if (loaded && now - lastCheck < 2000) return;
        lastCheck = now;
        try {
            if (!loaded || prefs.hasFileChanged()) {
                prefs.reload();
                parse(prefs.getAll());
                loaded = true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "load prefs failed: " + t);
            loaded = true;
        }
    }

    private static void parse(Map<String, ?> all) {
        Map<String, String> n = new HashMap<>();
        Map<String, String> i = new HashMap<>();
        Map<String, Boolean> s = new HashMap<>();
        Map<String, Set<String>> f = new HashMap<>();
        Map<String, Integer> t = new HashMap<>();
        Map<String, String> cc = new HashMap<>();
        Map<String, String> mk = new HashMap<>();
        Map<String, String> md = new HashMap<>();
        if (all != null) {
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (k.startsWith(Const.NAME) && v instanceof String && !((String) v).isEmpty()) {
                    n.put(k.substring(Const.NAME.length()), (String) v);
                } else if (k.startsWith(Const.ICON) && v instanceof String && !((String) v).isEmpty()) {
                    i.put(k.substring(Const.ICON.length()), (String) v);
                } else if (k.startsWith(Const.SHAPE) && v instanceof Boolean) {
                    s.put(k.substring(Const.SHAPE.length()), (Boolean) v);
                } else if (k.startsWith(Const.FOLD) && v instanceof String) {
                    // 值是要折叠的通知渠道 ID，逗号分隔。旧版本存的布尔值（整个应用）一律忽略：
                    // 把一个应用的全部通知折叠起来会让新消息不再提醒。
                    Set<String> ch = new HashSet<>();
                    for (String c : ((String) v).split(",")) {
                        if (!c.trim().isEmpty()) ch.add(c.trim());
                    }
                    if (!ch.isEmpty()) f.put(k.substring(Const.FOLD.length()), ch);
                } else if (k.startsWith(Const.COUNTRY) && v instanceof String && !((String) v).isEmpty()) {
                    cc.put(k.substring(Const.COUNTRY.length()), (String) v);
                } else if (k.startsWith(Const.MAKER) && v instanceof String && !((String) v).isEmpty()) {
                    mk.put(k.substring(Const.MAKER.length()), (String) v);
                } else if (k.startsWith(Const.MODE) && v instanceof String && !((String) v).isEmpty()) {
                    md.put(k.substring(Const.MODE.length()), (String) v);
                }
                // 启动器缓存失效用：同一个包（含它的分身）的名称/图标/形状配置合成一个摘要
                if (k.startsWith(Const.NAME) || k.startsWith(Const.ICON) || k.startsWith(Const.SHAPE)) {
                    String key = k.substring(2);
                    int at = key.indexOf('@');
                    t.merge(at < 0 ? key : key.substring(0, at), java.util.Objects.hash(k, v), Integer::sum);
                }
            }
        }
        names = n;
        icons = i;
        shapes = s;
        folds = f;
        tokens = t;
        countries = cc;
        makers = mk;
        modes = md;
        rawCache.clear();
        styledCache.clear();
    }

    /** 配置的键：主用户就是包名，分身（多开）是 包名@用户ID，互不影响。 */
    private static String key(String pkg, int userId) {
        // 系统通知（USB 调试 / 充电等）的用户是 USER_ALL(-1)，和主用户用同一份设置
        return userId <= 0 ? pkg : pkg + "@" + userId;
    }

    private static int userOf(ApplicationInfo ai) {
        return ai == null ? 0 : ai.uid / 100000;
    }

    private static String keyOf(PackageItemInfo pi) {
        ApplicationInfo ai = pi instanceof ApplicationInfo ? (ApplicationInfo) pi
                : pi instanceof ComponentInfo ? ((ComponentInfo) pi).applicationInfo : null;
        return key(pi.packageName, userOf(ai));
    }

    private static String keyOf(LauncherActivityInfo l) {
        ComponentName cn = l.getComponentName();
        return cn == null ? null : key(cn.getPackageName(), userOf(l.getApplicationInfo()));
    }

    private static String nameFor(String pkg) {
        if (pkg == null) return null;
        refresh();
        return names.get(pkg);
    }

    /** 这条通知（应用 key + 渠道）是否要折叠；只认明确指定的渠道。 */
    private static boolean shouldFold(String key, String channelId) {
        if (key == null || channelId == null) return false;
        refresh();
        Set<String> ch = folds.get(key);
        return ch != null && ch.contains(channelId);
    }

    private static boolean hasIcon(String pkg) {
        if (pkg == null) return false;
        refresh();
        return icons.containsKey(pkg);
    }

    /**
     * 该应用的自定义图标是否允许出现在指定位置。
     * 旧配置没有 t_ 键 → 视为「全部」，与之前行为一致。
     */
    private static boolean allow(String pkg, boolean desktop) {
        if (pkg == null) return false;
        String mode = modes.get(pkg);
        if (mode == null) return true;
        return desktop ? !Const.MODE_NOTIF.equals(mode) : !Const.MODE_DESK.equals(mode);
    }

    private static Bitmap rawBitmap(String pkg) {
        String b64 = icons.get(pkg);
        if (b64 == null) return null;
        Bitmap b = rawCache.get(pkg);
        if (b == null) {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            rawCache.put(pkg, b);
        }
        return b;
    }

    /**
     * 最终显示用的位图。开启「套用主题形状」时交给澎湃主题引擎处理（遮罩/底板/描边与当前主题一致），
     * 主题引擎不可用时退回到圆角矩形。
     */
    private static Bitmap styledBitmap(String pkg) {
        if (!hasIcon(pkg)) return null;
        Bitmap b = styledCache.get(pkg);
        if (b != null) return b;
        Bitmap raw = rawBitmap(pkg);
        if (raw == null) return null;
        Boolean shape = shapes.get(pkg);
        if (shape != null && !shape) {
            b = raw;
        } else {
            b = themeStyled(raw);
            if (b == null) b = roundRect(raw);
        }
        styledCache.put(pkg, b);
        return b;
    }

    private static Bitmap themeStyled(Bitmap raw) {
        try {
            Class<?> ic = Class.forName("miui.content.res.IconCustomizer");
            Method m = ic.getMethod("generateIconStyleDrawable", Drawable.class);
            Object r = m.invoke(null, new BitmapDrawable(Resources.getSystem(), raw));
            if (r instanceof BitmapDrawable) return ((BitmapDrawable) r).getBitmap();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "IconCustomizer unavailable: " + t);
        }
        return null;
    }

    private static Bitmap roundRect(Bitmap raw) {
        int s = SYSUI_ICON_PX;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path p = new Path();
        p.addRoundRect(new RectF(0, 0, s, s), s * 0.225f, s * 0.225f, Path.Direction.CW);
        c.clipPath(p);
        c.drawBitmap(raw, null, new Rect(0, 0, s, s), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static Boolean miuiIcons;

    /** 有澎湃的主题图标引擎就走它，否则（moto、Pixel 等原生系统）交给启动器自己套形状。 */
    private static boolean hasMiuiIconEngine() {
        if (miuiIcons == null) {
            try {
                Class.forName("miui.content.res.IconCustomizer");
                miuiIcons = Boolean.TRUE;
            } catch (Throwable t) {
                miuiIcons = Boolean.FALSE;
            }
        }
        return miuiIcons;
    }

    private static Drawable iconFor(String pkg, Resources res) {
        if (!allow(pkg, procIsLauncher)) return null;
        Resources r = res != null ? res : Resources.getSystem();
        if (!hasMiuiIconEngine()) {
            if (!hasIcon(pkg)) return null;
            Bitmap raw = rawBitmap(pkg);
            if (raw == null) return null;
            BitmapDrawable bd = new BitmapDrawable(r, raw);
            Boolean shape = shapes.get(pkg);
            if (shape != null && !shape) return bd;
            // 图片放在可视区域（108dp 里居中的 72dp）正好铺满，由启动器的形状遮罩去裁
            return new AdaptiveIconDrawable(new InsetDrawable(bd, 1f / 6f), new ColorDrawable(Color.TRANSPARENT));
        }
        Bitmap b = styledBitmap(pkg);
        if (b == null) return null;
        return new BitmapDrawable(r, b);
    }

    /** 让启动器的图标缓存在名称/图标改动后失效：拼进它的 system_state 里。 */
    private static String stateToken(String pkg) {
        refresh();
        Integer h = tokens.get(pkg);
        return h == null ? "" : "facet2:" + Integer.toHexString(h);   // 2：按用户区分配置后的缓存格式
    }

    /** SystemUI 的图标缓存要求是 Bitmap。 */
    private static Bitmap sysuiBitmap(String pkg) {
        if (!allow(pkg, false)) return null;
        return styledBitmap(pkg);
    }

    // ---------------------------------------------------------------- 通用（每个作用域进程）

    private static void hookFramework() {
        Class<?> apm = XposedHelpers.findClass("android.app.ApplicationPackageManager", null);

        safeHookAll(apm, "getApplicationLabel", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (bypass.get() != null) return;
                Object ai = param.args.length > 0 ? param.args[0] : null;
                if (ai instanceof ApplicationInfo) {
                    String n = nameFor(keyOf((ApplicationInfo) ai));
                    if (n != null) param.setResult(n);
                }
            }
        });

        XC_MethodHook labelHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (bypass.get() != null || param.hasThrowable()) return;
                if (!(param.thisObject instanceof PackageItemInfo)) return;
                PackageItemInfo pi = (PackageItemInfo) param.thisObject;
                String n = nameFor(keyOf(pi));
                if (n == null) return;
                if (pi instanceof ApplicationInfo) {
                    param.setResult(n);
                } else if (pi instanceof ComponentInfo) {
                    ComponentInfo ci = (ComponentInfo) pi;
                    ApplicationInfo ai = ci.applicationInfo;
                    boolean sameAsApp = ci.nonLocalizedLabel == null
                            && (ci.labelRes == 0 || (ai != null && ci.labelRes == ai.labelRes));
                    if (!sameAsApp && ai != null && param.args.length > 0
                            && param.args[0] instanceof PackageManager && param.getResult() != null) {
                        String orig = origLabel(ai, (PackageManager) param.args[0]);
                        sameAsApp = orig != null && orig.contentEquals(param.getResult().toString());
                    }
                    if (sameAsApp) param.setResult(n);
                }
            }
        };
        for (String m : new String[]{"loadLabel", "loadUnsafeLabel", "loadSafeLabel"}) {
            safeHookAll(PackageItemInfo.class, m, labelHook);
            safeHookAll(ComponentInfo.class, m, labelHook);
        }

        XC_MethodHook itemIconHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 2 || !(param.args[0] instanceof PackageItemInfo)) return;
                PackageItemInfo item = (PackageItemInfo) param.args[0];
                ApplicationInfo ai = param.args[1] instanceof ApplicationInfo ? (ApplicationInfo) param.args[1] : null;
                ApplicationInfo owner = ai != null ? ai : item instanceof ApplicationInfo ? (ApplicationInfo) item
                        : item instanceof ComponentInfo ? ((ComponentInfo) item).applicationInfo : null;
                String key = key(item.packageName, userOf(owner));
                if (!allow(key, procIsLauncher || isLauncherCaller(param.thisObject))) return;
                if (!hasIcon(key)) return;
                boolean sameAsApp = item instanceof ApplicationInfo || item.icon == 0
                        || (ai != null && item.icon == ai.icon);
                if (!sameAsApp) return;
                Drawable d = iconFor(key, null);
                if (d == null) return;
                if ("loadItemIcon".equals(param.method.getName()) && ai != null) {
                    d = ((PackageManager) param.thisObject).getUserBadgedIcon(d, UserHandle.getUserHandleForUid(ai.uid));
                }
                param.setResult(d);
            }
        };
        safeHookAll(apm, "loadItemIcon", itemIconHook);
        safeHookAll(apm, "loadUnbadgedItemIcon", itemIconHook);

        safeHookAll(LauncherActivityInfo.class, "getIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String k = keyOf((LauncherActivityInfo) param.thisObject);
                if (!allow(k, procIsLauncher)) return;
                Drawable d = k == null ? null : iconFor(k, null);
                if (d != null) param.setResult(d);
            }
        });
    }

    private static String origLabel(ApplicationInfo ai, PackageManager pm) {
        String cached = origLabels.get(ai.packageName);
        if (cached != null) return cached;
        bypass.set(Boolean.TRUE);
        try {
            CharSequence l = ai.loadLabel(pm);
            String s = l == null ? "" : l.toString();
            origLabels.put(ai.packageName, s);
            return s;
        } catch (Throwable t) {
            return null;
        } finally {
            bypass.remove();
        }
    }

    // ---------------------------------------------------------------- 国家码伪装（应用自己的进程）

    /**
     * 有些应用按 SIM / 网络国家码限制功能（比如三星健康监测器在 CN 下不开放心电图和血压）。
     * 只改本进程里 TelephonyManager 返回的国家码，系统和其它应用看到的不变。
     */
    private static void hookCountry() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (procKey == null) return;
                refresh();
                String c = countries.get(procKey);
                if (c != null) param.setResult(c);
            }
        };
        Class<?> tm = android.telephony.TelephonyManager.class;
        safeHookAll(tm, "getSimCountryIso", h);
        safeHookAll(tm, "getNetworkCountryIso", h);
        safeHookAll(tm, "getNetworkCountryIsoForPhone", h);

        // 三星应用还会查 CSC 国家：先走 SemSystemProperties（非三星机没有），再反射读这个系统属性
        Class<?> sp = XposedHelpers.findClassIfExists("android.os.SystemProperties", null);
        safeHookAll(sp, "get", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (procKey == null || !"ro.csc.countryiso_code".equals(param.args[0])) return;
                refresh();
                String c = countries.get(procKey);
                if (c != null) param.setResult(c.toUpperCase(java.util.Locale.ROOT));
            }
        });
    }

    /**
     * 有些应用只认三星机（比如三星健康监测器的入口页 Build.MANUFACTURER 不含 samsung 就直接关掉）。
     * 进程启动时改掉本进程的 Build.MANUFACTURER / BRAND；改了配置要强行停止应用后才生效。
     */
    private static void applyMaker() {
        refresh();
        String m = makers.get(procKey);
        if (m == null) return;
        try {
            for (String f : new String[]{"MANUFACTURER", "BRAND"}) {
                java.lang.reflect.Field field = android.os.Build.class.getDeclaredField(f);
                field.setAccessible(true);
                field.set(null, m);
            }
            XposedBridge.log(TAG + "maker spoofed to " + m + " in " + procKey);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "maker spoof failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 系统框架：LauncherApps

    /**
     * 澎湃 OS 4 的桌面是原生进程（hyos_spawner 拉起，没有 Java 运行时），LSPosed 进不去。
     * 它通过 LauncherApps 向 system_server 要应用列表，名称优先取 ActivityInfo.nonLocalizedLabel，
     * 所以在这里把自定义名称写进返回给桌面的副本里。
     */
    private static void hookLauncherAppsService(ClassLoader cl) {
        Class<?> impl = XposedHelpers.findClassIfExists(
                "com.android.server.pm.LauncherAppsService$LauncherAppsImpl", cl);
        safeHookAll(impl, "getLauncherActivities", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object slice = param.getResult();
                    if (slice == null) return;
                    Object list = XposedHelpers.callMethod(slice, "getList");
                    if (!(list instanceof java.util.List)) return;
                    for (Object info : (java.util.List<?>) list) renameLauncherInfo(info);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "getLauncherActivities rename failed: " + t);
                }
            }
        });
        safeHookAll(impl, "resolveLauncherActivityInternal", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    renameLauncherInfo(param.getResult());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "resolveLauncherActivityInternal rename failed: " + t);
                }
            }
        });
        // 桌面的 Flutter 层另外走 PackageManager.getApplicationLabel → IPackageManager.getApplicationInfo，
        // 只对桌面发来的调用改名，别的应用拿到的 ApplicationInfo 不变
        Class<?> pmBase = XposedHelpers.findClassIfExists("com.android.server.pm.IPackageManagerBase", cl);
        safeHookAll(pmBase, "getApplicationInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object r = param.getResult();
                    if (!(r instanceof ApplicationInfo)) return;
                    ApplicationInfo ai = (ApplicationInfo) r;
                    String n = nameFor(key(ai.packageName, userOf(ai)));
                    if (n == null || !isLauncherCaller(param.thisObject)) return;
                    ApplicationInfo copy = new ApplicationInfo(ai);
                    copy.nonLocalizedLabel = n;
                    copy.labelRes = 0;
                    param.setResult(copy);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "getApplicationInfo rename failed: " + t);
                }
            }
        });
        XposedBridge.log(TAG + "LauncherApps hooks installed in system_server");
    }

    private static android.os.FileObserver themeObserver;   // 必须留着引用，否则被回收后就不再监听

    /**
     * 换主题会重新生成 /data/system/theme/icons，把写进去的自定义图标冲掉。
     * 在 system_server 里盯着这个文件，变化稳定 5 秒后通知 Facet 的 ThemeReceiver 重新写入。
     * Facet 自己写入也会触发一次，由包里的标记判断出已是最新而跳过。
     */
    private static void watchThemeIcons() {
        try {
            android.os.HandlerThread ht = new android.os.HandlerThread("FacetTheme");
            ht.start();
            final android.os.Handler h = new android.os.Handler(ht.getLooper());
            final Runnable notify = () -> {
                try {
                    refresh();
                    if (icons.isEmpty()) return;
                    Class<?> at = Class.forName("android.app.ActivityThread");
                    Object thread = at.getMethod("currentActivityThread").invoke(null);
                    android.content.Context ctx = (android.content.Context) at.getMethod("getSystemContext").invoke(thread);
                    android.content.Intent i = new android.content.Intent(ThemeIcons.ACTION_CHANGED)
                            .setClassName(Const.SELF, ThemeReceiver.class.getName())
                            .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    ctx.sendBroadcastAsUser(i, android.os.UserHandle.getUserHandleForUid(0));
                    XposedBridge.log(TAG + "theme icons changed, asked Facet to rewrite");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "theme notify failed: " + t);
                }
            };
            themeObserver = new android.os.FileObserver(new java.io.File(ThemeIcons.THEME_DIR),
                    android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MOVED_TO
                            | android.os.FileObserver.CREATE
                            // 系统重建/清理主题包时是「删除后再创建」，只监听创建会漏掉删除那一步
                            | android.os.FileObserver.DELETE | android.os.FileObserver.MOVED_FROM) {
                @Override
                public void onEvent(int event, String path) {
                    if (!"icons".equals(path)) return;
                    // 只有删除事件时先等系统把新包放好，再触发重写
                    h.removeCallbacks(notify);
                    h.postDelayed(notify, 5000);
                }
            };
            themeObserver.startWatching();
            XposedBridge.log(TAG + "watching " + ThemeIcons.THEME_ICONS);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "theme watch failed: " + t);
        }
    }

    private static final Set<String> LAUNCHERS = new HashSet<>(java.util.Arrays.asList("com.miui.home"));
    private static final Map<Integer, Boolean> launcherUids = new ConcurrentHashMap<>();

    /** 当前 Binder 调用方是不是桌面（按 uid 缓存，避免每次都查包名）。 */
    private static boolean isLauncherCaller(Object pm) {
        int uid = android.os.Binder.getCallingUid();
        Boolean known = launcherUids.get(uid);
        if (known != null) return known;
        boolean is = false;
        try {
            Object name = XposedHelpers.callMethod(pm, "getNameForUid", uid);
            is = name instanceof String && LAUNCHERS.contains(name);
        } catch (Throwable ignored) {
        }
        launcherUids.put(uid, is);
        return is;
    }

    /** 给 LauncherActivityInfoInternal 换上带自定义名称的 ActivityInfo 副本（不动系统里缓存的原对象）。 */
    private static void renameLauncherInfo(Object info) {
        if (info == null) return;
        Object v = XposedHelpers.getObjectField(info, "mActivityInfo");
        if (!(v instanceof android.content.pm.ActivityInfo)) return;
        android.content.pm.ActivityInfo ai = (android.content.pm.ActivityInfo) v;
        String n = nameFor(key(ai.packageName, userOf(ai.applicationInfo)));
        if (n == null) return;
        android.content.pm.ActivityInfo copy = new android.content.pm.ActivityInfo(ai);
        copy.nonLocalizedLabel = n;
        copy.labelRes = 0;
        if (ai.applicationInfo != null) {
            copy.applicationInfo = new ApplicationInfo(ai.applicationInfo);
            copy.applicationInfo.nonLocalizedLabel = n;
            copy.applicationInfo.labelRes = 0;
        }
        XposedHelpers.setObjectField(info, "mActivityInfo", copy);
    }

    // ---------------------------------------------------------------- 系统桌面 + 最近任务

    private static void hookLauncher(ClassLoader cl) {
        Class<?> iconProvider = XposedHelpers.findClassIfExists("com.miui.home.icon.IconProvider", cl);
        safeHookAll(iconProvider, "getActivityIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof LauncherActivityInfo)) return;
                String k = keyOf((LauncherActivityInfo) param.args[0]);
                Drawable d = k == null ? null : iconFor(k, null);
                if (d != null) param.setResult(d);
            }
        });

        Class<?> iconLoader = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.recents.model.IconLoader", cl);
        XC_MethodHook taskIconHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Drawable d = iconFor(taskKeyOf(param.args[0]), null);
                if (d != null) param.setResult(d);
            }
        };
        safeHookAll(iconLoader, "getAndInvalidateIfModified", taskIconHook);
        safeHookAll(iconLoader, "getIcon", taskIconHook);
        safeHookAll(iconLoader, "getBadgedActivityIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof ComponentInfo)) return;
                Drawable d = iconFor(keyOf((ComponentInfo) param.args[0]), null);
                if (d != null) param.setResult(d);
            }
        });

        Class<?> taskLoader = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.recents.model.RecentsTaskLoader", cl);
        safeHookAll(taskLoader, "getAndUpdateActivityTitle", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String n = nameFor(taskKeyOf(param.args[0]));
                if (n != null) param.setResult(n);
            }
        });

        Class<?> recentsModel = XposedHelpers.findClassIfExists("com.miui.home.recents.RecentsModel", cl);
        safeHookAll(recentsModel, "onTaskDescriptionChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof ActivityManager.RunningTaskInfo)) return;
                ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) param.args[0];
                ComponentName cn = info.baseActivity != null ? info.baseActivity : info.topActivity;
                String n = cn == null ? null : nameFor(key(cn.getPackageName(), intField(info, "userId")));
                if (n == null) return;
                try {
                    Object task = XposedHelpers.callMethod(param.thisObject, "findTaskById", info.taskId);
                    if (task != null) XposedHelpers.setObjectField(task, "title", n);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ---------------------------------------------------------------- 原生 Launcher3（moto、Pixel 等）

    private static void hookLauncher3(ClassLoader cl) {
        Class<?> provider = XposedHelpers.findClassIfExists("com.android.launcher3.icons.IconProvider", cl);
        safeHookAll(provider, "getIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String pkg = null;
                Object a = param.args.length > 0 ? param.args[0] : null;
                if (a instanceof ComponentInfo) pkg = keyOf((ComponentInfo) a);
                else if (a instanceof LauncherActivityInfo) pkg = keyOf((LauncherActivityInfo) a);
                Drawable d = pkg == null ? null : iconFor(pkg, null);
                if (d != null) param.setResult(d);
            }
        });
        safeHookAll(provider, "getSystemStateForPackage", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length < 2 || !(param.args[1] instanceof String) || param.getResult() == null) return;
                String token = stateToken((String) param.args[1]);
                if (!token.isEmpty()) param.setResult(param.getResult() + "," + token);
            }
        });
        Class<?> logic = XposedHelpers.findClassIfExists("com.android.launcher3.icons.LauncherActivityCachingLogic", cl);
        safeHookAll(logic, "getLabel", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof LauncherActivityInfo)) return;
                String k = keyOf((LauncherActivityInfo) param.args[0]);
                String n = k == null ? null : nameFor(k);
                if (n != null) param.setResult(n);
            }
        });
    }

    private static String taskKeyOf(Object taskKey) {
        if (taskKey == null) return null;
        try {
            String pkg = (String) XposedHelpers.callMethod(taskKey, "getPackageName");
            return key(pkg, intField(taskKey, "userId"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intField(Object o, String name) {
        try {
            Object v = XposedHelpers.getObjectField(o, name);
            return v instanceof Integer ? (Integer) v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 系统界面（通知）

    private static void hookSystemUI(ClassLoader cl) {
        Class<?> expanded = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.ExpandedNotification", cl);
        safeHookAll(expanded, "getAppName", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String pkg = (String) XposedHelpers.getObjectField(param.thisObject, "mPkgName");
                if (pkg == null) pkg = (String) XposedHelpers.callMethod(param.thisObject, "getPackageName");
                int user = param.thisObject instanceof StatusBarNotification
                        ? ((StatusBarNotification) param.thisObject).getUserId() : 0;
                String n = nameFor(key(pkg, user));
                if (n != null) param.setResult(n);
            }
        });

        // 折叠进「更多通知」：澎湃每次新增/更新通知都会走 scheduleHistoryNotification，
        // 默认等 6 小时才折叠，而且每次更新都会重新计时；这里对指定应用立刻折叠。
        Class<?> foldCoordinator = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.collection.coordinator.FoldCoordinator", cl);
        final Class<?> notifUtil = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.utils.NotificationUtil", cl);
        safeHookAll(foldCoordinator, "scheduleHistoryNotification", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object entry = param.args[0];
                    Object sbn = XposedHelpers.getObjectField(entry, "mSbn");
                    String pkg = (String) XposedHelpers.getObjectField(sbn, "mPkgName");
                    StatusBarNotification sb = (StatusBarNotification) sbn;
                    if (notifUtil != null && shouldFold(key(pkg, sb.getUserId()), sb.getNotification().getChannelId())) {
                        XposedHelpers.callStaticMethod(notifUtil, "setFold", entry, true);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "fold failed: " + t);
                }
            }
        });

        // 系统通知（发送者是 android 等系统包，比如 LSPosed 的「已加载」）会被 shouldIgnoreEntry 整个跳过，
        // 根本走不到上面的 scheduleHistoryNotification；对用户明确勾选的渠道放行。
        safeHookAll(notifUtil, "shouldIgnoreEntry", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    Object sbn = XposedHelpers.getObjectField(param.args[0], "mSbn");
                    StatusBarNotification sb = (StatusBarNotification) sbn;
                    String pkg = (String) XposedHelpers.getObjectField(sbn, "mPkgName");
                    if (shouldFold(key(pkg, sb.getUserId()), sb.getNotification().getChannelId())) {
                        param.setResult(Boolean.FALSE);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "shouldIgnoreEntry failed: " + t);
                }
            }
        });

        Class<?> appIcons = XposedHelpers.findClassIfExists("com.miui.systemui.graphics.AppIconsManager", cl);
        XC_MethodHook drawableHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Bitmap b = sysuiBitmap(key((String) param.args[0], param.args[1] instanceof Integer ? (Integer) param.args[1] : 0));
                if (b != null) param.setResult(new BitmapDrawable(Resources.getSystem(), b));
            }
        };
        safeHookAll(appIcons, "getAppIconInner", drawableHook);
        safeHookAll(appIcons, "loadAppIcon", drawableHook);
        safeHookAll(appIcons, "getAppIconBitmap", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Bitmap b = sysuiBitmap(key((String) param.args[1], param.args[0] instanceof Integer ? (Integer) param.args[0] : 0));
                if (b != null) param.setResult(b);
            }
        });
    }

    // ---------------------------------------------------------------- 工具

    private static void safeHookAll(Class<?> cls, String method, XC_MethodHook hook) {
        if (cls == null) {
            XposedBridge.log(TAG + "class missing for " + method);
            return;
        }
        try {
            int n = XposedBridge.hookAllMethods(cls, method, hook).size();
            if (n == 0) XposedBridge.log(TAG + "no method " + cls.getName() + "#" + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook " + cls.getName() + "#" + method + " failed: " + t);
        }
    }

    private static final class XC_MethodReplacementTrue extends XC_MethodHook {
        static final XC_MethodReplacementTrue INSTANCE = new XC_MethodReplacementTrue();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(Boolean.TRUE);
        }
    }
}
