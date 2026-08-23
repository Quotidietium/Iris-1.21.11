# 稳定性/安全性审计 第 2-4 轮：命令面 / 生命周期与泄漏 / 配置并发

日期：2026-08-24
分支：`perf/optimization`
前置：第 1 轮（网络下载集群，见 `round1-network-download-cluster.md`）
验证：`benchmark/build.sh` 全量编译通过（1248 类）；golden 回归 49/49 位一致（`results/audit-r1.csv`）。

## R2：命令权限与输入校验（commit 5af7eca / 15d8a30 / 05370a0）

权限模型确认：plugin.yml 未声明权限，`DecreeSystem.onCommand` 统一要求 `iris.all`（Bukkit 未声明权限默认 op-only）——粗粒度但封闭。命令经 `J.aBukkit` 异步执行；`sync = true` 的命令经 `J.s` 回主线程执行。

| ID | 位置 | 缺陷 | 修复 |
|----|------|------|------|
| B1 | `CommandJigsaw.create` | 空检查查的是 String 参数 `object` 而非加载结果 `o`——"Failed to find" 分支不可达，null 对象进入编辑器 | 检查 `o == null` |
| B2 | `CommandJigsaw.create` | `piece`/`project` 未校验直接拼 `packs/<project>/jigsaw-pieces/<piece>.json`——路径穿越写原语 | `[a-zA-Z0-9_-]+` 白名单 |
| B3 | `CommandObject.analyze/shrink/paste` | `loadAnyObject` 返回 null 无检查——输入不存在对象名即 NPE | null 守卫 + 明确报错 |
| B4 | `CommandObject.save` | `name` 设计上允许 `/` 子目录，但 `../` 可逃逸 objects 目录 | 规范路径包含检查（保留子目录特性） |
| B5 | `CommandIris.create` | 世界名可含路径分隔符穿越 world container | 白名单校验 |
| B6 | `CommandIris.loadWorld` | dimensions 目录无 json 时把 `"Iris:null"` 写进 bukkit.yml（配置损坏）；世界名未校验 | null 守卫 + 名单校验 |
| B7 | `CommandIris.deleteDirectory` | `listFiles()` 可返回 null（Windows 文件锁）→ NPE 中止删除重试循环 | null 守卫 |
| B8 | `deletingWorld`/`worldCreation` | 跨线程读写的静态标志非 volatile | volatile |
| B9 | `StudioSVC.create` | 项目名未校验（穿越）；模板包缺失时在主线程（sync decree）同步下载阻塞全服 | 名单校验 + 下载移出主线程后重入，`finishCreate` 保持"仅删除本次下载的模板"语义 |
| B10 | `StudioSVC.download` | `packEntry` 可能为文件时 `listFiles()` NPE | isDirectory 守卫 |

## R3/R4：生命周期、线程与内存泄漏（commit 0583645 / 10569a6）

| ID | 位置 | 缺陷 | 修复 |
|----|------|------|------|
| C1 | `PreservationSVC.onEnable` | **`dereferencer` Looper 从未 `start()`**（其他 Looper 服务都调了）——每 60s 的 `IrisContext`/`IrisData` 解引用从未运行，关闭的引擎连同整个 IrisComplex 图被强引用永久滞留（世界反复开/关的长期运行服务器慢性内存泄漏） | `start()` + 命名 |
| C2 | `IrisWorldManager.close` | 每世界一个的 `cleanupService`（单线程调度执行器）从不关闭——每次世界创建/关闭泄漏一根空闲线程 | `shutdownNow()` |
| C3 | `JigsawEditor` | 无 `PlayerQuitEvent` 清理——编辑中掉线泄漏编辑器、重复 ticker 任务和 Player 引用；quit 处理器内联清理（`exit()` 的 `J.sfut().get()` 在主线程会死锁）；`exit()` 补 `sfut` 返回 null（插件禁用时）防护 | quit 处理器 + null 防护 |
| C4 | `ObjectSVC.undos` | 无界 `ArrayDeque<Map<Block,BlockData>>`——每次 paste 追加整块方块映射，永不回收 | 上限 20 条（丢弃最旧） |
| C5 | `NoiseExplorerGUI` | 每次 `/iris studio noise` 注册新 Bukkit 监听器且从不注销 | 静态实例替换 |
| C6 | `PregeneratorJob.close` | GUI 未开启时 `frame` 为 null → 每次报错日志噪音 | null 守卫 |
| C7 | `IrisSettings.get/invalidate` | `invalidate()` 对 null settings 同步 → NPE；`get()` 无同步可双重加载；`settings` 无 volatile | 类级锁 + volatile 双检 |

### 审查为健全（未改）

- **IOWorker/Holder/SynchronizedChannel**（Mantle 板块 IO）：信号量保护驱逐与关闭，写路径临时文件→channel 拷贝，无死锁路径；R14/R18 已有摘要与竞争验证背书。
- **Mantle.close()**：并行落盘 + 异常逐板块捕获，结构完整。
- **MultiBurst**：`getService()` 对已关闭执行器自愈重建，`/reload` 安全。
- **PreservationSVC.onDisable / IrisEngineSVC / GlobalCacheSVC**：停用路径完整（中断 Looper、关闭执行器、清缓存）。
- **IrisEngine.close()**：顺序完整（worldManager→target→engineData→mantle→complex→data dump）。
- **FileWatcher**：无线程轮询器，无泄漏。
- **VisionGUI**：非 Bukkit 监听器，Swing 线程自持。
- **CommandDeveloper.compression**：`algorithm` 走 switch 白名单（gzip/lz4f/lz4b），`path` 任意读为 dev 工具设计（op 权限门）。

## 累计修复统计（R1+R2+R3）

- R1：13 项（网络/下载/包安装集群）
- R2：10 项（命令/输入校验）
- R3/R4：7 项（泄漏/并发/生命周期）
- 共 30 项，12 个 commit；编译全量通过；golden 49/49 位一致。

## 下一轮建议

- R6：NMS 层（11 个版本模块）的异常处理与边界。
- R7：Decree 参数解析框架的异常路径（玩家输入非常规类型）。
- R8：事件处理器全集扫描（WorldUnload/ChunkLoad 等配对清理）。
