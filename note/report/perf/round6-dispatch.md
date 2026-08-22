# 性能优化 · 第 6 轮：分支派发扁平化（结论：收益 <1%，可测面已到顶）

**日期**：2026-08-22 · **分支**：`perf/optimization` · **提交**：`5cbf5d569`
**环境**：JDK 25 · 32 逻辑处理器 · 23 场景（无新增，纯控制流改动）

## 改动清单

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `IrisInterpolation.getNoise` | 20+ 连 `equals()` if-else 链 → enum switch（tableswitch）；分支映射逐一保留（含上游 BICUBIC→bilinear 的历史映射） | enum switch 与 equals 派发均为恒等比较，纯控制流 |
| 2 | `CompiledStarcast.getStarcast` | 128 连 float 比较 if 链 → `return switch ((int) checks)`；原链对 checks∈[k,k+1) 分派 sc(k)，正数域即 floor → `(int)checks` 逐点等价；`[1,128]` 守卫与 guard 外通用回退循环原样保留 | 分派目标逐 check 值等价（1..128 全覆盖 + default 断言） |

## 结果（中位数，vs round5）

| 场景 | round5 | round6 | Δ |
|------|--------|--------|---|
| interp-bilinear | 876 | 874 | -0.2% |
| interp-bilinear-starcast6 | 4180 | 4150 | -0.7% |
| interp-hermite | 3476 | 3451 | -0.7% |
| interp3d-trilinear | 1637 | 1623 | -0.9% |
| interp3d-trilinear-starcast6 | 25938 | 25853 | -0.3% |

**23/23 场景 digest 位级一致**（`verify.sh` vs golden）。

## 结论

- 预期之内但值得记录：JIT（分支预测 + 内联）早已把线性 equals 链的成本摊薄到
  不足 1%，tableswitch 只带来卫生意义上的改善。
- 至此**离线可验证的优化面已收敛到本质开销**：23 个场景的耗时均已由
  噪声内核（FastNoiseDouble simplex，数学不可动——digest 红线）、
  数据结构本体分配与确定性 RNG 构成。
- 后续方向（需服务器环境，无法离线 digest 验证，建议用户实测后按需进行）：
  Matter/Mantle 写入路径（HyperLock）、装饰器/沉积 Stage 3、`IrisCarveModifier`
  的 KMap 位置表（换数组会改变装饰 rng 顺序，需连装饰器一起重设计）。

## 六轮累计（vs round0 基线）

| 指标 | 累计效果 |
|------|---------|
| 热点路径分配（单线程合计） | 18818 → ~3575 B/op（-81%），主路径 0 B/op |
| implode 子群系选择 | **11.4×** |
| cache2D 命中路径 | 18.8 → **6.7 ns/get**；8 线程 raster 负扩展 → **5.9× 聚合** |
| 地形列填充（典型配置） | **1.55×** |
| 单线程噪声内核 | 1.0-1.2×（主体收益在分配与多线程扩展） |
