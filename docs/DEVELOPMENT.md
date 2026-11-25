# 开发与验证手册

本文只维护开发方法和实现约束。总目标见 [ROADMAP](ROADMAP.md)，当前实现见 [ARCHITECTURE](ARCHITECTURE.md)；任务与问题台账在本机 `docs/PLAN.md`、`docs/ISSUES.md`，不随仓库分发。版本号以本地 Gradle 配置为准，实测结果只写在本机 HANDOFF 和对应报告中。

## 环境与构建

项目是 Windows 原生 Kotlin / Jetpack Compose Android 工程。默认使用 PowerShell 7；先定位真实项目根目录，不使用历史目录名。应用只构建 `arm64-v8a`；SDK、插件及依赖版本查 `build.gradle.kts` 与 `app/build.gradle.kts`。

目前仓库只分享产品源码和公开文档；构建配置、JVM 测试、原生库等保留在作者本机。只克隆仓库不能执行下面的构建，不应自动下载未知依赖或凭记忆重建配置。

本机没有项目 Wrapper，现有入口是 `.tooling` 中的 Gradle CLI JAR。先在项目根目录执行检查：

```powershell
$ErrorActionPreference = 'Stop'
Get-Command java
$gradleCli = Join-Path (Get-Location).Path '.tooling\gradle-9.6.0\lib\gradle-gradle-cli-main-9.6.0.jar'
if (-not (Test-Path -LiteralPath $gradleCli)) { throw '缺少本机 Gradle，先核对环境与构建配置' }
& java -version
if ($LASTEXITCODE -ne 0) { throw "Java 检查失败：$LASTEXITCODE" }
```

针对实现改动的完整本地验证入口：

```powershell
& java -jar $gradleCli --console=plain :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
if ($LASTEXITCODE -ne 0) { throw "Gradle 失败：$LASTEXITCODE" }
```

需要检查 release 配置时单独运行 `:app:assembleRelease`；它目前产生 unsigned APK，不等于正式发布。同一时间只跑一个 Gradle；不要加 `-g .gradle-home`，既有记录显示该缓存缺少所需 Android 插件。不要把 JVM 编译目标 17 当作 Gradle 运行时的唯一 Java 要求。

小范围改动可用任务的 `--tests` 选择相关测试类，例如 `app.noveltts.domain.SpeakerAttributionTest`。先确认类实际存在；失败时报告命令、关键错误、判断与下一步，不能把跳过当通过。

测试结果从 XML 汇总，不写死测试数量：

```powershell
$reports = @(Get-ChildItem -LiteralPath 'app\build\test-results\testDebugUnitTest' -Filter 'TEST-*.xml' -File)
if ($reports.Count -eq 0) { throw '没有测试结果文件' }
$rows = foreach ($report in $reports) {
    [xml]$result = Get-Content -LiteralPath $report.FullName -Raw
    [pscustomobject]@{
        Tests = [int]$result.testsuite.tests
        Failures = [int]$result.testsuite.failures
        Errors = [int]$result.testsuite.errors
        Skipped = [int]$result.testsuite.skipped
    }
}
$rows | Measure-Object -Property Tests,Failures,Errors,Skipped -Sum
```

必须同时记录构建命令的退出状态与结果文件时间，避免拿残留 XML 代表刚执行的命令。

## 按改动选择回归

测试位于本机 `app/src/test/java/app/noveltts/`；设备探针位于 `app/src/androidTest/`。以下是定位入口，不代表每次都运行全部，也不代表已有完整覆盖。

| 改动 | 首先查看的回归 |
| --- | --- |
| 文本、角色与别名 | `TextPipelineTest`、`SpeakerAttributionTest`、`ResourceBudgetTest`、`LargeTextRegressionTest` |
| 阅读器输入边界与角色回落 | `ReaderInputBoundaryTest`、`BuiltInRoleSettingsTest`；前者刻画现有限制，不能替代真实阅读器联动验收 |
| 书籍人物、固定绑定与恢复 | `BookRolesTest`、`BookRolesRepositoryTest`、`BookBackupTest`、`BookVoiceFallbackTest`；Android 隔离探针 `BookRolesDeviceProbe` 通过 runner 的 `mode=books` 选择，构建不代表执行 |
| 书籍表单与主题交互 | `BookEditorValidationTest` 校验姓名/别名边界、去重及同书冲突，`ThemeModeTest` 校验主题值；固定操作区、选择草稿和触控语义需另做界面验收 |
| 规则保存、草稿、导入 | `RoleSaveTest`、`RuleEditorSaveTest`、`RuleMutationsTest` |
| 系统回调与参数 | `SynthesisCallbackPumpTest`、系统 TTS 设备探针 |
| 取消、空音频、截止时间与预取 | `EmptySynthesisTest`、`SynthesisDeadlineTest`、`PrefetchSynthesisTest`、`SpoolBudgetTest` |
| 调度与音频缓存 | `BackendSchedulerTest`、`AudioCacheTest`、`CacheConcurrencyTest` |
| Edge 鉴权与帧解析 | `EdgeGecTest`、`EdgeFramesTest`；真实合成另做设备验证 |
| YAML、安装与音色快照 | `PackInputTest`、`PackYamlCacheTest`、`VoiceCatalogTest`、`VoiceOverridesTest`；目录切换/回滚需 Android 覆盖 |
| JSON、备份与恢复 | `JsonInputTest`、`AuditBackupTest`、`BackupManagerTest`、`RestoreDepthTest` |
| HTTP | `ForwardServerTest`、`ForwardHardeningTest`、`ForwardPcmCollectorTest`、`ForwardConnectionWatcherTest` |
| 正则 worker、系统 TTS、播放生命周期、真实音频 | `RulesProcessProbe`、`AuditDeviceProbe`、`AuditLifecycleProbe`、`SynthesisDeviceProbe` |
| 首段实验 | `FirstSegmentExperimentTest` 与同设备配对测量 |

纯文档修改只查链接、引用、状态一致性与 diff；不需要为了更新计划去构建 APK。

角色归属的现有黄金样本入口是 `SpeakerAttributionTest.sampleChapterResolvesEveryQuote`，读取本机 `app/src/test/resources/attribution/sample-yixin.txt`。不要按旧设计稿另找或新建不存在的黄金测试类；该用例的断言不能替代工作台预览、角色编辑和真实播放验收。

`ReaderInputBoundaryTest` 覆盖完整段落、旁白/对白拆分、引语中途切开、重复请求、无配置角色和样本逐段输入。它不模拟 Android Binder、实际阅读器全部切分策略或播放时序；今后改变会话行为时，需要同步区分“旧无状态模式的特征断言”与“新会话模式的验收断言”，不能为保留旧损失阻止修复。

## 设备与证据

- adb 优先用 `Get-Command adb` 查找；本机 SDK 的候选路径为 `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`。用 `adb devices` 核对当前设备，不写死旧序列号。
- 安装、修改设备设置或执行会改变数据的探针，按当前用户任务授权范围操作。测试前读探针参数及副作用，保留原配置，结束恢复临时修改。
- 设备 instrumentation 串行执行，不与独立系统 TTS 客户端的播放/停止测试重叠；工具可能重启应用进程。点击删除或恢复前核对当前界面的目标身份与确认内容，不能凭上次滚动位置推断。
- 历史设备拒绝 `INJECT_EVENTS`。不要为测试修改手机安全设置；只在相关操作确有需要时查看本机旧测试客户端和会话中的可用方法。
- 二进制拉取使用文件传输或保持二进制的 `adb exec-out`，不经过可能转换换行的交互式 `adb shell cat`。
- 每次验证使用新的 `.tmp/<任务>-<日期>/` 证据目录。至少记录源码基线与未提交范围、命令/退出状态、APK SHA-256、设备/系统、输入/参数、结果和配置恢复。不得覆盖旧报告。
- JVM、设备探针、人工交互和听感是不同证据。构建探针不等于运行；短句不代表长段；PCM 一致不代表尾音、焦点或听感全部通过。
- 比较合成路径时固定文本、完整音色 ID、最终 rate/pitch/volume、包/后端 revision 和缓存条件。分别记录墙钟等待、PCM 时长与播放开始，不能混用。

本机 `RulesProcessProbe` 还提供 `mode=bookRuntime`（当前书籍音源停用/恢复、注入失败回落及取消）与 `mode=edgeCancel`（真实 Edge 连接建立后取消及重试）。运行前检查其输入前提和报告目录；前者会暂时修改音色覆盖并恢复，二者都不能与用户朗读并行。`mode=synthesis` 的 `book=true` 比较当前书籍下的共用应用管线、系统 TTS 与 HTTP；`bookFixture=true` 会暂建人物方案并恢复。直接调用共用管线的 PCM 对照不能替代工作台界面/AudioTrack 播放验收。

外部路由由 `MainActivity` 定义：`open` 可定位工作台、语音包、规则或设置详情；`packUrl` / `catalog` 请求必须经过应用内确认，重建不能重放。`narrator` / `license` / `keepAwake` 调试 extras 只在 debuggable 下生效；不把许可证写到日志、示例或对话。完整包安装协议见 [VOICE_PACK_FORMAT](VOICE_PACK_FORMAT.md)。

## 修改时不能破坏的约束

### 配置与兼容

- `multitts:<packId>:<engineCode>:<voiceCode>` 是外部客户端保存的稳定身份，不是品牌文案。重命名前缀属于迁移任务；界面名称变化不得顺带修改 ID。
- 内部界面使用 `NovelTtsScreen` / `NovelTtsTheme`。命名整理保留导出服务 `app.noveltts.service.MultiTtsService`、偏好文件 `multitts` / `multitts.reading` / `multitts.text` / `multitts.secrets`、Keystore 别名 `multitts.license` 及原版语音包解析兼容；修改这些标识须有迁移与回归依据。原生库来源及参考项目说明按事实保留，不能通过改名删除来源记录或擅自替换依赖。
- 规则文件损坏与不存在必须区分。局部变更锁内读最新文件，编辑器校验打开时的基线；删除角色保留定向替换及 targetRoleId。不能拿旧 UI 副本覆盖整份配置。
- 书籍长期绑定与请求短期上下文分开；`BookRolesRepository.prepare` 只填补未绑定人物，不因停用或重排改写旧绑定。三入口均在配置锁内读取当前书籍后冻结计划，回落音色不得写回人物；编辑使用原书 ID/版本，不从当前书籍重新猜保存目标。
- 书籍主文件及 `.bak` 必须加入恢复事务；应用备份 schema 2、书库内部 schema 1 不混用。旧备份不含书库时保留本机；导入书库按 ID 整本合并，损坏和过期预览均不能无声覆盖。
- 事务入口 `ConfigurationRecovery` 与 `RestoreTransaction` 同在 `data/backup/RestoreTransaction.kt`。回滚前完整校验 journal；先保留可恢复副本，再替换。
- 包安装走临时目录、校验、备份、替换与失败恢复，不能先删除旧包。YAML 去除原类标签后用 `SafeConstructor` 解析，不能加载原 APK 的类。
- 不支持或缺模型/许可证的音色不能提供给系统。导入声明成功不等于后端已实现。

### 合成、协议与生命周期

- Edge GEC 的特殊哈希、时间分桶及 CS64 小端顺序以 `EdgeGec` 和固定向量为准；不要改为普通 SHA-256。端点、握手头及版本参数以 `SettingsStore` / `EdgeTtsEngine` 为准，不复制旧文档中的“最新浏览器版本”。
- Edge 二进制帧 payload 从 `2 + headerLength` 开始；不能按双 CRLF 搜索后再固定跳字节。
- 微软当前 SDK/JNI 组合固定为 1.37.0 的本机方案。不能混换版本，不能延续已撤销的“已补 DT_NEEDED”结论。历史建议的 embedded 依赖迁移必须另作当前证据核对。
- spool 等待超时先 `abort()` 再 `cancel()`，否则同步取消回调可能覆盖超时原因；预取失败等轮到该段才上报。停止后 AudioTrack 写入异常和迟到回调必须有界清理。
- 系统 TTS 只由框架消费线程调用回调，静音也遵守 `maxBufferSize`；失败和空输入必须有终止回调。ISO3 locale 与音色 ID 不混用，服务保留 `CATEGORY_DEFAULT`。
- 播放对象生命周期不重新绑定到 Activity 销毁；暂停试听与整篇朗读的恢复语义分开。取消只影响所属请求和会话。
- worker 超时/中断后先解绑、再终止、等待 Binder 断开后释放队列；保留中断标志，不能降级到主进程执行不可信正则。
- `PackYaml` 缓存的是完整清单内容，`VoiceCatalog` 缓存的是短期可用性；二者不等于 PCM 缓存。配置变化需失效，冷扫描仍须单独评估。
- 参数换算、系统音量、重试和空音频语义见 ARCHITECTURE；不要仅为让不同入口的耗时数字相同而修改它们。

### Compose 与交互

- `RoleConfigSheet` 的编辑基线有意固定；不要把 `state.roles` 加成会中途重置基线的 remember key。Sheet 的 `confirmValueChange` 保持稳定引用，变化值通过当前状态读取，避免重建导致反复入场。
- `BookEditorSheet` 同样固定打开时的原书 ID/版本；草稿与操作 ID 用 `rememberSaveable` 保留，异步任务仍由 ViewModel 持有。不要把新加载的书库作为重置草稿的 key；声音池绑定和手动指定音色分别保留，切换模式不能隐式清空。姓名草稿最多接收 256 字符、别名原文最多 2048 字符，超长输入保留原草稿并提示；保存时仍按书名 80、人物名/别名 60、16 个别名校验。UI 校验须与仓库的 trim、去重、同名冲突和限额一致，不能替代锁内校验。
- 书籍回归覆盖搜索/无匹配、连续多选池音色、64 项上限、失效音色保留、重排、取消确认、保存失败草稿、合并目标提示，以及 POOL → FIXED → DEFAULT → POOL 后原绑定仍在；关闭声音池只停止本面板发起且仍在播放的试听。
- 主题首页和顶栏共用 `ThemeModeSelector`，保留外观详情路由、存储值及备份兼容。主题切换、系统明暗变化和旋转后重查书籍草稿；跨页播放控制分别验证朗读、暂停续读、试听和停止试听后的恢复。
- UI 实测至少覆盖窄屏（约 360/375 dp）、横屏、系统大字号、键盘展开、系统减少动画设置、TalkBack、手势/三键导航栏；核对固定保存区、列表最后一项、单选整行、搜索清空和错误提示可达。静态文本颜色对比度计算不替代实际渲染、遮罩、禁用态和 Android 15+ 系统栏验收。
- 旁白/对话显示的可能是回落音色。用户只改倍率时用 `updateBuiltInMultipliers`，不要把回落音色写成用户主动选择。
- 保持青绿主色、少量暖橙提示、清晰阅读层级和现有 `AppIconGlyph`；不新增图标库，不整体移植网页动效。
- 控件保留文字/语义与足够触控区，不能只靠颜色表达状态。动画不延迟错误和播放控制；无后端的入口不能伪装成可用操作。
- 工作台、规则页和包页的详细状态行为见 ARCHITECTURE；UI 适配是否通过只在 ISSUES/证据中记录，不在设计规范里假定完成。

## 文档维护检查

改动后确认当前文档的相对路径和锚点可解析；归档内容允许保留原始路径，但不作为默认入口。检查 PLAN 引用的 `I-xxx` 都存在，完成问题没有残留为待办，README 的导航指向有效文件。

归档前保存原文件及 SHA-256 清单，并验证归档能读回。清退文件时核对绝对路径与归档哈希，只操作明确的旧文档/旧任务目录；不清理 `.tmp` 证据、测试代码或用户资料。文件和网络权限始终以当前会话为准，旧沙箱限制不作为永久工程规则。
