# 性能优化第 27 轮：BurstExecutor.complete 异常传播修复（稳定性基建轮）

日期：2026-08-27 · 分支：`perf/optimization` · 主题：修复 R26 遗留的 **`BurstExecutor.complete()` 吞异常语义**——它是所有"多核任务静默失败"的温床（R26 的板块卸载静默失败、R14 被迫另建 FutureJoiner 皆源于此）。

## 结论先行

- `complete()` 从"join 到第一个异常即停 + 吞掉（futures 不清理）"改为：**join 全部任务（R12 屏障契约保持）→ 记录首个失败 → 恒清 futures（重试 complete 变 no-op）→ `Iris.reportError` 保留 → 解包重抛**。
- **golden 49/49 位级一致**（`round27-post.csv`）；全部走 `complete()` 的场景（perfection-modify / deposit-place / carve-modify / cave-carve 等）**B/op 1.000× 零回归**，时间读数在漂移带内不声明。
- 新验证器 `bench.VerifyBurstException`：屏障 join-all、首失败传播、幂等重试、单核内联语义四项断言全过。
- `VerifyMemoryBound` 复跑 PASS（R26 的卸载路径在新语义下正常，含 `Mantle.unloadTectonicPlate` catch 分支的双重 complete 模式——修复后第二次调用成为无害 no-op）。

## 修复前的三重缺陷

```java
// 旧实现（要点）
try {
    for (Future<?> i : futures) i.get();   // (1) 第一个异常即中断循环：剩余任务被抛弃（屏障破洞）
    futures.clear();
} catch (InterruptedException | ExecutionException e) {
    Iris.reportError(e);                    // (2) 吞掉：调用方以为 burst 成功
}                                           // (3) 异常路径 futures 不清理：下次 complete() 重新等待已失败 future
```

1. **吞异常**：多核任务失败（板块写盘、区块阶段、资源加载）静默消失。单核路径（queue 内联执行）却直接从 queue() 抛出——**单核/多核异常语义分裂**。
2. **屏障破洞**：循环在第一个异常处中断，后续 future 未 join——与 R12 修复的"hunk 提前交还"竞态同构（只是触发条件是失败而非缺失 complete）。
3. **失败 future 永驻**：futures 不清理 → `Mantle.unloadTectonicPlate` 的 catch 分支再次 `burst.complete()` 会重新等待已失败的 future 并再次 reportError。

## 实现要点与 JDK 陷阱

- **逐 future 捕 Throwable**（而非只捕 ExecutionException）：JDK 的 `ForkJoinTask.get()` 对未检查任务异常**直接重抛反射重建的异常**（异常表压缩机制），**不包 ExecutionException**（与 FutureTask 的行为不同）——验证器首跑即抓到：异常直接穿出 join 循环，屏障与清理全被跳过。catch-Throwable 后屏障与清理无条件执行。
- **异常身份不保留**：FJP 的反射重建保类/消息/cause 链但丢身份（`t == 原异常` 为假）——验证器按类 + cause 链消息断言；单核内联路径身份保留（单独断言）。Sentry 指纹不受影响。
- **reportError 保留于重抛之前**：现有调用方的遥测零回退；上游已 catch+report 的调用方（如 `Mantle.close`）最多双报，无害。
- `InterruptedException`：恢复中断标志并记为失败。

## 调用方审计（29 处 complete()，全部分类安全）

| 类别 | 调用方 | 抛出后的归宿 |
|---|---|---|
| 引擎 Stage 屏障 | `EngineMode.burst`、`IrisPerfectionModifier`(R12 屏障)、`IrisDepositModifier`、`IrisCustomModifier`、`IrisObject`、`MantleObjectComponent`、`Locator/ResultLocator`、`Hunk` 并行×5、`ProceduralStream`、`StreamUtils` | `BukkitChunkGenerator.generateNoise` 的 catch Throwable → 红陶标记 + chunk-errors 落盘（错误处理链既定路径）——**多核失败首次与单核同等可见** |
| Mantle 生命周期 | `Mantle.close`（已有 try/catch Throwable）、`Mantle.unloadTectonicPlate`（catch 内二次 complete 现为 no-op） | 原有 catch 承接 + 上报 |
| 加载器 | `ResourceLoader.loadAllParallel`、`IrisData` dump×2 | 引擎 setup/hotload 上游 catch；**修复前"部分结果静默丢失"（清单缺资源=世界悄悄变错）属红线隐患，现在可见失败** |
| 预生成 | `TurboPregenerator.cache`（经 `MultiBurst.submit` 调度） | 失败进入 future 由池吸收 + 本轮 reportError 保留遥测 |
| 命令/GUI/其他 | CommandDeveloper、CommandStudio×2、NoiseExplorerGUI、UniqueRenderer、ParallelQueueJob、MultiBurst.burst 链×2 | Decree 框架（R7 D3 已加发送者反馈）/调度任务 catch-all |

**行为影响面**：仅在"任务已失败"的路径上可见（异常从静默变为传播 + 原有上报）；成功路径逐位不变（golden 证明）。

## 验证

- `bench.VerifyBurstException`（常驻验证器）：A 屏障（7 任务中 1 失败，`ran==7` 才准出）；B 首失败传播（类+cause 链消息，FJP 重建语义）；C 幂等重试（二次 complete no-op）；D 单核内联身份保留 + complete no-op。
- `bash benchmark/run.sh round27-post.csv 3 5` + golden 比对：**49/49 BIT-IDENTICAL**。
- 受影响场景 B/op 全 1.000×（perfection/deposit/carve/cave/ctx-fill/flag-raise）。
- `bench.VerifyMemoryBound 6 640 32`：PASS（硬上限 + settle 零钉住 + 堆平台不变）。

## 关联收益与遗留

- R14 为绕开吞异常而建的 `FutureJoiner` 语义已与 complete() 对齐——保持现状（直接 join 已有 future 列表，少一层 BurstExecutor 记账），无需回切；已在 memory 记录为可选简化。
- R26 报告中"unload 静默失败的温床"就此消除：板块卸载失败现在必然 reportError + 抛给 EngineSVC 调度任务（其 catch 记日志）。

## 改动清单

| commit | 内容 |
|---|---|
| `83413b267` | BurstExecutor.complete 异常传播重写（join-all + 首失败重抛 + 恒清理 + catch-Throwable/FJP 语义） |
| `c1d176750` | VerifyBurstException + round27-post.csv（golden 验证数据） |
