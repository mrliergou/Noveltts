# NovelTTS 开发交接

最后更新：2026-11-15（第六次更新：完成 Effect Atlas UI 规划与顶栏菜单/关闭图标资产，见 §0.3；阶段 0+1 改动仍未提交）
项目根目录：`F:\codex\MultiTTS`  
目标设备：`M2102J2SC`，Android 13 / SDK 33，arm64-v8a，adb 序列号记录为 `5cb24277`

## 0. 新会话先看这里

> **最新进展先看 §0.3（2026-11-15）**：已完成 Effect Atlas 交互规划和顶栏菜单/关闭图标资产，尚未接入页面；§0.2 的界面拆分与 schema v2 改动仍未提交。
> 下面这段「一句话状态」写于 2026-11-12，其中界面结构、单测数量已过时。

**一句话状态：** `ui/MultiTtsScreen.kt` 已按新 `MainViewModel` 重建（1270 行，五页：工作台/语音包/角色/替换/设置），`:app:assembleDebug` 成功、13 个单测全绿、APK 已产出；三个系统 TTS 配置 Activity 也已补齐。**剩下的唯一断点是真机验证**——设备当前未连接（`adb devices` 为空），所以界面与系统调用都还没有在设备上跑过。

新会话请按以下顺序行动：

1. 先读本文件第 2、4、5、6 节，不要重新设计已经完成的数据层和引擎层。
2. 用只读方式核对 `ui/MainViewModel.kt`、`MainActivity.kt`、`service/MultiTtsService.kt` 的实际 API。
3. 接上设备（`adb devices` 应出现 `5cb24277`）后按 §4 的验收清单跑真机验证。
4. 验证新发现的问题优先于新功能。

**改名说明（2026-11-12）：** 应用名 `NovelTTS`，`namespace`/`applicationId` 均为 `app.noveltts`，源码目录 `app/src/main/java/app/noveltts/`。
系统的音色前缀**仍然是** `multitts:<packId>:<engineCode>:<voiceCode>`——它是已保存的协议标识，改名会让用户已选音色全部失效，所以没有跟着改。
**这是有意保留，不是遗漏**；用户已明确表示"后期正常后会进行整体更改"，迁移清单见 §6.13，改之前不要动这个前缀。
包名变化意味着旧的 `org.nobody.multitts.replica` 装不上也升不上，**必须卸载重装**，装完还要在系统设置里重新选一次默认引擎。

**工作区已在提交 `f9da1eb` 收口**（界面重建 + 引擎/管线接入 + 打包修复）。本地 119 MB 测试包 `语音包\【离线】 微软官方语音（晓晓+云希）(1).zip` 已加入 `.gitignore`，**不要提交**。不要执行 `git reset --hard`、`git clean -fdx` 或回滚已完成改动。

**当前绝对不能宣称已完成的事项：**

- Edge 在线真实发声、MP3 解码、系统 TTS 调用、离线包导入后的真机发声都尚未验证（目前只有编译与单测证据）。
- 微软离线引擎在没有用户合法 Embedded Speech 许可证时只验证了“缺许可证会报错”，不能宣称真实离线发声已完成。
- 界面只做了静态核对与编译验证，**没有截图验收**。

## 0.1 2026-11-14 会话记录：音色前缀迁移（只出了计划，代码未动）

**状态：** 计划已写好并保存在 `.claude/plan/voice-prefix-migration.md`，**产品代码一行没改**，
`VoiceRegistry.systemName()` 仍然产出 `multitts:`。执行方式：新会话运行
`/ccg:execute .claude/plan/voice-prefix-migration.md`。

**计划要点**（细节以计划文件为准）：

- 新前缀 `noveltts:<packId>:<engineCode>:<voiceCode>`；新增纯 Kotlin 的 `VoiceIds.canonicalize()`，只替换开头的 `multitts:`。
- 迁移挂在 `SettingsStore` / `RulesRepository` 的 `init` 里，不新建 `Application`；幂等，不加"已迁移"标记。
- `roles.json` 升到 schema 2；`replacements.json` 保持 schema 1。
- 系统 `onGetVoices()` 只列新名字；旧名字只在 `onIsValidVoiceName` / `onLoadVoice` / `onSynthesizeText` 里兼容。
- 界面只改两处文案并加一行说明，不做弹窗。
- **不改**：SharedPreferences 文件名 `"multitts"`、`SecretStore` 别名、类名、线程名、原版包 YAML 标签。

**执行前要知道的事：**

- 外部阅读器遇到旧名字的表现（保留 / 回落默认音色 / 报错）只是推断，**没有在设备上验证**，验收时要实测并写回 §6.13。
- `roles.json` 迁移没有 JVM 单测：项目只有 `junit` 一个测试依赖，本地单测里 `org.json` 是桩。
  计划用真机验收覆盖；若要 JVM 覆盖需加 `testImplementation("org.json:json")`，属于新增依赖，先问用户。
- 本次会话的 Git Bash 里 `adb` 不在 PATH（`adb: command not found`），设备是否连接没查到；真机验收前先确认 adb 路径。
- 多模型调用：codex 分析成功（`CODEX_SESSION: 01a1da99-0886-7592-b572-85a149ff493f`）；
  gemini 启动即退出（退出码 41，缺 `GEMINI_API_KEY`），计划里的界面部分没有 gemini 的意见。

**本文件里已过时、尚未修订的章节**（以 §6.5、§6.6、§6.14 和 git 记录为准）：

- §0 一句话状态、§4 P2：写的是"设备未连接、Edge 与离线发声未验证"；之后的提交 `01d4829`、`0138ab3`、`ba632fa`
  已修掉 Edge 握手 403 和电音问题，§6.14 记录离线「晓晓」真机合成成功。
- §2、§7、§8：仍写 `MultiTtsScreen.kt` 缺失，实际已重建。
- §3.1：`client-sdk:1.51.0` 实际已是 `1.37.0`。
- §3.7：端点与鉴权参数是旧的，正确值见 §6.5。
- §3.11：测试文件还有 `EdgeFramesTest.kt`，单测数量多于 13。
- 文档仍未记录的验证项：系统 TTS 被其他应用调用能否出声、五页截图验收、小说文本的角色/替换/纯标点过滤实测。

## 0.2 2026-11-15 会话记录：深度功能计划阶段 0 + 1（已实施，未提交）

**计划文件：** `.claude/plan/noveltts-deep-features.md`（共 9 个阶段 0–8）。用户本轮只批准了**阶段 0（拆包）+ 阶段 1（规则 schema v2）**，
直接在 `main` 上改，**尚未提交**。下一批按计划顺序是阶段 3（单段试听 + 朗读进度）→ 阶段 6（音色选择器增强）。
执行方式：`/ccg:execute .claude/plan/noveltts-deep-features.md`，并告诉它从阶段 3 开始。

**验证结果（只有编译和单测，没有真机）：**

- `:app:testDebugUnitTest` → 5 个测试类共 **40 个**测试全部通过（RulesRepositoryMigrationTest 13、TextPipelineTest 13、PackYamlTest 6、EdgeFramesTest 5、EdgeGecTest 3）。
- `:app:assembleDebug` → `BUILD SUCCESSFUL`；改动文件里没有编译警告。
- 拆包后的界面、音色选择器 Sheet、删除确认弹窗、v1→v2 迁移都**没有在设备上跑过**。

### 阶段 0：界面拆包

| 位置 | 内容 |
|------|------|
| `ui/MultiTtsScreen.kt` | 只剩外壳：抽屉 `AppDrawer`、顶栏、`BottomPanel`。签名不变（`MainActivity` 依赖它）。 |
| `ui/workbench/` | `WorkbenchPage.kt`（含 `SliderRow`、`SAMPLE_TEXT`）、`PreviewRow.kt` |
| `ui/packs/` | `PacksPage.kt`（含 `PackCard`、删除语音包二次确认）、`CatalogRow.kt` |
| `ui/rules/` | `RulesPageScaffold.kt`（单一 `LazyColumn` 骨架 + 加载失败横幅 + `RuleCard`）、`RolesPage.kt`、`ReplacementsPage.kt` |
| `ui/settings/` | `SettingsPage.kt`（拆出 `EdgeEndpointCard`、`LicenseCard`） |
| `ui/common/` | `SectionCard`/`SectionTitle`、`Badge`、`EmptyState`、`ConfirmDialog`、`SearchField`、`FilterChipRow`、`VoiceField`、`VoicePickerSheet`、`MenuCloseIcon`、`Formatters.kt`（`formatSize`/`voiceStatusLabel`/`voiceSubtitle`） |

- `ui/VoicePackDialog.kt` **已删除**（没有引用，和主界面代码重复）。
- 角色/替换列表从 `verticalScroll + forEach` 改为单一 `LazyColumn`，条目 key 是 `"rule:${id}"`；这是阶段 2 拖拽排序的前提。
- `VoicePickerSheet` 取代旧的 `AlertDialog` 选择器：可搜索（显示名/包名/引擎/音色代码/系统名）、全部/在线/离线筛选、不可用音色灰显并显示原因。
  整个 Sheet 是一个 `LazyColumn`（避免横屏时列表被压成 0 高度）；选中后先回调 `onSelect`，播完收起动画再回调 `onDismiss`，**调用方只在 `onDismiss` 里关闭**。
- 删除规则、删除语音包都改为先弹 `ConfirmDialog`。
- `PACK_BUSY_IMPORT` 现在是 `MainViewModel.kt` 里的 `internal const`，界面通过 `MainUiState.isImportingPack` 读，不要在 UI 再写一份 `"import"`。

### 阶段 1：规则 schema v2

**数据模型（`domain/Rules.kt`）：**

- 新增 `RuleGroup(id, name, enabled, order)`，默认分组 `RuleGroup.DEFAULT`（id `"default"`，名称「默认」，order 100）。
- 新增 `RuleSet<T>(groups, items)`；`RulesRepository` 的读写接口都改用它。
- `RoleRule.voiceId: String` → **`voiceIds: List<String>`**，取首个非空音色用 `primaryVoiceId`；新增 `groupId`（非空，默认 `"default"`）、`matchMode`、`voiceSelectionMode`、`sortIndex`。
- `ReplacementRule` 新增 `sortIndex`、`groupId`；`order` 保留为 v1 兼容字段。
- `RoleMatchMode`：`REGEX_CONTAINS`（v1 行为）/ `REGEX_PREFIX`（`lookingAt`）/ `REGEX_SUFFIX`（`(?:p)\z`）/ `EXACT`（整段 `matches`）。
- `VoiceSelectionMode`：`FIXED`（v1 行为）/ `ROUND_ROBIN`（同一次 `build()` 内该规则每命中一次换下一个音色）。

**文本管线（`domain/TextPipeline.kt`）：**

- `build()` 新增可选参数 `roleGroups`、`replacementGroups`（默认空列表，旧调用方不受影响）。
- 规则生效条件：`enabled` 且所在分组没有停用；**不认识的分组按默认分组处理**（排序也用默认分组的 order）。
- 替换执行顺序：(分组 order, sortIndex)。角色匹配顺序：priority 降序 → 分组 order → sortIndex。
- 没有任何非空音色的角色规则会被跳过。
- `RoleMatcher` 在构造时一次性编译全部正则（无效正则只报一个 issue），轮转计数按规则在列表中的位置记，不依赖 id 唯一。
- `SynthesisPlanBuilder.build()` 的 `roles`/`replacements` 参数改为 `RuleSet`。

**仓库（`data/config/RulesRepository.kt`）：**

- 主构造函数改为 `RulesRepository(directory: File)`（供 JVM 单测），`RulesRepository(context)` 作为次构造函数保留。
- 文件格式 `{"schema":2,"groups":[...],"items":[...]}`；v2 条目里**额外写一份 `voiceId`（首个音色）**，旧版本回装时还能读。
- 迁移：`schema` 缺失或为 1 → 读取时迁移并**立即写回 v2**，原 v1 内容留在 `.bak`。
  角色 sortIndex 按「优先级降序、同级保持文件顺序」生成；替换 sortIndex 按 `order` 升序生成。
- 出错处理（新异常 `RulesStoreException : IOException`）：
  - JSON 解析失败、缺 `items` 数组、`items` 里有非对象元素 → 原文件复制为 `.corrupt` 后抛异常，**原文件不动**；
  - `schema > 2` → 直接抛异常，文件、`.bak`、`.corrupt` 都不碰；
  - 文件不存在 → 返回只含默认分组的空集合。
- 归一化：保证默认分组存在、分组 id 去重、条目引用不存在的分组时改为 `"default"`；
  **重复的规则 id 会改名为 `id#2`、`id#3`…**（v1 在 id 缺失时回落成规则名，同名规则共享 id，列表以 id 为 key 会崩溃）。
- 写入：`File.createTempFile` 生成唯一临时文件 → 重新解析校验 → 旧文件复制为 `.bak` → `Files.move(ATOMIC_MOVE)`，不支持原子移动时退回普通替换；临时文件在 `finally` 里删除。
- 读写都持有一个**进程内**的全局锁 `LOCK`（见下方「已知限制」）。

**调用方：**

- `MainUiState` 新增 `roleGroups`、`replacementGroups`、`rolesLoadError`、`replacementsLoadError`，以及派生属性 `roleSet`/`replacementSet`/`isImportingPack`。
- 某类规则加载失败时：`MainViewModel` 保留上一次的内存数据、记录错误，`persistRoles`/`persistReplacements` 拒绝保存；规则页显示红色横幅，新增/编辑/删除/开关全部禁用。
- 保存时 ViewModel 会重排 sortIndex：角色按「优先级降序、同级按名称」，替换按 `order` 升序（界面仍按旧的优先级/顺序字段编辑，阶段 2 改成拖拽后再换）。
- 编辑既有规则用 `rule.copy(...)`，**分组、匹配模式、轮转音色等 v2 字段会原样保留**；编辑对话框只改首个音色，其余轮转音色显示「另有 N 个轮转音色保持不变」。
- `MultiTtsService` 加载规则失败时记日志，本次按没有规则合成（`loadRulesOrEmpty`），不阻断系统朗读。
- `app/build.gradle.kts` 新增 `testImplementation("org.json:json:20240303")`：Android 自带的 `org.json` 在 JVM 单测里是空桩，规则仓库测试需要真实实现。只影响测试，不进 APK。

### 已知限制（有意保留）

- **只防同一进程内的并发写入。** `LOCK` 是 JVM 静态锁；目前 Manifest 里没有 `android:process`，界面和 `MultiTtsService` 在同一进程，所以现在不会出问题。
  以后如果把服务放到独立进程，两边同时迁移或保存可能互相覆盖、`.bak` 也可能被冲掉——那时要加跨进程文件锁，并在保存前检查文件版本。
- **新字段还没有编辑界面。** 分组、匹配模式、多音色、轮转在数据层和管线里都已生效，要到阶段 2（规则管理 UI 重构）才能在界面上编辑。

### 与前缀迁移计划（§0.1）的冲突 —— 执行那个计划前必须先改它

`.claude/plan/voice-prefix-migration.md` 写于本轮之前，里面的假设已经过时：

1. 它计划把 `roles.json` 升到 **schema 2**——现在 schema 2 已被分组/多音色占用，前缀迁移需要改成 schema 3，或者不升版本、在 v2 里幂等地替换前缀。
2. 它按单值 `voiceId` 写迁移逻辑——现在是 `voiceIds` 列表，外加一个冗余的 `voiceId`，两处都要替换。
3. 它说 `roles.json` 迁移没有 JVM 单测、加 `org.json` 测试依赖需要先问用户——这个依赖本轮已经加上，可以直接写 JVM 单测。
4. 它引用的界面行号（`ui/MultiTtsScreen.kt:145`、`:1028`）已失效：「系统音色名」文案现在在 `ui/MultiTtsScreen.kt` 的 `AppDrawer` 和 `ui/settings/SettingsPage.kt` 的「系统集成」卡片里。

### 多模型协作记录

- codex（`CODEX_SESSION: 01a1dd7f-a7ab-7110-a24a-281c110709e5`）：出了阶段 1 原型并做后端审计。采纳的修复：无效正则重复报错、重复 id 串用正则缓存和轮转计数、未知分组排到最后、非对象条目被静默丢弃。跨进程锁只记录、未实施（见上）。
- gemini（`GEMINI_SESSION: 769e1742-acbd-4969-bb58-3a93c3dbeb61`）：本轮可用（§0.1 里的 `GEMINI_API_KEY` 问题已不存在），出了公共组件原型并做前端审计。采纳的修复：重复 id 导致列表崩溃、横屏时 Sheet 列表高度为 0、收起动画缺失、开关的读屏标签、48dp 触控区、`PACK_BUSY_IMPORT` 重复定义。
  gemini 读不到 `.claude/plan/` 下的文件（被它的忽略规则挡住），需要把计划要点直接写进提示词。

## 0.3 2026-11-15 会话记录：Effect Atlas UI 规划与图标资产

**状态：** 已完成设计规划和一个可复用的 Compose 图标组件，未接入现有页面，未添加依赖，未修改业务逻辑。

- `docs/UI_EFFECT_ATLAS.md` 记录了从 Effect Atlas 选取的可迁移交互模式、适用页面、采用边界、图标位置清单和后续落地顺序。
- Effect Atlas 只作为交互灵感来源；Android 实现使用 Compose 状态、手势和动画，不接入网页 JavaScript、CSS、GSAP 或 hover 行为。
- 已绘制 `app/src/main/java/app/noveltts/ui/common/MenuCloseIcon.kt`：顶栏菜单按钮的两条线在抽屉展开时交叉为关闭符号，图形为 24dp 单色线性风格，颜色跟随 Material 主题。
- 组件使用 `animateFloatAsState` 驱动状态变化，并提供“打开导航菜单/关闭导航菜单”和展开状态的语义描述。接入时应由至少 48dp 的 `IconButton` 承载，并与 `drawerState` 共用展开状态。
- 工作台、语音包、角色、替换、设置、日志等页面的通用图标仍等 UI 框架定稿后从同一套 Material 风格图标集中映射；不混用 emoji、Unicode 字符或临时手绘图标。
- `.gitignore` 新增 `.ui-preview/`，用于忽略未来 Compose 截图或临时导出的本地 UI 预览；`MenuCloseIcon.kt` 和 `docs/UI_EFFECT_ATLAS.md` 属于项目源文件，必须保留在版本控制中。

**本轮核对：** 已检查新组件和文档，`git diff --check -- docs/UI_EFFECT_ATLAS.md` 通过；未运行构建、单测、截图验收或真机验证。

**下一步：** UI 框架定稿后，将 `MultiTtsScreen.kt` 顶栏的文字“菜单”替换为 48dp `IconButton` + `MenuCloseIcon(expanded = drawerState.isOpen)`，然后再按图标位置清单逐页接入统一图标集。

### 待办（按优先级）

1. [ ] 真机验证：放一份旧的 v1 `roles.json`（可以带两条同名、没有 id 的规则），打开应用后确认规则都在、`files/config/` 下生成了 `.bak`、重复 id 被改名为 `#2`。
2. [ ] 真机检查音色选择器的搜索、筛选、横屏显示、收起动画，以及删除确认弹窗。
3. [ ] 提交本轮改动（用户说「提交」或执行 `/ccg:commit` 时才做）。`.gitignore` 和本文件里有本轮之前就存在的未提交改动，提交前一起检查。
4. [ ] 继续深度功能计划：阶段 3 → 阶段 6 → 阶段 2 → …

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

数据层、原版语音包解析、统一音色注册表、Edge 引擎、微软嵌入式引擎、文本/角色/替换管线和系统 TTS 服务主体已经写完，相关纯 Kotlin 测试已通过；界面已按新 `MainViewModel` 重建（`ui/MultiTtsScreen.kt`，1270 行，五页），仓库**已恢复可编译**，`assembleDebug` 与 13 个单测都通过。

最直接的待办：

- 真机验收尚未进行（设备未连接），界面与系统 TTS 调用都还没在设备上跑过。
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
- ~~JSON 带 `schema=1`，写入使用 `.tmp` 并保留 `.bak`。~~ **2026-11-15 起为 schema 2**（分组 + 多音色 + 自动迁移 + 损坏保护），详见 §0.2。

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

**2026-11-15 更新：** 第 2、7 步改为按分组和 sortIndex 排序、支持分组停用、四种匹配模式和轮转音色，详见 §0.2「文本管线」。

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

**最新（2026-11-15）：40 个测试全部通过**，新增 `data/config/RulesRepositoryMigrationTest.kt`（13 个），`TextPipelineTest` 增至 13 个；明细见 §0.2。以下是旧记录：

最后一次完整单测结果：`13 tests completed`（TextPipeline / EdgeGec / PackYaml），全部通过。

测试文件：

- `app/src/test/java/.../engine/edge/EdgeGecTest.kt`
- `app/src/test/java/.../domain/TextPipelineTest.kt`
- `app/src/test/java/.../data/voicepack/PackYamlTest.kt`

已覆盖：

- Edge SHA-256 中间摘要。
- Edge CS64 中间值。
- Edge v2 最终固定向量。
- 引号对话分段。
- 角色规则优先级。
- 替换顺序和正则分组。
- 纯标点过滤。
- 原版 YAML 解析：根节点/列表项的 `!!` 类型标签剥离、引擎 supported 标记、locale 推断、
  `type` 与 edgetts 的在线判定、`packId` 稳定性（同一 YAML 必得同一 ID）。

另用真实包做过手工核对（未入库）：`【在线】 EdgeTTS（中文+英文）.zip` 解析出 61 个在线音色，
`【离线】 微软官方语音（晓晓+云希）(1).zip` 解析出 2 个离线音色（`param` 为
`Microsoft Xiaoxiao (Natural) - ...`，模型目录落在 `microsoft/<code>`，与 zip 内布局一致）。

界面重建后已重新跑全量构建与单测，结果见 §4 P0。

## 4. 当前卡点

### P0：已完成（2026-11-12）

1. `ui/MultiTtsScreen.kt` 已重建（1270 行），签名保持不变：
   ```kotlin
   @Composable
   fun MultiTtsScreen(
       viewModel: MainViewModel,
       onOpenSystemSpeechSettings: () -> Unit,
       openVoicePacksOnStart: Boolean = false,
   )
   ```
2. 五页齐全：工作台（文本/旁白对话音色/三滑杆/朗读停止/分段预览）、语音包（导入/已安装/在线目录/下载进度/删除）、角色（正则/音色/优先级/仅对话/启停）、替换（普通与正则/顺序/启停/测试框）、设置（纯标点过滤/系统音色/Edge 端点/许可证/系统语音设置/清除最近文本）。
3. 验证结果：
   - `:app:compileDebugKotlin` → `BUILD SUCCESSFUL`
   - `:app:assembleDebug` → `BUILD SUCCESSFUL`，APK `app/build/outputs/apk/debug/app-debug.apk`（27.8 MB）
   - `:app:testDebugUnitTest` → `tests="4"` + `tests="3"`，0 failures / 0 errors
4. 打包修复：微软语音 SDK 传递依赖的 10 个 netty jar 各带一份 `META-INF/INDEX.LIST`，`mergeDebugJavaResource` 报重复文件；已在 `app/build.gradle.kts` 的 `packaging.resources.excludes` 排除 `INDEX.LIST`、`io.netty.versions.properties` 与签名文件。

### P1：已完成（2026-11-12）

- 新增 `app/src/main/java/app/noveltts/ui/engine/EngineSupportActivities.kt`：
  - `TtsCheckDataActivity`：响应 `android.speech.tts.engine.CHECK_TTS_DATA`，回传 `EXTRA_AVAILABLE_VOICES` / `EXTRA_UNAVAILABLE_VOICES`（后者含缺许可证的离线音色）。
  - `TtsSampleTextActivity`：响应 `android.speech.tts.engine.GET_SAMPLE_TEXT`，按 `language` extra 返回中/英示例文本。
  - `TtsConfigureActivity`：响应 `android.speech.tts.engine.CONFIGURE_ENGINE`，打开主界面。
- `AndroidManifest.xml` 已注册三个 `exported=true`、带 `DEFAULT` 类别、`Theme.NoDisplay` 的 Activity；`aapt2 dump xmltree` 已确认合并进 APK。

### P2：运行时未验证

- 尚未导入两个真实 zip 做设备测试。
- Edge 在线真实发声尚未验证。
- MP3 解码器尚未在真机验证。
- 系统 TTS 调用本应用引擎尚未验证。
- 微软离线无许可证路径可验证“可见但不可用”；真实发声必须等用户提供合法许可证。

## 5. 下一步计划

建议按这个顺序：

1. ~~写回 `MultiTtsScreen.kt`~~（已完成）。
2. ~~编译并修 Compose/API 错误~~（已完成，BUILD SUCCESSFUL）。
3. ~~TTS 数据检查/示例文本/引擎配置 Activity + Manifest~~（已完成）。
4. **当前第 1 步**：接上设备（`adb devices` = `5cb24277`），`adb install -r app/build/outputs/apk/debug/app-debug.apk`，截图验收五页界面。
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
10. 更新文档（**2026-11-12 已完成一轮，未提交**）：
    - `README.md` 已按「真引擎」现状重写（对照表、能力/未实现、目录、构建、语音包、系统 TTS 使用、已知限制、下一步）。
    - `docs/VOICE_PACKS.md` 已重写为原版 YAML 包格式 + 目录协议 + 安装安全边界。
    - `docs/REIMPLEMENTATION_TARGET.md` §7 改为「进展」并写入本轮踩坑，§8 验收框标注「已编码，待真机」。
    - 真机验收完成后只需把 §8 的框按实测结果勾选，并把「运行时未验证」的措辞收紧。

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

**2026-11-12 已修正，此前文档里的 `api.msedgeservices.com` 端点会稳定 403。**

当前必须使用的端点（`SettingsStore.DEFAULT_EDGE_ENDPOINT`）：

```text
wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1
```

鉴权参数是 `TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4`，**不是** `Ocp-Apim-Subscription-Key`。
`SettingsStore` 会把旧的 `api.msedgeservices.com` 值当作"未设置"自动回落到新端点。

版本号必须跟当前 Edge 浏览器一致（`EdgeGec.SEC_MS_GEC_VERSION`）：

```text
2-143.0.3650.75     // EdgeGec.CHROMIUM_FULL_VERSION = 143.0.3650.75
```

UA 主版本号要与之匹配，握手还要带这些头，缺一不可：

```text
Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold
Pragma: no-cache
Cache-Control: no-cache
Cookie: MUID=<32 位大写十六进制，随机生成>
```

排查方法：`pip install --target .tmp/site edge-tts`（7.3.1），对照 `.tmp/site/edge_tts/constants.py`，
PC 上能出声就说明服务端没问题，是客户端参数不对。

### 6.6 Edge 音频帧：payload 起点是 `2 + headerLength`，不是 `\r\n\r\n` 之后

**这条踩过两次，第二次的后果是"在线音色全是电音/杂声"。**

帧布局：

```text
[2 字节大端 header 长度][header][payload]
```

header 是若干 `Key:Value\r\n` 行，**末尾只有一个 CRLF**，payload 紧接其后。
所以 `payloadStart = 2 + headerLength`（`app/src/main/java/app/noveltts/engine/edge/EdgeFrames.kt`）。

不要：
- 用固定偏移 `130` / `105`；
- 用 `\r\n\r\n` 定位再 `+4`——这类帧里根本没有空行，找不到就退化成 `2+len`，再 `+4` 会**每帧多跳 4 字节**，正好吃掉 MP3 帧同步头 `FF F3 64 C4`，MediaCodec 一路重新同步，听起来就是电音。

`EdgeFramesTest` 5 个用例守住这条（payload 首字节必须是 `FF F3 64 C4`、大端长度、payload 里出现 CRLF 也不能被误切）。

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
- **2026-11-15 实测：** 直接跑 `./gradlew` 会去下载 `gradle-9.6.0-bin.zip`（`~/.gradle` 下没有完整的分发包），中途打断会留下 `.lck`/`.part`，下次报 `Timeout ... waiting for exclusive access`。
  一律用项目脚本，它会指向带离线缓存的 Gradle Home：
  `pwsh -NoProfile -File scripts/build.ps1 -Task ':app:testDebugUnitTest' -NoOffline`（在 Git Bash 里要写 pwsh 的完整路径 `"/c/Program Files/PowerShell/7/pwsh.exe"`）。
- `minSdk=26` 是微软 SDK 要求，不要为了兼容降回 23。
- 当前只打包 `arm64-v8a`；x86_64 模拟器需要临时加 ABI。

### 6.13 音色前缀 `multitts:` 是故意保留的，别顺手改

应用叫 NovelTTS，但 `VoiceRegistry.systemName()` 产出的仍然是 `multitts:<packId>:<engineCode>:<voiceCode>`。

原因：这个字符串是**持久协议标识**，同时存在于第三方阅读器/系统设置保存的音色选择、`SettingsStore` 的
`narratorVoiceId`/`dialogueVoiceId`/`systemVoiceId`、以及 `RulesRepository` 角色规则的 `voiceId` 里。
换前缀等于让所有已选音色失效，而外部应用里存的名字我们无法回写。

用户已确认"后期正常后会进行整体更改"。真正迁移时必须一次做完：

1. `VoiceRegistry.systemName()` 改新前缀；
2. `VoiceRegistry.find()` 保留旧 `multitts:` ID 的兼容映射（读旧写新），否则老用户音色瞬间全丢；
3. `SettingsStore`/`RulesRepository` 持久化 voiceId 做一次性迁移（`roles.json` 现在是 schema 2，要同时改 `voiceIds` 列表和冗余的 `voiceId`，见 §0.2）；
4. 界面两处「系统音色名」说明文案（现位于 `ui/MultiTtsScreen.kt` 的 `AppDrawer` 和 `ui/settings/SettingsPage.kt` 的「系统集成」卡片）；
5. 发布说明提示用户：外部阅读器需要重新选一次音色，这部分无法自动迁移。

**2026-11-14：** 上述迁移已出完整实施计划（`.claude/plan/voice-prefix-migration.md`），**尚未实施**，本节"别顺手改"的约束在计划执行前仍然有效。
计划对清单的两处细化：第 2 条的兼容不只在 `find()`，`SynthesisPlanBuilder` 和 `MultiTtsService` 的精确比较点也要归一；
第 4 条的行号现为 `ui/MultiTtsScreen.kt:145`、`:1028`。概况见 §0.1。

## 7. 关键文件索引

数据：

- `app/src/main/java/app/noveltts/data/voicepack/VoicePack.kt`
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

（2026-11-15 已按阶段 0 拆包，下面是现状）

- `.../ui/MainViewModel.kt`：状态与业务入口
- `.../ui/MultiTtsScreen.kt`：外壳（抽屉 / 顶栏 / 底部面板），签名由 `MainActivity` 固定
- `.../ui/workbench/`、`ui/packs/`、`ui/rules/`、`ui/settings/`：各页面
- `.../ui/common/`：公共组件（`VoicePickerSheet`、`ConfirmDialog`、`EmptyState`、`MenuCloseIcon` 等）
- `.../ui/engine/EngineSupportActivities.kt`：系统 TTS 配置 Activity
- ~~`ui/VoicePackDialog.kt`~~ 已删除

文档：

- `docs/UI_EFFECT_ATLAS.md`：Effect Atlas 交互模式适配记录、图标位置清单和 UI 落地顺序

测试：

- `app/src/test/java/app/noveltts/engine/edge/EdgeGecTest.kt`
- `app/src/test/java/app/noveltts/engine/edge/EdgeFramesTest.kt`
- `app/src/test/java/app/noveltts/domain/TextPipelineTest.kt`
- `app/src/test/java/app/noveltts/data/voicepack/PackYamlTest.kt`
- `app/src/test/java/app/noveltts/data/config/RulesRepositoryMigrationTest.kt`

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

### 6.14 离线音色（微软 Embedded）的原生库与 SDK 版本（2026-11-12 实测）

- Maven 的 `com.microsoft.cognitiveservices.speech:client-sdk` **只带 core / java.bindings / kws / codec**，
  不带 `libMicrosoft.CognitiveServices.Speech.extension.embedded.tts.so`、`...embedded.tts.runtime.so`、
  `...extension.telemetry.so`，也不带 `libc++_shared.so`。缺 telemetry 时 `EmbeddedSpeechConfig.<init>`
  直接 `dlopen` 失败；缺 embedded.tts 时 `SpeechSynthesizer` 构造报
  `SPXERR_EXTENSION_LIBRARY_NOT_FOUND (0x24)`。
- 版本必须对齐：用 `1.51.0` 的 Java/native 配原版（`1.37.0`）的 embedded 扩展时，`setSpeechSynthesisVoice`
  会 `No implementation found`，扩展也加载不上。改成 `client-sdk:1.37.0`（与原版 APK 内 native 同版本，
  原版 `lib...core.so` 里可见 `1.37.0`）后一次通过。
- `telemetry.so` 的 `DT_NEEDED` 里**没有** `libc++_shared.so`，直接加载会报
  `cannot locate symbol _ZNKSt6__ndk120__vector_base_commonILb1EE20__throw_length_errorEv`；
  用 `lief` 给它（以及 `embedded.tts.so`、`embedded.tts.runtime.so`）补一条 `DT_NEEDED libc++_shared.so`
  即可，另外在 `MicrosoftEmbeddedEngine` 的 companion `init` 里 `System.loadLibrary("c++_shared")`。
  注意：预加载本身不解决符号解析（Java 的 `loadLibrary` 不把库放进全局组），补 `DT_NEEDED` 才是关键。
- 这些库是微软的分发物，**不进仓库**：`app/src/main/jniLibs/` 已加入 `.gitignore`，需要时从官方
  Embedded Speech 包或本地联调环境放入。
- 已验证：注入许可证后，离线音色「晓晓」真机合成成功（`AudioTrack: stop(...) called with 39600 frames
  delivered`，24 kHz 单声道 ≈1.65 s），界面状态「朗读完成」。
- `MainActivity` 新增了两个**仅 debuggable 生效**的 extra：`--es license <许可证>`、`--es narrator <systemName>`，
  用于自动化验证；正式包忽略它们。

