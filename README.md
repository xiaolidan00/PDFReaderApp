# Android PDF 阅读器

PDF阅读器，只查看PDF，不进行编辑修改

## 技术栈

语言：Android Koltin

框架：Jetpack Compose

数据库：SQLite (Room ORM)

导航方式:ViewModel + StateFlow

PDF阅读引擎：Android-PDFView

## 功能需求

**PDF文件列表**

- 右上角有添加PDF文件的按钮（PDF文件多选），排序切换按钮（时间、名称、文件大小进行升序或降序排序，默认按时间降序排序）
- 顶部搜索栏，按照关键词过滤出符合的PDF文件
- PDF卡片列表，显示名字和进度（当前页数/总页数），文件项长按进入批量操作模式，可多选勾选PDF文件卡片，可进行批量删除记录或批量删除文件，删除记录及删除该条阅读记录，删除文件即删除改改PDF文件

布局如下

```
PDF列表		添加按钮，排序切换按钮
——
关键词搜索框
——
PDF文件列表
——
```

**PDF阅读界面**

- 点击PDF文件项，进入阅读界面
- 顶部有返回按钮，显示文件名称，目录按钮
- 阅读区，展示PDF该页内容，支持文本、图片、打字机、荧光笔、高亮等渲染，阅读区可以进行双指操作缩放移动
- 底栏，进度（当前页数/总页数），左右切换按钮，切换上一页和切换下一页
- 点击目录按钮，打开左侧Drawer，展示PDF目录，点击目录项跳转到该页

布局如下

```
返回按钮   名称  目录
——
PDF阅读内容区
——
上一页   进度（当前页数/总页数） 下一页
```

## 应用内更新（自动升级）

- 打开 App 时检查一次 GitHub Release（`releases/latest`），存在比当前 `versionName` 更新的版本且 Release 带 `.apk` 资源时，弹窗提示升级
- 升级弹窗展示新版本号与新版本描述（Release 正文）；点「稍后」或下载中点「取消下载」会记住该版本，之后不再提示
- 点「立即升级」后先检查本地下载目录是否已有该版本 APK（按 Release asset 大小校验完整性，残缺文件自动删除）：有则跳过下载直接安装，没有才由系统 `DownloadManager` 后台下载（App 内显示进度弹窗，也可点「后台下载」收起弹窗），下载完成自动拉起系统安装页
- Android 8+ 首次升级会跳转「允许安装未知应用」设置页，授权返回后自动继续安装

## 版本发布流程

发版前确认 GitHub 仓库为 `xiaolidan00/PDFReaderApp`（检查地址硬编码在 `update/UpdateChecker.kt`）。

1. 修改 `app/build.gradle.kts`：递增 `versionCode`，`versionName` 改为新版本号（如 `1.1`），必须比线上版本新，否则不会提示升级
2. 构建 release APK：`.\gradlew.bat :app:assembleRelease`（需在 `build.gradle.kts` 配置 `signingConfig`；升级安装要求新旧 APK 签名一致，建议配置正式签名）
3. 打 tag 并推送：`git tag v1.1 && git push origin v1.1`（tag 必须以 `v` + 数字开头，如 `v1.1`）
4. 在 GitHub 仓库 **Releases** 页面创建该 tag 的 Release：
   - 标题/正文：正文会显示在升级弹窗的「新版本描述」里
   - **必须上传 APK 作为 asset**（文件名以 `.apk` 结尾），否则 App 检查到新版也无法升级，会静默跳过
