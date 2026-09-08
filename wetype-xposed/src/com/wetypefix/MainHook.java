package com.wetypefix;

import android.view.KeyEvent;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
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
 * Feature 2 Termux 特殊环境关闭英文候选与自动纠错:
 *   The engine SessionConfig is built in i0.M2() from j1 getters:
 *     - j1.t1()  -> enable_english_assisted_spelling_correction (英文自动纠错)
 *     - j1.o2()  -> enable_local_correction + cloud enable_cloud_correction (拼写检查)
 *     - j1.R1()  -> enable_auto_most_likely + cloud enable_text_recommend (联想候选)
 *   When the current input target is a "special environment" (package in the list,
 *   e.g. Termux, or EditorInfo.inputType == TYPE_NULL) we force all three to false.
 *
 * Feature 3 中文字码回滚到候选栏:
 *   WeType's "拼音显示" setting (isEnableDirectPreInput, key
 *   ime_enable_pending_input_to_screen_all_scene, j1.f2()) makes the Chinese
 *   composing code be written into the app's edit box (pending-input-to-screen).
 *   Rolling it back (off) shows the code in the candidate bar's strike area
 *   (ImeCandidateView.s1()) like older versions. We force:
 *     - j1.f2() -> false
 *     - pendinginput.a.t()/q(int)/u(int) -> false  (all pending-to-screen paths)
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WeTypeFix";
    private static final String IME_PKG = "com.tencent.wetype";

    // ---------------- module options (defaults; overridable via prefs) ----------------
    private boolean fixCapsDelay = true;
    private boolean rollbackChineseCode = true;
    private boolean specialEnvDisableEnglish = true;
    private Set<String> specialPackages =
            new HashSet<>(Arrays.asList("com.termux", "com.termux.x11"));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!IME_PKG.equals(lpp.packageName)) {
            return;
        }
        try {
            loadConfig();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": config load failed, using defaults");
        }
        ClassLoader cl = lpp.classLoader;
        if (fixCapsDelay) {
            safe("capsDelay", () -> hookCapsDelay(cl));
        }
        if (rollbackChineseCode) {
            safe("chineseCodeRollback", () -> hookChineseCodeRollback(cl));
        }
        if (specialEnvDisableEnglish) {
            safe("specialEnv", () -> hookSpecialEnv(cl));
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
        XC_MethodHook forceFalse = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (isSpecialEnv(cl)) {
                        param.setResult(Boolean.FALSE);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        // j1.t1(): ime_enable_english_auto_correction && abtest -> english assisted spelling correction
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.utils.j1", cl, "t1", forceFalse);
        // j1.o2(): ime_plus_detail_spelling_correct -> local & cloud correction
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.utils.j1", cl, "o2", forceFalse);
        // j1.R1(): ime_enable_associating -> auto most likely / text recommend (联想候选)
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.utils.j1", cl, "R1", forceFalse);
    }

    private boolean isSpecialEnv(ClassLoader cl) {
        try {
            String pkg = currentPackageName(cl);
            if (pkg != null && specialPackages.contains(pkg)) {
                return true;
            }
            // TYPE_NULL fields (terminals, raw editors) count as special environments too
            Object n1 = singleton(XposedHelpers.findClass("com.tencent.wetype.plugin.hld.utils.n1", cl));
            Integer inputType = (Integer) XposedHelpers.callMethod(n1, "c0");
            return inputType != null && inputType == 0;
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
        XC_MethodReplacement alwaysFalse = XC_MethodReplacement.returnConstant(Boolean.FALSE);
        // j1.f2(): ime_enable_pending_input_to_screen_all_scene ("拼音显示" / direct pre-input)
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.utils.j1", cl, "f2", alwaysFalse);
        // pendinginput.a (ImePendingInputMgr): all pending-to-screen gates
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.pendinginput.a", cl, "t", alwaysFalse);
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.pendinginput.a", cl, "q", int.class, alwaysFalse);
        XposedHelpers.findAndHookMethod("com.tencent.wetype.plugin.hld.pendinginput.a", cl, "u", int.class, alwaysFalse);
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
