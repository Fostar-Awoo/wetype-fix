# WeType Fix — 微信输入法 Xposed 模块

针对 **微信输入法 (com.tencent.wetype) 3.5.4 (versionCode 56201)** 的 Xposed 模块，
实现三个功能。产物: `wetype-xposed/wetypefix-v1.0.0.apk`。

## 安装
1. 安装 APK，在 LSPosed 中启用模块，勾选作用域 `com.tencent.wetype`，重启系统或重启微信输入法进程。
2. 日志 tag: `WeTypeFix` (LSPosed 日志中过滤)。

## 功能与 Hook 点（逆向结论）

### 1. 修复大写取消延迟
**现象**: 英文键盘按一次 Shift（一次性大写, upperMode=2）打出大写字母后，Shift 状态要等引擎
around-text 异步回包（`S.updateAroundText → S.p(FALSE,true) → S.e() → 协程`）才回落到小写，
感知为明显延迟。

**Hook**:
- `com.tencent.wetype.plugin.hld.key.d.R(va.h, boolean)`（按键提交主路径）之后：
  若当前键盘为 EnglishQwerty（`N.K1()`）且 `keyboard.getUpperMode()==2`，
  调用 `S.n(keyboard, 1)`（= `m(1,type)` + `keyboard.o0(1)` + `keyboard.t0()`）同步回落。
- `com.tencent.wetype.plugin.hld.hardware.d.f(boolean, KeyEvent)`（物理键盘 CapsLock key-up，
  private 方法）之后：原逻辑在硬件键盘模式（`hardware.g.j()==true`）下直接跳过状态更新导致
  Shift 状态残留；补上 `S.n(keyboard, isCapsLockOn?3:1)`。

**相关符号**（真实 dex 名，jadx 显示的 fXXXXa 字段名是重命名）:
- 单例: `N.a`, `S.a`, `j1.a`, `pendinginput.a`, `n1.a`, `hardware.g.a`（均为同类型静态字段）
- `keyboard.s`（基类 AbstractC2017s）: `getUpperMode()I` / `o0(I)V` / `t0()V` / `getKeyboardType()Lkeyboard/t;`
- `keyboard.t.c()I` = 枚举 getValue；`t.l` = EnglishQwerty(100)
- `WxHldService` companion 实例在静态字段 `H`（类型 `WxHldService$a`），`$a.e()` 返回 `va.e`

### 2. 特殊输入环境（Termux 等）关闭英文候选与自动纠错
**机制**: 引擎 SessionConfig 在 `model/i0.M2()`（getSessionConfig, jadx 反编译失败, 用 smali 分析,
`apk-extract/smali3/com/tencent/wetype/plugin/hld/model/i0.smali:20789`）中由 `j1` 各 getter 填充:

| j1 getter | 底层设置 | SessionConfig 字段 |
|---|---|---|
| `t1()` = V1() && k1.b() | `ime_enable_english_auto_correction` | `enable_english_assisted_spelling_correction` |
| `o2()` | `ime_plus_detail_spelling_correct` | `enable_local_correction` + cloud `enable_cloud_correction` |
| `R1()` | `ime_enable_associating`(联想) | `enable_auto_most_likely` + cloud `enable_text_recommend` |

**Hook**: 目标环境（包名在 `specialPackages`，或 `EditorInfo.inputType == TYPE_NULL(0)`，
通过 `utils/n1.c0()` 获取）时将 `t1()/o2()/R1()` 强制返回 false。
当前包名获取: `WxHldService$a.e().i0()`（`va.e.i0()` = currentPackageName）。

### 3. 中文字码回滚显示在候选栏
**机制**: 新版"拼音显示"（`isEnableDirectPreInput`）= 设置键
`ime_enable_pending_input_to_screen_all_scene`（读 `j1.f2()`，写 `j1.X4()`，默认值来自服务端
feature flag），开启后中文待上屏字码直接写入输入框（pending-input-to-screen）。关闭时字码显示在
候选栏左侧删除线区域（`ImeCandidateView.s1()` 的 StrikeTv）。

- `pendinginput.a.t()` = 全场景 to-screen；`q(int)` = `t() || u(int)`（搜索框场景）；
  `ImeCandidateView.s1()` 在 `t()==false` 时才显示字码区；`pendinginput.b.a(a,b)` 也是按 `t()` 分流。

**Hook**: 强制 `j1.f2()`、`pendinginput.a.t()`、`pendinginput.a.q(int)`、`pendinginput.a.u(int)`
全部返回 false，即恢复旧版"字码在候选栏"行为。

## 配置（可选）
模块读取 `XSharedPreferences("com.wetypefix", "config")`（需模块本身可写 prefs，本版未带 UI）：
- `fixCapsDelay` (bool, 默认 true)
- `rollbackChineseCode` (bool, 默认 true)
- `specialEnvDisableEnglish` (bool, 默认 true)
- `specialPackages` (string, 逗号分隔, 默认 `com.termux,com.termux.x11`)

## 构建
`./wetype-xposed/build.sh`（依赖 /opt/android-sdk + /tmp/xapi.jar 即 XposedBridgeApi-82）。
流程: aapt → javac --release 11 → d8 → aapt package → zipalign → apksigner。

## 逆向工具产物
- jadx 反编译: `jadx-out/sources/`（jadx 1.5.0, --no-res）
- baksmali 反编译（3 个 dex）: `apk-extract/smali{1..4}/`（baksmali-fat.jar 2.5.2, Bitbucket 下载,
  main class `org.jf.baksmali.Main`; maven central 的薄 jar 缺依赖不可 `java -jar` 直跑）
