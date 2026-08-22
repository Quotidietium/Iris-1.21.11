# 性能优化 · 第 5 轮：地形执行器（每方块循环重写）

**日期**：2026-08-22 · **分支**：`perf/optimization` · **提交**：`1c847fc8a`
**环境**：JDK 25 · 32 逻辑处理器 · 基准新增 2 个场景（`terrain-col-legacy` / `terrain-col-fill`，共 23 场景）

## 优化点

`IrisTerrainNormalActuator.terrainSliver` 是引擎每区块最热的循环（每列 × 每方块）。
原实现对**每个方块**重复执行：

1. **三级矿石检查 × 2 轮**（biome→region→dimension，surface + buried），即使该级
   根本没有配置对应放置类型的矿石——典型包只有 dimension 配 buried 矿石（默认
   `generateSurface=false`），biome/region 矿石表通常为空，即 surface 轮的 3 次调用
   与 deep 轮的 biome/region 2 次调用每方块必然返回 null；
2. **每方块重复读取列不变量**：`context.getRock().get(xf,zf)`、`context.getFluid().get(xf,zf)`
   （整列同值）、`h.getHeight()`、`getData()`、`getDimension()` 虚调用链。

## 改动清单

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `engine/actuator/TerrainColumn`（新增） | 列填充循环逐字提取为纯静态方法（无 Bukkit 静态初始化，离线基准可直接驱动生产代码路径）；`terrainSliver` 变为：按 sliver 提升 dimension/data/complex/height/fluidHeight/bedrock，按列提升 rock/fluid/biome/region 后调用 fill | 纯提升：所有被提升的读取在循环内无副作用且列内恒值（context hunks 为 Stage 1 前预采样数组，输出 hunk 写入不回流） |
| 2 | `IrisBiome/IrisRegion/IrisDimension.hasOres(boolean)`（新增） | fill 在列首计算 6 个「该级是否有此放置类型的矿石」布尔；为假时跳过对应的 `generateOres` 调用 | hasOres=false ⟹ generateOres 确定性返回 null 且**不消耗 rng**（矿石列表无 flag 匹配项时循环不进入 `ore.generate`，palette/rng 均不触碰）⟹ 跳过=同一结果、同一 rng 流。rng 消耗顺序不变的逐点论证见提交说明 |
| 3 | `IrisBiome.BARRIER`、`IrisDimension.STONE/WATER` | BARRIER → 懒 holder；STONE/WATER 为全仓零引用死常量，删除 | BARRIER 仅在 explode 调色板调试模式使用，首访才创建，值不变；STONE/WATER 无任何引用。此改动同时解除了离线实例化 IrisBiome/IrisDimension 的障碍（原急切静态 `Material.createBlockData()` 在无服务器时抛 NPE），使本轮基准能驱动**真实**引擎对象 |

**未做（红线否决）**：雕刻修改器（IrisCarveModifier）的 `KMap<Long,KList>` 位置表
可换 256 槽数组直索引，但会改变 processZone→装饰器的迭代顺序→rng 消耗流→改变地形，
违反红线；且 Matter 层离线不可测，无法验证，故不动。

## 结果（典型包配置：biome/region 无矿石，仅 dimension 3 个 buried 矿石）

| 场景 | ns/列（中位数） | 分配 |
|------|---------------|------|
| terrain-col-legacy（逐字原循环） | 1038 | 104 B/op |
| terrain-col-fill（新生产路径） | **671（1.55×）** | 104 B/op |

- digest 位级一致（`13afa65a6f2ec5df`，写序列 + 分支决策逐位相同）；两轮不同矿石
  fixture（含 biome/region 配矿配置）产生同一 digest，佐证空凋色板下矿石配置不影响
  写序列、等价性稳健。
- 104 B/op 为每列两次 `generateLayers/generateSeaLayers` 空表 KList 分配（数据结构
  本体，保留共享实例有可变性风险，不动）。
- 每 chunk 收益 ≈ 256 列 × 367 ns ≈ 94 µs 纯开销消除；实际区块总耗时还包含噪声/
  函数（已由第 1-4 轮覆盖），相对占比小于 1.55×。

## 验证

- `verify.sh`：**23/23 场景 digest 一致**；原 21 场景摘要与第 4 轮 golden 完全相同
  （本轮改动不影响任何已有基准路径）。
- 全量编译校验通过（1213 类）。
- 生产等价性：基准 digest 覆盖循环骨架（分支/写序列/层决策）；矿石命中路径（消耗
  rng、调色板取样）为逐字搬运的原调用（参数、顺序、级别链完全一致），且跳过仅发生
  在可证明返回 null 且不消耗 rng 的调用上。

## 五轮累计（vs round0 基线）

| 指标 | 累计效果 |
|------|---------|
| 热点路径分配（单线程合计） | 18818 → 3471+104（+2 场景）B/op，主路径 0 B/op |
| implode 子群系选择 | **11.4×** |
| cache2D 命中路径 | 18.8 → **6.7 ns/get**；8 线程生产 raster 负扩展 → **5.9× 聚合** |
| 地形列填充循环 | **1.55×**（典型配置） |
