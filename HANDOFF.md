# MultiTTS 复刻版开发交接

最后更新：2026-11-12  
项目根目录：`F:\codex\MultiTTS`  
目标设备：`M2102J2SC`，Android 13 / SDK 33，arm64-v8a，adb 序列号记录为 `5cb24277`

## 0. 新会话先看这里

**一句话状态：** 数据层、语音包解析、语音注册表、Edge/微软引擎、文本角色替换管线和系统 TTS 服务都已写完并有单元测试；唯一的大断点是 `ui/MultiTtsScreen.kt` 被删除后尚未重建，所以当前仓库不能完整编译。

新会话请按以下顺序行动：

1. 先读本文件第 2、4、5、6 节，不要重新设计已经完成的数据层和引擎层。
2. 用只读方式核对 `ui/MainViewModel.kt`、`MainActivity.kt`、`service/MultiTtsService.kt` 的实际 API。
3. 第一项代码工作必须是重建 `ui/MultiTtsScreen.kt`，并保留 `MainActivity.kt` 现有调用签名。
4. 编译和单测通过后，再做 TTS 配置 Activity、Manifest intent filter 和真机验证。

**当前工作区不是干净状态。** `git status` 里已有大量未提交的新增/修改文件，其中包括：

- 已删除的 `ui/MultiTtsScreen.kt`（必须重建，不要用 `git checkout` 恢复旧界面）。
- 新增的数据、领域、引擎、测试目录。
- 未跟踪的 119 MB 离线测试包：
  `语音包\【离线】 微软官方语音（晓晓+云希）(1).zip`

这个离线包只用于本地测试，**不要提交进 Git**。也不要执行 `git reset --hard`、`git clean -fdx` 或回滚任何已完成改动。

**当前绝对不能宣称已完成的事项：**

- 当前代码尚未通过完整编译（缺 `MultiTtsScreen`）。
- Edge 在线真实发声、MP3 解码、系统 TTS 调用、离线包导入后的真机发声都尚未验证。
- 微软离线引擎在没有用户合法 Embedded Speech 许可证时只验证了“缺许可证会报错”，不能宣称真实离线发声已完成。

## 1. 我们在做什么

把当前 Kotlin/Compose 原型改造成一个真正独立工作的 Android TTS 引擎，而不是把系统声音转发给其他已安装 TTS。

用户明确的核心需求：

1. 应用自身能导入离线或在线语音包并直接发声。
2. 被 Android 系统识别为 TTS provider，其他应用选择 MultiTTS 后能听到本应用引擎生成的音频。
3. 支持小说旁白/中文引号对话分段，不同角色可绑定不同音色。
4. 支持显式角色规则、普通/正则替换规则、多音字替换。
5. 支持纯标点片段过滤开关，默认开启。
6. 对标并尽量超过 `jing332/tts-server-android` 的引擎能力。

已锁定的首版边界：

- 不支持 `tts-server-android` 的 JS 插件或配置兼容。
- 不做 LLM 人物识别，不做自动角色分配。
- 角色匹配只使用显式规则；未命中对话使用默认对话音色。
- 首版用普通字符串替换和正则替换实现多音字，不做音素编辑器。
- 不引入 Room；规则用 JSON 文件，设置用 SharedPreferences。
- 在线音源是实现 Edge Read Aloud 非官方接口，接受上游接口可能变化的风险。

## 2. 当前状态一句话总结

数据层、原版语音包解析、统一音色注册表、Edge 引擎、微软嵌入式引擎、文本/角色/替换管线和系统 TTS 服务主体已经写完，相关纯 Kotlin 测试已通过；**界面重写尚未完成，当前仓库因此处于暂时不可编译的中间状态**。

最直接的断点：

- `app/src/main/java/org/nobody/multitts/replica/ui/MultiTtsScreen.kt` 已被删除，等待按新 `MainViewModel` 重写。
- `MainActivity.kt` 仍然调用：
  ```kotlin
  MultiTtsScreen(
      viewModel = viewModel,
      onOpenSystemSpeechSettings = ::openSystemSpeechSettings,
      openVoicePacksOnStart = intent?.getStringExtra(EXTRA_OPEN) == OPEN_VOICE_PACKS,
  )
  ```
  新界面必须保留这个函数签名。
- 因此下一次构建大概率首先报 `Unresolved reference 'MultiTtsScreen'`。

不要回滚或删除已经完成的数据/引擎代码来“先让项目能跑”。

## 3. 已经完成的内容

### 3.1 Gradle 与依赖

`app/build.gradle.kts` 已修改：

- `minSdk = 26`，这是微软 Embedded Speech SDK 的要求。
- `abiFilters += listOf("arm64-v8a")`，目标真机是 arm64；x86_64 模拟器需要临时修改。
- 新增：
  ```kotlin
  implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.51.0")
  implementation("org.yaml:snakeyaml:2.3")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  testImplementation("junit:junit:4.13.2")
  ```
- 已成功联网解析依赖并完成过一次 `:app:compileDebugKotlin`。
- 微软 AAR 内确认存在 `EmbeddedSpeechConfig`、`SpeechSynthesizer`、PCM 输出和 arm64 native 库。

构建命令：

```powershell
& 'F:\codex\MultiTTS\scripts\build.ps1' -Task ':app:compileDebugKotlin' -NoOffline
& 'F:\codex\MultiTTS\scripts\build.ps1' -Task ':app:testDebugUnitTest' -NoOffline
```

受限沙箱会拦截 Gradle/JDK 的 AF_UNIX 回环管道，需要提权执行。不要把沙箱错误误判成代码错误。

### 3.2 原版语音包解析与安装

新增/修改：

- `data/voicepack/VoicePack.kt`
- `data/voicepack/OriginalPackParser.kt`
- `data/voicepack/VoicePackStore.kt`
- `data/voicepack/VoiceDescriptor.kt`
- `data/voicepack/VoiceRegistry.kt`
- `data/voicepack/VoicePackEvents.kt`

支持两种包：

1. 原版包：zip 根目录同时包含 `config.yaml` 和 `engines.yaml`。
2. 旧版兼容包：zip 根目录包含 `pack.json`。

原版包事实：

```text
config.yaml
engines.yaml
microsoft/<voiceCode>/...      # 离线微软模型时可存在
```

`config.yaml` 是“引擎代码 -> speaker 列表”，YAML 带类似标签：

```yaml
edgetts:
- !!org.nobody.multitts.tts.speaker.Speaker
  code: zh-CN-XiaoxiaoNeural
  name: 女青年-晓晓
  locale: zh-CN
  gender: 0
  param: zh-CN-XiaoxiaoNeural
  type: 1
  sampleRate: 24000
  speed: 1.0
  pitch: 1.0
  volume: 1.0
```

`engines.yaml`：

```yaml
!!org.nobody.multitts.tts.engine.EngineConfig
engines:
- code: edgetts
  name: EdgeTTS
  type: inner
```

已实现行为：

- 去掉 `!!org.nobody...` 类型标签后，用 SnakeYAML `SafeConstructor` 解析为普通 map/list。
- 从 `config.yaml` 根键补全 `engineCode`。
- `type == 1` 或 `edgetts` 视为在线；`microsoft` 视为离线。
- 原版包 ID 为 `"vp-" + SHA256(config.yaml + 0x00 + engines.yaml).take(16)`。
- 写入 `installed.json`，记录格式、安装时间、来源和引擎代码。
- 安装流程：临时目录解压 -> 校验 -> 解析 -> 备份旧目录 -> 替换；失败恢复旧版本。
- 已增加 Zip Slip、条目数、压缩/解压大小和可用空间检查。
- 兼容旧 `pack.json`，但旧格式音色目前不参与本应用真实合成。

本地现有两个原版包：

- `语音包\【在线】 EdgeTTS（中文+英文）.zip`
- `语音包\【离线】 微软官方语音（晓晓+云希）(1).zip`

离线包中实际模型目录：

```text
microsoft/zh-CN-XiaoxiaoNeural/
microsoft/zh-CN-YunxiNeural/
```

离线 `param` 是类似：

```text
Microsoft Xiaoxiao (Natural) - Chinese (Simplified, China)
```

### 3.3 统一音色注册表

`VoiceRegistry` 只为原版包生成可调用音色，系统音色名固定格式：

```text
multitts:<packId>:<engineCode>:<voiceCode>
```

可用性规则：

- Edge：格式支持且包已安装即 `usable=true`。
- 微软离线：模型目录存在、许可证已配置才 `usable=true`。
- 未配置许可证的离线音色仍应显示在应用内，并标注原因，但**不能出现在系统 TTS 的 `onGetVoices()` 中**。

### 3.4 设置、密钥和规则持久化

新增：

- `data/settings/SettingsStore.kt`
- `data/settings/SecretStore.kt`
- `data/config/RulesRepository.kt`
- `domain/Rules.kt`

已实现：

- `punctuationFilter` 默认 `true`。
- 旁白、对话、系统默认音色 ID。
- Edge 端点。
- Microsoft Embedded Speech 许可证。
- 许可证用 Android Keystore AES-GCM 加密后存 SharedPreferences，不写明文，不打印日志。
- 角色规则存 `files/config/roles.json`。
- 替换规则存 `files/config/replacements.json`。
- JSON 带 `schema=1`，写入使用 `.tmp` 并保留 `.bak`。

### 3.5 文本管线

新增：

- `domain/TextPipeline.kt`
- `domain/SynthesisPlanBuilder.kt`

固定处理顺序：

1. 文本规范化。
2. 按 `order` 顺序执行启用的替换规则。
3. 识别 `“”`、`「」`、`『』`、ASCII 双引号。
4. 引号外为旁白，引号内为对话。
5. 按句号、问号、感叹号、分号、省略号、换行分句。
6. 单段超过 500 字继续按标点/空格切分。
7. 显式角色规则按优先级匹配对话；未命中用默认对话音色。
8. 纯标点过滤默认开启；关闭时保留。
9. 生成有序 `PlannedSegment(text, VoiceDescriptor)`。

正则规则非法时返回带规则名的 `PipelineIssue`，不会让整个合成崩溃。

已测试：

- 旁白/中文引号对话分离。
- 角色规则高优先级胜出。
- 普通替换与正则分组替换顺序。
- 纯标点过滤开/关。

### 3.6 多段合成

新增：

- `engine/EngineManager.kt`
- `engine/MultiSegmentSynthesizer.kt`

行为：

- 按片段顺序串行合成。
- 统一要求输出 `24 kHz / PCM16 / 单声道`。
- 片段之间插入 80 ms 静音。
- 任一片段失败向上传播错误。
- 取消时取消当前引擎并丢弃后续片段。

### 3.7 Edge 在线引擎

新增：

- `engine/edge/EdgeGec.kt`
- `engine/edge/EdgeTtsEngine.kt`
- `engine/edge/Mp3PcmDecoder.kt`

已实现：

- OkHttp WebSocket。
- 当前端点默认：
  ```text
  wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1
  ```
- 鉴权参数：
  ```text
  Ocp-Apim-Subscription-Key=6A5AA1D4EAFF4E9FB37E23D68491D6F4
  ```
- Token 参数：
  ```text
  Sec-MS-GEC=<v2 token>
  Sec-MS-GEC-Version=2-155.0.4283.45
  ConnectionId=<随机 hex>
  ```
- 发送 `speech.config` 和 SSML。
- 解析 `turn.end`。
- 解析二进制音频帧。
- 把 MP3 临时写入 cache。
- 用 MediaExtractor + MediaCodec 解码，再转成 24 kHz 单声道 PCM16。
- 403 时读取响应 `Date` 校正时钟并重试一次。
- 429 返回可读错误。

`EdgeGec` 已与开源 `rany2/edge-tts` 参考算法对照，固定测试向量全部通过：

```text
unixtime 0:
52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6

unixtime 1700000000:
2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2

unixtime 1762500000.5:
713C2BF431375CEC-2322C6B3E513D50276E09641CE873C8914A93295C8DDA6121A6CBE99EDD41784
```

### 3.8 微软嵌入式离线引擎

新增：

- `engine/microsoft/MicrosoftEmbeddedEngine.kt`

调用路径：

```kotlin
EmbeddedSpeechConfig.fromPath(modelDir.absolutePath)
config.setSpeechSynthesisVoice(descriptor.param, license)
config.setSpeechSynthesisOutputFormat(Raw24Khz16BitMonoPcm)
SpeechSynthesizer(config, null as AudioConfig?)
synthesizer.SpeakSsml(ssml)
```

行为：

- 无许可证返回 `LICENSE_REQUIRED`，明确提示用户配置。
- 模型目录不存在返回 `BAD_REQUEST`。
- 合成结果必须为 `ResultReason.SynthesizingAudioCompleted`。
- 释放 `SpeechSynthesisCancellationDetails`、`SpeechSynthesizer`、`EmbeddedSpeechConfig`。
- 取消使用 `StopSpeakingAsync()`。

注意：**没有用户自己的合法 Embedded Speech 许可证时，离线真实发声尚未验证，也不能宣称已完成。**

### 3.9 系统 TTS 服务

`service/MultiTtsService.kt` 已重写：

- 直接调用本应用 Edge / 微软引擎，不再默认走 `SystemProxyEngine`。
- `onGetVoices()` 只返回 `usable=true` 的音色。
- Edge 音色设置 `requiresNetwork=true`。
- `onSynthesizeText()` 使用 `TextPipeline` + `SynthesisPlanBuilder` + `MultiSegmentSynthesizer`。
- 输出回调顺序为 `callback.start -> audioAvailable -> done`。
- `onStop()` 取消当前合成。
- 语言查询、Voice 校验、默认 Voice 基于当前注册表。
- 收到应用内广播 `VOICE_PACKS_CHANGED` 后刷新缓存。
- 合成等待上限 600 秒。

### 3.10 ViewModel 已改成直接合成

`ui/MainViewModel.kt` 已重写，新增：

- 五个页面状态：工作台、语音包、角色、替换、设置。
- 直接调用 `MultiSegmentSynthesizer`。
- 用 `AudioTrack` 流式播放本应用 PCM。
- 旁白/对话/系统默认音色选择。
- 角色和替换规则增删改、启停。
- 许可证、Edge 端点、纯标点过滤设置。
- 语音包导入、删除、在线目录下载。
- 语音包安装后广播刷新系统 TTS 音色。
- 工作台分段预览。

**当前缺的是与这个 ViewModel 对应的新 Compose 页面。**

### 3.11 测试状态

最后一次完整单测结果：`7 tests completed`，全部通过。

测试文件：

- `app/src/test/java/.../engine/edge/EdgeGecTest.kt`
- `app/src/test/java/.../domain/TextPipelineTest.kt`

已覆盖：

- Edge SHA-256 中间摘要。
- Edge CS64 中间值。
- Edge v2 最终固定向量。
- 引号对话分段。
- 角色规则优先级。
- 替换顺序和正则分组。
- 纯标点过滤。

UI 删除后没有再次运行全量构建；预计唯一首要错误是 `MultiTtsScreen` 缺失。

## 4. 当前卡点

### P0：必须马上完成

1. 重新创建 `ui/MultiTtsScreen.kt`。
2. 保持签名：
   ```kotlin
   @Composable
   fun MultiTtsScreen(
       viewModel: MainViewModel,
       onOpenSystemSpeechSettings: () -> Unit,
       openVoicePacksOnStart: Boolean = false,
   )
   ```
3. 页面至少包含：
   - 工作台：文本、旁白/对话音色、语速/音调/音量、朗读/停止、分段预览。
   - 语音包：导入 zip、已安装包、在线/离线状态、删除、在线目录。
   - 角色：名称、正则、音色、优先级、仅匹配对话、启停、删除。
   - 替换：名称、查找、替换、正则、顺序、启停、测试预览。
   - 设置：纯标点过滤、默认音色、许可证、Edge 端点、打开系统语音设置。
4. `openVoicePacksOnStart=true` 时切到语音包页。
5. 完成后重新跑：
   ```powershell
   & 'F:\codex\MultiTTS\scripts\build.ps1' -Task ':app:compileDebugKotlin' -NoOffline
   & 'F:\codex\MultiTTS\scripts\build.ps1' -Task ':app:testDebugUnitTest' -NoOffline
   ```

### P1：系统体验缺口

- `MainActivity` 目前只处理启动、分享文本、`packUrl`、`catalog`、`open=voicepacks`。
- 尚未增加 TTS 配置 Activity：
  - `android.speech.tts.engine.CHECK_TTS_DATA`
  - `android.speech.tts.engine.GET_SAMPLE_TEXT`
  - `android.speech.tts.engine.CONFIGURE_ENGINE`
- Manifest 尚未注册这些 intent filter。
- 系统设置里的“试听/配置”入口目前还不完整。

### P2：运行时未验证

- 尚未导入两个真实 zip 做设备测试。
- Edge 在线真实发声尚未验证。
- MP3 解码器尚未在真机验证。
- 系统 TTS 调用本应用引擎尚未验证。
- 微软离线无许可证路径可验证“可见但不可用”；真实发声必须等用户提供合法许可证。

## 5. 下一步计划

建议按这个顺序：

1. 写回 `MultiTtsScreen.kt`，接上现有 `MainViewModel`。
2. 编译，修 Compose/API 错误。
3. 增加 TTS 数据检查/示例文本 Activity 和 Manifest 配置。
4. `assembleDebug`，安装到真机。
5. 通过 SAF 或 `packUrl` 导入在线 Edge 包。
6. 检查：
   - 应用内音色列表出现在线音色。
   - 系统 TTS 音色列表出现同名 `multitts:...` 音色。
   - 应用内朗读能听到声音。
   - 其他应用调用系统 TTS 能听到声音。
7. 导入离线微软包：
   - 无许可证时应用内可见且标注原因。
   - 系统 TTS 不暴露该音色。
8. 用户提供合法许可证后测试离线发声。
9. 测试小说文本：
   - 旁白/对话不同音色。
   - 角色规则命中。
   - 多音字替换。
   - 纯标点过滤开关差异。
10. 更新：
    - `README.md`
    - `docs/VOICE_PACKS.md`
    - `docs/REIMPLEMENTATION_TARGET.md`

## 6. 绝对不能重复踩的坑

### 6.1 不要再把“系统代理”当默认实现

旧实现 `SystemProxyEngine` 只是把请求转发给设备上另一个 TTS。它不是本项目核心功能。  
可以保留作旧 `pack.json` 兼容参考，但不能回到“应用只能转发系统声音”的状态，也不能把外部引擎标签伪装成真实供应方。

### 6.2 YAML 不能直接反序列化成原版类

原版标签：

```text
!!org.nobody.multitts.tts.speaker.Speaker
!!org.nobody.multitts.tts.engine.EngineConfig
```

本项目没有这些类。必须先去掉标签，再用 `SafeConstructor` 解析普通 map/list。不要在运行时加载原版 APK 的类。

### 6.3 不要复制原版 APK 的模型、native 库或许可证

- 不从原 APK 提取授权信息。
- 不伪造许可证。
- 不把微软模型打进应用仓库。
- 用户导入原版离线包只代表模型文件存在，仍需合法 Embedded Speech 许可证。

### 6.4 Edge v2 Token 不是普通 SHA-256

已修正并验证的公式：

```text
ticks = unixTimestamp + 11644473600
ticks -= ticks % 300
ticks *= 10_000_000        // 不是 10_000_000 / 100
data = ticks.toLong().toString() + TRUSTED_CLIENT_TOKEN
```

最后 token：

```text
<CS64 16 hex>-<SHA256 64 hex>
```

两个容易错的地方：

- SHA-256 使用 `rany2/edge-tts/drm.py` 的 patched 实现，不是 `MessageDigest("SHA-256")`。
- CS64 的两个 32 位结果最终按**小端字节序**转成十六进制。`52BC7A17B50DFEC9` 来自整数 `0x177ABC52` 和 `0xC9FE0DB5` 的小端输出。

不要重写这段“看起来可以简化”的算法。改完必须跑固定向量测试。

### 6.5 Edge 端点与参数

旧端点当前会 403：

```text
wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1
```

当前默认：

```text
wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1
```

鉴权键必须是：

```text
Ocp-Apim-Subscription-Key
```

不是 `TrustedClientToken`。  
版本使用：

```text
2-155.0.4283.45
```

### 6.6 不要用固定偏移裁 Edge 音频帧

不要使用某些旧 Java 实现的固定 `130` 或 `105` 字节偏移。  
二进制帧应先读 2 字节大端 header 长度，再找 `\r\n\r\n`，payload 从分隔符之后开始，并确认 header 包含 `Path:audio`。

### 6.7 Microsoft SDK 方法名大小写

Java SDK 暴露的是：

```kotlin
synthesizer.SpeakSsml(...)
synthesizer.StopSpeakingAsync()
```

不是小写 `speakSsml` / `stopSpeakingAsync`。  
`setSpeechSynthesisVoice` 是两参数方法，不能当 Kotlin 属性赋值。

### 6.8 离线音色不能暴露给系统

没有许可证时：

- 应用内：显示、标注“需要许可证”。
- 系统 `onGetVoices()`：不返回。

否则系统会显示一个实际合成必然失败的音色。

### 6.9 安装包不能破坏旧版本

`VoicePackStore` 已采用临时目录 + 备份替换。不要为了省事直接 `deleteRecursively()` 后解压；中途失败会让用户丢失已安装音色。

### 6.10 不要假设系统 Voice 名可以随便改

系统音色名已经固定为：

```text
multitts:<packId>:<engineCode>:<voiceCode>
```

UI 显示名可以变，系统 ID 不能随意变，否则已保存的默认音色、角色规则和系统调用都会断。

### 6.11 不要把未实现功能做成可点击空控件

- 首版没有 JS 插件、自动人物识别、HTTP 转发服务、缓存系统。
- 旧“兼容代理”设置目前没有真正接入，不应在 UI 中伪装成已生效。
- 未配置许可证的离线音色不能显示成“可直接发声”。

### 6.12 环境和构建

- Windows 原生，PowerShell 7，不要用 Bash 风格命令。
- Git 有 dubious ownership，可用：
  ```powershell
  git -c safe.directory=F:/codex/MultiTTS status
  ```
- 受限沙箱不能完成 Gradle/JDK 回环管道，必须提权。
- 首次依赖下载需要 `-NoOffline` 和网络。
- `minSdk=26` 是微软 SDK 要求，不要为了兼容降回 23。
- 当前只打包 `arm64-v8a`；x86_64 模拟器需要临时加 ABI。

## 7. 关键文件索引

数据：

- `app/src/main/java/org/nobody/multitts/replica/data/voicepack/VoicePack.kt`
- `.../OriginalPackParser.kt`
- `.../VoicePackStore.kt`
- `.../VoiceRegistry.kt`
- `.../VoicePackEvents.kt`
- `.../data/settings/SettingsStore.kt`
- `.../data/settings/SecretStore.kt`
- `.../data/config/RulesRepository.kt`

领域：

- `.../domain/TextPipeline.kt`
- `.../domain/SynthesisPlanBuilder.kt`
- `.../domain/Rules.kt`

引擎：

- `.../engine/EngineManager.kt`
- `.../engine/MultiSegmentSynthesizer.kt`
- `.../engine/edge/EdgeGec.kt`
- `.../engine/edge/EdgeTtsEngine.kt`
- `.../engine/edge/Mp3PcmDecoder.kt`
- `.../engine/microsoft/MicrosoftEmbeddedEngine.kt`

系统服务：

- `.../service/MultiTtsService.kt`

UI：

- `.../ui/MainViewModel.kt` 已完成
- `.../ui/MultiTtsScreen.kt` 缺失，必须重建
- `.../ui/VoicePackDialog.kt` 是旧界面，未被新 ViewModel 使用，可暂留或删除
- `.../MainActivity.kt` 仍引用缺失的 `MultiTtsScreen`

测试：

- `app/src/test/java/org/nobody/multitts/replica/engine/edge/EdgeGecTest.kt`
- `app/src/test/java/org/nobody/multitts/replica/domain/TextPipelineTest.kt`

## 8. 新会话推荐开场动作

1. 读本文件。
2. 执行：
   ```powershell
   git -c safe.directory=F:/codex/MultiTTS status --short
   ```
3. 只读查看：
   - `ui/MainViewModel.kt`
   - `MainActivity.kt`
   - `data/voicepack/VoicePack.kt`
   - `service/MultiTtsService.kt`
4. 先写 `ui/MultiTtsScreen.kt`，不要重新设计数据层。
5. 编译通过后再做 TTS 配置 Activity。
6. 真机验证前不要宣称“离线发声已完成”。

