package app.facet.kit;

final class Const {
    static final String SELF = "app.facet.kit";
    static final String PREFS = "config";
    /** 自定义名称：n_<包名> */
    static final String NAME = "n_";
    /** 自定义图标（PNG base64）：i_<包名> */
    static final String ICON = "i_";
    /** 是否套用系统图标形状：s_<包名>，默认 true */
    static final String SHAPE = "s_";

    /** 通知是否折叠进「更多通知」：f_<包名>，默认 false */
    static final String FOLD = "f_";

    /** 伪装 SIM / 网络国家码（两位小写 ISO，如 us）：c_<包名>，只对该应用自己的进程生效 */
    static final String COUNTRY = "c_";

    /** 伪装厂商（Build.MANUFACTURER / BRAND，如 samsung）：m_<包名>，只对该应用自己的进程生效 */
    static final String MAKER = "m_";

    /**
     * 自定义图标的作用范围：t_<包名>，取值 ALL / DESK / NOTIF，缺省视为 ALL（兼容旧配置）。
     * ALL   = 桌面 + 通知 / 设置 / 最近任务
     * DESK  = 只改桌面（通知、设置等保持系统图标）
     * NOTIF = 只改通知 / 设置 / 最近任务（桌面保持系统图标；澎湃 OS 4 上同时表示不写主题图标包）
     */
    static final String MODE = "t_";

    static final String MODE_ALL = "all";
    static final String MODE_DESK = "desk";
    static final String MODE_NOTIF = "notif";

    /**
     * 已写入主题图标包的应用列表（换行分隔），存一份在偏好里。
     * 主题包会被系统整体重建（换主题 / 深色模式切换 / 开机），包内记录会随之消失，
     * 只有这份持久记录能让「自动重写」知道该恢复哪些应用。
     */
    static final String WRITTEN = "w_";

    private Const() {}
}