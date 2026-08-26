# 性能优化第 28 轮：Mantle.trim 除零怪语义清理（语义修正轮）

日期：2026-08-27 · 分支：`perf/optimization` · 主题：R26 审计中发现并遗留的 `Mantle.trim` 除零怪语义——`trim(0,0)` 的"立即全部卸载"意图被 4000ms floor 静默顶起。

## 缺陷与修复

```java
// 旧（R28 前）
if (loadedRegions.size() > tectonicLimit) {
    idleDuration = Math.max(idleDuration - (1000 * (((loadedRegions.size() - tectonicLimit) / (double) tectonicLimit) * 100) * 0.4), 4000);
}
```

`tectonicLimit=0` 时 `(loaded-0)/0.0` = **Infinity**（loaded 也为 0 时是 **NaN**），`1000×Infinity×0.4` = Infinity，`idleDuration - Infinity` → `Math.max(-∞, 4000)` = **4000**。结果：`trim(0, 0)`（IrisPregenerator 预生成停止时的 flush-all 调用）名义上"闲置 0ms 即卸载"，实际被顶成"闲置 ≥4s 才卸载"——刚触碰过的板块滞留到引擎关闭才落盘。R26 的 VerifyMemoryBound settle 曾被迫 `Thread.sleep(4500)` 规避此 floor（当时记录为"怪语义未清理"）。

```java
// 新（R28）
if (tectonicLimit > 0 && loadedRegions.size() > tectonicLimit) { ...同公式... }
```

**显式语义**：`limit<=0` = "不允许驻留"——完全跳过超限修正，`idleDuration` 保持调用方给定的 baseIdleDuration（0 = 立即）。正 limit 路径（EngineSVC trimmer 的 30s→4s 渐进收缩）逐字不变。

## 影响面

| 调用方 | 旧行为 | 新行为 |
|---|---|---|
| `IrisPregenerator` 停止时 `trim(0,0)` | 仅 ≥4s 闲置板块落盘，近期板块滞留至引擎关闭 | **全部立即落盘**（flush-all 本意） |
| EngineSVC trimmer/unloader（正 limit） | 30s→4s 渐进 | 不变 |
| R26 硬上限块（`tectonicLimit >= 0` 全量强制） | 与 flush 语义一致 | 不变 |
| `unloadTectonicPlate` 的 `adjustedIdleDuration` | 被 floor 顶起 | 0 → `lastUse < now` 即卸 |

磁盘格式、数据内容零变化（仅驻留时机）；地形输出零变化。

## 验证

- **VerifyMemoryBound 去掉 settle 的 4.5s sleep**：双臂（hardcap on/off）在 `trim(0,0)+unload×2` 后**立即**清零（旧代码下无 sleep 的 hardcap-off 臂会残留全部近期板块）——除零修复的直接证明。
- **golden 49/49 位级一致**（`round28-post.csv`）。
- `VerifyBurstException` 复跑 PASS（R27 语义未受影响）。
- 同毫秒边界：`lastUse < now` 的严格小于使极新板块可能漏过一轮 pass——settle 的双 pass 模式本就覆盖（且 hardcap 臂的 forced 路径不受 idle 检查约束）。

## 改动清单

| commit | 内容 |
|---|---|
| `e9c3a91fb` | Mantle.trim limit<=0 显式跳过超限修正 |
| `24c1184eb` | VerifyMemoryBound 去 sleep 复跑 + round28-post.csv |
