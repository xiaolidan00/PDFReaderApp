# AGENTS.md

Android PDF 阅读器（只查看，不编辑）。列表页与阅读页功能已实现并通过 `assembleDebug` 验证。原始需求见 `README.md`，与本文件冲突时以本文件为准。

## 关键技术栈

- Kotlin 2.2.10 + Jetpack Compose（BOM 2026.02.01，Material3），AGP 9.3.0
- 单模块 `:app`；包名/namespace：`com.xld.pdfreader`；rootProject：`PDFReader`
- AGP 9 内置 Kotlin 支持：**不要添加 kotlin-android 插件**，现有插件仅 `com.android.application` + `org.jetbrains.kotlin.plugin.compose` + `com.google.devtools.ksp`
- **KSP 用老式 `kotlinVersion-kspVersion` 版本 2.2.10-2.0.2**（与 TxtReaderApp 一致），前提是 `gradle.properties` 中有 **`android.disallowKotlinSourceSets=false`（绝对不能删除）**，否则 AGP 9 内置 Kotlin 会阻止 KSP 的 source-set DSL，报 "Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin"；构建时会输出该选项为 experimental 的 WARNING，属预期
- compileSdk / minSdk / targetSdk = 36（保持不变，**不要升 compileSdk 到 37**）；navigation 2.10+ / lifecycle 2.10+ 强制要求 compileSdk 37，故锁定 **navigation 2.9.8 / lifecycle 2.8.7 / Room 2.8.4 / KSP 2.2.10-2.0.2**——版本与 `D:\github\TxtReaderApp` 的 `libs.versions.toml` 保持一致，升级前先参考该项目
- 状态管理：ViewModel + StateFlow（`collectAsStateWithLifecycle`）；导航：Navigation-Compose，路由 `list`、`reader/{docId}`
- 数据库：SQLite via Room 2.8.4（room-runtime + KSP 编译器）
- 依赖注入：手动 `AppContainer`（Application 持有），**不使用 Hilt**
- 依赖清单集中在 `gradle/libs.versions.toml`

## 构建与运行

- JDK 21 位于 `D:\softwares\android-studio\jbr\bin`（Android Studio JBR）
- 只在 build 模式进行构建验证，plan 模式不构建

```bash
# Windows
.\gradlew.bat :app:assembleDebug

# macOS/Linux
./gradlew :app:assembleDebug
```

无测试套件（版本比较逻辑有单元测试 `:app:testDebugUnitTest`），`assembleDebug` 是主要验证方式。

## 执行流程

1. 根据需求给出详细计划，并确认任务细节
2. 确认后进行具体代码执行
3. 验证测试，有报错则进行修复
4. 若更新了功能逻辑或新增文档，同步更新本文件

## 目录结构

```
app/src/main/java/com/xld/pdfreader/
├── MainActivity.kt              # setContent { PDFReaderTheme { AppNavGraph(); UpdateHost() } }
├── PDFApplication.kt            # Application + AppContainer（手动 DI）+ Context.appContainer 扩展
├── data/
│   ├── PdfDocumentEntity.kt     # Room 实体
│   ├── PdfDocumentDao.kt
│   ├── AppDatabase.kt           # 单例 databaseBuilder
│   └── PdfRepository.kt         # SAF 导入/删除/换绑 + Room 封装
├── update/                      # 应用内升级
│   ├── UpdateChecker.kt         # GitHub Releases API 检查 + 版本号归一化/比较
│   ├── ApkInstaller.kt          # FileProvider 安装 intent + 「安装未知应用」授权跳转
│   └── UpdateViewModel.kt       # 升级状态机：检查/弹窗/DownloadManager 进度/安装
└── ui/
    ├── navigation/AppNavGraph.kt
    ├── update/UpdateHost.kt     # 启动检查 + 升级弹窗/下载进度弹窗（全局叠加）
    ├── list/
    │   ├── PdfListScreen.kt     # 搜索/排序菜单/多选/确认弹窗/Snackbar
    │   ├── PdfListViewModel.kt  # 过滤排序在内存完成，query debounce 300ms
    │   └── components/PdfCard.kt
    ├── reader/
    │   ├── PdfReaderScreen.kt   # TopBar/底栏/Drawer/失效文件处理
    │   ├── PdfReaderViewModel.kt# 进度防抖 500ms 写库，ioScope 兜底保存
    │   ├── PdfViewer.kt         # AndroidView 包装 PDFView（视口 = 顶栏与底栏间整个阅读区）
    │   ├── PageClampedPdfView.kt# PDFView 子类：onDraw 裁剪只画当前页 + 滚动偏移钳制在当前页内
    │   └── TocDrawer.kt
    └── theme/                   # 模板主题文件
```

## 数据库结构

Room 表 `pdf_documents`：

| 列           | 类型    | 说明                                               |
| ------------ | ------- | -------------------------------------------------- |
| id（PK）     | String  | UUID                                               |
| uri          | String  | SAF content:// URI（不复制文件，直接引用存储地址） |
| displayName  | String  | 文件名                                             |
| sizeBytes    | Long    | 文件大小                                           |
| addedAt      | Long    | 导入时间                                           |
| lastOpenedAt | Long    | 最近阅读时间，默认 0                               |
| currentPage  | Int     | 1-based 阅读进度                                   |
| pageCount    | Int     | 总页数，导入后回填，0 表示未知                     |
| isBroken     | Boolean | 原文件被移动/删除后置 true                         |

## 功能需求（约定设计）

文件管理策略：SAF 引用不复制；删除文件 = 删除存储地址对应文件 + 删除记录。

**列表页**（README「PDF文件列表」细化）

- 添加按钮走 SAF `OpenMultipleDocuments("application/pdf")`，`takePersistableUriPermission`，按 uri 去重
- 排序：时间/名称/大小 × 升/降序，默认时间降序；时间 = lastOpenedAt（未阅读按 addedAt）
- 搜索词 debounce 300ms，按文件名过滤；名称排序用 `Collator`（中文拼音序）
- 过滤/排序在 ViewModel 内存完成（Room Flow 返回全量列表）
- 卡片：文件名 + 进度 `当前页/总页`；长按进入多选模式
- 批量操作分两种：「删除记录」仅删 DB 行；「删除文件」用 `DocumentsContract.deleteDocument` 删文件后删行，失败的条目保留记录并标记 `isBroken`，汇总提示

**阅读页**（README「PDF阅读界面」细化）

- 只读渲染 PDF 已有内容（含批注），不做编辑标注
- PDFView 用 `AndroidView` 包装；**视口 = 顶栏与底栏之间的整个阅读区**（初始与缩放后都是），只显示当前页：
  - `AndroidView` 直接 `fillMaxSize()` + `clipToBounds()`——可视范围始终是整个阅读区，缩放内容被裁剪在阅读区内；内容 Box 用 `padding(innerPadding)` 对齐顶/底栏（不能再叠加 `safeDrawingPadding`，TopAppBar/底栏已含系统栏 inset）
  - 「只显示当前页」由 `PageClampedPdfView.onDraw` 实现：按 `页顶屏幕Y = pageTops[page]*zoom + currentYOffset`（与库 `drawPart`/`onDraw` 坐标同源）上下各留半个 `spacingPx` 做 `canvas.clipRect` 后再调 `super.onDraw`——相邻页即使被库渲染也不会画出，页外透明露出主题背景；每帧现算，无遮挡条联动
  - `Configurator` 需 `.fitEachPage(true)`（每页独立适配阅读区宽度，混合尺寸 PDF 也完整）+ `.spacing(1)`（1dp 间距，吸收 fitWidth 向下取整的 1px 误差）
  - `Constants.PRELOAD_OFFSET = 0` 只渲染与阅读区相交的页，不向可见区外扩展；`enableSwipe(false)` 禁用手势翻页，双指缩放/双击缩放/缩放后拖动平移仍可用
  - **`PageClampedPdfView` 必须钳制滚动偏移**，且要保证**视口中心恒在当前页内**：库的 `DragPinchManager.onScroll` 每次拖动都调 `loadPageByOffset()`，它按视口中心所在页判定 `currentPage`，中心落到页外会误翻页。子类重写 `moveTo(float,float,boolean)` 分两种情况：页高 ≥ 视口高时 Y 限制在 `[-pageTop, -(pageTop + pageHeight - viewHeight)]`（视口 ⊆ 页）；页高 < 视口高时取「页完整在视口内 且 视口中心在页内」的交集（恒非空）。页顶偏移按 `Σ(前 i 页高度 + spacingPx) * zoom` 计算（与 `PdfFile.pageOffsets` 同源），并缓存、在 `onSizeChanged`（super 之前）失效；`jumpTo` 期间放行钳制
  - **`jumpTo` 后需居中**：库的 `jumpTo` 是页顶对齐视口顶，页矮于视口时子类在跳完后补一次 `moveTo` 到垂直居中（既保持居中观感，也保证视口中心在本页内）；页 ≥ 视口高时维持顶对齐（从页首显示）
  - 视口尺寸变化（横竖屏等）后，用 `addOnLayoutChangeListener` + `jumpTo(currentPage, false)` 重新对齐（同时触发居中校正）
- **`PdfViewer` 只要在 `doc` 就绪后就组合，不能等 `loadState == LOADED`**——LOADED 依赖 PdfViewer 的 `onLoad` 回调，先等后组合会死锁停在 loading；loading 态在其上叠加进度遮罩，relocate 换绑用 `key(uri)` 重建 AndroidView
- `onPageChange` 更新 StateFlow 并防抖 500ms 写库（currentPage + lastOpenedAt）；`onLoad` 回填 pageCount 并读取 `tableOfContents`
- 库本身就是按需渲染（分块 + 缓存），`PRELOAD_OFFSET = 0` 只渲染与阅读区相交的页（相邻页可能被渲染但被 `onDraw` 裁剪、不可见）；翻页 `jumpTo(page, withAnimation = false)` 立即跳转，不做滚动动画（动画会滚过并渲染中间页）
- 目录用 `ModalNavigationDrawer` + `TocDrawer`（**`Surface` 实现**，非 `ModalDrawerSheet`；尺寸与 m3 一致 min 240.dp / max `DrawerDefaults.MaximumDrawerWidth`，内部 `windowInsetsPadding(statusBars/navigationBars)` 避免内容被系统栏遮盖，`LazyColumn` 用 `weight(1f)` 占满剩余高度）；`gesturesEnabled = drawerState.isOpen`——m3 源码中 scrim（空白处）点击收起受 `gesturesEnabled` 控制，故打开时启用（点击空白处/边缘滑动均可收起），关闭时禁用（避免边缘滑动与 PDF 手势冲突），点击目录项 `jumpTo(page)`
- 底栏上一页/下一页按钮是**唯一翻页入口**（`requestJump` → `jumpTo`）+ `当前页/总页`，首末页禁用对应按钮

**应用内升级**（README「应用内更新」细化）

- 打开 App 只检查一次（`UpdateViewModel.checkOnStart()`，每次会话一次）：请求 `https://api.github.com/repos/xiaolidan00/PDFReaderApp/releases/latest`，`tag_name` 归一化（去 `v` 前缀、只取数字段）后与本地 `versionName` 数值比较，**必须更新且 assets 里有 `.apk` 资源**才弹窗；网络失败/无新版/无 APK 一律静默跳过
- 弹窗展示新版本号 + Release 正文（空则显示占位文案）；「稍后」/下载中「取消下载」把该版本写入 SharedPreferences `app_update.ignored_version`，此后不再提示，只有更高版本才再弹
- 确认升级 → **先查本地**：`getExternalFilesDir(DIRECTORY_DOWNLOADS)` 下已有 `PDFReader-<版本>.apk` 且大小等于 Release asset `size`（`AppUpdate.apkSize`，`UpdateChecker` 解析）→ 跳过下载直接安装；文件缺失或大小不符（上次中断的残缺文件，查到即删除）才入队 `DownloadManager` 下载（系统通知栏同步显示进度），ViewModel 每 500ms 轮询 `COLUMN_STATUS`/`COLUMN_BYTES_DOWNLOADED_SO_FAR` 驱动 App 内进度弹窗；「后台下载」只收起弹窗，下载与完成后自动安装不受影响
- 下载完成 → `FileProvider`（authority `${applicationId}.fileprovider`，路径 `res/xml/file_paths.xml`）生成 content URI 拉起系统安装页；无「安装未知应用」权限时先跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`，activity result 回流后重试安装
- 发版流程见 README「版本发布流程」：`versionName` 递增 + tag `vX.Y` + Release 必须带 `.apk` asset，新旧 APK 签名必须一致

## 注意事项

- **PDF 引擎坐标是 `com.github.mhiew:android-pdf-viewer:3.2.0-beta.3`**（barteksc 原库已随 jcenter 死亡，此为 Maven Central 上的维护分支，无需 JitPack）。API 细节：
  - 包名是 `com.github.barteksc.pdfviewer`（**不是**原库的 `...androidpdfviewer`）
  - 构造函数只有 `(Context, AttributeSet)` 两参数版本：`PDFView(context, null)`
  - 缩放/滑动/抗锯齿等配置在 `fromUri(...)` 的 **Configurator 链**上，不在 PDFView 直接调用
  - `Constants.PRELOAD_OFFSET`（`com.github.barteksc.pdfviewer.util.Constants`，可变 `public static int`，单位 dp）控制渲染范围向可见区外扩展量，设 0 = 只渲染与阅读区相交的页（相邻页相交部分仍会渲染，但被 `PageClampedPdfView.onDraw` 裁剪、不可见）；**必须在 `PDFView(context, null)` 构造之前设置**——`PagesLoader` 在 PDFView 构造函数里实例化并读取该值
  - `enableSwipe(false)` 后库内 `onFling` 直接 return 不翻页；`onScroll` 仅在 `isZooming || isSwipeEnabled` 时移动内容，即未缩放时拖动无效、缩放态拖动即平移；`jumpTo` 程序化跳页不受影响
  - 目录（TOC）类型是 `com.shockwave.pdfium.PdfDocument.Bookmark`（pdfium 传递依赖），字段走 `title`/`pageIdx`(Long, 0-based)/`children` getter；`tableOfContents` 仅在 `onLoad` 回调后可用
  - **批注渲染默认关闭**，必须 `pdfView.enableAnnotationRendering(true)` 才渲染 PDF 内已有高亮/荧光笔批注
- `settings.gradle.kts` 使用 `FAIL_ON_PROJECT_REPOS`：新增仓库必须加在 settings 的 `dependencyResolutionManagement`，不能加在 `app/build.gradle.kts`
- 图标使用 `res/drawable/` 下独立 Vector Drawable XML（24dp 网格，颜色经 `Icon` 的 `tint` 控制），**禁止引入 material-icons / material-icons-extended 库**，一律 `painterResource(R.drawable.ic_xxx)`；XML 内不要写 `android:tint="?attr/..."`（Compose painterResource 不解析主题属性）
- `AndroidView` 的 `onRelease` 必须调用 `pdfView.recycle()`，否则原生内存泄漏
- 翻页写库必须防抖（约 500ms），避免每次翻页都写数据库；返回键路径用 `ioScope` 立即落库（不随 VM 销毁取消）
- 删除文件需系统确认；批量删除失败的条目保留记录并标记 `isBroken`
- 字符串资源中文化；`compileSdk` 使用 AGP 9 新 DSL（`compileSdk { version = release(36) }`）
- 升级相关：`INTERNET` + `REQUEST_INSTALL_PACKAGES` 权限、FileProvider 声明都在 `AndroidManifest.xml`，`file_paths.xml` 只有 `external-files-path path="Download/"`（与 DownloadManager 目录严格对应，改下载目录必须同步改它）；DownloadManager 字段名是 `COLUMN_BYTES_DOWNLOADED_SO_FAR`（**不是** `..._SO_FUTURE`，后者非公开 API）；升级检查地址硬编码在 `UpdateChecker.RELEASES_URL`，仓库改名/迁移要同步更新
- **待真机/模拟器验证**：pdfium 原生 `.so`（libjniPdfium/libmodpdfium 等）的 16KB 页尺寸对齐；`assembleDebug` 无法覆盖此风险，首次安装到 Android 15+/16KB 页设备时需重点验证
