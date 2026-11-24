# 架构说明

本文描述 NovelTTS 的模块划分、主要数据流和设计取舍，面向想读懂源码的人。功能清单见 [README](../README.md)，后续计划见[路线图](ROADMAP.md)。

## 设计目标

- **自己发声**：导入语音包后由本应用的后端直接合成，而不是把请求转发给设备上的另一个 TTS 引擎。
- **三个入口，一条链路**：应用内朗读、Android 系统 TTS、HTTP 转发共用同一套规则、音色注册表和合成器，同样的输入得到同样的音频。
- **确定性的多角色朗读**：角色只来自用户配置的规则，不使用大模型识别人物；判断不了时用默认对话音色，不乱配。
- **不丢用户数据**：规则、设置和语音包的每次写入都可回滚；读取失败时暴露错误，不用空配置覆盖。

不在目标内的事见[路线图](ROADMAP.md#明确不做)。

## 技术栈

| 项目 | 取值 |
| --- | --- |
| 语言与界面 | Kotlin、Jetpack Compose（Material 3），单 Activity |
| SDK 版本 | `minSdk 26`、`targetSdk 35`、`compileSdk 37`，JVM 17 |
| ABI | 仅 `arm64-v8a` |
| 在线后端 | OkHttp 4.12 WebSocket，MediaCodec 解码 MP3 |
| 离线后端 | 微软 Speech `client-sdk` 1.37.0（版本必须与 Embedded 扩展库一致） |
| 清单解析 | SnakeYAML 2.3（`SafeConstructor`） |
| 存储 | JSON 文件 + SharedPreferences，不使用数据库 |

包名与 `applicationId` 均为 `app.noveltts`。

## 整体结构

```mermaid
flowchart TD
    subgraph 入口
        UI[应用内朗读<br/>MainViewModel]
        SYS[系统 TTS<br/>MultiTtsService]
        HTTP[HTTP 转发<br/>ForwardServer]
    end

    subgraph 文本规划
        PLAN[SynthesisPlanBuilder]
        PIPE[TextPipeline]
        ATTR[SpeakerAttributor]
        RULES[":rules 子进程<br/>正则执行"]
    end

    subgraph 合成
        MSS[MultiSegmentSynthesizer]
        CACHE[(AudioCache)]
        SCHED[BackendScheduler]
        EDGE[EdgeTtsEngine]
        MS[MicrosoftEmbeddedEngine]
    end

    subgraph 数据
        REPO[RulesRepository]
        SET[SettingsStore]
        REG[VoiceRegistry]
        STORE[VoicePackStore]
    end

    UI --> PLAN
    SYS --> PLAN
    HTTP --> PLAN
    PLAN --> PIPE --> ATTR
    PIPE -.正则.-> RULES
    PLAN --> MSS
    MSS --> CACHE
    MSS --> SCHED --> EDGE
    SCHED --> MS
    REPO --> PLAN
    SET --> PLAN
    REG --> PLAN
    STORE --> REG
```

三个入口各自创建仓库、引擎与合成器实例，共享配置锁、规则执行进程和后端调度器，尚没有统一的对象组装层。每个入口在开始时冻结一份规则与合成设置的快照；BGM 使用当前设置，播放期间的调整即时生效。

## 源码布局

源码位于 `app/src/main/java/app/noveltts/`。

| 包 | 职责 | 主要文件 |
| --- | --- | --- |
| （根） | 入口与路由 | `MainActivity.kt`、`NovelTtsApplication.kt` |
| `domain` | 纯 Kotlin 的文本处理，不依赖 Android | `TextPipeline.kt`、`SpeakerAttribution.kt`、`SynthesisPlanBuilder.kt`、`Rules.kt`、`RuleValidation.kt`、`ReplacementBudget.kt` |
| `rules` | 在独立进程里执行用户正则 | `RulesProcessService.kt`、`RulesProcessClient.kt`、`RuleWire.kt` |
| `engine` | 多段合成、调度、重试、计时与日志 | `MultiSegmentSynthesizer.kt`、`EngineManager.kt`、`BackendScheduler.kt`、`RetryPolicy.kt`、`DiagnosticsLog.kt` |
| `engine.edge` | Edge Read Aloud 后端 | `EdgeTtsEngine.kt`、`EdgeGec.kt`、`EdgeFrames.kt`、`Mp3PcmDecoder.kt` |
| `engine.microsoft` | 微软 Embedded Speech 后端 | `MicrosoftEmbeddedEngine.kt` |
| `engine.api` | 后端接口与资源预算 | `EngineApi.kt`、`ResourceBudget.kt`、`BoundedAudioBuffer.kt` |
| `data.voicepack` | 语音包解析、安装、音色注册表 | `VoicePackStore.kt`、`PackYaml.kt`、`VoiceRegistry.kt`、`VoiceOverrides.kt` |
| `data.config` | 规则文件、导入、草稿、阅读方案与续读快照 | `RulesRepository.kt`、`RuleMutations.kt`、`RulesImport.kt`、`ReadingSnapshot.kt` |
| `data.settings` | 设置与加密凭据 | `SettingsStore.kt`、`SecretStore.kt` |
| `data.backup` | 备份、还原与事务恢复 | `BackupManager.kt`、`RestoreTransaction.kt` |
| `data.cache` | 音频缓存与文件引用计数 | `AudioCache.kt`、`CacheManager.kt`、`ActiveAudioFiles.kt` |
| `service` | 系统 TTS 服务与前台服务 | `MultiTtsService.kt`、`PlaybackForegroundService.kt`、`ForwardForegroundService.kt` |
| `forward` | HTTP 服务器与协议 | `ForwardServer.kt`、`ForwardProtocol.kt`、`TtsHttpHandler.kt`、`ForwardPcmCollector.kt`、`LegadoIntegration.kt` |
| `audio` | 背景音乐、音频焦点与媒体控制 | `BgmPlayer.kt`、`PlaybackAudioPolicy.kt` |
| `ui` | Compose 界面，按页面分子包 | `MainViewModel.kt`、`MultiTtsScreen.kt`、`workbench/`、`packs/`、`rules/`、`settings/`、`log/`、`common/`、`theme/` |

`MainViewModel` 是界面状态与业务操作的汇聚点，体量较大；后续会按功能逐步拆出可测试的操作，不做一次性重写。

应用朗读的 ViewModelStore 由 `NovelTtsApplication` 持有，`MainActivity` 使用同一实例，关闭或重建界面不结束播放。首次完整加载与常见规则变更在 IO 线程执行；大文本持久化防抖。构造期间的部分读取、首次内容迁移和续读快照序列化仍同步执行，并非全部磁盘工作已经离开主线程。

## 文本管线

`TextPipeline` 把一段文本变成有序的朗读片段，每个片段带有角色、音色、倍率和命中解释。处理顺序：

1. 文本规范化。
2. 执行全文替换规则。
3. **按段落**识别引号（`“”`、`「」`、`『』`、ASCII 双引号）。引号不跨段，未闭合的引号到段尾为止——漏写一个后引号不会让整章都被读成对话。
4. 对每句引语做说话人归属（见下）。
5. 在旁白 / 对话范围内执行对应的替换，再按句末标点分句。
6. 超过单段字数上限的片段继续切分。
7. 用旧式角色规则填补归属没有判出的片段。
8. 执行指定角色的替换，过滤纯标点片段。
9. 生成稳定的片段 ID，解析最终音色与回退原因。

### 说话人归属

`SpeakerAttributor` 对每句引语只看**同一段内紧邻**的前后旁白，按下表顺序判断，命中即停：

| 依据 | 条件 | 置信 |
| --- | --- | --- |
| 前文引出 | 前一句旁白以 `：` 或 `，` 结尾并提到角色 | 高 |
| 后文点明 | 后一句旁白提到角色；名字后紧跟「闻言 / 听了」等词时视为听者，跳过 | 以说话动词收尾为高，否则中 |
| 前文提及 | 前一句旁白提到角色，但不是引出句式 | 中 |
| 沿用 | 引语前后都没有旁白，沿用上一个说话人 | 中 |
| 无法确定 | 以上都没有 | 交给旧式规则，再用默认对话音色 |

几条补充规则：

- 引号内出现的名字不算依据。「易心，当初……」里被称呼的人不可能是说话人。
- 同一分句里出现多个角色时置信降为中。
- 不含引语的段落会清空「上一个说话人」；空行不清空。
- 「没写是谁说的对话」是沿用上一个说话人还是用默认对话音色，由用户在设置里选择。

每个片段保留归属类型、置信和不超过 20 字的依据句，分段预览据此显示判断理由并标出「不确定」。

这是基于规则的近似，有明确的上限：一问一答都不写说话人时会读成同一个人；代词无法判定；后文提到的名字是听者但不在词表内时会判错。

### 规则执行的隔离

用户正则的编译、匹配和替换全部在非导出的 `:rules` 子进程里执行。原因是 Java 正则无法靠线程中断可靠终止，一条灾难性回溯的表达式会卡死调用线程。

- 主进程保留配置、凭据和音频；子进程只接收不可变的文本与规则数据。
- 预览与校验的总预算是 2 秒，正式合成规划是 10 秒，包含排队与绑定时间。
- 超时后先解绑、再终止子进程，等 Binder 断开后才释放队列，避免下一个请求绑到正在退出的服务上。
- 另有输入长度、单次替换结果、累计替换展开量三层上限，超限作为错误报告，不当成「没有匹配」；未变化的扫描文本不反复扣除展开预算。
- 等待中的普通预览可以被新预览替换，编辑器校验不受预览代际影响。规则临时文件在请求结束及进程首次使用时清理已知残留。

## 合成链路

### 参数

最终请求参数 = 全局滑块 × 音色默认值 × 角色倍率，相乘后截断到有效范围：语速 0.25–4、音调 0.25–2、音量 0–1。音频缓存以最终值为键。

系统 TTS 的语速、音调收到的是百分比，客户端没有显式设置时会继承系统值；这是平台语义，不做额外修正。客户端音量由框架应用于播放，不再次乘进 PCM；角色与音色默认音量仍保留。

### 多段合成

`MultiSegmentSynthesizer` 统一输出 24 kHz、16 位、单声道 PCM。每个片段先「备好」再「回放」：备好是查缓存，没有就合成到临时文件（含重试与备用音色）；回放是分块推给调用方。

- **默认模式**（系统 TTS、HTTP、试听）：逐段串行。
- **预取模式**（应用内整篇朗读）：当前段一到手就在后台备下一段，最多一段在播、一段在备。预取到的失败要等轮到那一段时才上报，否则会截断正在播放的上一段。

失败时先按 `RetryPolicy` 在同一音色上重试，再尝试同类的备用音色。缺许可证、请求非法、已取消、资源超限和后端繁忙是终止错误，不重试也不换音色。

正常结束但零音频的段跳过并记录警告，继续推进段号；空结果不重试或缓存，整篇零音频也能结束。网络 / 解码失败保留错误语义。常规重试和备用切换使用可取消的有界等待；Edge 保留握手状态码，401 / 403 / 429 终止常规重试与备用切换（403 的一次时钟校正仍由后端处理）；服务端关闭与取消竞态及时结束等待。

单段预算为「20 秒 + 字数 × 150 毫秒」，以用户超时为下限、300 秒为上限。同一次尝试共用单调时钟截止时间，后端排队、Edge 收包 / 校时重试、解码与 spool 等待不会各自重置预算。异常和线程中断关闭尚未交付的文件与句柄；音频内存不足按资源超限结束。

### 后端

| 后端 | 实现要点 |
| --- | --- |
| Edge | 每段新建 WebSocket，收齐全部 MP3 帧后整段解码并重采样；握手 403 时按响应时间校正时钟重试一次。音量不进 SSML，在本地 PCM 上做增益 |
| 微软 Embedded | 从语音包内的模型目录创建配置，经有界的推流缓冲输出 PCM；缺许可证直接报错 |

两个后端都是整段收齐后才交出 PCM，没有渐进播放。

### 调度与资源预算

`BackendScheduler` 在整个进程内共享：最多 2 个活动的后端请求加 8 个等待，前台请求优先于预取，超出返回繁忙。取消只作用于所属请求，不会误停其他入口。

每一层分配前都检查上限：单段压缩音频 16 MiB、解码后原始 PCM 64 MiB、归一化 PCM 16 MiB、临时文件 16 MiB，HTTP 单请求总输出 32 MiB。这些是保护性上限，不是性能指标。

### 音频缓存

缓存键是 SHA-256，包含语音包内容版本（整个 ZIP 的哈希）、后端版本、音色 ID、最终语速 / 音调 / 音量和文本。同一个包换了模型后，音色 ID 不变，缓存仍会失效。正在读写的文件由引用计数保护，淘汰和手动清理不会删到它们。三个入口共享同目录的保守占用计数；正常写入未触顶时复用计数，每 30 秒重扫捕获外部变化，超限和强制检查及时扫描。大小和修改时间在扫描时读取一次，再按快照排序。

## 语音包与音色注册表

语音包只**声明**音色，真正出声的是后端。安装流程是：写入临时目录 → 校验 → 解压 → 解析 → 同 ID 更新确认 → 备份旧目录 → 替换。安装提交、删除与扫描共用锁；失败尝试回滚，回滚失败明确报告并保留恢复副本。启动时清理已知临时目录，目标缺失且只有一份可解析备份时自动恢复，歧义或损坏备份保留。格式与限额见[语音包格式](VOICE_PACK_FORMAT.md)。

`VoiceRegistry` 汇总所有包里受支持的音色，并叠加用户的逐音色覆盖（别名、收藏、启停、默认倍率）。三个入口和试听看到的是同一份结果。

`PackYaml` 按完整内容缓存解析结果；系统 TTS 与 HTTP 的 `VoiceCatalog` 另外保留最多 5 秒的音色可用性快照，空结果也缓存。包、许可证及覆盖层变更立即使快照失效，外部文件变化在过期后重新检查。冷扫描仍同步执行。损坏覆盖层只让注册表回落到包默认值，编辑与备份不静默覆盖坏文件。

系统音色名固定为：

```
multitts:<packId>:<engineCode>:<voiceCode>
```

`packId` 由两个 YAML 清单的内容决定，同一个包重复导入得到相同的 ID。这个字符串保存在第三方阅读器、系统设置、角色规则和阅读方案里，改动它会让所有已选音色失效，所以应用改名为 NovelTTS 之后前缀仍保留 `multitts:`。

不可用的音色（缺模型、缺许可证、被用户停用）在应用内显示并说明原因，但不会出现在系统的音色列表里，避免其他应用选中一个必然失败的音色。

## 系统 TTS

`MultiTtsService` 继承 `TextToSpeechService`。计划线程经有界队列交接 PCM，只有框架合成线程调用回调，按 `maxBufferSize` 切块。成功为 `start → audioAvailable → done`，空文本直接 `start → done`，失败为 `error → done`。平台停止不覆盖成合成错误，通知停止返回服务错误。等待期限只累计无音频活动的时间，框架消费 PCM 的背压时间不计入。

两处容易出错的协议细节：

- `CHECK_TTS_DATA` 返回的是去重后的 ISO3 locale（如 `zho-CHN`），不是音色 ID。两者混用会让系统设置页崩溃。
- 服务的 intent-filter 必须带 `CATEGORY_DEFAULT`，否则系统枚举不到这个引擎。

## HTTP 转发

监听由前台服务持有，Activity 销毁后继续运行。默认关闭，绑定 `127.0.0.1`。服务器是自己实现的最小 HTTP/1.1，严格解析请求头与请求体，整个请求（排队、读取、合成、写出）受同一个截止时间约束。接口细节见 [HTTP 接口](HTTP_API.md)。

## 数据存储

| 数据 | 位置 | 说明 |
| --- | --- | --- |
| 角色规则、替换规则 | `files/config/roles.json`、`replacements.json` | schema 2，含分组；旧版本读取时自动迁移并保留 `.bak` |
| 逐音色覆盖 | `files/config/voice-overrides.json` | 按系统音色名索引 |
| 设置、阅读方案、续读索引、草稿 | SharedPreferences（文件名 `multitts`） | 草稿单独保留编辑基线，恢复后仍检查并发修改 |
| 续读快照、最近文本 | `multitts.reading`、`multitts.text` | 大字段单独保存，避免每次改小设置重写全文；旧数据先提交新副本再移除旧键 |
| 许可证、转发令牌 | 独立的 SharedPreferences | 经 Android Keystore 的 AES-GCM 加密，不写明文、不进日志 |
| 语音包 | 应用私有目录 | 每个包一个目录，附带安装元数据 |
| 诊断日志 | `files/diagnostics/` | 按天的 JSONL，单文件 512 KB 轮转 |

写入规则：

- 规则文件：临时文件 → 重新解析校验 → 旧文件复制为 `.bak` → 原子替换。
- 局部变更（删除、启停、移组、排序）在配置锁内重新读盘后再应用，不用界面上的旧副本覆盖整份文件。
- 编辑器保存时带上打开时的基线，条目已被改动或删除就拒绝保存并保留草稿。
- 备份还原和批量写入走事务：先快照规则、设置、凭据和音色覆盖，失败时回滚磁盘与内存；进程中途被杀，下次冷启动先恢复再加载。
- journal 回滚前检查全部条目，损坏日志隔离保留并报告，避免永久阻断下一次事务。规则临时文件在替换前 fsync。
- 备份排除 Edge 端点和 HTTP 启用 / 绑定 / 端口；规则备份读回保留总大小限制，不沿用单次外部规则导入的条数限制。显式选择导出凭据时文件仍是明文。

系统自动备份已关闭并配置了排除规则，避免令牌和文本被云备份或换机迁移带走。换机请使用应用内的导出。

## 进程与线程

| 进程 | 内容 |
| --- | --- |
| 主进程 | 界面、三个入口、配置、合成与播放 |
| `:rules` | 只执行用户正则，不读配置、不初始化后端 |

前台服务有两个：`PlaybackForegroundService`（朗读保活，类型 `mediaPlayback`）和 `ForwardForegroundService`（HTTP 监听，类型 `specialUse`）。「锁屏持续朗读」默认关闭，开启后只在有活动会话时续期有界的唤醒锁。

应用播放通过 `PlaybackAudioPolicy` 请求音频焦点，维护 MediaSession，并处理耳机拔出。临时丢焦点后只自动恢复原整篇朗读，打断试听不触发原朗读恢复；具体中断仍受设备音频策略影响。

release 构建明确关闭调试，尚未配置正式签名；暂不启用缩减，规则进程序列化按类名检查，开启混淆前必须验证保留规则和真机 IPC。构建出 unsigned APK 不代表完成发布。

## 权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | Edge 在线合成、下载语音包、HTTP 转发 |
| `FOREGROUND_SERVICE` 及两个子类型 | 朗读保活与 HTTP 监听 |
| `POST_NOTIFICATIONS` | 前台服务通知 |
| `WAKE_LOCK` | 朗读期间的唤醒锁，含可选的锁屏持续朗读 |
| `ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE` | 在线音色朗读期间保持 Wi-Fi 连接 |

微软 SDK 的清单会合并进 `RECORD_AUDIO`，本应用只做合成，已在清单里显式移除。

## 主要设计取舍

| 决定 | 理由 |
| --- | --- |
| 不用数据库 | 规则量级小，JSON 文件便于用户导入导出和手工恢复 |
| 引号不跨段 | 网文常有漏写的引号；容错比完整支持跨段引语更重要 |
| 不做自动人物识别 | 结果要可预期、可解释；判断不了就明说不确定 |
| 正则放进子进程 | 是唯一能可靠终止失控正则的办法 |
| 保留 `multitts:` 前缀 | 外部应用保存的音色名无法由本应用回写 |
| 整段合成后再播放 | 实现简单，取消、重试和缓存的语义清晰；代价是首段等待，见路线图 |
| 不可用音色不暴露给系统 | 系统里出现一个必然失败的音色比看不到它更糟 |
