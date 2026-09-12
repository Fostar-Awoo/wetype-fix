package com.wetypefix;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * WeType Fix — Xposed module for com.tencent.wetype (微信输入法 3.5.4, versionCode 56201)
 *
 * Feature 1 修复大写取消延迟:
 *   - Hook key.d.R(va.h,boolean) (ImeKeyboardActionListener key commit).
 *     Stock behaviour: after committing a letter with one-shot shift (upperMode==2)
 *     on the EnglishQwerty keyboard, the shift is only reverted asynchronously when
 *     the engine's around-text update arrives (S.updateAroundText -> S.p(FALSE,true)
 *     -> coroutine), which the user perceives as a delay. We reset the upper mode
 *     to 1 synchronously via S.n(keyboard, 1) right after the key commit.
 *   - Hook hardware.d.f(boolean,KeyEvent): on CapsLock key-up the stock code skips
 *     S.n(...) while in hardware-keyboard mode (g.j()==true), leaving a stale shift
 *     state. We apply the same S.n(keyboard, isCapsLockOn?3:1) that the soft path does.
 *
 * Feature 2 特殊环境(Termux 等)彻底关闭英文候选与自动纠错:
 *   The engine SessionConfig is built in i0.M2() from j1 getters:
 *     - j1.t1()  -> enable_english_assisted_spelling_correction (英文自动纠错)
 *     - j1.o2()  -> enable_local_correction + cloud enable_cloud_correction (拼写检查)
 *     - j1.R1()  -> enable_auto_most_likely + cloud enable_text_recommend (联想候选)
 *   之前的实现只在特殊环境把这三个 getter 置 false，但 SessionConfig 只在引擎会话
 *   (重新)创建时读取一次（i0.z1 -> i0.M2 -> engine.a.p(SessionConfig)），会话跨应用
 *   切换被复用时配置保持陈旧，导致英文候选词仍会显示。现在三层防护：
 *     a) WxHldService.onStartInput(EditorInfo,boolean) 前置记录当前包名/inputType，
 *        保证 isSpecialEnv 判定不再受 IME 内部 currentPackageName 更新时机影响；
 *     b) j1.t1()/o2()/R1() 在特殊环境强制 false（设置读取层 + 请求层，i0.j5 的
 *        TRY_TRIGGER_MOST_LIKELY 也会被 R1() 拦下）;
 *     c) engine.a.p(SessionConfig)（创建/更新引擎会话）前，特殊环境直接改写
 *        SessionConfig 字段（含 cloud_engine_config），即使配置来自陈旧快照也会被纠正;
 *     d) i0.N5(...)（本地候选列表分发给 UI 监听器的唯一入口，翻页 fetchMore 也走
 *        这里）前，特殊环境过滤掉英文词候选（CANDIDATE_FLAG_FULL_ENGLISH /
 *        EN_RESULT_WITH_SPECIAL_SYMBOL，或引擎生成的纯拉丁字母词）。
 *
 * Feature 3 中文字码显示动态回滚（仅特殊环境）:
 *   WeType's "拼音显示" setting (isEnableDirectPreInput, key
 *   ime_enable_pending_input_to_screen_all_scene, j1.f2()) makes the Chinese
 *   composing code be written into the app's edit box (pending-input-to-screen).
 *   之前无差别强制 j1.f2()/pendinginput.a.t()/q()/u() 返回 false（全局回滚到候选栏）。
 *   现在改为动态：仅在特殊环境（Termux 等）强制 false（回滚到候选栏 ImeCandidateView
 *   的删除线区域显示）；在其它应用不干预，走原始逻辑（用户未开启该设置时原值本来就是
 *   false，不执行任何操作）。WeType 自身在包名变化时（resetCurrentPackageName ->
 *   pendinginput.a.x()）会检测 pendingInputToScreenAllSceneMode 变化并自动重绘，
 *   因此切换应用时候选栏/输入框内的字码显示会即时切换。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WeTypeFix";
    private static final String IME_PKG = "com.tencent.wetype";

    // com.tencent.wxhld.info.Candidate.Flag
    private static final long FLAG_FULL_ENGLISH = 64L;              // CANDIDATE_FLAG_FULL_ENGLISH
    private static final long FLAG_EN_RESULT_WITH_SPECIAL_SYMBOL = 0x80000000L; // 2147483648L

    // candidateGenerateType (candidate.d.f, getter m()) — 0/1 为引擎/默认生成的普通候选
    private static final int GEN_ENGINE = 0;
    private static final int GEN_CLIENT_DEFAULT = 1;

    // ---------------- module options (defaults; overridable via prefs) ----------------
    private boolean fixCapsDelay = true;
    private boolean rollbackChineseCode = true;
    private boolean specialEnvDisableEnglish = true;
    private Set<String> specialPackages =
            new HashSet<>(Arrays.asList("com.termux", "com.termux.x11"));

    // ---------------- runtime state ----------------
    private static volatile ClassLoader sCl;
    /** 从 onStartInput 同步记录的当前输入目标包名（比 IME 内部 currentPackageName 更新更早、更可靠） */
    private static volatile String sTrackedPackageName;
    /** 从 onStartInput 同步记录的当前 EditorInfo.inputType（TYPE_NULL=0 视为特殊环境） */
    private static volatile Integer sTrackedInputType;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!IME_PKG.equals(lpp.packageName)) {
            return;
        }
        sCl = lpp.classLoader;
        try {
            loadConfig();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": config load failed, using defaults");
        }
        ClassLoader cl = lpp.classLoader;
        if (fixCapsDelay) {
            safe("capsDelay", new Runnable() {
                @Override
                public void run() {
                    hookCapsDelay(cl);
                }
            });
        }
        if (specialEnvDisableEnglish) {
            safe("specialEnv", new Runnable() {
                @Override
                public void run() {
                    hookSpecialEnv(cl);
                }
            });
        }
        if (rollbackChineseCode) {
            safe("chineseCodeRollback", new Runnable() {
                @Override
                public void run() {
                    hookChineseCodeRollback(cl);
                }
            });
        }
        XposedBridge.log(TAG + ": hooks installed for " + IME_PKG);
    }

    // ------------------------------------------------------------------ feature 1

    private void hookCapsDelay(final ClassLoader cl) {
        // 1) synchronous revert of one-shot shift after a key commit on English keyboard
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.key.d", cl,
                "R", "va.h", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            resetOneShotShift(cl);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });

        // 2) CapsLock key-up while in hardware-keyboard mode is skipped by stock code
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.hardware.d", cl,
                "f", boolean.class, KeyEvent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!Boolean.TRUE.equals(param.args[0]) || param.args[1] == null) {
                                return;
                            }
                            Object g = singleton(XposedHelpers.findClass(
                                    "com.tencent.wetype.plugin.hld.hardware.g", cl));
                            boolean hwMode = (Boolean) XposedHelpers.callMethod(g, "j");
                            if (!hwMode) {
                                return; // soft-keyboard path already handled by the original method
                            }
                            Object kb = currentKeyboard(cl);
                            if (kb == null) {
                                return;
                            }
                            KeyEvent ev = (KeyEvent) param.args[1];
                            Object s = singleton(XposedHelpers.findClass(
                                    "com.tencent.wetype.plugin.hld.utils.S", cl));
                            XposedHelpers.callMethod(s, "n", kb, ev.isCapsLockOn() ? 3 : 1);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
    }

    /** After a key commit on the EnglishQwerty keyboard, revert one-shot shift (mode 2) instantly. */
    private void resetOneShotShift(ClassLoader cl) {
        Object n = singleton(XposedHelpers.findClass("com.tencent.wetype.plugin.hld.model.N", cl));
        if (!(Boolean) XposedHelpers.callMethod(n, "K1")) {
            return; // only the English QWERTY keyboard has the perceived delay
        }
        Object kb = XposedHelpers.callMethod(n, "p0");
        if (kb == null) {
            return;
        }
        int mode = (Integer) XposedHelpers.callMethod(kb, "getUpperMode");
        if (mode == 2) {
            Object s = singleton(XposedHelpers.findClass("com.tencent.wetype.plugin.hld.utils.S", cl));
            // S.n(keyboard, 1): m(1,typeValue) + keyboard.o0(1) + keyboard.t0() — instant revert
            XposedHelpers.callMethod(s, "n", kb, 1);
        }
    }

    // ------------------------------------------------------------------ feature 2

    private void hookSpecialEnv(final ClassLoader cl) {
        // (a) 最早时机同步记录输入目标，供 isSpecialEnv 使用
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.WxHldService", cl,
                "onStartInput", EditorInfo.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            EditorInfo info = (EditorInfo) param.args[0];
                            if (info != null) {
                                sTrackedPackageName = info.packageName;
                                sTrackedInputType = info.inputType;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });

        // (b) 设置读取层 + 请求层：特殊环境强制 false
        XC_MethodHook forceFalseInSpecialEnv = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (isSpecialEnv()) {
                        param.setResult(Boolean.FALSE);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        // j1.t1(): ime_enable_english_auto_correction && abtest -> english assisted spelling correction
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.utils.j1", cl, "t1", forceFalseInSpecialEnv);
        // j1.o2(): ime_plus_detail_spelling_correct -> local & cloud correction
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.utils.j1", cl, "o2", forceFalseInSpecialEnv);
        // j1.R1(): ime_enable_associating -> auto most likely / text recommend (联想候选)
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.utils.j1", cl, "R1", forceFalseInSpecialEnv);

        // (c) 引擎会话配置层：无论 SessionConfig 由哪个（可能陈旧的）快照构建，特殊环境一律改写
        safe("specialEnv.sessionConfig", new Runnable() {
            @Override
            public void run() {
                hookSessionConfig(cl);
            }
        });

        // (d) 候选展示层：特殊环境过滤英文词候选（最终防线，保证英文候选词不再显示）
        safe("specialEnv.candidateFilter", new Runnable() {
            @Override
            public void run() {
                hookCandidateFilter(cl);
            }
        });
    }

    /** engine.a.p(SessionConfig) — 创建/更新引擎会话的入口，特殊环境强制关闭英文相关能力。 */
    private void hookSessionConfig(ClassLoader cl) {
        final Class<?> engineA = XposedHelpers.findClass("com.tencent.wetype.plugin.hld.engine.a", cl);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null || param.args.length != 2 || param.args[0] == null) {
                        return;
                    }
                    if (!"com.tencent.wxhld.info.SessionConfig".equals(param.args[0].getClass().getName())) {
                        return;
                    }
                    if (!isSpecialEnv()) {
                        return;
                    }
                    Object cfg = param.args[0];
                    XposedHelpers.setBooleanField(cfg, "enable_english_assisted_spelling_correction", false);
                    XposedHelpers.setBooleanField(cfg, "enable_local_correction", false);
                    XposedHelpers.setBooleanField(cfg, "enable_auto_most_likely", false);
                    XposedHelpers.setBooleanField(cfg, "enable_user_hot_word_recommend", false);
                    Object cloud = XposedHelpers.getObjectField(cfg, "cloud_engine_config");
                    if (cloud != null) {
                        XposedHelpers.setBooleanField(cloud, "enable_cloud_correction", false);
                        XposedHelpers.setBooleanField(cloud, "enable_text_recommend", false);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        int hooked = 0;
        for (Method m : engineA.getDeclaredMethods()) {
            if ("p".equals(m.getName())) {
                XposedBridge.hookMethod(m, hook);
                hooked++;
            }
        }
        if (hooked == 0) {
            throw new IllegalStateException("engine.a.p(SessionConfig) not found");
        }
    }

    /**
     * i0.N5(ArrayList, int, boolean, boolean, candidate.w, boolean) — 本地候选列表分发到
     * UI 监听器（ImeCandidateView 等）的唯一入口（同步路径与协程异步路径共用同一 list）。
     * 特殊环境下原地移除英文词候选。
     */
    private void hookCandidateFilter(ClassLoader cl) {
        Class<?> listenerCls = XposedHelpers.findClass("com.tencent.wetype.plugin.hld.candidate.w", cl);
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.model.i0", cl,
                "N5", ArrayList.class, int.class, boolean.class, boolean.class,
                listenerCls, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!isSpecialEnv()) {
                                return;
                            }
                            ArrayList<?> list = (ArrayList<?>) param.args[0];
                            if (list == null || list.isEmpty()) {
                                return;
                            }
                            int removed = 0;
                            Iterator<?> it = list.iterator();
                            while (it.hasNext()) {
                                if (isEnglishWordCandidate(it.next())) {
                                    it.remove();
                                    removed++;
                                }
                            }
                            if (removed > 0) {
                                XposedBridge.log(TAG + ": filtered " + removed
                                        + " english candidate(s) in special env");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
    }

    /**
     * 判断一个候选（candidate.d，继承 com.tencent.wxhld.info.Candidate）是否为英文词候选：
     *  - 带 FULL_ENGLISH / EN_RESULT_WITH_SPECIAL_SYMBOL 标志；或
     *  - 引擎/默认生成的普通候选，且文本为纯拉丁字母（可含 ' - 分隔），
     *    不含 CJK/数字/符号/表情 —— 数字、符号、表情等候选不受影响。
     */
    private static boolean isEnglishWordCandidate(Object c) {
        try {
            String text = (String) XposedHelpers.getObjectField(c, "text");
            if (text == null || text.isEmpty()) {
                return false;
            }
            long flag = XposedHelpers.getLongField(c, "flag");
            if ((flag & FLAG_FULL_ENGLISH) != 0L
                    || (flag & FLAG_EN_RESULT_WITH_SPECIAL_SYMBOL) != 0L) {
                return true;
            }
            boolean hasLetter = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                    hasLetter = true;
                } else if (ch != '\'' && ch != '-') {
                    return false; // CJK / 数字 / 符号 / 表情等 → 不是英文词候选
                }
            }
            if (!hasLetter) {
                return false;
            }
            int genType = XposedHelpers.getIntField(c, "f"); // candidateGenerateType
            return genType == GEN_ENGINE || genType == GEN_CLIENT_DEFAULT;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前是否处于特殊输入环境（Termux 等终端 / TYPE_NULL 输入框）。
     * 判定来源优先级：
     *   1. onStartInput 同步记录的包名 / inputType（最早、最可靠）；
     *   2. IME 内部 WxHldService.currentPackageName；
     *   3. n1.c0() 缓存的 inputType。
     */
    private boolean isSpecialEnv() {
        ClassLoader cl = sCl;
        if (cl == null) {
            return false;
        }
        String pkg = sTrackedPackageName;
        if (pkg != null && specialPackages.contains(pkg)) {
            return true;
        }
        Integer inputType = sTrackedInputType;
        if (inputType != null && inputType == 0) {
            return true; // EditorInfo.TYPE_NULL：终端、原始编辑器
        }
        try {
            String imePkg = currentPackageName(cl);
            if (imePkg != null && specialPackages.contains(imePkg)) {
                return true;
            }
            // n1.c0(): IME 缓存的上一个 EditorInfo.inputType
            Object n1 = singleton(XposedHelpers.findClass(
                    "com.tencent.wetype.plugin.hld.utils.n1", cl));
            Integer cur = (Integer) XposedHelpers.callMethod(n1, "c0");
            return cur != null && cur == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private String currentPackageName(ClassLoader cl) {
        try {
            Object companion = staticInstanceOfType(
                    XposedHelpers.findClass("com.tencent.wetype.plugin.hld.WxHldService", cl),
                    "com.tencent.wetype.plugin.hld.WxHldService$a");
            if (companion == null) {
                return null;
            }
            Object svc = XposedHelpers.callMethod(companion, "e");
            if (svc == null) {
                return null;
            }
            return (String) XposedHelpers.callMethod(svc, "i0");
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ feature 3

    private void hookChineseCodeRollback(final ClassLoader cl) {
        // 仅在特殊环境强制 false（回滚到候选栏显示字码）；
        // 其它应用不干预 —— 用户开启“拼音显示”则恢复输入框内显示，未开启则原样为 false，不执行任何操作。
        XC_MethodHook forceFalseInSpecialEnv = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (isSpecialEnv()) {
                        param.setResult(Boolean.FALSE);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        // j1.f2(): ime_enable_pending_input_to_screen_all_scene ("拼音显示" / direct pre-input)
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.utils.j1", cl, "f2", forceFalseInSpecialEnv);
        // pendinginput.a (ImePendingInputMgr): all pending-to-screen gates
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.pendinginput.a", cl, "t", forceFalseInSpecialEnv);
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.pendinginput.a", cl, "q", int.class, forceFalseInSpecialEnv);
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.pendinginput.a", cl, "u", int.class, forceFalseInSpecialEnv);
    }

    // ------------------------------------------------------------------ helpers

    private Object currentKeyboard(ClassLoader cl) {
        Object n = singleton(XposedHelpers.findClass("com.tencent.wetype.plugin.hld.model.N", cl));
        return XposedHelpers.callMethod(n, "p0");
    }

    /** Returns the Kotlin `object`/companion instance stored in a static field typed as the class itself. */
    private static Object singleton(Class<?> cls) {
        Object o = staticInstanceOfType(cls, cls.getName());
        if (o == null) {
            throw new IllegalStateException("singleton field not found in " + cls.getName());
        }
        return o;
    }

    private static Object staticInstanceOfType(Class<?> cls, String fieldTypeName) {
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType().getName().equals(fieldTypeName)) {
                f.setAccessible(true);
                try {
                    return f.get(null);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private void safe(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "/" + what + " hook failed");
            XposedBridge.log(t);
        }
    }

    private void loadConfig() {
        XSharedPreferences prefs = new XSharedPreferences("com.wetypefix", "config");
        if (!prefs.getFile().exists()) {
            return;
        }
        prefs.reload();
        fixCapsDelay = prefs.getBoolean("fixCapsDelay", true);
        rollbackChineseCode = prefs.getBoolean("rollbackChineseCode", true);
        specialEnvDisableEnglish = prefs.getBoolean("specialEnvDisableEnglish", true);
        String list = prefs.getString("specialPackages", "com.termux,com.termux.x11");
        Set<String> parsed = new HashSet<>();
        for (String p : list.split(",")) {
            p = p.trim();
            if (!p.isEmpty()) {
                parsed.add(p);
            }
        }
        if (!parsed.isEmpty()) {
            specialPackages = parsed;
        }
    }
}
