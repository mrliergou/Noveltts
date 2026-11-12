# MultiTTS Kotlin 重实现目标

本文是后续 Kotlin 重实现的产品与行为目标。实现者应先读本文，再按阶段开发并更新验收状态。除非注明，本文中的“原版行为”来自 APK 静态证据，不代表已经在设备上动态验证。

## 1. 目标与范围

重实现目标：以 Kotlin 和 Jetpack Compose 构建一个结构清晰、可安装的 Android TTS 引擎应用；首要目标是实现原版 `org.nobody.multitts` 的核心本地语音合成能力、引擎/音色配置、角色与替换规则，并通过 Android 系统 TTS 服务供其他应用调用。

当前仓库中的复刻版是一个 UI 与系统默认 TTS 的原型，不是原版等价实现。重实现必须围绕真正的系统 TTS 服务和原版数据模型补齐能力，不能把演示通道标签或系统默认音色列表描述成原版引擎。

### 范围分期

| 阶段 | 目标 | 完成标准 |
| --- | --- | --- |
| P0 基础引擎 | 以 `TextToSpeechService` 注册系统 TTS；扫描本地音色；其他应用和 Android TTS 设置可发现并调用 | 设置中可选中 MultiTTS；调用成功发声；状态/错误能返回调用方 |
| P1 本地工作台 | 文本试听、语速/音调/音量、停止、音色搜索与筛选、设置持久化 | 应用内试听和系统 TTS 调用使用同一合成管线 |
| P2 数据管理 | 引擎配置、音色库、角色规则、文本替换规则的增删改查、搜索、导入导出 | 数据可持久化、校验、冲突处理和迁移 |
| P3 语义合成 | 句子分段、角色识别/自动分配、替换规则、表情处理、合成多音色对话 | 同一输入按启用规则稳定产生分段及音色映射 |
| P4 系统体验 | 前台合成通知、暂停/停止、快速设置入口、转发服务、缓存与日志 | 后台长文本可控，通知动作有效，异常可诊断 |
| P5 在线扩展 | 在线引擎协议、云音色/引擎更新、登录/认证（仅在确认确有需要后） | 网络状态、认证、超时、重试、错误和隐私均有测试 |

每个阶段必须保持应用可构建、可启动；未实现的能力应显示为未提供或禁用，不得出现看似可用但没有实际后端的控件。

## 2. 原版 APK 基线

样本：`MultiTTS_1.8.3(84)-arm64-v8a-release.apk`

| 属性 | 观察值 |
| --- | --- |
| 包名 / 版本 | `org.nobody.multitts` / `1.8.3`（versionCode `84`） |
| ABI | APK 仅包含 `arm64-v8a` |
| SDK | minSdk 21，targetSdk 35，compileSdk 35 |
| 启动页 | `org.nobody.multitts.ui.main.MainActivity` |
| TTS 入口 | `org.nobody.multitts.service.TTSService`，继承 `TextToSpeechService` |
| 重要组件 | 引擎、角色、音色库、替换、设置 Activity；前台 TTS 服务；前台转发服务；Quick Settings tile；TTS 数据检查/安装入口 |
| 核心本地数据 | Room 数据库包含 speaker、role/voice pair 等表；另有 engine、role、replace 配置模型 |
| 原版身份 | Android 系统 TTS provider；不只是一个内部播放器 |
| APK SHA-256 | `D3F7736B1BF162829677C0772A344696F8F5940E99F45879AA5E26070D0416AF` |

> 注意：上表来自逆向样本 `1.8.3 (84)`，但**真机安装的是 `1.5.6 (57)`（targetSdk 34，2024-07-27 安装）**。
> 后续行为对照以真机版本为准，1.8.3 的差异需单独确认。

### 真机观测（2026-11-10，Observed）

设备：`M2102J2SC`（Redmi，Android 13 / SDK 33，arm64-v8a），adb 序列号 `5cb24277`。

| 项目 | 观测值 |
| --- | --- |
| 已安装 TTS 引擎 | 仅两个：`com.xiaomi.mibrain.speech`（`com.xiaomi.mibrain.speech.tts.TtsService`）、原版 `org.nobody.multitts`（`org.nobody.multitts.service.TTSService`） |
| 系统默认合成引擎 | `org.nobody.multitts`（即原版），见 `settings get secure tts_default_synth` |
| 原版实际版本 | `versionName=1.5.6`、`versionCode=57`、`targetSdk=34` |
| 复刻版（重装后） | `org.nobody.multitts.replica` v1.0.0，可启动，`am start` 返回 ok，进程存活，无自身崩溃 |
| 设备限制 | MIUI 拒绝 `adb shell input` 注入（`SecurityException: INJECT_EVENTS`），UI 自动化需先解锁或改用模拟器 |

结论：本机可用的委托后端只有小米引擎与原版引擎，**没有可扫描的本地模型库**；这决定了 P0 必须先把"委托代理"链路做实。

### 证据等级

- **Observed**：`aapt dump badging` 和解码后的 Manifest 给出了包名、版本、ABI、SDK、组件、权限、系统 TTS service 声明。
- **Observed**：JADX 1.5.6 可读出应用自己的 `MainActivity`、`TTSService`、数据模型和主要管理 Activity。整包共有 4710 个类，反编译报告 38 个错误；核心应用类已输出，但不可把整包 Java 输出视为无损源码。
- **Observed**：`TTSService.onGetVoices()` 从本地 speaker 数据创建 Android `Voice` 列表；`onSynthesizeText()` 读取 TTS 请求文本、语速、音调、voice name 与参数，再交给引擎协调器。
- **Observed**：原版包含大量本地语音相关 `.so` 与模型/字典资源，因此它具备原生/本地处理成分。具体每个内置 engine 的工作方式应继续逐个核查。
- **Unknown**：静态分析尚未确定真实在线 API 地址、请求/响应协议、内置引擎完整清单、在线服务存续状态、账户行为和动态加载行为。
- **Observed（2026-11-10 补充）**：原版 1.5.6 与复刻版均已安装在真机 `M2102J2SC` 上；后者可构建、可安装、可启动，但锁屏状态下界面与音频结果仍未动态验证。
- **Unknown**：后台运行、多 Android 版本、网络请求及音频输出的实际质量均未动态验证。

### 分析方式与约束

使用 Android SDK `aapt` 查看 APK metadata/Manifest，使用 JADX 1.5.6 对原 APK 只读反编译。JADX 输出在系统临时目录，不作为仓库源码。没有安装原 APK、没有修改原 APK、没有访问在线服务或连接手机。APK 的业务类存在混淆，字符串常量部分 XOR 混淆，静态结果需要设备验证补足。

## 3. 产品信息架构

原版导航及核心模块：

1. **主工作台**：文本朗读参数、文本搜索/建议、页面/内容管理，导航抽屉与快捷菜单。
2. **引擎管理**：创建/编辑/删除内置或插件引擎，设置 URL、请求头、参数、登录信息；删除引擎会影响关联音色。
3. **音色库**：搜索音色；按“音色设置”和“音色分配表”两类页面管理；启用/禁用、试听、编辑、删除、导入导出；支持多选和批量操作。
4. **角色管理**：定义角色及匹配条件，关联一个或多个音色；控制匹配模式、优先顺序、分组和启用状态；支持搜索、导入导出和临时角色查看。
5. **替换规则**：定义源文本、目标文本、规则名、规则组、正则开关和启用状态；支持测试文本、排序、搜索、导入导出。
6. **系统设置**：播放、基础、缓存、其他、关于等类别。详细开关见“设置能力”。
7. **系统 TTS 配置**：应用被 Android 识别为一个 TTS 引擎，可从系统 TTS 设置选为默认引擎；提供 GET_SAMPLE_TEXT、CHECK_TTS_DATA、INSTALL_TTS_DATA 系统交互。
8. **后台与快捷入口**：前台合成通知；通知提供停止/关闭动作；ForwardService 与 Quick Settings tile 提供转发相关入口。
9. **辅助模块**：提示/帮助、日志、登录页面、数据导入与备份恢复。是否进入第一版由分期决定。

静态 Manifest 中主 Activity 暴露 Launcher、TTS engine configuration 和 Quick Settings preferences 入口；未看到主 Activity 注册 `ACTION_SEND text/plain`。因此不要默认原版支持“系统分享文本至 MultiTTS 后自动朗读”，先用设备验证或其他组件证据确认。

**已从解码资源确认的导航顺序**：设置；替换；角色；音色库；引擎；帮助；查看日志；清理音色；转发服务开关；更新通道；资源通道。主界面溢出菜单另含搜索、系统 TTS 设置、导入、背景音乐、重新加载；另一份 APK 菜单资源还列有设置、替换、角色、音色库、引擎、帮助和日志入口，可能用于不同布局/版本分支，运行时是否显示需再核查。

## 4. 核心交互目标

### 4.1 系统 TTS 调用（P0）

1. 用户进入 Android 系统的“文字转语音输出”页面。
2. Android 发现 MultiTTS 的 `TextToSpeechService` 及其 engine metadata。
3. 用户选定引擎后，调用方应用通过标准 `TextToSpeech` API 请求语言、Voice、语速、音调、音量和文本。
4. MultiTTS 校验 voice/language；文本为空或语言/音色不可用时返回明确错误。
5. 合成管线分段处理文本并调用当前选择的引擎。
6. 将 PCM 音频及开始、范围、完成、错误回调交给 Android `SynthesisCallback`。
7. 用户停止、切换音色或服务被销毁时，取消当前任务并释放音频、网络、CPU/Wi-Fi 锁等资源。

验收重点：系统测试语句可发声；英语/中文语言探测符合引擎能力；暂停/停止响应；无可用音色、网络不可达、引擎错误时系统调用方获得错误而非永久等待。

### 4.2 应用内试听（P1）

1. 工作台显示文本输入框、当前 Voice、语速/音调/音量数值和合成状态。
2. 输入或粘贴文本；可清空、载入示例文本。
3. 选择 Voice 后立即更新当前选择；搜索列表需显示语言、性别、在线/离线、描述及启用状态。
4. 点“朗读”进入处理中状态；正在朗读时允许停止。任务完成或失败后状态回到可操作状态。
5. 参数和最近文本按设置策略持久化。密码、token 等敏感值不得用普通偏好明文保存。

原版主界面的 speed、volume 默认值为 50，pitch 默认值为 25；设置控件更新时持久化。具体数值到引擎倍率的换算应依据当前 engine 能力，不应硬编码成单一 Android 默认 TTS 公式。

### 4.3 音色与引擎管理（P1/P2）

- 引擎列表能区分内置与插件类型、在线/离线状态、描述、启用状态。
- 新建/编辑引擎字段至少覆盖：名称、类型、URL、请求头、参数、备注、代码/标识和必要的登录配置。
- 保存前校验必填项、URL/标识格式、重复代码和不支持的参数；网络引擎可单独测试连通性。
- 音色记录至少保存：稳定 ID、代码、名称、分组、引擎 ID、语言/地区、性别、描述、头像、备注、扩展 UI、类型、采样率、默认 speed/volume/pitch、参数。
- 删除有引用的数据需提示影响范围；不可静默留下悬空角色音色或默认 Voice。
- 音色试听应走与 TTS service 共用的合成接口；在线状态和下载/更新状态可见。

### 4.4 角色匹配与多角色朗读（P2/P3）

角色规则至少支持：启用状态、名称/短名、分组、具体音色、正则表达式、前向/后向匹配、匹配模式（文本/角色/语言）、抽象角色、次级语言和优先级。

多角色合成目标流程：

1. 对输入文本做 Unicode 规范化和标点/句子切分。
2. 根据设置决定是否使用上文段落、自然语言分析和临时角色。
3. 识别文本中的人物/角色，应用角色过滤条件及匹配规则。
4. 为每个角色选择显式配置的 Voice；未匹配时按设置决定使用最近匹配音色、性别推断或自动分配。未开启自动分配时不得擅自分配。
5. 生成有序的“文本片段 → Voice”分配表供用户检查/修改。
6. 合并每段生成的 PCM 流，保证段落次序、采样率、停止和失败传播一致。

原版资源明确包含“合成对话”开关、朗读/对话音色未设置提示、自动分配配置、最近音色记录和临时角色/人物-音色分配表。具体自动分析算法和对话角色选择 UI 需动态检查后定稿。

### 4.5 文本替换（P2/P3）

规则字段：启用状态、规则名、分组、source、target、regex。规则按组和顺序应用，正则无效或替换结果为空时有可读错误/预览，不应中止整个引擎。管理页提供搜索、新建、编辑、启停、删除、排序和测试文本。TTS 合成应记录原始输入与处理结果（在隐私允许范围内），支持用户定位是哪条规则改变了文本。

### 4.6 导入/导出和数据恢复（P2）

- 数据管理页面提供导入/导出，使用 Android Storage Access Framework，不依赖宽泛存储权限。
- 文件采用有版本号、校验和、明确 schema 的压缩包或 JSON 数据格式。
- 导入前解析并展示将新增/覆盖/跳过的数量；重复配置允许跳过或覆盖；确认后用事务写入。
- 解压文件路径必须防止 Zip Slip；文件大小、条目数和 JSON 深度设上限。
- 角色、规则、引擎、音色可支持分项导入；关联 ID 在导入时重映射。
- 失败时事务回滚并保留原数据；导出可经 Android 分享面板传递。

### 4.7 后台朗读、通知与转发（P4）

- 长文本或在线任务按 Android 前台服务规则运行；通知显示当前合成状态与任务信息。
- 通知动作至少包括停止合成和关闭/停止后台服务；点击通知返回工作台。
- 锁屏/后台场景使用 WakeLock/WifiLock 必须由明确设置控制，设超时并在完成、失败、取消、销毁时释放。
- 转发服务和 Quick Settings tile 的外部协议/接收方尚未确认。先查清来源应用、Intent/API 契约、数据流和权限，再决定实现。未确认前不开放空操作开关。

## 5. 设置能力清单

原版 strings、Preference Activity 和模型证据表明以下设置存在或被支持。标为“待确认”的选项需要继续定位 preference key 与 listener，不能只根据字符串推断实际生效范围。

- **基础**：默认引擎/默认音色、语言过滤、无匹配角色的自动分配、文本/表情处理、字符或段落规则、语音缓存配置。
- **播放**：合成音量/速度/音调、最小采样率上采样、最大单句长度、后台通知、WakeLock/WifiLock。
- **角色识别**：是否记录上文、是否记录最近 Voice、男性/女性姓名规则、人物名规则、新人物筛选、临时分配表。
- **对话**：合成对话启停；长按选择对话音色；对话和旁白 Voice 必须可单独校验。
- **缓存**：引擎与 Voice 缓存数量上限、清理缓存；容量和淘汰策略待确认。
- **其他**：语言切换、帮助/提示、日志、关于、开源许可和免责声明。

设置必须按领域拆分，提供稳定 key、默认值、校验和迁移。UI 不直接读取全局静态配置；由配置仓库发布状态，ViewModel 暴露可观察状态。

## 6. Kotlin 工程设计约束

建议保留清晰的单向依赖：

```text
Compose UI -> ViewModel -> domain use cases -> repository interfaces
                                      -> TTS engine adapters
data repositories -> Room / DataStore / file import-export
Android TextToSpeechService -> domain synthesis coordinator
```

- `app`：Manifest、Activity、`TextToSpeechService`、通知、Quick Settings adapter。
- `core:model`（可与 app 同模块起步）：Engine、Speaker、RoleRule、ReplaceRule、SynthesisRequest、SynthesisEvent 等纯 Kotlin 模型。
- `core:domain`：文本处理、规则执行、角色分配、任务编排和可测试用例。
- `data`：Room DAO、DataStore 设置、导入导出、音色/引擎 repository。
- `engine:api`：统一合成接口、PCM 格式、取消、能力描述和错误类型。
- `engine:local` / `engine:plugin`：具体本地或插件引擎 adapter。每个 adapter 独立依赖和测试。
- UI 按工作台、引擎、音色、角色、替换、设置分包；Composable 保持小而可读，业务规则不放 UI。

必须支持从现有简单 SharedPreferences 数据升级；迁移失败时保留旧值并提供可恢复错误。在线引擎凭据进入 Android Keystore 加密存储。日志不记录完整文本、账户凭据或语音内容，除非用户明确开启诊断导出。

## 7. 当前 Kotlin 复刻版进展

`org.nobody.multitts.replica` 已从「委托系统引擎转发」改为**真实引擎实现**：
自己解析语音包（原版 `config.yaml` + `engines.yaml`），走 Edge 在线与微软嵌入式离线两个后端合成，
并把结果以系统 `TextToSpeechService` 的身份回传。

代码证据（2026-11-12）：`:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest` 13 项全绿；
`:app:assembleDebug` 产出 27.8 MB APK。**运行时仍未验证**（无真机截图、无 Edge 实际发声记录）。

### 7.1 已完成（有代码 + 编译/单测证据）

- **P0 系统 TTS 引擎**：`service.MultiTtsService`（action `TTS_SERVICE` + `CATEGORY_DEFAULT`，permission `BIND_TEXT_TO_SPEECH_SERVICE`），
  语言/音色查询、语速/音调/音量映射、`onStop` 取消、`onGetVoices` 只暴露 `usable` 音色。
  另加 `CHECK_TTS_DATA` / `GET_SAMPLE_TEXT` / `CONFIGURE_ENGINE` 三个 `Theme.NoDisplay` Activity（`ui/engine/EngineSupportActivities.kt`）。
- **P1 本地工作台**：`ui/MultiTtsScreen.kt` 五页（工作台 / 语音包 / 角色 / 替换 / 设置），分段预览、管线告警、音色选择、停止。
- **P2 数据管理**：语音包安装/删除/在线目录（`VoicePackStore`、`VoicePackCatalog`、`VoiceRegistry`），
  角色规则与替换规则 JSON 持久化（`data/config/RulesRepository`）、许可证 SecretStore、Edge 端点设置。
- **P3 语义合成**：`domain/TextPipeline`（替换 → 引号对话识别 → 分句 → 角色匹配 → 纯标点过滤）、
  `SynthesisPlanBuilder`（音色回退）、`MultiSegmentSynthesizer`（24 kHz/PCM16/单声道串行 + 片段间 80 ms 静音）。
- **引擎适配**：`EdgeTtsEngine`（WebSocket + `Sec-MS-GEC` + 403 校时重试 + `Mp3PcmDecoder`）、
  `MicrosoftEmbeddedEngine`（`EmbeddedSpeechConfig.fromPath`，缺许可证直接 `LICENSE_REQUIRED`）。

### 7.2 与原版目标的差距

- **P3 角色识别**：只有显式规则匹配，没有原版的自动人物分配、最近 Voice 记忆、男女名规则。
- **P4 全部未做**：前台服务通知、Quick Settings tile、后台 WakeLock/WifiLock、缓存与日志导出、转发协议。
- **语音缓存**：无音频缓存，每次朗读都重新请求/合成。
- **在线引擎的合规性**：Edge Read Aloud 是非官方接口，可能随时失效；微软离线必须用户自备合法许可证。
- **旧版 `pack.json`**：仍能安装，但音色不注册给系统（绑定的是别的引擎的语音名）。
- **界面未做真机验收**：五页只有编译与静态核对，没有截图对照原版。
- **分享朗读**：`ACTION_SEND text/plain` 保留为复刻版附加能力，未在静态证据中证实属于原版。

### 7.3 本轮修复记录（踩坑）

- service 的 intent-filter 必须含 `CATEGORY_DEFAULT`：`TextToSpeech` 用 `queryIntentServices(..., MATCH_DEFAULT_ONLY)` 枚举引擎，
  缺失时 `getEngines()` 不含本应用，显式指定引擎会被静默回落到默认引擎。
- 打包必须排除 netty 的 `META-INF/INDEX.LIST` 等重复资源（微软语音 SDK 的传递依赖各带一份）。
- `scripts/build.ps1` 需要 UTF-8 BOM，否则 PowerShell 5.1 按 ANSI 读中文会语法报错。

## 8. 验收清单

勾选只代表**已验证**；编码完成但未上真机的条目标注「（已编码，待真机）」。

### P0 系统 TTS

- [x] Android TTS 设置能发现并选择 MultiTTS。（2026-11-10 真机：引擎可枚举可绑定，手动切换默认引擎成功）
- [ ] `CHECK_TTS_DATA` 与 `GET_SAMPLE_TEXT` 返回合法结果。（已编码，待真机）
- [ ] 中英文语言能力与 Voice 列表一致。（已编码，待真机）
- [x] 标准调用能成功朗读；语速、音调、音量和 Voice 确实传入 engine adapter。（2026-11-10 真机验证的是旧「委托代理」链路；新引擎链路待真机）
- [ ] Stop 会取消网络/本地任务并触发正确状态；Activity 不在前台也能合成。（已编码，待真机）
- [ ] Voice 不存在、语言不支持、引擎未初始化、超时和离线均有明确错误和恢复路径。（已编码，待真机）
- [ ] 前台合成通知遵循 Android 版本权限要求；任务结束后无残留服务/锁。（P4 未做）

### P1/P2 工作台和数据

- [ ] 输入、粘贴、清空、示例、Voice 搜索和选择可用。（已编码，待真机）
- [ ] 设置旋转/进程重建后仍正确；数据库迁移有测试。（规则/设置走 JSON + SharedPreferences，无迁移测试）
- [ ] 引擎/Voice 的新建、编辑、禁用、删除与引用清理正确。（语音包安装/删除已编码；引用清理未做）
- [ ] 角色/替换的所有字段可编辑；正则非法时有定位信息。（已编码，待真机）
- [ ] 导入冲突可选择跳过/覆盖；异常包不会损坏已有数据。（同 ID 覆盖安装已实现；无冲突交互）
- [ ] 关键界面中文资源完整，长名称和较大字体不遮挡控件。（待真机看截图）

### P3/P4 语义与后台

- [ ] 同一文本的分句、替换、角色映射有可重复的单元测试。（`TextPipelineTest` 已覆盖部分，待补齐角色/过滤用例）
- [ ] 自动分配开关关闭时不为未匹配角色静默选择 Voice。（无自动分配功能）
- [ ] 多音色输出片段顺序正确，采样率转换不造成断音/失速。（已编码，待真机听感验收）
- [ ] 后台通知动作、Quick Settings 和转发流程经真机验证。（P4 未做）
- [ ] 诊断导出明确提示隐私内容并支持用户检查导出范围。（未做）

## 9. 未决问题与下一轮逆向工作

1. 继续追踪原版 `MainActivity.onNavigationItemSelected()`，确认导航标签对应的实际跳转、清理音色/更新通道/资源通道操作及各入口的显示条件；导航顺序已从 `activity_main_drawer.xml` 静态确认。
2. 提取 preference XML 的真实 key、默认值和每个 listener 的作用，补全第 5 节“待确认”项。
3. 追踪 `Engine` 初始化和请求执行路径，识别内置 engine 类型、插件协议、YAML/JSON 配置格式与登录方式；不要在文档写入未经确认的 URL 或密钥。
4. 追踪 VoicePool 下载/更新/筛选和 Speaker 数据来源；确认本地模型是随 APK 提供、按需下载还是分设备加载。
5. 追踪角色分析、分句、最近 Voice、对话/旁白配置及文本处理顺序。
6. 追踪 `ForwardService`、Quick Settings tile 与外部阅读器/Legado 的精确协议。
7. 获得授权后在隔离设备上安装**原版样本**，检查首屏、主要页面、系统 TTS 调用、断网状态、后台通知及请求日志；不将静态推断升级为事实。
8. 为原版样本做副本上的 JADX 深入追踪；原始 APK 保持只读。远程接口仅在合法授权和必要性明确时访问。

## 10. 仓库与后续使用规则

- 本文件是重实现行为目标；代码结构和依赖细节以 Gradle、源码和架构变更为准，行为变更后必须同步本文件。
- 文档中标为 Observed 的事实由原版 APK 静态证据支持；Unknown 项只能作为待调查问题。
- 每阶段结束更新相应验收框，并记录测试设备、Android 版本、APK 版本与测试结果。
- GitHub 发布前确认原 APK、密钥、服务凭据、用户数据、临时反编译产物均未纳入提交；原始 APK 继续保持本地文件，不上传。
- 不从原 APK 复制受版权约束的音频模型或云服务密钥到重实现仓库；使用这些资源前先确认分发许可和服务授权。
