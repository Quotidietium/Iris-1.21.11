# 性能优化 · 第 8 轮：对象放置写路径（IrisObject.place）

**日期**：2026-08-22 · **分支**：`perf/optimization` · **提交**：`8a5528745`（设施修复+场景）+ `4c9f14259`（优化）
**环境**：JDK 25 · 32 逻辑处理器 · **29 → 31 场景**（object-place / object-place-stilt，A/B 自证后并入 golden）

## 本轮主旨

对象放置是地形之外最大的方块写源（每棵树/结构每个方块都走 `IrisObject.place`）。
R7 的 Bukkit 代理桩已解锁 BlockData 供给，本轮把整条放置写循环纳入基准并优化。

## 设施修复（先于优化）

R7 勘误的根因：桩 `Bukkit.getRegistry` 返回 null → `org.bukkit.Registry` 静态初始化
（对 MusicInstrument/GameEvent 做 `requireNonNull`）失败 → `B` 类被 `ExceptionInInitializerError`
毒化。修复：`getRegistry` 对所有请求返回惰性空代理（Registry 的枚举支撑 SimpleRegistry 字段
不受遮蔽，Material/Biome 等仍真实解析）。附带澄清了 R7 matter-roundtrip 的读侧覆盖
（见 round7 报告勘误框）。

## 新场景（真实 IrisObject + 真实默认 IrisObjectPlacement + 记录型 IObjectPlacer）

| 场景 | 配置 | digest 证明 |
|------|------|------------|
| `object-place` | 9×13×9 树（原木干+椭球冠 ~130 方块），CENTER_HEIGHT 默认模式（Y 轴随机旋转激活） | 每次写入的坐标+材质序 |
| `object-place-stilt` | 同树，STILT 模式（额外走 stilt 循环的旋转/平移链） | 同上 |

## 改动清单（1 个生产文件，IrisObject.place 两处循环 + 一处判序）

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | 主写循环（原 L949-952） | `i = rotate(i.clone(),…).clone(); i = translate(i.clone(),…).clone()` 五层防御性克隆 → `i = rotate(g,…); i = translate(i,…)` 零显式克隆 | rotate/translate 均不改入参、返回新向量或入参本身；i 后续只读（坐标取值/判 lowest/标记查询用 g），别名安全；坐标数学逐位不变 |
| 2 | stilt 循环（原 L1075-1077） | 相同克隆链削减 | 同上 |
| 3 | 原L1036 | `wouldReplace = isSolid(placer.get(x,y,z)) && isVineBlock(data)` → **操作数重排**为 `isVineBlock(data) && isSolid(placer.get(…))` | 两个谓词均纯读；非藤蔓方块（绝大多数）跳过一次 placer.get（生产中即一次 Mantle 读 ~40-60ns） |

## 结果（中位数，A8b=旧代码 vs B8=新代码，31 场景 digest 全部位级一致）

| 场景 | A ns/op | B ns/op | 提升 | A B/op | B B/op |
|------|--------:|--------:|-----:|-------:|-------:|
| object-place | 57 363 | **23 476** | **2.44×** | 94 480 | **63 568（-33%）** |
| object-place-stilt | 70 853 | **33 582** | **2.11×** | 142 480 | **83 968（-41%）** |

- 其余 29 场景均在跨轮噪声带内（±10%），digest 31/31 位级一致。
- 分配构成（每方块）：原先 5-7 个 BlockVector（防御克隆链）→ 0-2 个（rotate/translate 内部克隆）。
  生产环境还有 placer.get 消除的额外收益（离线 placer 是数组读，收益被低估）。
- stilt 场景 B/op 降幅更大（-41%）：其双循环各自承担一条克隆链。

## 八轮累计（vs round0 基线）

| 指标 | 累计效果 |
|------|---------|
| 热点路径分配（单线程合计） | 18818 → ~3575 B/op（-81%），主路径 0 B/op |
| implode 子群系选择 | **11.4×** |
| cache2D 命中路径 | 18.8 → **6.7 ns/get**；8 线程 raster **5.9× 聚合** |
| 地形列填充（典型配置） | **1.55×** |
| HyperLock 加解锁 | 422 → **15.8 ns（26.8×）**，0 B/op |
| 调色板容器写 / Mantle 写链 | **2.62× / 1.67×**，分配 -85%/-70% |
| Matter 段序列化往返 | **1.26×**，分配 -64% |
| 对象放置（树，CENTER_HEIGHT） | **2.44×**，分配 -33% |

## 结论与后续

- 防御性克隆链是对象放置的最大单点开销：JIT 无法消除跨虚调用的 5 层 clone，手工收缩后
  放置耗时近半。放置吞吐直接决定对象丰富群系的区块生成速度。
- R9 候选（按预估收益排序）：装饰器选择循环（IrisEngineDecorator.getDecorator 的 per-biome
  partOf 预过滤 + KList/RNG 每列分配）、IrisDepositGenerator 团块几何（需 palette 反射预灌）、
  `IrisObject.getSigned/VectorMap` 键结构（BlockVector 32B → 原始 int 键）。
