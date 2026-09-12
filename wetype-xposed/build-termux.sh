#!/usr/bin/env bash
# Termux 环境构建脚本（无需 Android SDK / proot）：
#   javac(Termux openjdk) + dx(Termux) + aapt2(Termux) + 系统framework-res.apk + apksigner(Termux)
# 依赖: pkg install openjdk-21 dx apktool apksigner
set -e
cd "$(dirname "$0")"
OUT=wetypefix-v1.1.1.apk
WORK=${TMPDIR:-/data/data/com.termux/files/home/tmp}/wetypefix-build
STUBS="$WORK/stubs"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$STUBS"

# 1) 编译所需的 Android/Xposed API 桩（仅编译期使用，不打进 dex）
mkdir -p "$STUBS/de/robv/android/xposed/callbacks" "$STUBS/android/view/inputmethod"
cat > "$STUBS/android/view/KeyEvent.java" <<'EOF'
package android.view;
public class KeyEvent { public boolean isCapsLockOn() { return false; } }
EOF
cat > "$STUBS/android/view/inputmethod/EditorInfo.java" <<'EOF'
package android.view.inputmethod;
public class EditorInfo {
    public static final int TYPE_NULL = 0;
    public String packageName;
    public int inputType;
}
EOF
cat > "$STUBS/de/robv/android/xposed/IXposedHookLoadPackage.java" <<'EOF'
package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
EOF
cat > "$STUBS/de/robv/android/xposed/XC_MethodHook.java" <<'EOF'
package de.robv.android.xposed;
import java.lang.reflect.Member;
public class XC_MethodHook {
    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
    }
    public static class Unhook {
        public void unhook() {}
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
EOF
cat > "$STUBS/de/robv/android/xposed/XC_MethodReplacement.java" <<'EOF'
package de.robv.android.xposed;
public abstract class XC_MethodReplacement extends XC_MethodHook {
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable { return null; }
    public static XC_MethodReplacement returnConstant(final Object value) {
        return new XC_MethodReplacement() {};
    }
}
EOF
cat > "$STUBS/de/robv/android/xposed/XposedBridge.java" <<'EOF'
package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) { return null; }
}
EOF
cat > "$STUBS/de/robv/android/xposed/XSharedPreferences.java" <<'EOF'
package de.robv.android.xposed;
import java.io.File;
public final class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public File getFile() { return null; }
    public void reload() {}
    public boolean getBoolean(String key, boolean def) { return def; }
    public String getString(String key, String def) { return def; }
}
EOF
cat > "$STUBS/de/robv/android/xposed/XposedHelpers.java" <<'EOF'
package de.robv.android.xposed;
public final class XposedHelpers {
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) { return null; }
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static long getLongField(Object obj, String fieldName) { return 0L; }
    public static int getIntField(Object obj, String fieldName) { return 0; }
    public static void setBooleanField(Object obj, String fieldName, boolean value) {}
}
EOF
cat > "$STUBS/de/robv/android/xposed/callbacks/XC_LoadPackage.java" <<'EOF'
package de.robv.android.xposed.callbacks;
public final class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
    }
}
EOF

# 2) 编译（--release 8：dx 不支持 invokedynamic 脱糖，源码需避免 lambda）
#    桩 API 的方法签名（含返回类型）必须与真实 XposedBridgeApi 完全一致：
#    方法描述符包含返回类型，不一致会在运行时抛 NoSuchMethodError
javac --release 8 -Xlint:-options -nowarn -d "$WORK/classes" \
  $(find "$STUBS" -name "*.java") src/com/wetypefix/MainHook.java

# 3) jar + dx -> classes.dex
(cd "$WORK/classes" && jar cf "$WORK/module.jar" com)
dx --dex --min-sdk-version=24 --output="$WORK/classes.dex" "$WORK/module.jar"

# 4) aapt2 打包资源（用系统 framework-res.apk 充当 android.jar）
aapt2 compile --dir res -o "$WORK/res.zip"
aapt2 link -o "$WORK/base.apk" -I /system/framework/framework-res.apk \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 35 res.zip 2>/dev/null ||
aapt2 link -o "$WORK/base.apk" -I /system/framework/framework-res.apk \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 35 "$WORK/res.zip"

# 5) 组装 + zipalign(4, 全部 STORED) + 签名
python3 - "$WORK" <<'EOF'
import struct, zipfile, zlib, time, sys, os
work = sys.argv[1]
src = zipfile.ZipFile(os.path.join(work, 'base.apk'))
entries = [(i.filename, src.read(i.filename)) for i in src.infolist()]
entries.append(('classes.dex', open(os.path.join(work, 'classes.dex'), 'rb').read()))
entries.append(('assets/xposed_init', open('assets/xposed_init', 'rb').read()))
t = time.localtime()[:6]
dtime = ((t[0]-1980)<<9) | (t[1]<<5) | t[2]
ddate = (t[3]<<11) | (t[4]<<5) | (t[5]//2)
out = bytearray(); central = bytearray()
for fn, data in entries:
    fname = fn.encode()
    crc = zlib.crc32(data) & 0xffffffff
    offset = len(out)
    base = offset + 30 + len(fname)
    pad = (4 - (base % 4)) % 4
    lfh = struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, 0, 0, dtime, ddate, crc, len(data), len(data), len(fname), pad)
    out += lfh + fname + (b'\x00'*pad) + data
    central += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, 0, 0, dtime, ddate, crc, len(data), len(data), len(fname), 0, 0, 0, 0, 0, offset) + fname
cd_offset = len(out); out += central
out += struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(entries), len(entries), len(central), cd_offset, 0)
open(os.path.join(work, 'aligned.apk'), 'wb').write(bytes(out))
print('zipaligned:', len(out), 'bytes')
EOF

if [ ! -f debug.jks ]; then
  keytool -genkeypair -keystore debug.jks -storetype JKS -storepass android -keypass android \
    -alias key0 -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=wetypefix" 2>/dev/null
fi
rm -f "$OUT" "$OUT.idsig"
apksigner sign --ks debug.jks --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 24 --out "$OUT" "$WORK/aligned.apk"
apksigner verify --print-certs "$OUT" >/dev/null && echo "OK: $OUT"
