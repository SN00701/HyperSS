# HyperSS 开发文档 2.0
logo采用"E:\trae pg\HyperSS\logo.png"图片
> 智能长截图 · 项目化管理 · 本地优先

| 项目 | 内容 |
|---|---|
| 软件名称 | HyperSS |
| 目标平台 | Android（建议 minSdk 26 / targetSdk 35，重点优化 API 30+） |
| 后端语言 | Rust（核心业务逻辑、图像处理、数据层） |
| 前端语言 | Kotlin + Jetpack Compose |
| 初始版本号 | 0.0.01beta |
| GitHub 仓库 | https://github.com/SN00701/HyperSS |
| 仓库当前状态 | 刚完成初始化，尚无任何代码 / 文档提交（空仓库），本文档发布后应作为第一批提交内容之一，并同步完成第 13 章的隐私防护配置 |
| 文档版本 | v2.0（较 v1.0 新增：仓库信息与隐私文件防护策略、完整的截图任务退出/中断逻辑） |

---

## 目录

1. 产品概述
2. 竞品调研与设计取舍
3. 总体技术架构
4. 权限与系统能力设计
5. 核心功能：四种截图模式
6. 图像拼接算法设计（Rust 核心）
7. 项目管理系统设计
8. 数据模型与本地存储
9. UI / UX 设计规范
10. 页面级交互设计与任务生命周期管理
11. 版本号规则与自动化打包
12. 工程目录结构
13. 仓库管理与隐私文件防护 【本版本新增】
14. 代码规范
15. 安全与隐私保障
16. 免责声明（文案模板）
17. 用户反馈机制
18. 风险清单与已知挑战
19. 开发路线图
附录 A：关键技术代码示例
附录 B：术语表
附录 C：v1.0 → v2.0 变更记录

---

## 1. 产品概述

### 1.1 产品定位

HyperSS 是一款 Android 端的智能长截图工具，核心差异化在于**把截图当作"项目"来管理**：每一次截图任务隶属于一个项目，图片按 `项目前缀 + 4位序号`（如 `zc0001` ~ `zc9999`）自动命名归档，而不是像大多数同类软件那样把长图散落在系统相册里。

### 1.2 核心价值主张

对比市面产品（详见第 2 章调研），HyperSS 的定位是：

- **四种截图模式合一**：自动滚动、手动滚动、自定义步长滚动、双点取距滑动截图，覆盖从"全自动"到"像素级精确控制"的全部需求，而多数竞品只提供其中一到两种。
- **项目化管理**：截图不是零散文件，而是按项目归档、编号、可批量整理的资产。
- **本地优先、零采集**：所有截图与拼接均在设备本地完成，不需要网络权限即可完整工作（参考 Zeta 等产品"零网络权限"的隐私承诺）。
- **现代化悬浮圆形按钮 + 玻璃拟态视觉**，区别于大部分同类工具较为陈旧的 Material 列表式 UI。
- **Rust 核心**：拼接算法、图像编解码、项目数据层用 Rust 实现，兼顾性能与内存安全，同一套核心逻辑未来可平移到 iOS / 桌面端。

### 1.3 目标用户

需要高频截取网页、聊天记录、长文章、表单、代码等"流式内容"，并且希望截图按项目/任务归类保存（例如取证记录、需求走查、UI 走查、资料收集）的用户，而非偶尔截一张长图发朋友圈的轻量用户。

---

## 2. 竞品调研与设计取舍

在设计 HyperSS 前，调研了当前 Android / iOS 平台上有代表性的长截图产品，结论如下：

| 产品 | 核心机制 | 亮点 | 局限 / 我们要避免的问题 |
|---|---|---|---|
| MIUI 自带长截图 / `auto-scroll-capture`（开源方案） | 模拟 `dispatchTouchEvent` 驱动 `ScrollView` 滚动 + `Canvas` 逐帧绘制拼接 | 系统级体验流畅 | 仅适用于系统内置 View 体系，无法通用捕获任意三方 App 界面 |
| 咔咔截屏录屏大师 | 需 Root，悬浮窗划定"内容识别区域"，靠遮挡距离传感器触发自动滚动 | 提出了"框选滚动区域"的交互思路 | 依赖 Root，交互门槛高（挡传感器很反直觉） |
| LongShot | `dispatchGesture` 自动滚动（Android 7.0+）+ 手动精细拼接微调 + 内置浏览器抓长网页 | 自动滚动 + 手动兜底的组合思路成熟 | 广告；拼接微调是事后补救，不是"设定即所得" |
| PixPin（桌面参考） | 框选滚动区域后，基于内容识别（图像相似度）算法逐帧拼接，明确提示"避免动态内容/避免包含滚动条" | 对拼接算法的边界条件说明清晰，值得借鉴给用户的操作提示 | 桌面产品，无法直接照搬到移动端手势体系 |
| Zeta（iOS） | 截取多张有重叠区域的图自动识别拼接；"零网络权限、纯本地运行"作为核心卖点 | 隐私承诺明确，值得作为 HyperSS 的对外文案参考 | 功能相对基础，无项目化管理概念 |
| 滚动截长图（多家马甲应用） | 功能大而全（长图拼接、台词拼图、九宫格切图、截屏套壳） | 说明用户确实有"周边编辑"需求 | 普遍插入较重的广告，用户评价中大量吐槽"打不开、广告关不掉" |

**取舍结论（落到 HyperSS 设计中）：**

1. 采用 `AccessibilityService.dispatchGesture()` 做自动滚动（同 LongShot），**不采用挡光线传感器**这种反直觉交互。
2. 拼接算法采用"重叠区域相似度识别"的思路（同 PixPin/Zeta），但因移动端场景滚动只有**纯垂直（或纯水平）平移、无旋转缩放**，选用比全景拼接更轻量的**模板匹配 / 归一化互相关**算法，兼顾 Rust 实现的性能与准确率（详见第 6 章）。
3. 明确"本地优先、不采集图片、不需要网络权限"作为产品定位的一部分（对标 Zeta），写入免责声明与设置页说明，并延伸为第 13 章的仓库隐私防护策略——产品对用户的隐私承诺，也应体现在开发过程本身不把任何真实用户数据带入公开代码仓库。
4. 不做 Root 依赖，全部基于 `AccessibilityService` + `MediaProjection`/`takeScreenshot()` 官方 API，保证在非 Root 设备上可用。
5. 项目化管理 + 规范命名（现有竞品均未提供）作为 HyperSS 的差异化卖点重点打磨。
6. 坚决不做插屏广告、不做强制订阅，作为产品原则写入文档（用户评价证明这是同类产品的最大痛点）。

---

## 3. 总体技术架构

### 3.1 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│  Android App（Kotlin + Jetpack Compose）                        │
│  ─ UI 层：Compose 界面、动效、玻璃拟态渲染                         │
│  ─ 交互层：ViewModel / StateFlow（单向数据流）                     │
│  ─ 系统能力层：                                                   │
│     · CaptureAccessibilityService（无障碍：手势模拟 + 截屏）       │
│     · MediaProjectionService（API<30 截屏兜底，前台服务）          │
│     · FloatingToolbarService（悬浮工具条 WindowManager）           │
│     · SessionLifecycleObserver（任务生命周期/中断监听，2.0 新增）   │
└───────────────────────────┬──────────────────────────────────┘
                            │  UniFFI 生成的 Kotlin 绑定（FFI 调用）
┌───────────────────────────▼──────────────────────────────────┐
│  Rust Core（hyperss-core，cdylib，经 cargo-ndk 编译为 .so）       │
│  ─ capture 模块：截图帧接收、裁剪、去除状态栏/工具条遮挡区域         │
│  ─ stitch 模块：重叠区域检测、模板匹配、拼接、导出                 │
│  ─ project 模块：项目 / 图片 元数据管理、命名序号分配               │
│  ─ session 模块：截图会话状态机、草稿落盘与恢复（2.0 新增）         │
│  ─ storage 模块：SQLite（rusqlite）本地索引 + 文件系统封装         │
│  ─ config 模块：版本号、语言、主题等配置持久化                     │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 技术选型说明

**为什么 Kotlin + Jetpack Compose 作为前端？**
Compose 是当前 Google 官方推荐、生态最活跃的 Android UI 方案，声明式 UI 天然契合本产品大量的动效需求（长按抖动、玻璃材质模糊、悬浮按钮呼吸感等），且与 UniFFI 生成的 Kotlin 绑定可以无缝协作。

**为什么核心逻辑放 Rust，而不是全部用 Kotlin 写？**
- 图像拼接（模板匹配 / 相似度计算）是 CPU 密集型任务，Rust 在无 GC 停顿、内存安全、SIMD 友好方面显著优于 Kotlin/JVM。
- 项目 / 图片元数据、命名规则、版本号规则、会话生命周期状态机等属于"业务规则"，用 Rust 实现可以获得强类型 + `Result` 错误处理带来的健壮性，且未来若拓展 iOS / 桌面版本可直接复用。
- Rust 编译的 `.so` 体积与运行时开销远小于嵌入一个完整的 JVM 侧图像处理框架。

**Rust ↔ Kotlin 绑定方案：UniFFI（Mozilla）**
调研了三种方案：手写 JNI、UniFFI、Diplomat。选择 **UniFFI**，理由：
- 已在 Firefox for Android 等生产项目中大规模验证，成熟度高于自研 JNI 层。
- 通过 `#[uniffi::export]` 宏或 `.udl` 接口定义文件，自动生成 Kotlin 绑定代码，避免手写 JNI 样板代码和内存管理陷阱。
- 配合 `cargo-ndk` 可以直接在 Gradle 构建流程中集成 Rust 交叉编译（`aarch64-linux-android` / `armv7-linux-androideabi` / `x86_64-linux-android`），一条流水线出包。
- 错误类型可映射为 Kotlin 侧的异常类，业务层可以用 `try/catch` 直接处理 Rust 返回的 `Result::Err`。

不选择纯手写 JNI 的原因：跨语言回调、线程附加（`AttachCurrentThread`）、对象生命周期管理这些"深水区"问题，UniFFI 已经趟过并提供了推荐范式，没有必要重复造轮子。

### 3.3 模块划分（构建产物）

```
hyperss/
├── rust-core/                 # Rust workspace
│   ├── hyperss-core/          # 主业务 crate（project / stitch / session / storage / config）
│   └── uniffi-bindgen/        # bindgen 可执行入口
└── android-app/                # Android Gradle 工程
    ├── app/                    # UI + 系统服务
    └── core-bindings/          # UniFFI 生成的 Kotlin 绑定所在模块
```

---

## 4. 权限与系统能力设计

### 4.1 权限清单

| 权限 / 能力 | 用途 | 申请时机 | 备注 |
|---|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE`（无障碍服务） | 驱动自动滚动手势 `dispatchGesture()`；Android 11+ 下作为截屏通道 `takeScreenshot()` | 用户在设置页主动开启时跳转系统无障碍设置 | 必须在 `accessibility_service_config.xml` 中声明 `canPerformGestures="true"`，API 30+ 还需声明截屏能力 |
| `SYSTEM_ALERT_WINDOW`（悬浮窗 / 显示在其他应用上层） | 悬浮工具条、悬浮圆形按钮常驻 | 首次尝试显示悬浮控件时引导跳转 | **重要说明**：产品需求中列出的"悬浮窗"与"显示在其他应用上面"在 Android 系统层面是**同一个权限**（`SYSTEM_ALERT_WINDOW`），部分厂商 ROM（如小米/华为）在系统设置里会用不同措辞展示。设置页可以保留两条文案入口以贴合用户认知，但底层只对应一次权限申请、一个开关状态 |
| `POST_NOTIFICATIONS`（Android 13+） | 前台服务通知（MediaProjection 截屏兜底时系统强制要求前台服务通知；同时用于第 10 章"硬中断"场景下的兜底提示通知） | 首次进入需要该权限的截图模式时申请 | Android 13 以下无需运行时申请 |
| 无独立存储权限（默认方案） | 项目图片默认写入应用专属外部目录 `getExternalFilesDir()` | 不需要申请 | Android 10+ 分区存储下，App 私有目录无需 `WRITE_EXTERNAL_STORAGE` |
| `WRITE_EXTERNAL_STORAGE`（仅 API ≤ 28 兼容） | 兼容极少数老设备需要显式写外部存储的场景 | 仅在检测到 API ≤ 28 且执行"导出到系统相册"时申请 | targetSdk 高于 29 时系统会自动忽略此权限声明，仅保留兼容分支 |
| `MediaStore` 写入（导出到系统相册功能） | 用户主动"导出到相册"时，通过 `ContentResolver.insert()` 写入 `MediaStore.Images`，无需运行时权限 | 用户点击导出按钮时 | 这是设置页"存储"开关真正对应的能力：不是"能否使用 App"，而是"能否导出到系统相册" |

> 设计原则：**所有敏感权限均"按需在首次使用对应功能时申请"，而不是启动 App 就一次性弹窗申请全部权限**。设置页的开关只是权限状态的可视化入口和快捷跳转，不代表 App 会主动申请所有权限。

### 4.2 双通道截屏策略

这是 HyperSS 相对多数同类产品的一个关键工程优化点：

- **主通道（Android 11 / API 30 及以上）**：使用 `AccessibilityService.takeScreenshot()`。
  - 优点：不需要 `MediaProjection` 授权弹窗、**不会在状态栏常驻"正在录屏"通知**，用户体验更接近系统原生长截图。
  - 需要在无障碍服务配置中声明截屏能力，并处理 `SecurityException`（服务未被授予截图能力时的兜底提示）。
- **兜底通道（Android 8 ~ 10 / API 26 ~ 29）**：使用经典 `MediaProjectionManager` + `ImageReader` 方案，需要前台服务 + 持续通知。
- 两个通道在 Kotlin 层统一封装成同一个 `ScreenCaptureProvider` 接口，向上（Rust 侧）暴露统一的"取一帧屏幕位图"调用，业务逻辑无需感知底层差异。

### 4.3 无障碍手势驱动自动滚动

通过 `GestureDescription` + `dispatchGesture()` 模拟滑动路径：

- 起止坐标、滑动时长、滑动方向（向上截图 = 手势路径向上滑动内容即向下拖拽；向下截图相反）均由 Rust 侧根据当前截图模式计算后传给 Kotlin 执行。
- 每次滑动完成后等待一小段时间（默认 150–250ms，可在高级设置中调节）再触发截图，避免捕获到滚动惯性/动画尚未停止的帧，这也是多数竞品文档中反复强调"滚动不要太快"的根本原因——HyperSS 用固定等待代替对"用户手速"的依赖。

### 4.4 权限被拒绝 / 撤销的兜底体验

- 无障碍服务被系统杀死或用户手动关闭时，`onInterrupt()` 回调中应立即停止当前截图任务并弹出"无障碍服务已断开，本次截图已保存至当前进度"的提示，而不是静默失败。**详细的中断分类与恢复策略见第 10 章。**
- 部分厂商 ROM（小米/OPPO/vivo）对后台无障碍服务有额外的省电限制，需要在设置页提供"检测到可能被系统限制后台运行，点击查看解决方法"的引导文案（跳转厂商自启动/省电白名单设置）。

---

## 5. 核心功能：四种截图模式

### 5.1 模式总览

| 模式 | 触发方式 | 滚动控制者 | 适用场景 |
|---|---|---|---|
| ① 自动滚动长截图 | 悬浮工具条点击"开始" | App 通过 `dispatchGesture` 自动滑动 | 网页、聊天记录等标准可滚动界面，追求"一键完成" |
| ② 手动滚动长截图 | 用户自己手指滑动界面 | 用户 | 界面滚动逻辑复杂（如内嵌多层可滚动区域）、自动滚动误判时的兜底 |
| ③ 自定义步长滚动截图 | 用户预设固定滚动像素/dp 值 | App 按固定步长自动滑动 | 需要精确控制重叠比例、防止拼接算法误判的场景 |
| ④ 双点取距滑动截图 | 用户先点选起点、终点标定一次"标准滑动距离" | App 按标定距离循环自动滑动 | 界面内容存在规律性分段（如商品列表、表格行）时，按"一整段"为单位截图 |

四种模式共享同一套**截图 → 拼接 → 落盘**流水线，区别仅在"滚动动作由谁发起、滚动多少距离"。**四种模式也共享同一套第 10 章定义的任务生命周期与退出/中断处理机制。**

### 5.2 模式①：自动滚动长截图

流程：

```
用户点击悬浮"开始"
  → 截取第 1 帧（起始帧）
  → 循环：
      1. dispatchGesture 触发一次"半屏幅度"滑动（默认滑动距离 = 可视区域高度 × 0.85，
         预留 15% 重叠区供拼接算法比对）
      2. 等待动画稳定（默认 200ms，可配置）
      3. 截取新一帧
      4. Rust 侧对新帧与已拼接结果做重叠区域匹配（见第 6 章）
      5. 判定：
         - 匹配成功 → 拼接追加 → 立即落盘为草稿检查点（见 10.4）→ 继续循环
         - 连续 2 次新帧与上一帧几乎完全相同（内容已到底部/顶部）→ 结束循环，自动完成
         - 匹配失败（内容跳变过大，如出现弹窗/键盘）→ 暂停并提示用户，允许切换为手动模式续接
  → 用户点击悬浮"完成"或自动判定完成 → 生成最终长图 → 写入当前项目并按序号命名
```

### 5.3 模式②：手动滚动长截图

- 用户开启此模式后，悬浮工具条只保留一个"截图"按钮（以及"完成"按钮）。
- 每次用户点击"截图"按钮，触发一次 `takeScreenshot()`，Rust 侧对新截图与上一张做重叠匹配后拼接。
- 若拼接失败（例如用户两次点击间隔滚动幅度过大、重叠区不足以匹配），提示"未检测到有效重叠，请适当减小两次截图之间的滚动幅度"，并保留这一帧作为独立图片，允许用户稍后在项目内手动决定是否使用"合并相邻图片"功能。

### 5.4 模式③：自定义步长滚动截图

- 设置页/悬浮工具条提供步长输入（单位 dp，内部换算为 px：`px = dp × density`）与方向选择（向上 / 向下，对应内容滚动方向而非手势方向，UI 文案以"向下滚动查看更多内容"这类用户直觉描述，避免和手势方向混淆）。
- 每次固定滑动该步长后立即截图，不依赖拼接算法做"重叠区域检测判断步长是否合适"，而是**直接按用户设定的步长裁剪拼接**——因此这一模式比①更快（省去逐帧相似度计算），代价是需要用户自己保证步长小于一屏高度以形成有效重叠。
- 若检测到步长设置导致重叠不足（步长 ≥ 可视区高度），在设置该数值时即时给出警告，防止拼接出现内容缺失。
- **该模式没有内置的自动结束条件**（不像模式①靠"内容不再变化"判定），因此第 10 章设计了统一的"最大帧数/最大高度安全上限"作为兜底结束条件，避免用户忘记点击"完成"导致任务无限循环。

### 5.5 模式④：双点取距滑动截图

这是需求中最具特色、也是实现细节最多的模式，完整交互设计如下：

**Step 1 — 标定距离**
1. 用户进入该模式后，悬浮工具条切换为"标定"状态，界面上出现十字准星光标。
2. 用户点击目标界面上的"起始点"（例如某一行内容的顶部特征位置）。
3. 用户再点击"结束点"（例如下一行同一特征位置，即用户认为的"标准一次滑动"应该滑到的位置）。
4. Rust 侧计算两点欧氏距离在竖直方向的投影（因为滚动通常是纯垂直方向）：

   `scroll_distance_px = |y2 − y1|`

   （若产品未来要支持横向滚动场景，则改用 `|x2 − x1|`，由"滚动方向"设置决定取哪个投影分量）
5. 标定完成后悬浮工具条切换回"就绪"状态，显示"已标定滑动距离：xxx px（约 xxx dp）"，用户可确认或重新标定。

**Step 2 — 循环截图**
- 与模式③逻辑相同：每次按标定距离自动滑动 → 截图 → 直接拼接（不做重叠区智能判断），直至用户点击"完成"或触碰到无法继续滚动的边界（可选：通过检测无障碍事件流中滚动控件是否仍可继续滚动来自动判断边界，`AccessibilityNodeInfo.ACTION_SCROLL_FORWARD` 返回不可执行时视为到底）。
- **标定过程中途退出**（用户在选完起点、未选终点前离开）的处理见 10.2 表格中的场景⑪：直接放弃本次标定，不产生任何草稿数据。

**坐标系与精度注意事项**：
- 用户点击的是屏幕物理坐标（px），标定过程与实际截图过程必须使用同一密度（`density`）体系换算，避免因悬浮窗口自身也参与坐标系而产生偏移。
- 建议标定时短暂隐藏悬浮工具条自身，只保留一个透明点击层，防止用户点到工具条按钮上。

---

## 6. 图像拼接算法设计（Rust 核心）

### 6.1 算法选型

调研了图像拼接领域两类主流思路：

1. **通用全景拼接**（SIFT/ORB 特征点 + RANSAC 单应性矩阵 + 图像融合）：适用于存在旋转、缩放、视角变化的多图拼接（如相机全景照片）。
2. **模板匹配 / 帧间偏移检测**（归一化互相关，或直接的像素差值最小化搜索）：适用于**纯平移**场景。

手机长截图场景中，相邻两帧画面之间**只存在竖直方向的平移，没有旋转和缩放**，因此选用第二类更轻量的方案：**在候选偏移区间内做归一化互相关 / 像素差值匹配，取最优偏移量作为拼接位置**，而不是引入较重的 SIFT/ORB 特征点检测流水线。这样做的收益：

- 计算量显著低于全景拼接，适合在手机端 Rust 层实时执行，不需要 GPU 加速。
- 实现和调试复杂度低，出现的"接缝错位"问题也更容易定位（本质是一维搜索问题）。

### 6.2 拼接算法流程（伪代码）

```rust
/// 在 base 图像底部若干像素范围内，寻找 new_frame 顶部条带的最佳匹配偏移
fn find_overlap_offset(
    base: &Bitmap,
    new_frame: &Bitmap,
    search_range_px: (u32, u32), // 最小/最大可能重叠高度，用于限定搜索范围，提升性能
) -> Option<OverlapResult> {
    // 1. 分别在 base 底部条带、new_frame 顶部条带做降采样（如缩小到 1/4 分辨率）
    //    以降低匹配阶段的计算量，最终确定粗略偏移后再在原分辨率小范围内精修。
    let base_strip = downsample(crop_bottom_strip(base, search_range_px.1));
    let new_strip  = downsample(crop_top_strip(new_frame, search_range_px.1));

    // 2. 在候选偏移量集合内，逐一计算重叠区域的归一化互相关系数（或均方误差）
    let mut best: Option<(u32, f32)> = None;
    for offset in search_range_px.0..=search_range_px.1 {
        let score = normalized_cross_correlation(&base_strip, &new_strip, offset);
        if best.map_or(true, |(_, s)| score > s) {
            best = Some((offset, score));
        }
    }

    // 3. 置信度阈值判断：分数过低说明画面跳变过大（如弹窗/加载动画），判定为匹配失败
    let (offset, score) = best?;
    if score < CONFIDENCE_THRESHOLD {
        return None; // 交由上层触发"暂停并提示用户"
    }

    // 4. 在原始分辨率下，于粗略偏移附近 ±N px 做精修，避免降采样带来的量化误差
    let refined_offset = refine_offset(base, new_frame, offset);

    Some(OverlapResult { offset_px: refined_offset, confidence: score })
}
```

### 6.3 边界与遮挡处理

- **状态栏/导航栏裁剪**：每一帧截图先按当前设备的状态栏高度、导航栏高度（通过 `WindowInsets` 获取并传给 Rust 层）裁剪掉系统 UI 区域，避免这些固定不变的区域干扰重叠匹配（时钟、电量图标每帧都完全一致，容易造成误匹配）。
- **悬浮工具条自身的遮挡**：截图前必须先将悬浮工具条临时隐藏（`View.GONE` 或降低窗口层级）、截图完成后立即恢复显示，防止拼接结果中出现自己的悬浮按钮。
- **拼接失败的兜底体验**：无论哪种模式，只要连续两次拼接失败，都应暂停自动流程，把已完成部分保存为"未完成的长图草稿"，并提示用户可以切换到手动模式续接，而不是直接丢弃已截取的内容。这一兜底路径与第 10 章"草稿会话"机制共用同一套持久化逻辑。

### 6.4 输出与压缩

- 最终长图统一编码为 PNG（保证文字清晰度，避免 JPEG 压缩在文字边缘产生的伪影，这也是用户在竞品评价中反复提到的"清晰度"诉求）。
- 对于超长图片（高度超过设备/系统图片查看器的常见解码上限，经验值约 16000px 高度），弹出"图片较长，建议使用支持超大图片的查看器打开"的提示，而不是静默生成一个可能无法被相册正常预览的文件。这一高度阈值同时也是第 10 章"安全上限自动结束"机制的触发条件之一。

---

## 7. 项目管理系统设计

### 7.1 项目命名规则

- 用户创建项目时指定一个**项目前缀**（例如 `zc`）。
- 项目下的每一张（组）截图产物按创建顺序自动编号：`zc0001`、`zc0002`……直到 `zc9999`。
- 序号严格自增，**不因删除图片而回收复用**已使用过的编号（避免用户产生"这张图去哪了"的困惑，也避免误覆盖历史文件的风险）。
- 达到 `9999` 后该项目视为"已满"，创建新截图时提示用户"当前项目编号已用尽，请新建项目继续"。
- 项目前缀允许中英文/数字组合，但需要做文件系统非法字符过滤（如 `/ \ : * ? " < > |`），避免生成非法文件名。

### 7.2 项目 / 录制器 的关系

- **新建项目**：只创建一个空的项目容器（前缀、创建时间），不涉及任何截图操作。
- **新建录制器**：发起一次实际的截图任务流程——选择/新建目标项目 → 选择四种模式之一 → 授权检查 → 呼出悬浮工具条 → 用户切到目标 App 开始截图。产出的图片自动归档进所选项目并按规则命名。一次"录制器"任务在数据层对应一条第 10 章定义的 `capture_sessions` 会话记录。
- 一个项目可以承载多次"录制器"任务产出的图片；反之，每次开启录制器时都需要明确当前写入哪个项目（默认沿用上一次选择，允许切换）。

### 7.3 图片管理操作

- **删除**：单张删除需二次确认；文件与数据库记录同步删除。
- **批量删除**：进入多选模式（长按图片触发，或工具栏"批量管理"入口），底部出现"删除已选中的 N 张"操作栏。
- **重命名**：仅允许重命名"显示别名"（如给某张图打备注标签），底层的 `前缀+序号` 文件名保持不变，避免打乱命名规则的可追溯性；显示层展示"别名（原始编号）"。

---

## 8. 数据模型与本地存储

### 8.1 数据库表结构（SQLite，通过 Rust `rusqlite` 管理，Kotlin 层不直接访问数据库）

```sql
CREATE TABLE projects (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    prefix        TEXT NOT NULL UNIQUE,        -- 例如 "zc"
    display_name  TEXT NOT NULL,               -- 项目展示名（可与 prefix 不同）
    next_seq      INTEGER NOT NULL DEFAULT 1,  -- 下一个可用序号，1~9999
    created_at    INTEGER NOT NULL,            -- Unix 时间戳
    updated_at    INTEGER NOT NULL
);

CREATE TABLE images (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id    INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    seq           INTEGER NOT NULL,            -- 项目内序号，用于生成文件名 prefix+seq
    file_name     TEXT NOT NULL,               -- 例如 "zc0001.png"
    file_path     TEXT NOT NULL,               -- 相对 App 私有目录的路径
    alias         TEXT,                        -- 用户自定义显示别名，可为空
    width_px      INTEGER NOT NULL,
    height_px     INTEGER NOT NULL,
    capture_mode  INTEGER NOT NULL,            -- 对应四种截图模式的枚举值
    session_id    INTEGER REFERENCES capture_sessions(id), -- 产出该图片的会话，2.0 新增
    created_at    INTEGER NOT NULL,
    UNIQUE(project_id, seq)
);

-- 2.0 新增：截图会话表，用于支撑第 10 章的退出/中断/草稿恢复逻辑
CREATE TABLE capture_sessions (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id        INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    mode              INTEGER NOT NULL,        -- 对应四种截图模式枚举
    status            TEXT NOT NULL,           -- 见 10.1 状态定义：
                                                -- 'in_progress' | 'soft_paused' |
                                                -- 'hard_interrupted' | 'completed' | 'discarded'
    interrupt_reason  TEXT,                    -- 记录中断原因（见 10.2 表格场景编号），
                                                -- 用于草稿恢复时向用户给出准确提示
    frame_count       INTEGER NOT NULL DEFAULT 0,
    draft_path        TEXT,                    -- 当前已拼接进度的临时文件路径，每帧追加后落盘
    started_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL         -- 每次检查点写入时更新，用于崩溃恢复时判断"最后进度"
);

CREATE INDEX idx_images_project ON images(project_id);
CREATE INDEX idx_sessions_status ON capture_sessions(status);
```

### 8.2 文件系统布局

```
<App 私有外部目录>/HyperSS/
  ├── projects/
  │   └── zc/
  │       ├── zc0001.png
  │       ├── zc0002.png
  │       └── ...
  └── .drafts/                     # 2.0 新增：进行中/中断会话的检查点草稿文件
      └── session_{id}.partial.png # 见 10.4，每帧追加成功后覆盖写入
```

- 默认写入 `Context.getExternalFilesDir(null)`（Android 10+ 分区存储下无需任何存储权限，卸载 App 时自动清理）。
- `.drafts/` 目录中的检查点文件在会话正常完成（`status = completed`）后会被移动/重命名为正式项目文件并从草稿目录清除；会话被丢弃（`status = discarded`）时同步删除对应草稿文件，避免残留占用空间。
- "导出到系统相册"为独立的显式用户操作，通过 `MediaStore.Images` 插入，与内部项目文件是"复制"关系而非"移动"，保证项目内数据始终完整、可追溯。

---

## 9. UI / UX 设计规范

### 9.1 设计语言：玻璃拟态 + 悬浮圆形按钮体系，参考"E:\trae pg\miuix"

**设计 Token（建议初始值，交由视觉阶段微调）：**

| Token | 数值 | 说明 |
|---|---|---|
| 玻璃材质模糊半径 | 20dp | Compose `Modifier.blur()` 或自绘 `RenderEffect` |
| 玻璃材质背景不透明度 | 浅色模式 55% 白 / 深色模式 45% 黑 | 保证背景内容若隐若现但不影响按钮可读性 |
| 悬浮按钮直径（主操作） | 64dp | 如"开始截图"这类核心操作 |
| 悬浮按钮直径（次操作） | 48dp | 如"设置入口"、"完成" |
| 圆角/描边 | 全圆形 + 1dp 半透明白色描边 | 强化玻璃质感的边缘高光 |
| 阴影 | 8dp elevation，柔和扩散 | 与背景内容形成层次区分 |

**实现要点（Jetpack Compose）：**
- 背景模糊：Android 12+（API 31+）可用原生 `RenderEffect.createBlurEffect`；更低版本需要用第三方模糊库或退化为半透明纯色渐变模拟玻璃感，需在实现前做兼容性方案评审。
- 按钮点击态：使用 `animateFloatAsState` 做轻微缩放反馈（如 0.95 倍），配合涟漪效果，避免"玻璃感"显得呆板。

### 9.2 主界面

```
┌─────────────────────────────┐
│         HyperSS              │  ← 顶部标题
│                               │
│   ⊕新建项目    ▶新建录制器     │  ← 并排悬浮圆形按钮
│                               │
│  ┌───────────┐ ┌───────────┐ │
│  │ 项目卡片1  │ │ 项目卡片2  │ │  ← 每行两张卡片
│  │ 图片数:12  │ │ 图片数:5  │ │
│  │ 创建于...  │ │ 创建于...  │ │
│  └───────────┘ └───────────┘ │
│  ┌───────────┐ ┌───────────┐ │
│  │ 项目卡片3  │ │ 项目卡片4  │ │
│  └───────────┘ └───────────┘ │
│                               │
│                          ⚙设置│  ← 右下角悬浮设置入口
└─────────────────────────────┘
```

- 项目卡片信息：项目名称（前缀+展示名）、图片总数量、创建时间；卡片本身也采用玻璃材质背景。
- **长按交互**：长按卡片触发轻微抖动动画（可用 Compose `rememberInfiniteTransition` 实现左右 ±2° 的旋转抖动循环），同时卡片右上角浮现红色圆形"×"删除角标；此时点击卡片主体不再进入项目，需先点击空白处或再次长按退出"抖动可删除"状态，点击红叉才会触发删除确认。
- 设置入口固定右下角悬浮，不随列表滚动而消失（`Box` 叠加层实现，独立于 `LazyVerticalGrid` 的滚动内容）。
- 若某个项目存在未完成的截图会话（见第 10 章"草稿恢复"），对应项目卡片应显示一个小角标提示"有未完成的截图"，点击进入项目后置顶展示恢复入口。

### 9.3 项目详情页

```
┌─────────────────────────────┐
│ ← 项目名称        [批量管理]   │
│                               │
│  ┌───────┐  ┌───────┐        │
│  │ 缩略图1│  │ 缩略图2│        │  ← 每行两张，等比例缩放
│  └───────┘  └───────┘        │
│  ┌───────┐  ┌───────┐        │
│  │ 缩略图3│  │ 缩略图4│        │
│  └───────┘  └───────┘        │
└─────────────────────────────┘
```

- 缩略图按原图宽高比等比缩放显示（避免长截图被裁切成正方形导致无法辨认内容），可采用固定宽度、高度按比例自适应的瀑布流布局，而非强制正方形裁剪。
- 点击缩略图进入全屏原图查看（支持双指缩放、双击放大，长截图场景下建议默认适配宽度而非适配高度，方便用户上下滑动阅读全文）。
- 长按缩略图进入批量选择模式，与主界面"长按抖动+勾选"逻辑保持一致的交互语言，降低学习成本。

### 9.4 设置页面

入口：主界面右下角悬浮设置按钮 → 全屏或半屏弹出设置页（建议用 `ModalBottomSheet` 或独立路由页面，视信息量决定，权限项较多建议用独立页面）。

设置页分组：

1. **权限与能力**
   - 无障碍服务（开关，点击跳转系统设置）
   - 悬浮窗 / 显示在其他应用上层（开关，两个入口共用同一权限状态，见 4.1 节说明）
   - 通知权限（开关，Android 13+ 生效）
   - 存储 / 相册导出权限（开关，用于"导出到系统相册"功能）
2. **外观**
   - 语言：中文 / English / 跟随系统
   - 主题：浅色 / 深色 / 跟随系统
3. **关于**
   - 当前版本号（如 `0.0.03beta`）
   - 免责声明（见第 16 章）
   - 用户建议 / 反馈入口（见第 17 章）
   - 开源许可 / 隐私说明（本地优先、零采集承诺；GitHub 仓库链接 `https://github.com/SN00701/HyperSS`）

---

## 10. 页面级交互设计与任务生命周期管理

> 本章是 v2.0 相对 v1.0 的核心扩充：完整定义一次截图任务从开始到结束可能经历的所有路径，明确"保存什么、丢弃什么、提示什么"，避免用户因为一次意外中断（切后台、来电、服务被杀等）而丢失已经截取的内容。

### 10.1 会话状态定义

一次"录制器"任务（对应 `capture_sessions` 表一条记录）在其生命周期内只处于以下五种状态之一：

| 状态 | 含义 | 是否保留草稿 | 是否可恢复继续 |
|---|---|---|---|
| `in_progress`（进行中） | 正常执行截图/拼接循环 | 是（每帧检查点） | — |
| `soft_paused`（软暂停） | 因临时性、非用户主动终止的原因暂停（切后台/来电/锁屏等），底层能力（无障碍服务、悬浮窗）仍然可用 | 是 | **可**，用户返回后一键继续 |
| `hard_interrupted`（硬中断） | 因关键能力被收回（无障碍服务关闭、悬浮窗权限撤销、进程被杀）导致无法自动继续 | 是 | **不能自动继续**，需用户重新授权后手动选择"恢复该草稿"，本质是"以草稿为基础新开一段" |
| `completed`（已完成） | 用户主动点击"完成"，或触发自动结束条件（内容到底/达到安全上限） | 转为正式项目图片，草稿清除 | 不适用（任务已结束） |
| `discarded`（已丢弃） | 用户主动取消，或标定阶段中途放弃 | 否，相关草稿文件同步删除 | 不适用 |

### 10.2 完整的退出 / 中断场景表

| 场景编号 | 触发条件 | 系统层面表现 | HyperSS 处理策略 | 用户感知结果 |
|---|---|---|---|---|
| ① 正常完成 | 用户点击悬浮工具条"完成"按钮 | 无特殊系统事件 | 停止循环 → 执行最终导出 → 写入项目、分配序号 → 草稿文件清理 → 收起悬浮工具条，会话置为 `completed` | Toast 提示"已保存至项目 XX，编号 zc0007" |
| ② 用户主动取消 | 点击悬浮工具条"取消/丢弃"，二次确认弹窗确认 | 无 | 停止循环，丢弃本次拼接进度与检查点草稿文件，会话置为 `discarded`；若该次会话此前已有独立帧被单独保存过（如模式②的容错分支），询问是否连带删除 | Toast 提示"已取消，未保存" |
| ③ 切换到桌面 / 最近任务 | Activity/无障碍事件检测到前台窗口包名变为桌面 launcher 或最近任务界面 | 目标 App 进入后台 | 自动转入 `soft_paused`：停止继续发起滑动手势，保留已拼接进度，悬浮工具条切换为"已暂停"外观 | 用户切回目标 App 后，悬浮工具条自动询问"继续上次的截图任务吗？" |
| ④ 熄屏 / 锁屏 | 收到系统 `ACTION_SCREEN_OFF` 广播 | 无障碍服务本身仍在运行，但界面不可见、不应继续发起手势 | 与③相同处理为 `soft_paused`，额外禁止在屏幕熄灭状态下发起任何 `dispatchGesture`，防止误操作 | 解锁后（`ACTION_USER_PRESENT`）主动弹出"检测到未完成的截图任务，是否继续" |
| ⑤ 来电 / 系统级弹窗打断 | 无障碍事件 `TYPE_WINDOW_STATE_CHANGED` 检测到前台包名变为电话/系统 UI 且非目标 App | 前台窗口被系统组件覆盖 | 视为③的同类处理，转入 `soft_paused` | 打断结束、目标 App 重新回到前台后自动询问是否继续 |
| ⑥ 无障碍服务被系统杀死 / 用户在设置中关闭 | 服务 `onInterrupt()` 回调或 `onUnbind()` | 服务生命周期结束，`dispatchGesture`/`takeScreenshot` 均不可用 | 立即转入 `hard_interrupted`：把当前检查点草稿标记保留，`interrupt_reason` 写入"无障碍服务已断开"；不做任何自动重试 | 悬浮工具条与主界面均展示明确提示，并提供"重新开启无障碍服务"快捷入口；服务恢复后需用户手动点击"恢复该草稿"才会继续 |
| ⑦ 悬浮窗权限被运行时撤销 | `SYSTEM_ALERT_WINDOW` 被系统或用户中途关闭 | 悬浮工具条窗口无法继续绘制，可能直接从屏幕消失 | 转入 `hard_interrupted`；由于悬浮 UI 已不可交互，改用系统通知栏作为兜底提示通道（前台服务通知升级为"任务已中断，点击查看"） | 用户点击通知进入主界面，弹出草稿恢复对话框 |
| ⑧ App 进程被系统杀死（低内存等） | 进程终止，Kotlin 侧内存状态全部丢失 | Rust/SQLite 侧数据不受进程死亡影响 | 依赖每帧检查点落盘（见 10.4），App 下次冷启动时检测到存在 `status = in_progress` 或 `soft_paused` 但长时间未更新（`updated_at` 超过阈值，如 30 秒）的会话，判定为异常终止，自动改标为 `hard_interrupted` 并提示恢复 | 至多丢失"最后一帧尚未来得及落盘的进度"，其余已确认拼接的内容均可恢复 |
| ⑨ 存储写入失败（空间不足等） | Rust `storage` 模块返回 `Err(CoreError::Storage)` | 无法继续写入检查点文件 | 立即停止当前循环（不再尝试继续截图），保留已成功落盘的最后一个检查点为完整草稿，会话转 `hard_interrupted`，`interrupt_reason` 写入具体错误 | 明确提示"存储空间不足，已为你保留到第 N 张的进度"，而非静默丢失或反复重试卡死 |
| ⑩ 达到安全上限（最大帧数 / 最大拼接高度） | Rust 侧计数器或高度阈值触发（默认最大帧数 300、最大高度约 16000px，可配置） | 属于正常业务判定，非异常 | 视为**正常结束**路径，等同于场景①执行"完成"流程，而不是报错中断 | Toast 提示"已达到长图安全上限（约 XXX px），已自动为你完成并保存" |
| ⑪ 双点标定中途退出 | 用户在选完起点、尚未选终点前按返回键或切走 App | 标定流程未完成 | 直接放弃本次标定，不创建任何 `capture_sessions` 记录（标定阶段本身不算正式会话开始） | 悬浮工具条回退到模式选择前的"就绪"状态 |
| ⑫ 用户按系统返回键 / 手势返回 | 触发目标 App 内部返回导航或退到桌面 | 视具体情况等同于③或维持在目标 App 内的普通页面跳转 | 若确实离开了目标 App（包名变化）按③处理；若仅是 App 内部页面返回（包名不变），不触发暂停，继续当前会话 | 与③一致，或无感知（会话不受影响） |

### 10.3 悬浮工具条状态机（含中断路径）

```
                 ┌────────────────────────────────────────┐
                 │                  [就绪]                  │
                 └───────────────────┬────────────────────┘
                          点击"开始"（或完成标定后）
                                     ▼
                 ┌────────────────────────────────────────┐
        ┌───────▶│                [进行中]                  │◀───────┐
        │        └───┬─────────────┬─────────────┬────────┘        │
        │            │             │             │                  │
    用户点击"继续"   场景③④⑤       场景⑥⑦⑧⑨       拼接失败(6.3)      │
        │        (切后台/锁屏/     (服务/权限/进程/  连续两次           │
        │         系统弹窗打断)     存储异常)        │                  │
        │            ▼             ▼             ▼                  │
        │    ┌───────────────┐ ┌────────────────┐ ┌────────────┐   │
        │    │   [软暂停]      │ │   [硬中断]        │ │ [等待用户处理]│   │
        │    │ soft_paused   │ │ hard_interrupted│ │             │───┘
        │    └───────┬───────┘ └────────┬────────┘ └─────────────┘
        │            │                   │
        └────────────┘          用户重新授权后手动"恢复草稿"
                                          │
                                          ▼
                                  [进行中]（以草稿为基础续接）

     [进行中] --点击"完成" / 场景⑩自动上限--> [已完成，写入项目]
     [进行中] --点击"取消/丢弃"（二次确认）--> [已丢弃，草稿清理]
     [软暂停] --用户主动放弃继续--> [已丢弃，草稿清理]（可选保留已完成部分为独立图片，见下）
```

**关于"软暂停"与"硬中断"后用户选择放弃时的产物处理**：无论是软暂停还是硬中断，只要草稿中已经成功拼接了至少一帧有效内容，放弃继续时都应询问用户"是否将已截取的部分保存为一张图片"，而不是简单地二选一"继续"或"彻底删除"——这是对用户已投入操作成本的尊重，也是本章相对 v1.0 版本的关键改进点。

### 10.4 草稿检查点机制

- **写入时机**：模式①②每完成一次成功拼接（即 6.2 节算法返回置信度达标的匹配结果）后，立即将当前完整拼接结果覆盖写入 `.drafts/session_{id}.partial.png`，并同步更新 `capture_sessions.frame_count`、`updated_at`；模式③④由于不做重叠置信度判断，则在每次滑动+截图成功后即写入检查点。
- **写入方式**：采用"先写临时文件、再原子性 rename 覆盖"的方式（`write tmp → fsync → rename`），避免写入过程中被进程杀死导致草稿文件本身损坏。
- **恢复时机**：App 冷启动时，`session` 模块统一扫描 `capture_sessions` 表中所有非终态（`in_progress` / `soft_paused` / `hard_interrupted`）记录：
  - 若发现 `status = in_progress` 但 `updated_at` 早于"App 上次正常退出时间"，判定为场景⑧（进程被杀），自动改标为 `hard_interrupted`。
  - 弹出统一的"草稿恢复"对话框，逐条列出未完成会话（所属项目、模式、已截帧数、最后更新时间），提供三个操作：**继续该会话**（需对应能力仍然可用，否则该按钮置灰并提示原因）/ **保存为已完成的图片**（直接按当前草稿内容结束）/ **丢弃**。
- **清理策略**：`completed` 与 `discarded` 状态的会话，其草稿文件应在状态转换的同一事务内删除，避免 `.drafts/` 目录无限增长；建议额外增加一个启动时的兜底清理任务，删除超过 7 天仍未被用户处理的孤儿草稿（防止用户长期忽略恢复提示导致存储占用）。

---

## 11. 版本号规则与自动化打包

### 11.1 规则定义

- 版本号格式：`MAJOR.MINOR.PATCHbeta`，其中 `PATCH` 固定两位数字（`01`~`99`）。
- 初始版本：`0.0.01beta`。
- **每次打包（构建正式产物）自动 +1 个 PATCH**，即：`0.0.01beta → 0.0.02beta → 0.0.03beta → …`
- 溢出规则（建议）：`PATCH` 达到 `99` 后下一次构建进位为 `MINOR + 1`，`PATCH` 重置为 `01`（即 `0.0.99beta → 0.1.01beta`），避免出现三位数打破"两位数"的展示约定。`MAJOR`/`MINOR` 的正式升级由开发团队手动决策（例如去掉 `beta` 后缀发布正式版时）。

### 11.2 实现方式

在仓库根目录维护一个纯文本的版本状态文件 `version.properties`（**该文件本身应当被提交进 Git 仓库以追踪版本历史，与第 13 章需要 `.gitignore` 排除的隐私/密钥文件是两类完全不同的东西，注意不要混淆排除范围**）：

```properties
major=0
minor=0
patch=1
suffix=beta
```

Gradle 构建脚本在 `assembleRelease` 任务前置一个自定义任务，读取并递增该文件，同时把结果写入 `versionName`：

```kotlin
tasks.register("bumpVersion") {
    doLast {
        val versionFile = file("${rootProject.projectDir}/version.properties")
        val props = Properties().apply { load(versionFile.inputStream()) }

        var major = props.getProperty("major").toInt()
        var minor = props.getProperty("minor").toInt()
        var patch = props.getProperty("patch").toInt()

        patch += 1
        if (patch > 99) {
            patch = 1
            minor += 1
        }

        props.setProperty("major", major.toString())
        props.setProperty("minor", minor.toString())
        props.setProperty("patch", patch.toString())
        props.store(versionFile.outputStream(), null)

        val versionName = "%d.%d.%02d%s".format(major, minor, patch, props.getProperty("suffix"))
        println("HyperSS version bumped to $versionName")
    }
}

tasks.named("preBuild") { dependsOn("bumpVersion") }
```

> 建议：仅在 CI 环境（正式打包流水线）中启用自动递增，本地 `debug` 构建不递增版本号，避免开发调试过程中版本号被大量无意义消耗。可以通过 Gradle 任务图判断当前构建变体（`assembleRelease` vs `assembleDebug`）来控制是否执行 `bumpVersion`。

---

## 12. 工程目录结构

```
hyperss/
├── rust-core/
│   ├── Cargo.toml                      # workspace 定义
│   ├── hyperss-core/
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs                  # uniffi::setup_scaffolding! 入口
│   │       ├── capture/                # 帧接收、裁剪、系统 UI 区域处理
│   │       ├── stitch/                 # 重叠检测、拼接、导出
│   │       │   ├── mod.rs
│   │       │   └── template_match.rs
│   │       ├── session/                # 2.0 新增：会话状态机、草稿检查点、恢复扫描
│   │       │   ├── mod.rs
│   │       │   └── state_machine.rs
│   │       ├── project/                # 项目/图片元数据、命名规则
│   │       ├── storage/                # rusqlite 封装、文件系统封装
│   │       └── config/                 # 语言/主题/版本 等配置持久化
│   └── uniffi-bindgen/
│       └── src/main.rs
│
├── android-app/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── version.properties
│   ├── local.properties.example        # 2.0 新增：签名/SDK路径等本地配置的模板，
│   │                                    # 真实的 local.properties 不进仓库，见第 13 章
│   ├── core-bindings/                  # UniFFI 生成的 Kotlin 绑定所在模块
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/hyperss/app/
│           │   ├── ui/                 # Compose 界面（home / project / settings / capture）
│           │   │   ├── home/
│           │   │   ├── project/
│           │   │   ├── settings/
│           │   │   └── theme/          # 玻璃拟态设计 Token、颜色、字体
│           │   ├── service/
│           │   │   ├── CaptureAccessibilityService.kt
│           │   │   ├── MediaProjectionCaptureService.kt
│           │   │   ├── FloatingToolbarService.kt
│           │   │   └── SessionLifecycleObserver.kt   # 2.0 新增：监听场景③④⑤等中断信号
│           │   ├── data/               # ViewModel、Repository（封装对 Rust 层的调用）
│           │   └── util/
│           └── res/
│               └── xml/
│                   └── accessibility_service_config.xml
│
├── sample-data/                         # 2.0 新增：仅供开发调试的脱敏示例项目数据，见 13.3
│   └── README.md
│
├── .github/
│   └── workflows/
│       └── release.yml                 # 2.0 新增：CI 打包流水线，见 13.4
│
├── docs/
│   └── HyperSS_开发文档_2.0.md          # 本文档
├── .gitignore                           # 2.0 新增，见 13.3
└── README.md
```

---

## 13. 仓库管理与隐私文件防护

> 本章为 v2.0 新增内容。仓库 https://github.com/SN00701/HyperSS 目前刚完成初始化，尚未有任何代码或文档提交。建议在**第一次提交**（即把本文档、`.gitignore`、`README.md`、`LICENSE` 一并加入仓库的那次提交）之前，先完整执行本章的防护配置，而不是等开发到一定阶段再补——因为一旦敏感文件被提交并推送，即便后续删除，其内容仍会残留在 Git 历史中，需要额外的历史重写操作才能彻底清除，成本远高于一开始就设好防线。

### 13.1 仓库现状与首次提交清单

当前仓库状态：空仓库（仅初始化，无 commit）。建议的"第一次提交"应包含且仅包含以下内容，不涉及任何真实用户数据：

- [ ] `README.md`（项目简介、技术栈、构建方式、指向本开发文档的链接）
- [ ] `LICENSE`（开源协议，由开发者自行选定，如 MIT / Apache-2.0）
- [ ] `.gitignore`（见 13.3，务必在提交任何代码前就位）
- [ ] `docs/HyperSS_开发文档_2.0.md`（本文档）
- [ ] `.github/ISSUE_TEMPLATE/`（Bug 报告 / Feature Request 模板，对应第 17 章用户反馈机制）
- [ ] 工程骨架代码（第 12 章目录结构中的空模块 + 最基础的 Gradle/Cargo 配置，不含任何业务实现也可以先占位）
- [ ] `version.properties`（初始内容 `major=0 minor=0 patch=1 suffix=beta`）

建议同时在仓库设置中开启：`main` 分支保护（禁止直接 push，须走 Pull Request）、`Squash and merge` 作为默认合并策略，为后续多人协作或社区贡献做好准备。

### 13.2 隐私与敏感文件红线

结合本产品"本地优先、零采集"的隐私承诺（见 2.2 节取舍结论、第 15 章安全保障），开发过程中**以下类型的文件严禁出现在任何一次 Git 提交中**：

| 文件类型 | 说明 | 典型路径特征 |
|---|---|---|
| 真实用户/开发者本机的项目截图数据 | 第 8 章定义的 `projects/` 目录内容，一旦提交即等于把某个人截过的真实屏幕内容（可能包含聊天记录、账号信息等）永久留存在公开仓库历史中 | `**/HyperSS/projects/**`、任意开发机本地调试产生的 `*.png` 长图 |
| 本地 SQLite 数据库文件 | 第 8 章数据库中可能记录了真实项目名、时间戳等使用痕迹 | `*.db`、`*.sqlite`、`*.sqlite3` |
| 草稿检查点文件 | 第 10 章 `.drafts/` 目录内容，本质也是真实截图内容 | `**/.drafts/**` |
| 签名密钥库与密码 | Android 发布签名一旦泄露，攻击者可伪造"官方更新" | `*.jks`、`*.keystore`、`keystore.properties` |
| 本地环境配置 | 包含开发者本机的 SDK 路径、可能夹带的临时调试密钥 | `local.properties` |
| Rust / Gradle 构建产物 | 体积大且无提交必要，另外编译产物有时会内嵌构建机器的绝对路径信息 | `rust-core/target/`、`android-app/**/build/`、`*.so`（除非是刻意发布的预编译产物） |
| IDE 与系统临时文件 | 无实际价值且容易带出本机路径信息 | `.idea/`、`*.iml`、`.DS_Store`、`local.env` |

### 13.3 `.gitignore` 模板

建议作为仓库根目录 `.gitignore` 的起始内容（后续可按需追加）：

```gitignore
# ===== 隐私红线：真实用户数据（最高优先级，务必保持在最前）=====
**/HyperSS/projects/**
**/.drafts/**
*.db
*.sqlite
*.sqlite3

# ===== 签名与密钥 =====
*.jks
*.keystore
keystore.properties
*.pem
*.p12

# ===== 本地环境配置 =====
local.properties
*.env
.env.local

# ===== Android / Gradle 构建产物 =====
android-app/**/build/
android-app/.gradle/
*.apk
*.aab
*.ap_
*.dex

# ===== Rust 构建产物 =====
rust-core/target/
Cargo.lock  # 若 hyperss-core 作为库被复用，建议忽略；若作为最终可执行产物追踪则保留，二选一并在 README 说明

# ===== UniFFI 生成的绑定代码（如流水线中动态生成，无需入库）=====
android-app/core-bindings/generated/

# ===== IDE / 系统文件 =====
.idea/
*.iml
.DS_Store
Thumbs.db

# ===== 日志与临时文件 =====
*.log
*.tmp
```

> 关于 `Cargo.lock` 是否入库：本项目 `hyperss-core` 是被 Android App 依赖的核心库，通常建议**不将 `Cargo.lock` 提交**（库场景交由使用方锁定依赖版本）；但如果团队希望 CI 构建结果强一致、可复现，也可以选择提交并追踪，两种做法都合理，关键是在 README 中明确写清楚团队的选择，避免协作者混淆。

### 13.4 示例/调试数据的处理原则

开发协作、UI 联调、自动化测试都难免需要一些"看起来像真实截图"的样例图片。为避免开发者手滑把自己手机上的真实截图当作调试素材提交，约定：

- 所有调试用的示例项目数据统一放在仓库根目录的 `sample-data/` 目录下，与运行时真实写入的 `projects/` 目录（已被 `.gitignore` 排除）在物理路径上就完全隔离，从源头降低误提交概率。
- `sample-data/` 内的图片必须是**合成/占位内容**（例如用纯色色块 + 假文本渲染生成的测试图，或使用开源图库中明确可自由使用的素材），不得是任何人手机上的真实截图，哪怕已经做了打码处理也不行——打码本身也存在遗漏风险，直接杜绝真实内容更彻底。
- `sample-data/README.md` 中需注明"本目录内容均为合成测试数据，不含任何真实用户内容"，让后来协作者一眼了解这一约定。

### 13.5 CI 层面的兜底拦截

除了 `.gitignore` 这道"事前防线"，建议在 `.github/workflows/` 中增加一个轻量的 PR 检查任务，作为"事后兜底"，防止有人强制add（`git add -f`）绕过 `.gitignore`：

```yaml
name: privacy-file-guard
on: [pull_request]
jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: 检查本次变更是否包含疑似隐私/密钥文件
        run: |
          CHANGED_FILES=$(git diff --name-only origin/${{ github.base_ref }} ${{ github.sha }})
          BLOCKED_PATTERN='(HyperSS/projects/|\.drafts/|\.jks$|\.keystore$|keystore\.properties$|local\.properties$|\.db$|\.sqlite3?$)'
          MATCHED=$(echo "$CHANGED_FILES" | grep -E "$BLOCKED_PATTERN" || true)
          if [ -n "$MATCHED" ]; then
            echo "检测到疑似隐私/密钥文件被提交，已阻止合并："
            echo "$MATCHED"
            exit 1
          fi
```

### 13.6 签名密钥与发布流水线的正确姿势

正式发布签名（`.jks`）与其密码**不进仓库**，改为存放在 GitHub Actions Secrets 中（如 `RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`），CI 构建时动态解码写入临时文件、构建完成后清理：

```yaml
      - name: 还原签名文件（仅构建期使用，不落盘进仓库）
        run: echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 -d > release.jks
      - name: 打包（自动携带第 11 章版本号递增逻辑）
        run: ./gradlew :app:assembleRelease
        env:
          KEYSTORE_PASSWORD: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
      - name: 清理签名文件
        if: always()
        run: rm -f release.jks
```

---

## 14. 代码规范

### 14.1 Kotlin / Compose

- 强制 `ktlint` + `detekt` 静态检查接入 CI，格式与常见坏味道（过长函数、魔法数字）在合并前拦截。
- Compose 遵循**状态上提（State Hoisting）+ 单向数据流**：UI 组件本身尽量无状态，状态统一由 `ViewModel` 持有并通过 `StateFlow` 下发。
- 系统服务（`AccessibilityService`、悬浮窗 `Service`）与 UI 层严格解耦，只通过定义清晰的接口（如 `CaptureController`）通信，避免服务直接持有 Activity/Compose 引用造成内存泄漏。
- 所有对 Rust 层（UniFFI 绑定）的调用统一收敛在 `data/repository` 层，UI/ViewModel 不直接 import 生成的绑定类，方便未来绑定层升级或替换。
- 第 10 章的 `SessionLifecycleObserver` 需要监听的信号（前台包名变化、`ACTION_SCREEN_OFF`/`ACTION_USER_PRESENT`）应统一注册与反注册，避免服务生命周期结束后残留监听造成内存泄漏或空指针。

### 14.2 Rust

- 强制 `rustfmt` + `clippy -D warnings` 接入 CI。
- 错误处理统一使用 `thiserror` 定义业务错误枚举，通过 UniFFI 的错误映射机制转换为 Kotlin 异常，禁止 `unwrap()`/`expect()` 出现在跨 FFI 边界的公共 API 路径上（内部测试代码除外）。
- `stitch` 模块中的图像处理函数需附带单元测试与基准测试（`criterion`），保证后续算法调优有性能回归基线。
- `session` 模块的状态机流转（10.1 五种状态）需要用状态机单元测试覆盖所有合法/非法转移路径，防止未来新增中断场景时引入状态不一致的 bug。
- 公共 API（`#[uniffi::export]` 标注的函数）需要有完整的文档注释，作为 Kotlin 侧调用方的唯一权威说明来源。

### 14.3 Git 提交规范

采用 Conventional Commits：`feat:` / `fix:` / `refactor:` / `docs:` / `chore:` / `perf:` 前缀，PR 标题与描述需说明"改动模块"（`core-stitch` / `core-session` / `app-ui` / `app-service` 等），便于变更集与版本号递增记录对齐。所有 PR 需经过 13.5 节的隐私文件扫描检查通过后方可合并。

---

## 15. 安全与隐私保障

1. **本地优先、零采集**：所有截图、拼接、存储流程均在设备本地完成，正式版不集成任何数据上报/统计 SDK；若未来加入网络能力（如云同步），必须作为可关闭的可选项，且默认关闭。
2. **权限最小化与按需申请**：见第 4 章，所有敏感权限只在对应功能被实际触发时申请，设置页的权限开关只做状态展示与快捷跳转，不代表隐式申请。
3. **无障碍服务使用边界的自我约束**：`CaptureAccessibilityService` 的手势与截图能力仅在用户主动点击悬浮工具条按钮后才会触发一次性的动作，不做任何后台静默监听或事件收集，代码实现上应避免在 `onAccessibilityEvent()` 中读取与截图任务无关的界面内容，降低被误判为"读屏木马"类行为的风险，也是对用户信任的基本尊重。仅有的常驻监听（`SessionLifecycleObserver` 监听前台包名变化）目的仅限于第 10 章的中断检测，不做任何内容记录或上报。
4. **导出行为透明**：任何写入系统相册（`MediaStore`）的操作必须是用户主动点击"导出"触发的显式行为，不做自动同步。
5. **依赖供应链安全**：Rust 侧使用 `cargo audit` 定期扫描依赖漏洞，Kotlin 侧使用 Gradle 依赖锁定 + 定期版本审计。
6. **开发过程本身的隐私保障（2.0 新增）**：见第 13 章——产品对最终用户的"本地优先、零采集"承诺，同样延伸到开发协作环节本身，任何真实用户/开发者本机的截图数据、数据库文件、签名密钥都不应进入公开的 GitHub 仓库历史。

---

## 16. 免责声明（文案模板，供法务/产品最终审定）

> HyperSS 是一款本地截图与图像拼接工具。使用本软件截取、保存、分享任何内容前，请确认您拥有相应的权利或已获得必要的授权；对于因使用本软件截取、传播他人受版权、隐私权或其他合法权益保护的内容而产生的一切后果，由使用者自行承担，开发者不承担由此引起的任何法律责任。
>
> 本软件所需的无障碍服务、悬浮窗等系统权限仅用于实现截图、自动滚动等产品功能，不会用于采集、上传或分析您的屏幕内容与个人信息。所有截图数据均保存在您的设备本地，开发者无法访问。
>
> 本软件按"现状"提供，不对截图内容的完整性、拼接结果的绝对准确性作出保证；对于因软件缺陷、设备兼容性问题（含第 10 章所述的任务中断场景）导致的数据丢失，开发者将尽力协助但不承担赔偿责任。

（正式发布前建议交由专业法务审核，尤其是版权免责与数据安全条款的表述。）

---

## 17. 用户反馈机制

- 设置页"用户建议"入口：应用内简单表单（问题类型 + 文字描述 + 可选截图附件），本地生成反馈内容后调起用户默认邮件客户端或跳转 GitHub Issues 页面（`https://github.com/SN00701/HyperSS/issues`），**不在应用内直接联网提交**，与"零网络权限"的产品原则保持一致（如未来需要应用内直接提交，需在该功能单独说明会产生网络请求，并允许用户拒绝）。
- 面向开发者/社区的反馈渠道：GitHub Issues（Bug 报告 / Feature Request 模板化，见 13.1 首次提交清单）。

---

## 18. 风险清单与已知挑战

| 风险点 | 说明 | 应对思路 |
|---|---|---|
| 厂商 ROM 对无障碍服务的后台限制 | 小米/华为/OPPO/vivo 等定制系统可能在省电策略下杀死无障碍服务进程，导致自动滚动截图中途失效 | 对应第 10 章场景⑥，设置页提供"检测到可能受限"的引导提示；已截取内容做增量保存，避免整次任务因中断而全部丢失 |
| `takeScreenshot()` 权限声明遗漏 | Android 11+ 若未在无障碍配置中正确声明截图能力，会抛出 `SecurityException: Services don't have the capability of taking the screenshot` | 开发/测试阶段建立专门的兼容性测试用例覆盖该异常路径，并提供清晰的错误提示与自检引导 |
| 拼接算法对动态内容的误判 | 视频、动图、进度条动画等非静态内容会导致重叠匹配失败或产生鬼影/错位 | 参考 PixPin 的产品化经验，在用户开始截图前给出"请确保内容非动态、滚动过程尽量平缓"的提示；匹配置信度低于阈值时主动暂停而非强行拼接 |
| 超长图片的查看兼容性 | 部分系统相册/图片查看器对超高分辨率图片解码存在上限 | 设定单张长图的最大高度阈值（对应第 10 章场景⑩），超过后自动完成并提示用户分段导出或使用专用查看器 |
| Android 隐私新规趋势 | 近年 Android 版本持续收紧无障碍服务的使用门槛与商店审核要求 | 关注每年 Play 商店政策更新，保证无障碍服务的用途描述、权限申请文案清晰、单一、可审计 |
| 双点取距模式的坐标系精度 | 悬浮层坐标与目标 App 内容坐标系可能因屏幕密度、状态栏偏移出现不一致 | 标定与截图使用统一的坐标转换工具函数，并在 QA 阶段针对不同屏幕密度设备做专项回归测试 |
| UniFFI 跨语言异步/回调复杂度 | 拼接为耗时操作，若同步阻塞主线程会导致 UI 卡顿 | Rust 侧拼接函数在独立线程执行，通过 UniFFI 支持的异步接口或回调机制通知 Kotlin 层进度与结果，避免阻塞 Compose 主线程 |
| 草稿检查点带来的存储/性能开销（2.0 新增） | 高频写检查点文件（尤其模式①②每帧都写）可能带来额外 I/O 开销与瞬时存储占用 | 检查点写入采用增量编码而非每次全量重新编码整张长图（如仅对新增条带做编码后追加），并做写入频率节流（如至少间隔 500ms 或累计若干帧才落盘一次），在"数据安全"与"性能开销"之间取得平衡 |
| Git 历史中意外残留敏感文件（2.0 新增） | 即便当前 `.gitignore` 配置正确，若早期误提交过一次，历史记录中仍会保留 | 严格遵循第 13 章"在第一次提交前就配置好防护"的顺序；若发现已经误提交，需要使用 `git filter-repo` 等工具重写历史并强制推送，且需通知所有协作者重新克隆仓库 |

---

## 19. 开发路线图

| 阶段 | 目标 | 关键交付 |
|---|---|---|
| M0 · 技术预研 | 验证 Rust + UniFFI + Compose 打通链路；验证 `takeScreenshot()` 与 `MediaProjection` 双通道截屏可用性 | 最小可运行 Demo（无 UI，命令行触发一次截图 + 拼接） |
| M0.5 · 仓库基线（2.0 新增） | 完成第 13 章仓库初始化清单与隐私防护配置 | `.gitignore`、CI 隐私扫描、README、LICENSE 就位后再开始推送业务代码 |
| M1 · MVP | 项目管理（创建/命名/列表）+ 模式①自动滚动截图 + 基础 Compose UI（无玻璃材质，先用纯色占位）+ 基础会话状态机（至少覆盖场景①②③） | 可完整跑通"新建项目 → 新建录制器 → 自动截长图 → 存入项目"闭环，且中途切后台不丢失进度 |
| M2 · 模式补全 | 补齐模式②③④；补齐图片管理（删除/重命名/批量删除）；补全全部 10.2 节退出/中断场景 | 四种截图模式全部可用，任务生命周期管理完整覆盖 |
| M3 · 视觉打磨 | 玻璃拟态设计系统落地、长按抖动动效、深浅色主题、多语言 | 视觉与交互达到发布标准 |
| M4 · Beta 发布 | 版本号自动化、免责声明、反馈入口、风险清单中的兼容性测试收尾 | 首个 `0.0.XXbeta` 对外发布包 |
| M5 · 正式版 | 收集 Beta 反馈、修复厂商适配问题、评估去除 `beta` 后缀 | 1.0 正式版 |

---

## 附录 A：关键技术代码示例

### A.1 无障碍服务：模拟滑动手势

```kotlin
@RequiresApi(Build.VERSION_CODES.N)
fun AccessibilityService.dispatchScroll(
    startX: Float, startY: Float,
    endX: Float, endY: Float,
    durationMs: Long,
    callback: (success: Boolean) -> Unit
) {
    val path = Path().apply {
        moveTo(startX, startY)
        lineTo(endX, endY)
    }
    val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()

    dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            callback(true)
        }
        override fun onCancelled(gestureDescription: GestureDescription?) {
            callback(false)
        }
    }, null)
}
```

### A.2 无障碍服务：Android 11+ 静默截图

```kotlin
@RequiresApi(Build.VERSION_CODES.R)
fun AccessibilityService.captureScreen(
    onSuccess: (Bitmap) -> Unit,
    onFailure: (Int) -> Unit
) {
    takeScreenshot(
        Display.DEFAULT_DISPLAY,
        mainExecutor,
        object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(
                    result.hardwareBuffer, result.colorSpace
                )?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                bitmap?.let(onSuccess) ?: onFailure(-1)
            }
            override fun onFailure(errorCode: Int) = onFailure(errorCode)
        }
    )
}
```

### A.3 无障碍服务配置 XML

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="100"
    android:description="@string/accessibility_service_description" />
```

### A.4 监听中断信号：前台窗口变化触发软暂停（2.0 新增）

```kotlin
class SessionLifecycleObserver(
    private val targetPackageName: String,
    private val onInterrupt: (reason: InterruptReason) -> Unit,
    private val onResume: () -> Unit
) {
    // 由 CaptureAccessibilityService.onAccessibilityEvent 转发调用
    fun onWindowStateChanged(event: AccessibilityEvent) {
        val currentPackage = event.packageName?.toString() ?: return
        when {
            currentPackage == targetPackageName -> onResume()
            currentPackage == "com.android.systemui" ->
                onInterrupt(InterruptReason.SYSTEM_OVERLAY) // 对应场景⑤
            isLauncherPackage(currentPackage) ->
                onInterrupt(InterruptReason.SWITCHED_TO_HOME) // 对应场景③
            else ->
                onInterrupt(InterruptReason.SWITCHED_TO_OTHER_APP)
        }
    }

    // 由前台服务中注册的 BroadcastReceiver 转发调用，对应场景④
    fun onScreenOff() = onInterrupt(InterruptReason.SCREEN_OFF)
    fun onUserPresent() = onResume()
}

enum class InterruptReason {
    SWITCHED_TO_HOME, SWITCHED_TO_OTHER_APP, SYSTEM_OVERLAY, SCREEN_OFF,
    ACCESSIBILITY_SERVICE_LOST, OVERLAY_PERMISSION_REVOKED, STORAGE_ERROR
}
```

### A.5 Rust 侧会话状态机与检查点落盘（2.0 新增）

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SessionStatus {
    InProgress,
    SoftPaused,
    HardInterrupted,
    Completed,
    Discarded,
}

#[uniffi::export]
pub fn append_frame_and_checkpoint(
    session_id: i64,
    frame: Vec<u8>,
) -> Result<SessionProgress, CoreError> {
    // 1. 调用 stitch::find_overlap_offset 尝试拼接
    // 2. 拼接成功：更新内存中的当前长图缓冲区
    // 3. 采用 "写临时文件 -> fsync -> 原子 rename" 落盘为 .drafts/session_{id}.partial.png
    // 4. 更新 capture_sessions.frame_count / updated_at
    // 5. 返回当前进度供 Kotlin 侧展示帧数/预览
    todo!()
}

#[uniffi::export]
pub fn mark_session_interrupted(
    session_id: i64,
    reason: String,
) -> Result<(), CoreError> {
    // 对应 10.2 表格场景⑥⑦⑨：将 status 更新为 HardInterrupted 并记录 interrupt_reason
    todo!()
}

#[uniffi::export]
pub fn resume_or_finalize_session(
    session_id: i64,
    action: SessionResumeAction, // Continue | SaveAsIs | Discard
) -> Result<(), CoreError> {
    // 对应 10.4 草稿恢复对话框的三个操作分支
    todo!()
}

uniffi::setup_scaffolding!();
```

### A.6 Gradle：集成 `cargo-ndk` 构建 Rust 库（简化示例）

```kotlin
tasks.register<Exec>("cargoBuildAndroid") {
    workingDir = file("${rootProject.projectDir}/../rust-core")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", "${project.projectDir}/src/main/jniLibs",
        "build", "--release", "-p", "hyperss-core"
    )
}

tasks.named("preBuild") { dependsOn("cargoBuildAndroid") }
```

---

## 附录 B：术语表

| 术语 | 含义 |
|---|---|
| 录制器 | 用户发起的一次实际截图任务流程（区别于"项目"这一静态容器概念），数据层对应一条 `capture_sessions` 记录 |
| 重叠区域 | 相邻两帧截图中内容相同的部分，是拼接算法定位偏移量的依据 |
| 置信度 | 拼接算法对某个候选偏移量匹配程度的量化评分，低于阈值判定为拼接失败 |
| 步长 | 用户自定义的每次滚动的固定像素/dp 距离（模式③④） |
| UniFFI | Mozilla 开源的多语言绑定生成工具，本项目用于生成 Rust → Kotlin 的调用绑定 |
| 软暂停 | 因临时性、非用户主动终止原因导致的会话暂停，底层能力仍可用，可一键继续（2.0 新增） |
| 硬中断 | 因关键系统能力被收回导致会话无法自动继续，需重新授权后手动恢复草稿（2.0 新增） |
| 检查点 | 截图过程中每次成功拼接后落盘保存的进度快照，用于中断后的恢复（2.0 新增） |

---

## 附录 C：v1.0 → v2.0 变更记录

| 类别 | 变更内容 |
|---|---|
| 仓库信息 | 新增 GitHub 仓库地址 `https://github.com/SN00701/HyperSS`，并在文档头部标注仓库当前为空仓库、尚未有任何提交 |
| 新增章节 | 第 13 章「仓库管理与隐私文件防护」：首次提交清单、隐私文件红线表、`.gitignore` 完整模板、示例数据处理原则、CI 隐私扫描 workflow、签名密钥的正确管理方式 |
| 核心功能完善 | 第 10 章「页面级交互设计与任务生命周期管理」由 v1.0 的两小节扩充为完整独立章节：定义 5 种会话状态、12 种退出/中断场景的详细处理表、含中断路径的完整状态机图、草稿检查点机制 |
| 数据模型调整 | 新增 `capture_sessions` 表支撑会话状态与草稿恢复；`images` 表新增 `session_id` 外键；文件系统布局新增 `.drafts/` 目录 |
| 交叉引用更新 | 第 4、5、6、7、9、15、18 章补充了指向第 10 章 / 第 13 章新增内容的交叉引用，保持全文一致性 |
| 风险清单 | 新增两项风险：草稿检查点的存储/性能开销、Git 历史中意外残留敏感文件的处理方式 |
| 路线图 | 新增 M0.5「仓库基线」阶段，明确要求先完成第 13 章配置再开始推送业务代码；M1/M2 目标补充会话状态机覆盖范围要求 |
| 附录 | 新增 A.4（中断信号监听 Kotlin 示例）、A.5（Rust 会话状态机与检查点 UniFFI 接口示例）；术语表新增"软暂停""硬中断""检查点" |

---

*本文档为 HyperSS 项目的 v2.0 开发文档，后续应随开发进度持续更新迭代，并在仓库 `docs/` 目录下用 Git 追踪变更历史（本身即遵循第 13 章的正常提交规范，不涉及任何隐私红线文件）。*
