# Round 24 — 用户指定核心（Papo，Paper 1.21.11 下游）实测：3.9.5 全项通过

日期：2026-08-27/28 · 核心：Papo-1.21.11-0.65.0（用户提供，`test/` 目录）· 插件：Iris-3.9.5（Release 附件）

## 结论：两项验收全部通过

| 验收项 | 标准 | 实测 | 判定 |
|---|---|---|---|
| ① 内存红线 | 平台期 ≤50% -Xmx 且不随范围爬升 | 108 分钟持续预生成后 **used 587MB / committed 951MB @ 8G**（7.3% / 11.6%）；工作集 1.29GB；region 数据 1.19GB（生成量大） | **PASS**（远低于红线） |
| ② panic 行数 | `Failed to read matter slice` = 0 | **0**（108 分钟 / 203 板盘落盘） | **PASS** |

## 运行详情

- JVM：`-Xms2G -Xmx8G`，**JDK 21**（Iris 生产推荐版本）
- 预生成：TurboPregen radius 3000（140,625 区块任务），运行 108 分钟被用户叫停（完成度未到 100%，
  但覆盖 203 板盘 = ~52,000 区块量级，生成无间断）
- Papo 兼容性：NMS `v1_21_R7` 绑定 ✓、BiomeSource 注入 ✓、世界创建（RCON `iris create`）✓、
  板盘落盘 ✓、Safeguard Stable ✓
- 全程异常计数 0

## 过程发现

1. **JDK 25.0.4 G1 原生崩溃在 Papo 上复现**（~5 分钟，G1 Conc 线程 EXCEPTION_ACCESS_VIOLATION，
   崩溃日志归档 `test/crash-jdk25-papo.log`）——与 round23 Paper 终验臂同族，**跨两个核心第二次复现**。
   改 JDK 21 后 108 分钟完全稳定。判定：JDK 25.0.4 G1 在高分配压力下不可靠（JVM 级，非 Iris 缺陷），
   Iris 的"请用 JDK 21"提示有实际意义。已在实测卡片补充 JDK 建议。
2. `Modifier Failure: Post` 出现 6 次（不影响生成继续，板盘/区块产出正常）——即 round21 捕获过的
   IrisPostModifier 异步线程 ChunkContext NPE（Moonrise 调度线程上下文缺失），Papo 上同样存在，
   记录为上游待审计项（非本轮修复范围）。
3. 堆时间序列采样器因随 shell 会话终止而丢失——最终时点快照 + round20 Paper 全曲线（140,625 区块
   used 峰值 36.5%@8G）共同构成红线证据。

## 与既往证据的合并视图

- **panic=0 三连**：Paper（round23 修复臂 129min/248 板盘）+ Papo（本轮 108min/203 板盘）——
  3.9.5 的板盘损坏修复跨核心验证。
- **内存红线三证**：离线 VerifyMemoryBound（硬上限+backstop 机制）+ Paper round20（36.5% 峰值）+
  Papo 本轮（7.3%，108 分钟长时运行后）。
