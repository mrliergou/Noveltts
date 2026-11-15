# NovelTTS UI 效果参考与图标规划

**状态：** 设计记录，尚未实施。供后续 UI 框架定稿和 Compose 页面重构时选用。
**整理日期：** 2026-11-15
**适用范围：** Android Kotlin + Jetpack Compose；不接入网页 JavaScript、CSS 或 GSAP。

## 项目背景与设计目标

NovelTTS 是给小说阅读和系统 TTS 使用的 Android 引擎。用户需要导入语音包、挑选音色、准备文本、查看分段与角色匹配、管理替换规则，并能迅速开始或停止试听。操作以手机触控为主，文本阅读与播放状态必须始终清楚。

界面方向暂定为**安静、清晰、略带编辑感的阅读工具**。当前主题已有浅色背景、墨绿主色和少量暖橙提示色，可作为框架设计的起点。页面应让文本、音色、当前分段和错误状态成为主要视觉信息；装饰性图形、重复大卡片、过度动效都不应抢占注意力。

设计约束：

- 以现有五个主页面为基础：工作台、语音包、角色、替换、设置；日志按深度功能计划作为第六个入口。
- 手机上保留抽屉导航；平板/宽屏再考虑导航栏加内容区。核心播放、新建、试听操作不能藏进多层菜单。
- 规则和语音包列表按可扫描的信息行组织。卡片只用于确实需要成组展示的单个项目，避免卡片套卡片。
- 图标采用一致的单色线性风格。无图标时保留文字标签；功能图标按钮触控区域至少 48dp，并提供 TalkBack 描述。
- 颜色不能单独承担启用、不可用、告警等状态表达；同时给出文字、图标或形状提示。
- 动效表达状态和层级变化。遵循系统减少动态效果设置；减少动态效果时直接切换到最终布局，不影响操作。
- 动效期间不隐藏或延迟核心状态、播放控制和错误信息；列表仍使用 Compose 原生滚动、焦点和无障碍语义。

## Effect Atlas 来源与适配

Effect Atlas 官方目录说明它收录 50 个以原生 JavaScript 编写的网页动效。这里选择的是可迁移的**交互模式**，不是可直接放进 Android 的控件。所有候选都需用 Compose 状态、手势和动画重写；不能依赖鼠标 hover、DOM、CSS、GSAP 或浏览器生命周期。

本轮无法通过研究工具直接打开 `effectatlas.com` 的在线演示页；候选筛选和交互描述已用官方索引、README、许可及对应源码注释交叉核对，未做网页截图或视觉细节验收。

参考入口：

- [Effect Atlas 网站](https://effectatlas.com/)
- [Effect Atlas GitHub 仓库](https://github.com/effect-atlas/effect-atlas)
- [官方效果索引 catalog.json](https://github.com/effect-atlas/effect-atlas/blob/main/catalog.json)
- [官方中文 README 与许可摘要](https://github.com/effect-atlas/effect-atlas/blob/main/README.zh-CN.md)
- [官方 AI 接入说明](https://github.com/effect-atlas/effect-atlas/blob/main/docs/ai-integration.zh-CN.md)
- [Effect Atlas License 1.0](https://github.com/effect-atlas/effect-atlas/blob/main/LICENSE)

许可摘要：Effect Atlas License 1.0 允许把效果作为更大项目的一部分使用、复制和修改，但它明确说明自己不是 MIT，也不是开源许可证；禁止把效果单独出售、移除版权声明，或整体/大部分镜像网站或仓库。若后续直接复制或改写某个效果源码，必须在对应源码中保留版权声明和许可引用。`expanding-dock` 带有 GSAP 运行时代码及其独立许可，不纳入代码移植；此处只记录交互灵感。无需在 NovelTTS 页面内展示来源署名。

## 候选效果

| 优先级 | Effect Atlas 效果 | NovelTTS 目标位置 | 迁移后的交互 | 采用边界 |
| --- | --- | --- | --- | --- |
| P1 | [Hairline Burger](https://effectatlas.com/effects/hairline-burger) `hairline-burger` | 全局顶栏菜单按钮 | 两条短线随抽屉开合交叉成关闭符号；按钮状态与抽屉状态共用一个来源 | 重画为 Compose 图标动画；不用 hover 旋转或网页文案滚动。保留 48dp 点击区和菜单/关闭语义。 |
| P1 | [Shared Element Transition](https://effectatlas.com/effects/shared-element-transition) `shared-element-transition` | 语音包条目到详情；必要时规则行到编辑面板 | 条目中的名称、状态等关键元素连续进入详情，关闭后回到原位置 | 优先保证内容可读、返回路径明确；共享转场不可用或减少动态效果时直接打开/关闭详情。不要为了共享转场伪造图片内容。 |
| P1 | [Reading Word Highlight](https://effectatlas.com/effects/reading-word-highlight) `reading-word-highlight` | 工作台分段预览与当前朗读状态 | 当前朗读段落以轻量底色/侧边标记突出，完成后保留可读文本 | 改为**由合成回调驱动的分段高亮**，绝不按滚动位置假装朗读进度。当前阶段只规划段级进度；没有词级时间戳前不做逐词高亮。 |
| P1 | [Rotating Tab Accordion](https://effectatlas.com/effects/rotating-tab-accordion) `rotating-tab-accordion` | 设置一级分类和未来二级页；规则分组 | 点选一组展开内容，其余组收起；窄屏改成竖向列表 | 借用单组展开和内容层级；不用竖排旋转标签。设置项多时进入独立二级页，不把所有设置塞进长手风琴。 |
| P2 | [Dock Menu Dropdown](https://effectatlas.com/effects/dock-menu-dropdown) `dock-menu-dropdown` | 规则筛选、语音包目录筛选或紧凑分类入口 | 紧凑筛选入口展开为带当前选择标记的可滚动选项 | 使用 Material 原生菜单/Sheet 与 `LazyColumn`；支持触控、键盘、Escape/返回键和当前选择语义。主导航不采用悬浮胶囊目录。 |
| P2 | [Elastic Menu](https://effectatlas.com/effects/expanding-dock) `expanding-dock` | 工作台文本辅助操作；规则页次要操作 | 一组次要命令由紧凑入口展开，点选后收起 | 只放示例文本、清空、导入/导出等次要操作；朗读、停止、新建等主要动作必须清晰可见。只实现交互模型，不移植 GSAP 或随源码附带的库。 |
| P2 | [Scroll Minimap](https://effectatlas.com/effects/scroll-minimap) `scroll-minimap` | 很长的规则列表或日志；平板横屏 | 概览当前长列表位置，并允许跳到分组 | 手机上不加常驻侧边缩略图；先用 sticky 分组标题、搜索与快速定位入口。只有实测长列表确有定位负担时才考虑大屏小地图。 |
| P3 | [Focus Cascade](https://effectatlas.com/effects/focus-cascade) `focus-cascade` | 工作台最近音色快捷选择（候选） | 少量候选以中央焦点和前后上下文呈现，浏览焦点与最终选择分开 | 不代替完整音色选择器；完整目录可能超过 120 个音色。优先级低，只有最近音色选择确有高频需求时再评估。 |
| P3 | [Horizontal Wheel Strip](https://effectatlas.com/effects/horizontal-wheel-strip) `horizontal-wheel-strip` | 最近使用音色横向快捷带（候选） | 可拖动的横向条目带，轻微惯性后停稳 | 若采用，使用原生 `LazyRow`/Pager 和有限惯性，不接管垂直滚轮、不缩放卡片、不做循环甩动。不可放进规则排序或完整音色目录。 |

**建议首批实现：** 顶栏菜单状态图标、分段当前状态、详情连续展开。设置手风琴随二级设置页面一并定稿。其余候选等各阶段页面和实际操作频次明确后再决定。

## 页面结构与交互计划

### 全局外壳与导航

- 顶栏左侧使用菜单图标按钮，打开抽屉；图标在抽屉打开时交叉为关闭状态。标题显示 NovelTTS 和当前页面名称。
- 抽屉按工作台、语音包、角色、替换、设置排列；日志功能完成后加入日志入口。每项使用一个易辨认的图标，当前页通过选中容器和文字状态共同表达。
- 抽屉底部放系统语音设置入口及当前引擎状态/版本信息。内部技术标识 `multitts:...` 不占主要导航空间，放在系统集成说明中。
- 工作台显示固定播放控制。其他页面只显示紧凑状态行；用户切页后仍能知道当前合成/导入状态，避免误触造成冲突。
- 页面切换采用短距离淡入或直接切换；不做整页遮罩、撕纸、噪声溶解等转场。

### 工作台

工作台是首屏和主要任务页面，优先使用窄而清晰的纵向阅读流：

1. 文本输入区及文本辅助操作。
2. 语速、音量、音调控制；每项的数值和慢/快等方向标签保持可见。
3. 旁白与对话音色选择。
4. 分段预览，显示旁白/对话、角色、音色和分段文本。
5. 固定底部播放控制与合成状态。

后续阶段 3 增加：每段预览行上的单段试听按钮；播放期间当前段高亮；底部显示“第 N / M 段”和整体分段进度。`onSegmentStart` 只能证明当前正在处理哪一段，若没有音频时长/播放位置回调，进度控件应表现为分段计数或不确定进度，不能绘制虚假的精确百分比。

“填入示例”“清空文本”等放入轻量文字/图标操作区。开始朗读和停止朗读保持显眼且位置稳定；播放状态切换不应让底栏高度抖动。文本编辑器不应用逐词滚动动画或自动移动光标。

### 语音包与音色

- 分开呈现本地已安装包与在线目录；导入本地 zip 是主操作，在线地址和刷新是次级入口。
- 条目优先显示包名、版本、音色数量、引擎支持和可用状态；长描述默认收起。
- 下载显示逐项进度，成功后显示已安装状态。删除保留二次确认，并明确引用音色将不可用。
- 点击语音包后可进入详情页/Sheet，沿用条目名称和包状态的视觉连续性；详情中再看音色列表和引擎信息。
- 完整音色选择器仍以搜索、在线/离线筛选、可用性、包名分组和当前选中项为主。不要用叠卡或惯性带替代长列表。
- 阶段 6 可在音色行加入短句试听按钮。试听状态属于单项，避免多个音色同时播放；不可用音色始终显示原因，且不能被误认为可选。

### 角色与替换规则

- 页面顶部使用搜索、状态筛选与新建操作；规则按分组显示。分组标题可折叠，并提供独立启停状态和规则数量。
- 规则行以名称、匹配内容/替换映射、音色或启用状态为首要信息；编辑和删除作为行内/溢出操作。
- 长按拖动时显示拖拽手柄和目标位置，拖过其他分组时明确高亮目标分组。拖动只改变排序，不展示 `sortIndex` 数值。
- 规则编辑使用可全屏展开的 ModalBottomSheet；角色表单包括匹配模式、一个或多个音色、轮转模式和“仅对话”。高级优先级默认折叠。正则错误需在输入附近说明。
- 替换编辑器按普通替换/正则切换字段说明；测试输入和输出与保存动作区分，标明测试使用已保存规则还是当前草稿，避免误解。
- 导入先显示新增/覆盖/跳过冲突预览，再确认写入；导出按系统文件选择器流程。
- 规则加载失败时保留错误横幅并禁用会覆盖文件的编辑操作；恢复与备份入口比普通空状态优先。

### 设置与日志

- 设置首页采用清晰的分区入口，进入二级页后集中展示该类设置：朗读行为、合成与网络、背景音乐、外部调用、数据管理、系统集成。
- Legado 协议尚未确认前，外部调用相关控件需保持不可操作并附状态说明，不能伪装成可用服务。
- 背景音乐页明确标注只作用于应用内试听，系统 TTS 调用路径不混音。
- 许可证使用安全输入、掩码状态和明确的保存/清除操作。许可证和令牌不默认放入备份导出。
- 日志页采用级别/类别筛选、搜索和按时间顺序的 `LazyColumn`。底部事件摘要点开后显示最近事件；导出诊断前说明隐私范围，不记录朗读原文。
- 日志级别同时用图标、文字和颜色表达。错误详情可以展开，但默认摘要保持紧凑，避免把异常堆栈塞进列表行。

## 图标位置清单

图标名称是设计语义，最终应从框架选定的同一套 Android 矢量图标资源中映射；这份清单不要求新增图标依赖或现在制作资源。图标按钮旁有稳定文字时，图标可作为辅助；独立图标按钮必须有清晰的无障碍描述。

| 页面/位置 | 图标语义（建议名称） | 优先级与说明 |
| --- | --- | --- |
| 顶栏菜单按钮 | menu / close（由 Hairline Burger 状态转换） | P1；这是首个需要绘制/动画化的图标位置。 |
| 抽屉导航项 | text/record_voice_over（工作台）、folder_open（语音包）、person/group（角色）、find_replace（替换）、settings（设置）、receipt_long/terminal（日志） | P1；整套使用同一笔画风格；日志项在阶段 4 后出现。 |
| 抽屉系统设置入口 | record_voice_over 或 settings_voice；必要时 open_in_new | P2；若调用系统 Activity，外部跳转语义需明确。 |
| 工作台文本操作 | article（填示例）、content_paste（粘贴来源明确时）、backspace/delete（清空） | P2；清空为破坏性动作时仍需符合现有清空策略，不能只靠图标暗示。 |
| 语速/音量/音调 | speed、volume_up、tune | P2；图标只作辨识辅助，滑杆和文字标签仍保留。 |
| 旁白/对话/系统默认音色字段 | record_voice_over / forum / graphic_eq + expand_more | P1；字段整体可点击；选择器打开语义可读。 |
| 单段试听与底部播放 | play_arrow；播放中 pause 或 stop；完成 check | P1；当前阶段 3 使用。暂停仅在真实支持暂停时显示，不以暂停图标冒充停止。 |
| 分段当前状态 | graphic_eq/speaker、check_circle、error_outline | P1；图标与“正在朗读/已完成/失败”文字配合。 |
| 分段/规则/目录展开 | expand_more / expand_less | P1；指示当前折叠状态，容器仍使用可访问按钮语义。 |
| 页面搜索与清除 | search / close | P1；空查询时不显示清除图标，有内容时提供 48dp 清除目标。 |
| 筛选和溢出菜单 | filter_list / more_vert | P2；筛选状态可由文字或数量补充。 |
| 规则组 | folder / expand_more；组启用使用现有 Switch；新建组使用 create_new_folder/add | P1（阶段 2）；启停不另造图标开关。 |
| 规则行操作 | drag_handle、edit、delete、more_vert | P1（阶段 2）；拖动把手只在排序可用时出现；删除使用危险语义和确认。 |
| 规则编辑器 | add（添加音色/分组）、close（移除音色）、science/play_arrow（测试）、check（保存） | P1（阶段 2）；匹配模式使用有文字的 SegmentedButton，不用难猜的图标代替模式名称。 |
| 本地语音包导入 | file_upload / folder_open | P1；按钮文字保留“导入本地 zip”。 |
| 在线语音包目录 | link / refresh / download / check_circle / progress_activity | P1；加载时禁用冲突操作；状态需配文字。 |
| 删除语音包 | delete | P1；配确认对话框和影响说明。 |
| 语音包详情 | package / record_voice_over / cloud / offline_pin / warning | P2；云/离线图标表达来源，不代替可用性说明。 |
| 设置分类入口 | record_voice_over（朗读行为）、tune/wifi（合成网络）、music_note（背景音乐）、lan（外部调用）、backup（数据管理）、settings_voice/security（系统集成） | P2（阶段 7）；每个入口同时显示分类文字。 |
| 设置动作 | visibility/visibility_off（许可证显示状态，如提供）、content_copy（复制地址，如提供）、open_in_new（系统设置）、delete_sweep（清除最近文本/缓存，按实际动作选择） | P2；不为未实现功能预留可点图标。 |
| 日志事件条和日志行 | expand_less/expand_more、info、warning、error、download/export | P2（阶段 4）；级别图标与级别文字同时出现。 |
| 空状态 | 可选的单个语义图标，例如 record_voice_over、rule_folder 或 search_off | P3；只在有助于理解页面状态时使用，不给每个标题机械加大图标。 |

**图标绘制优先顺序：** 先确定一套 Material 风格矢量资源和尺寸/笔画规则；只为项目独特或需表达状态变换的元素自行绘制，例如顶栏双线菜单到关闭符号。其余优先使用同一图标集现成图标，不混搭 emoji、Unicode 字符和临时手绘 SVG。

**已绘制资产：** `ui/common/MenuCloseIcon.kt` 提供顶栏菜单/关闭双线图标。图形占 24dp，继承 Material 主题前景色，抽屉状态通过 Compose 动画驱动，且语义描述随状态切换。使用时放入至少 48dp 的 `IconButton`；其他位置先沿用后续框架选定的统一图标集，不在本阶段重复手绘通用图标。

## 暂不采用的效果

- 鼠标光标互动：`orbit-cursor`、`magnetic-symbol-field`、`torn-cursor-ink`。
- 只靠 hover 才能发现的状态：`film-jitter-hover`、`fractured-word-hover`、`scribble-ring`、`annotated-menu`。
- 营销首屏或长篇展示转场：`fluid-mask-hero`、`card-fan-intro`、`poster-masthead-collapse`、`torn-sheet-transition`、`noise-dissolve-reveal`。
- 3D/WebGL 展示：`curved-gallery`、`disc-carousel`、`hold-to-burst`。
- 大量滚动卡片变形：`scroll-card-deal`、`scroll-card-stack`、`scroll-warp-zoom`。

这些效果与小说朗读工具的高频任务关系弱，可能增加耗电、无障碍和触控风险，或遮挡文本与状态。后续若有明确需求，再逐项审查，不整体移植效果目录。

## 后续落地顺序

1. UI 框架定稿：确认手机/平板导航、页面宽度、间距、圆角、文字层级、图标集和动态效果策略。
2. 阶段 3：先完善工作台单段试听与分段播放反馈；校准当前进度数据能证明的粒度。
3. 阶段 6：完善音色选择与单项试听，保留搜索和长列表作为主要选择方式。
4. 阶段 2：完成规则分组、拖拽排序、编辑 Sheet、导入导出和冲突预览。
5. 阶段 4：新增日志入口、事件摘要条、日志页和诊断导出。
6. 阶段 7：设置二级页、备份还原与数据管理。
7. 回到本清单逐项复核 P2/P3 候选，只实现能改善真实导航或选择效率的项目。

本文件以设计规划和来源记录为主。图标规划中已新增顶栏菜单/关闭图标组件，未添加依赖或修改现有页面接入；本轮未运行构建和测试。
