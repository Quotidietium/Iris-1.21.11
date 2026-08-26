# 稳定性/安全性审计 第 14 轮：第三方 DataProvider 集成层

日期：2026-08-27
分支：`perf/optimization`
前置：R1-R13（R10 已修 ExternalDataSVC 侧：CME 列表、PluginDisable 生命周期、parseState limit-2、getAllBlockProperties 逐 id 守卫、parseYawAndFace 容错）
范围：`core/link/data/` 全部 10 个 DataProvider（CraftEngine/Nexo/HMCLeaves/ItemAdder/MythicCrucible/MythicMobs/MMOItems/EcoItems/ExecutableItems/KGenerators）——此前 10 个全部在 benchmark/build.sh 排除路径上，本轮**以最小 API stub 解锁 3 个**（Nexo 4 类、CraftEngine 11 类）+ HMCLeaves（纯反射零依赖）进入编译门禁；其余 7 个仍排除（需要真实插件 jar，逐一通读审计）。
验证：`benchmark/build.sh` 全量编译通过（**1274 类**，新增 3 个 provider + 14 个 stub）；golden 49/49 位级一致（`results/audit-r14.csv`；本机进入慢速期 ~1.6×，全量 3+3 超外部工具时限，改 1+1 迭代——verify 只用 it=0 digest，与 warmup 无关）。

## 修复清单（4 项）

| ID | 文件 | 缺陷 | 修复 | 严重性 |
|----|------|------|------|--------|
| I1 | `ExternalDataSVC.processUpdate` | **对 provider 调用无任何防护**（spawnMob 有 MissingResourceException catch）：第三方插件的 place() 抛异常会穿进区块激活路径——R27 起 BurstExecutor.complete 还会传播放大（失败整批区块更新） | 与 spawnMob 对齐的双重 catch：MissingResourceException → error 日志；Throwable → reportError + 具名 provider/坐标的 error——**伞形防护，同时兜住本轮无法编译验证的 7 个 provider** | 高（稳定性） |
| I2 | `HMCLeavesDataProvider` | **部分初始化 NPE**：init() catch Throwable 后字段保持 null（如 setCustomBlock 绑定失败但 blockDataMap 已建），后续 processUpdate/getBlockData 在 null 反射句柄上 NPE | getBlockData 句柄 null → MissingResourceException；processUpdate 句柄 null → warn 跳过 | 中 |
| I3 | `CraftEngineDataProvider.parseYawAndPitch` | **本地复制版解析无容错**（R10 修的是 ExternalDataProvider.parseYawAndFace，此处漏网）：数据包写 `yaw=abc` → NumberFormatException → 穿透 processUpdate | 与 R10 同处理：NFE catch → warn + 保持默认 0 | 中 |
| I4 | `NexoDataProvider.getItemStack` | `e.printStackTrace()` 绕过日志管道 | `Iris.reportError` | 低（债务） |

## 审查为健全（未改，逐一通读）

- **MythicCrucible / Nexo 的 `BiomeColor.valueOf(state.get("matchBiome").toUpperCase())`**：已有 `NullPointerException | IllegalArgumentException` 双 catch ✓。
- **MMOItems** `Integer.parseInt` 有 NFE catch ✓；tier 查询 null 走 MissingResourceException ✓。
- **ItemAdder / EcoItems / ExecutableItems / KGenerators / MythicMobs**：访问器模式与上述健全面一致（null 检查 + MissingResourceException）；异常路径全部落入 I1 伞形防护。
- **R10 修复复核**：providers/activeProviders 的 CopyOnWriteArrayList、PluginDisableEvent 移除 + 注销、启用重注册——在位。

## 门禁解锁设施

- `benchmark/stubs/` 新增 `com.nexomc.nexo.*`（4 类）与 `net.momirealms.craftengine.*`（11 类）最小编译形状 stub（运行时永不被调——真实插件在场时用真类，benchmark classpath 上 stub 仅补符号）。CraftEngine 的 `IntegerProperty.min/max` 为**实例字段**（provider 源码 `property.min` 字段访问语法暴露了真实 API 形状）。
- `build.sh` 排除模式从整体 `*DataProvider.java` 改为精确排除 7 个无 stub 文件——Nexo/CraftEngine/HMCLeaves 三者进入每次门禁编译。
- README 方法论补充：慢速期（本机 ~1.6×）全量 3+3 超外部工具时限时，门禁可用 `run.sh <csv> 1 1`（verify 只用 it=0 digest，warmup 无关）。

## 影响面

- 集成层运行时行为仅在"第三方插件抛异常/配置畸形/插件半初始化"时变化（从崩溃/穿透变为容错+上报）；正常路径零变化——golden 49/49。
- I1 是本轮主价值：与 R27 的异常传播收紧互补——异常传播链修好后，未经防护的第三方边界反而更容易把失败放大到区块批次，伞形防护把第三方故障面隔离在 provider 边界内。

## 累计统计（R1-R14）

- **65 项修复，~31 个 commit**
- 编译：benchmark/build.sh 全量通过（1274 类，含 3 个 provider + 14 stub）
- 回归：golden 49/49 位级一致（audit-r14.csv）
- 报告：`round1`、`round2-4`、`round5-8`、`round9-11`、`round12`、`round13`、本文件

## 后续建议

- R1-R14 审计循环收官（全部主要面已覆盖）；剩余为格式债务：全库 `e.printStackTrace()` → `Iris.reportError`（~226 处，机械批量+抽查）。
- 合并前事务：服务器实测清单、版本号确认、Release 发布。
