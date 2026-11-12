# NovelTTS

NovelTTS 是一个用 Kotlin + Jetpack Compose 写的 **Android TTS 引擎**：自己解析语音包、自己合成音频，
作为系统 TTS provider 被阅读器/浏览器等应用调用，而不是把朗读转发给别的引擎。

项目起点是重实现 [`org.nobody.multitts`](https://github.com/jing332/tts-server-android)（MultiTTS）的行为，
现在已独立成型：包名 `app.noveltts`，与原版 MultiTTS 可共存安装，方便真机对照。

> **当前状态**：`:app:assembleDebug` 通过，13 个单元测试全绿，APK 可产出；
> **但真机运行尚未验证**（Edge 是否真能出声、系统 TTS 是否真调到本应用、界面截图验收都还没做）。
> 详见 [HANDOFF.md](HANDOFF.md) 的状态与卡点章节。

## 与原版 MultiTTS 的区别

旧版本只做「委托代理」：把文本转给设备上另一个 TTS，再把 WAV 回传，本身没有音色。
现在改为真正的引擎实现：

| 能力 | 旧版 | 现在 |
| --- | --- | --- |
| 合成来源 | 转发给系统默认引擎 | 自己解析语音包，走 Edge 在线 / 微软嵌入式离线引擎 |
| 音色 | 系统已装引擎的音色 | `multitts:<packId>:<engineCode>:<voiceCode>` 稳定音色名 |
| 文本处理 | 无 | 规范化 → 替换规则 → 引号对话识别 → 分句 → 角色匹配 → 纯标点过滤 |
| 多音色 | 无 | 旁白/对话/角色各自绑定音色，串行合成后拼接（24 kHz/16-bit/单声道，片段间 80 ms 静音） |
| 语音包 | 只能声明「引擎 + 语音名」 | 直接吃原版语音包（`config.yaml` + `engines.yaml`），带引擎元数据与音色参数 |

`engine/proxy/SystemProxyEngine.kt` 仍然保留，仅作为旧 `pack.json` 兼容参考，**不再是默认真实现**。

## 已有能力

- **系统 TTS 引擎**：`service.MultiTtsService`，注册 `android.intent.action.TTS_SERVICE` + `CATEGORY_DEFAULT`，
  支持语言/音色查询、语速/音调/音量映射、`onStop` 取消，按语音包解析结果合成。
- **语音包**：导入原版 zip（`config.yaml` + `engines.yaml`）或旧版 `pack.json`；本地 SAF 导入、在线目录下载、
  sha256 校验、临时目录安装（失败不破坏已装版本）、删除。
- **音色注册表**：把语音包展开为 `VoiceDescriptor`，离线音色缺许可证时标记 `usable=false` 且**不暴露给系统**。
- **在线引擎（Edge Read Aloud）**：WebSocket 协议 + `Sec-MS-GEC` 校验 + 403 校时重试，MP3 经 `MediaCodec` 解码为 PCM16。
- **离线引擎（微软 Embedded Speech）**：`EmbeddedSpeechConfig.fromPath` + 用户在设置里填入的自己合法许可证。
- **文本管线**：普通/正则替换、中文引号（`“”`/`「」`/`『』`/ASCII）对话切分、句读分句、超长切分、
  显式角色规则匹配（优先级、仅对话）、纯标点过滤（默认开）。
- **工作台界面**：五页（工作台 / 语音包 / 角色 / 替换 / 设置），分段预览、管线告警、音色选择、朗读与停止。
- **系统入口**：`CHECK_TTS_DATA`（回传可用/不可用音色）、`GET_SAMPLE_TEXT`（中英文示例）、`CONFIGURE_ENGINE`（打开应用）。
- 分享文本直接朗读（`ACTION_SEND` / `text/plain`），`--es packUrl` / `--es catalog` 自动化入口。

## 尚未实现 / 不要误认为可用

- **未做真机验证**：Edge 真实发声、MP3 解码、系统 TTS 调用、离线包发声都只有编译与单测证据。
- **微软离线必须用户自己提供合法嵌入式语音许可证**，仓库不携带、也不复制原版 APK 里的授权信息。
- 旧版 `pack.json` 语音包可以安装，但音色不会进入系统音色列表（需要映射到真实引擎）。
- 无前台服务/通知/Quick Settings tile/后台资源锁，长文本在后台可能被系统回收。
- 无自动角色分配、无 LLM 人物识别，未命中的对话一律用默认对话音色；多音字靠替换规则。
- 不兼容 `tts-server-android` 的 JS 插件与配置格式；不引入 Room（规则 JSON + SharedPreferences）。
- 界面只做了编译与静态核对，没有截图验收；MiUI 锁屏时 `adb shell input` 被拒，UI 自动化受限。

## 目录

```
app/src/main/java/app/noveltts/
├── MainActivity.kt              # 入口：分享文本、packUrl、catalog、open=voicepacks
├── data/
│   ├── config/RulesRepository   # 角色/替换规则 JSON 持久化
│   ├── settings/                # SettingsStore + SecretStore（许可证）
│   └── voicepack/               # 原版包解析、安装仓库、在线目录、音色注册表
├── domain/                      # TextPipeline / SynthesisPlanBuilder / Rules
├── engine/
│   ├── api/                     # 引擎契约：SynthesisEngine、Listener、错误码
│   ├── edge/                    # Edge Read Aloud + GEC 校验 + MP3 解码
│   ├── microsoft/               # 微软嵌入式离线引擎
│   ├── EngineManager.kt         # 按 engineCode 选后端
│   ├── MultiSegmentSynthesizer   # 多段串行 + 拼接
│   └── proxy/                   # 旧的系统代理实现（兼容参考）
├── service/MultiTtsService.kt   # 系统 TextToSpeechService
└── ui/                          # MultiTtsScreen 五页、MainViewModel、引擎配置 Activity、主题
docs/VOICE_PACKS.md              # 语音包格式、在线目录协议、安全边界
docs/REIMPLEMENTATION_TARGET.md  # 原版逆向结论与分期目标
samples/demo-xiaomi/             # 示例语音包（pack.json，旧格式）
scripts/                         # build.ps1 / install.ps1 / make-pack.ps1
```

## 构建

前置：JDK 17+、Android SDK（`platforms/android-37.0`、`build-tools/36.x`）、已预热的 Gradle 缓存。

```powershell
./scripts/build.ps1 -Task ':app:compileDebugKotlin' -NoOffline
./scripts/build.ps1 -Task ':app:testDebugUnitTest' -NoOffline
./scripts/build.ps1                                   # 默认 :app:assembleDebug
./scripts/build.ps1 -Clean -NoOffline                 # 需要联网拉依赖时
```

脚本会自动解析 `ANDROID_HOME`、`GRADLE_USER_HOME`（默认 `%LOCALAPPDATA%\MultiTTS\gradle-home`）、
`GRADLE_RO_DEP_CACHE` 并生成 `local.properties`。产物在 `app/build/outputs/apk/debug/app-debug.apk`。

脚本带 UTF-8 BOM，`powershell`（5.1）和 `pwsh`（7+）都能直接跑。

### 构建必须提权的原因

Gradle 启动器需要 JDK 的 NIO selector；JDK 21 在 Windows 上用 AF_UNIX（命名管道）建立回环连接。
受限沙箱会拒绝该调用，表现为：

```
java.io.IOException: Unable to establish loopback connection
```

这不是项目配置问题，`--no-daemon`、换 SelectorProvider 均无效。构建时需以 `danger-full-access` 权限执行。

## 安装

```powershell
./scripts/install.ps1 -Rebuild     # 构建 + 安装 + 启动
./scripts/install.ps1              # 只安装现有 APK
```

## 语音包

原版语音包 zip 的根目录有 `config.yaml`（引擎代码 → 音色列表）与 `engines.yaml`（引擎元数据），
直接导入即可；旧版 `pack.json` 仍能安装。格式、字段与在线目录协议见
[`docs/VOICE_PACKS.md`](docs/VOICE_PACKS.md)。

```powershell
# 本地联调：静态服务 + 设备回环
adb reverse tcp:8765 tcp:8765
python -m http.server 8765

# 直接安装 zip 直链 / 设置在线目录
adb shell am start --es packUrl "http://127.0.0.1:8765/pack.zip" -n app.noveltts/.MainActivity
adb shell am start --es catalog "http://127.0.0.1:8765/catalog.json" -n app.noveltts/.MainActivity
```

## 作为系统 TTS 使用

1. 安装并打开应用，在「语音包」里导入语音包；
2. 系统设置 → 语音合成（部分 ROM 在「无障碍」）→ 首选引擎选 **NovelTTS**；
3. 其他应用调用 TTS 时，系统只会看到 `usable=true` 的 `multitts:...` 音色；
4. 想验证引擎被调用：`adb logcat -s MultiTtsService`，应看到 `synthesize` 相关日志。

## 已知限制

- **服务必须声明 `android.intent.category.DEFAULT`**：`TextToSpeech` 用
  `queryIntentServices(..., MATCH_DEFAULT_ONLY)` 枚举引擎，少了该 category 时 `getEngines()` 不含本应用，
  显式指定引擎会被静默回落到默认引擎（实测踩过）。
- 系统默认引擎切换无法用 adb 完成（MIUI 拒绝 `WRITE_SECURE_SETTINGS`），需手动在设置里选。
- 打包需排除 netty 的 `META-INF/INDEX.LIST`（微软语音 SDK 的传递依赖各带一份，重复会打不出包）。
- 语音包目录默认允许明文 HTTP（自建 NAS/局域网场景），公网分发请用 HTTPS 并填写 `sha256`。
- `values/strings.xml` 与 `values-zh-rCN/strings.xml` 内容相同，默认语言仍是中文。
- **音色前缀仍是 `multitts:`**：应用已改名 NovelTTS，但系统音色名保持
  `multitts:<packId>:<engineCode>:<voiceCode>` 不变——它同时被第三方阅读器、系统设置和本应用设置记住，
  改前缀会让所有已选音色失效。计划功能稳定后统一迁移，迁移清单见
  [docs/VOICE_PACKS.md](docs/VOICE_PACKS.md) 的「前缀为什么没跟着改名」一节。
- **离线音色（微软 Embedded）需要额外两个条件**：
  1. Microsoft Embedded Speech 许可证（设置页粘贴，Keystore 加密存储）；
  2. 微软嵌入式语音的原生库（`embedded.tts`、`embedded.tts.runtime`、`telemetry` 与 `libc++_shared`）——
     Maven 上的 `client-sdk` AAR **不含**这几个库，仓库也不分发它们。把对应 `.so` 放进
     `app/src/main/jniLibs/arm64-v8a/` 后才能出声；缺少时合成会报
     `SPXERR_EXTENSION_LIBRARY_NOT_FOUND`。原生库版本需与 `client-sdk` 一致
     （当前固定在 `1.37.0`，与原版 MultiTTS 内置版本相同）。
- 真机为 MIUI 且常处于锁屏，`adb shell input` 被系统拒绝，UI 自动化需先解锁或改用模拟器。

## 下一步

按 [HANDOFF.md](HANDOFF.md) §5 的顺序推进，当前第一件事是真机验收：

1. 安装 APK，五页界面截图验收；
2. 导入 Edge 语音包，确认音色出现在系统 TTS 列表并能出声；
3. 导入离线微软包，确认无许可证时可见但不可用、且不暴露给系统；
4. 用小说文本验证旁白/对话分段、角色规则、多音字替换、纯标点过滤；
5. 再做 P4（前台通知/QS tile/后台锁）与本地模型扩展。
