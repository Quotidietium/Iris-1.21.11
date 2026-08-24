# 稳定性/安全性审计 第 9-11 轮：IrisConverter / link 集成层 / 热载入并发

日期：2026-08-25
分支：`perf/optimization`
前置：R1（`round1-network-download-cluster.md`）、R2-R4（`round2-4-commands-lifecycle-config.md`）、R5-R8（`round5-8-nms-decree-pregen.md`）
验证：`benchmark/build.sh` 全量编译通过（1246 类）；golden 49/49 位一致（含一次基线重校准，见下文事故记录）。

## R9：IrisConverter（commit 67a9033）

| ID | 位置 | 缺陷 | 修复 |
|----|------|------|------|
| F1 | `convertSchematics` | 固定线程池提交后**永不 shutdown**——每次 `/iris convert` 泄漏一个常驻线程 | submit 后 shutdown |
| F1 | 同上 | 大对象（>200 万方块）进度任务 `J.ar` 只在成功路径取消；palette 解析/三重循环任何异常都会跳过 `J.car` → **进度任务永久每 tick 刷屏玩家**（会话级泄漏） | finally 中取消 |
| F1 | 同上 | `blockmap.get(blockIndex)` 对 palette 缺失条目 NPE（畸形/外来 .schem 整文件中止且无诊断） | null 守卫跳过 |
| F1 | 同上 | `e.printStackTrace()` 绕过日志管道 | `Iris.reportError` |

注：`resolveVersion` 的 NPE 已包装为诊断异常；`Bukkit.createBlockData` 在 Paper 上注册表只读、异步调用可接受，未改。

## R10：ExternalDataSVC / link 集成层（commit c236547）

| ID | 位置 | 缺陷 | 修复 |
|----|------|------|------|
| F2 | `ExternalDataSVC` 字段 | `providers`/`activeProviders` 为裸 ArrayList，被引擎线程（战利品 `getItemStack`、方块 `getBlockData`、装饰 `processUpdate`）流式遍历，而主线程在 PluginEnableEvent 时写入 → **CME 可杀死生成中的装饰线程**（高负载 + 管理员中途加载 CraftEngine/Nexo 即触发） | CopyOnWriteArrayList（读多写少） |
| F2 | 生命周期 | 无 PluginDisableEvent 处理：目标插件禁用后 provider 仍留在 activeProviders，引擎线程继续调用**已禁用插件**的 API；重新启用时 `registerEvents` 二次注册 → **事件双触发**（每次禁用/启用循环翻倍） | onPluginDisable 移除 + `unregisterListener`，启用时重新注册 |
| F2 | `parseState` | `s.split("=")[1]`：无 `=` 的状态段（`mod:block[a,b=c]`）或空段 AIOOBE → 整个方块查询中止（B.getOrNull 有 Throwable 兜底但整块退化为空气）；值含 `=` 被截断（与 D2 同模式） | limit-2 分割 + 畸形段告警跳过 |
| F2 | `getAllBlockProperties` | 单个 provider 抛 MissingResourceException 未捕获 → `B.getBlockStates()`（schema 构建）整体失败；其余访问器均有守卫，唯独此漏 | 逐 id 守卫跳过 |
| F2 | `parseYawAndFace` | `Float.parseFloat`/`BlockFace.valueOf` 未校验：包配置写错 `yaw=abc`/`face=BAD` 在放置路径抛 NFE/IAE | try/catch 回退 0/NORTH |
| F2 | `WorldEditLink` | `e.printStackTrace()` 与 `Iris.reportError` 重复 | 去重 |

审查过无缺陷：`Identifier.fromString`（limit-2 分割正确）、`ServerConfigurator.installDataPacks` 并行流（KSet 为 CHM 底层 + `synchronized(biomes)` add-if-absent 模式正确，同 loadKey 双包竞争安全）、`Iris.service` 空返回仅在 pre-enable 窗口可达（调用点均在 enable 后）。

## R11：热载入并发（commit 19984f9）

| ID | 位置 | 缺陷 | 修复 |
|----|------|------|------|
| F3 | `IrisData.hotloaded()` | `loaders.clear()` 后逐个重放 20 个 loader：studio 热重载窗口内引擎线程 `getLoader()` 命中**空/半填充映射** → 该窗口生成的区块把资源当缺失（biome null → 回退行为） | 旁路构建新映射 + 单次引用交换（读方要么旧全要么新全） |
| F3 | `IrisEngine` 字段 | `complex`/`worldManager`/`effects`/`mode`/`execution`/`hash32` 为普通字段，被热载入路径换引用，而生成线程持续读取——**无 happens-before 边**：线程可能无限期使用已被替换的旧 complex（"热载入不生效"的间歇性缺陷；JIT 可在无安全点循环中缓存字段读） | 六字段 volatile |
| F3 | `IrisEngine.hotloadComplex()` | 先 `close()` 旧 complex 再赋新值——交换窗口内生成线程必然使用已关闭实例 | 先构建→发布→再关闭 |

`prehotload()`/`setupEngine()` 的先关后建顺序保持未动（worldManager 关闭会中断其线程，重排有两实例并存风险，超出本轮安全改动范围）。

## 事故记录：golden 基线漂移（commit d1fbf8a, 0279633）

**现象**：R11 改动后 verify.sh 报 2/49 场景 digest 变化（`decorator-decorate`、`layers-gen`）。

**排查**（完整二分）：
1. stash 掉 R11 改动在 HEAD 复跑 → 仍变 → 非本会话代码引起；
2. 逐提交回退复跑（R10/R9/D1/C3/**2265ca79e**——即当时代码+当时声称 49/49 验证通过的提交）→ **全部产出新 digest**；
3. 历史结果 CSV 溯源：8 月 23-24 全部运行（round9→19、iso15-18、audit-r1）均为 golden 值 `fc83d904`/`f7eb17a7`；8 月 25 全部运行（跨 5 个不同构建）稳定产出 `784ea6be`/`ac12bdb4`。

**结论**：digest 值与代码无关（同提交跨两日产出不同值、同日跨全部提交产出相同值）→ **运行环境在 8 月 24-25 之间变化**。`java` 经 Oracle `javapath` 垫片解析（`C:\Program Files\Common Files\Oracle\Java\javapath`），自动更新可静默重指向新构建；仅这两个三角函数/噪声密集场景对 `Math` 实现微差敏感，其余 47 个 digest 不受影响。

**处置**：以当前环境重校准这两行 golden（其余 47 行未动），verify 49/49 恢复；`benchmark/README.md` 方法论一节新增"JDK 必须固定"警告与事故记录。

## 累计统计（R1-R11）

- **51 项修复，21 个 commit**（本批 +9 修复 / +6 commit，含 golden 重校准与 README）
- 编译：benchmark/build.sh 全量通过（1246 类）
- 回归：golden 49/49 位一致（重校准后）
- 报告：`round1`、`round2-4`、`round5-8`、本文件

## 下一轮建议（未完待续）

- R12：GUI/编辑器交互层（IrisGUI、JigsawEditor、NoiseExplorer 等玩家高频交互面）——R3 仅覆盖命令层
- R13：`IrisObjectRegistry` / .iob 读写路径（文件 I/O 完整性、并发 paste）
- R14：`core/link/data/*DataProvider`（CraftEngine/Nexo/HMCLeaves 等 6 个离线不可编译的集成实现，需 gradle 环境验证）
- 债务：全库 227 处 `e.printStackTrace()`（94 文件）统一为 `Iris.reportError`（格式债，非稳定性缺陷，建议机械批量+抽查）
