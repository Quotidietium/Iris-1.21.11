# 性能优化 · 第 13 轮:高度采样与铺层路径(AtomicCache 命中路径加固 + 覆盖封口)

**日期**:2026-08-23 · **分支**:`perf/optimization`
**环境**:JDK 25 · 32 逻辑处理器 · **37 → 39 场景**(biome-height、layers-gen,A/B 自证后并入 golden)

## 本轮主旨

R11 发现的"computeIfAbsent/aquire 捕获 lambda 在命中路径也分配"是**模式类**问题(全仓
98 处 `.aquire(() ->` 调用点)。本轮沿该模式清扫引擎最热的两条访问链:

1. **高度采样链**(`IrisBiome.getHeight` → `IrisBiomeGeneratorLink.getHeight` →
   `getCachedGenerator`):引擎中执行次数最多的流——地形列、生物群系放置、沉积、对象、
   洞穴全部消费高度流,每次采样 × 每个生成器都要过一次 aquire。
   `IrisComplex.computeHi/sample` 的 `getGenLinkMax/Min` 同链(每采样 × 每生成器 ×2)。
2. **铺层链**(`IrisBiome.generateLayers/generateCeilingLayers/generateSeaLayers/
   generateLockedLayers` → `getLayerHeightGenerators`):每列 × 每层调用,且原代码
   **在层循环内部**重复调用;`IrisBiomePaletteLayer.getBlockData/getLayerGenerator/
   getHeightGenerator` 每方块多次。

## 新场景

| 场景 | 配置 | digest 证明 |
|------|------|------------|
| `biome-height` | 真实 IrisBiome + 2 条生成器链接(键离线加载未命中 → 生产同款默认 IrisGenerator 回退路径),每 op 一次完整生物群系高度采样 | 高度值序列 |
| `layers-gen` | 真实 IrisBiome 3 层(2-3 权重方块调色板,厚 2-8,STATIC 层高),每 op 一列完整 generateLayers(列 RNG 语义同 TerrainColumn) | 每方块材质序数 |

## 改动清单(4 个生产文件)

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `AtomicCache` | 新增 `peek()`:不构造 supplier 的命中探测(缺失/空缓存返回 null,调用方回落 aquire) | 纯新增 API;命中值非 null 的调用点等价 |
| 2 | `IrisBiomeGeneratorLink.getCachedGenerator` | peek-先行(高度采样最热访问器) | 缓存值恒非 null(load 失败回退默认生成器);命中/未命中语义不变 |
| 3 | `IrisBiome` 12 个热 getter | peek-先行:genLink 三兄弟、realCarveBiome、surface/carveObjects、biomeGenerator、childrenCell、maxHeight、maxWithObjectHeight、两个层高生成器表 | 同上;捕获 lambda 仅在未命中路径构造 |
| 4 | `IrisBiome` 4 个 generate*Layers | `getLayerHeightGenerators/getLayerSeaHeightGenerators` 提出层循环(每列 1 次而非 layers.size() 次) | 同一缓存列表;循环体取值不变 |
| 5 | `IrisBiomePaletteLayer` 3 个 getter | peek-先行(getBlockData 每方块多次) | 同上 |

## 结果(隔离跑,3 预热 + 9 迭代,后 5 次中位数)

| 场景 | A ns/op | B ns/op | A B/op | B B/op |
|------|--------:|--------:|-------:|-------:|
| biome-height | 18.6 | **17.9**(1.04×) | 0.0 | 0.0 |
| layers-gen | 1115.6 | 1111.5(1.00×) | 691 | 691 |

- **digest 逐迭代 18/18 一致**;全套 37 旧场景位级一致;golden 更新为 **39 场景**。
- **持平的机理(重要教训)**:隔离小链路里 HotSpot 逃逸分析已把命中路径的捕获 lambda
  标量替换掉(0 B/op 在 A 侧即如此)。R11 的 JFR 证明该模式在**内联断裂的大链路**
  (MantleChunk.get → IrisMatter.slice → CHM.computeIfAbsent)真实分配(2.4 GB 采样)。
  本轮改动对内联健康时零成本(一次 volatile 读 vs 一次被消除的分配),对内联退化
  (深链路/多态点/巨大方法体)的画像提供确定性免疫——保险性质,与 R11 的实测修复
  同型同源。
- 铺层循环提升是确定性收益(每列少 layers.size()-1 次缓存探测),量级小于测量噪声。

## 结论与后续

- 引擎全部生成阶段(地形/生物群系/装饰/洞穴/沉积/后处理/对象/高度流/铺层)现已
  **全部有 golden 覆盖的场景**;离线可测面的结构优化趋于收敛。
- 剩余大头均需服务器实测或结构性重设计:Engine.java 在线更新路径、Mantle IO/驱逐、
  VectorMap 原始化(加载期)。
- 建议下一动作:用户服务器端到端实测(预生成吞吐 + GC 停顿对比 master 分支),
  十三轮累计的主路径分配削减(-99.4% 沉积、-64.4% carve、-82% 合计)应直接可见。
