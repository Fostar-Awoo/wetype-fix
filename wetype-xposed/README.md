# WeType Fix — 微信输入法 Xposed 模块

针对 **微信输入法 (com.tencent.wetype) 3.5.4 (versionCode 56201)** 的 Xposed 模块，
实现三个功能。产物: `wetype-xposed/wetypefix-v1.1.1.apk`（Termux 内构建: `./build-termux.sh`）。

## v1.1.1 更新（修复 v1.1.0 全部 hook 失败）
- **根因**: v1.1.0 为实现 Termux 无 SDK 构建改用内嵌桩 API 编译，但桩的
  `XposedHelpers.findAndHookMethod` 返回类型写成了 `Object`、
  `XposedBridge.hookMethod` 写成了 `void`，而真实 XposedBridgeApi 均返回
  `XC_MethodHook$Unhook`。**方法描述符包含返回类型**，固化的错误描述符在运行时
  解析不到方法 → 每次调用抛 `NoSuchMethodError` → 三个 hook 全部失败。
- 修复: build-termux.sh 内嵌桩签名与真实 XposedBridgeApi-82 完全一致
  （含新增 `XC_MethodHook.Unhook` 嵌套类），并已 baksmali 核对 dex 中全部
  `Lde/robv/android/xposed/*` 引用描述符无差异。

## v1.1.0 更新
- **Termux 英文候选/纠错禁用不彻底 → 四层防护**：
  1) `WxHldService.onStartInput(EditorInfo,boolean)` **前置**同步记录包名/inputType（原实现依赖的
     `va.e.i0()`/`n1.c0()` 更新时机晚于会话创建，且会话跨应用复用时 SessionConfig 保持陈旧）；
  2) 保留 `j1.t1()/o2()/R1()` 特殊环境强制 false（设置读取层 + 请求层，含 `i0.j5` 的
     TRY_TRIGGER_MOST_LIKELY 拦截）；
  3) 新增 `engine.a.p(SessionConfig)`（i0.z1 → i0.M2 → 创建引擎会话的唯一入口）前置改写
     `enable_english_assisted_spelling_correction/enable_local_correction/enable_auto_most_likely/
     enable_user_hot_word_recommend` 及 `cloud_engine_config.enable_cloud_correction/
     enable_text_recommend`，即使配置来自陈旧快照也会被纠正；
  4) 新增 `i0.N5(ArrayList,int,boolean,boolean,candidate.w,boolean)`（本地候选列表分发到 UI
     监听器的唯一入口，翻页 fetchMore 也走这里）前置过滤英文词候选：带
     `CANDIDATE_FLAG_FULL_ENGLISH(64)`/`EN_RESULT_WITH_SPECIAL_SYMBOL(0x80000000)` 标志，
     或引擎/默认生成（candidateGenerateType∈{0,1}，candidate.d 私有字段 `f`，getter `m()`）且文本为
     纯拉丁字母（不含 CJK/数字/符号/表情，数字、符号、表情候选不受影响）。
- **拼音显示回滚改为动态**：`j1.f2()`、`pendinginput.a.t()/q(int)/u(int)` 从无条件替换改为
  仅在特殊环境强制 false；其它应用不拦截，走原始逻辑（用户未开启该设置时原值本来就是 false，
  不执行任何操作）。WeType 自身在包名变化时（`resetCurrentPackageName → pendinginput.a.x()`）
  会检测 pendingInputToScreenAllSceneMode 变化并自动重绘，切换应用时候选栏/输入框内的字码
  显示即时切换。

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

**Hook**: `j1.f2()`、`pendinginput.a.t()`、`pendinginput.a.q(int)`、`pendinginput.a.u(int)`
仅在特殊环境（Termux 等）时强制返回 false，即回滚旧版"字码在候选栏"行为；
在其它应用不干预（用户开启"拼音显示"则恢复输入框内显示，未开启则原样为 false，不执行任何操作）。
动态切换依赖 WeType 自带的 `pendinginput.a.x()` 模式变化重绘机制。

## 配置（可选）
模块读取 `XSharedPreferences("com.wetypefix", "config")`（需模块本身可写 prefs，本版未带 UI）：
- `fixCapsDelay` (bool, 默认 true)
- `rollbackChineseCode` (bool, 默认 true)
- `specialEnvDisableEnglish` (bool, 默认 true)
- `specialPackages` (string, 逗号分隔, 默认 `com.termux,com.termux.x11`)

## 构建
- Termux 内：`./build-termux.sh`（依赖 `pkg install openjdk-21 dx apktool apksigner`，
  aapt2 用系统 `/system/framework/framework-res.apk` 充当 android.jar；
  dx 不支持 lambda 脱糖，源码需保持 --release 8 + 无 lambda；zipalign 由内嵌 Python 脚本实现）。
- 有 Android SDK 的环境：`./build.sh`（依赖 /opt/android-sdk + /tmp/xapi.jar 即 XposedBridgeApi-82）。
  流程: aapt → javac → d8 → aapt package → zipalign → apksigner。

## 逆向工具产物
- jadx 反编译: `jadx-out/sources/`（jadx 1.5.0, --no-res）
- baksmali 反编译（3 个 dex）: `apk-extract/smali{1..4}/`（baksmali-fat.jar 2.5.2, Bitbucket 下载,
  main class `org.jf.baksmali.Main`; maven central 的薄 jar 缺依赖不可 `java -jar` 直跑）
