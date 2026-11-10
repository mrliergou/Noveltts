# MultiTTS 复刻版

以 Kotlin + Jetpack Compose 重实现 `org.nobody.multitts` 的**系统 TTS 引擎**能力。
复刻版包名为 `org.nobody.multitts.replica`，可与原版共存安装，便于真机对照。

> 当前状态：**P0 引擎链路已打通**。应用已注册 `TextToSpeechService`，可作为系统语音引擎被其他应用绑定；
> 合成目前**委托给设备上已有的引擎**（默认优先系统默认引擎），自身尚无音色模型。

## 已有能力

- 系统 TTS 引擎：`service.MultiTtsService`（`android.intent.action.TTS_SERVICE`），支持语言/音色查询、语速/音调/音量映射、停止与取消
- 委托代理合成：把文本转交选定引擎 `synthesizeToFile`，解码 WAV（PCM 8/16/24/32、float32）后按 PCM 分块流式回传
- Compose 工作台：文本输入、语速/音量/音调滑块、**真实引擎选择**（跟随系统默认 / 任一已装引擎）、音色选择、朗读与停止
- **语音包**：导入本地 zip、填写在线目录地址后下载安装、查看/应用/删除；包内音色可绑定到"引擎 + 语音名"，应用即切换
- 分享文本直接朗读（`ACTION_SEND` / `text/plain`）
- 角色、替换规则、最近文本本地持久化（SharedPreferences）
- 读取系统已安装语音引擎的音色列表

## 尚未实现（不要误认为可用）

- 角色与替换规则只做字符串保存，**从未参与合成**
- 无多音色分角色朗读、无本地模型、无前台服务/通知/QS 磁贴
- 引擎只会转发给别的引擎；把系统默认引擎切成复刻版后，若设备上再没有其他引擎，将无音源

## 目录

```
app/src/main/java/org/nobody/multitts/replica/
├── MainActivity.kt          # 入口，处理分享文本
├── data/                    # SpeechController（系统 TTS 客户端）、SpeechVoice、voicepack/（语音包解析/仓库/在线目录）
├── engine/                  # 引擎契约（api/EngineApi）、引擎枚举（EngineCatalog）、委托代理（proxy/）
├── service/                 # MultiTtsService：系统 TextToSpeechService 实现
├── ui/                      # Compose 界面与 MainViewModel
└── ui/theme/                # 配色与主题
docs/REIMPLEMENTATION_TARGET.md   # 原版逆向结论与分期目标
docs/VOICE_PACKS.md          # 语音包格式、在线目录协议、导入/下载与安全边界
samples/demo-xiaomi/         # 示例语音包（pack.json）
scripts/                     # build.ps1 / install.ps1 / make-pack.ps1
```

## 构建

前置：JDK 17+、Android SDK（`platforms/android-37.0`、`build-tools/36.x`）、已预热的 Gradle 离线缓存。

```powershell
./scripts/build.ps1                       # 默认 :app:assembleDebug
./scripts/build.ps1 -Task :app:assembleRelease
./scripts/build.ps1 -Clean -NoOffline     # 需要联网拉依赖时
```

脚本会自动解析 `ANDROID_HOME`、`GRADLE_USER_HOME`（默认 `%LOCALAPPDATA%\MultiTTS\gradle-home`）、
`GRADLE_RO_DEP_CACHE` 并生成 `local.properties`，不再需要手工 export。

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 构建必须提权的原因

Gradle 启动器需要 JDK 的 NIO selector；JDK 21 在 Windows 上用 AF_UNIX（命名管道）建立回环连接。
受限沙箱会拒绝该调用，表现为：

```
java.io.IOException: Unable to establish loopback connection
```

这不是项目配置问题，`--no-daemon`、换 SelectorProvider 均无效。构建时必须以
`danger-full-access` 权限执行 `scripts/build.ps1`。

## 安装

```powershell
./scripts/install.ps1 -Rebuild     # 构建 + 安装 + 启动
./scripts/install.ps1              # 只安装现有 APK
```

设备签名不一致时脚本会提示先 `uninstall`，因为旧的复刻版安装包使用了不同的调试签名。

## 已知限制

- **服务必须声明 `android.intent.category.DEFAULT`**：`TextToSpeech` 用 `queryIntentServices(..., MATCH_DEFAULT_ONLY)` 枚举引擎，
  少了该 category 时 `getEngines()` 不含本应用，显式指定引擎会被静默回落到默认引擎（实测踩过）
- 系统默认引擎切换无法用 adb 完成（MIUI 拒绝 `WRITE_SECURE_SETTINGS`），需在“设置 → 语音合成”手动选择
- 无测试依赖（离线缓存中缺少 JUnit/AndroidX Test），单测需联网补齐后再接入
- 语音包目录默认允许明文 HTTP（自建 NAS/局域网场景），公网分发请用 HTTPS 并填写 `sha256`
- `values/strings.xml` 与 `values-zh-rCN/strings.xml` 内容相同，默认语言仍是中文
- 真机为 MIUI 且常处于锁屏，`adb shell input` 被系统拒绝，UI 自动化需先解锁或改用模拟器

## 真机验证（2026-11-10）

```powershell
# 用显式引擎启动（不改系统默认），随后观察 logcat
adb shell am start -a android.intent.action.SEND -t text/plain `
  --es android.intent.extra.TEXT "复刻版引擎自检。" `
  --es engine org.nobody.multitts.replica `
  -n org.nobody.multitts.replica/.MainActivity
adb logcat -s MultiTtsService
```

预期日志：`Sucessfully bound to org.nobody.multitts.replica` → `delegating synthesis to <engine>` →
`streaming pcm: 24000Hz fmt=2 ch=1` → `synthesis finished`，同时客户端进程出现 `AudioTrack` 播放。

## 语音包

详见 [`docs/VOICE_PACKS.md`](docs/VOICE_PACKS.md)。最简流程：

```powershell
# 1) 打包（输出 zip 路径与可直接粘贴进 catalog.json 的字段）
./scripts/make-pack.ps1 -SourceDir ./samples/demo-xiaomi -OutputDir ./dist

# 2) 本地联调：起一个静态服务并让设备回环访问
adb reverse tcp:8765 tcp:8765
python -m http.server 8765   # 在 dist 所在目录

# 3) 直接安装 zip 直链 / 指定在线目录
adb shell am start --es packUrl "http://127.0.0.1:8765/demo-xiaomi-1.0.0.zip" -n org.nobody.multitts.replica/.MainActivity
adb shell am start --es catalog "http://127.0.0.1:8765/catalog.json" -n org.nobody.multitts.replica/.MainActivity
```

应用内「语音包」界面同样支持 SAF 导入本地 zip 与在线下载（含 sha256 校验、临时目录安装、失败不破坏已装版本）。

## 后续规划

分阶段目标见 [`docs/REIMPLEMENTATION_TARGET.md`](docs/REIMPLEMENTATION_TARGET.md)：
P0 引擎注册 → P1 工作台收口 → P2 数据层与 CRUD → P3 语义合成（分句/角色/多音色）→ P4 系统体验 → P5 在线扩展。

当前 P0 采用**委托代理**策略：`TextToSpeechService` 把请求转发给设备上已安装的其他引擎，
先把引擎契约、参数映射与真机链路打通；本地模型引擎作为独立模块后续接入。
