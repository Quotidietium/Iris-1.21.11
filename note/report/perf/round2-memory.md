# 性能优化 · 第 2 轮：内存/分配路径

**日期**：2026-08-22 · **分支**：`perf/optimization` · **提交**：`e52705c42`
**对照**：round0 基线与 round1（中位数，5×1M ops，JDK 25）

## 改动清单

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `util/cache/ChunkCache2D` | 删除构造器预分配 256 个 Entry（每流每区块约 6KB）；`get()` 中已有的 CAS 惰性路径本就覆盖 | 惰性创建路径与预分配路径行为一致（同一 compute 双检锁语义）；仅未访问单元不再分配 |
| 2 | `util/cache/WorldCache2D` | 区块键 Long 装箱的单槽备忘（单 volatile 引用保证配对原子发布） | `boxed != k` 为值比较；备忘未命中只多分配一个盒子；CLHM 按 equals 寻址 |
| 3 | `IrisInterpolation`（2D） | 星射包装 lambda → 挂在 memo 层级的可复用 Bilinear/Hermite 适配器 | 适配器体与原 lambda 表达式相同（含 (int) 截断）；嵌套层级各持独立适配器 |
| 4 | `IrisInterpolation`（3D） | `getStarcast3D` 的 3 个捕获 lambda 与 TRILINEAR_TRISTARCAST 组合 → 线程内 `Starcast3DAdapter` 池（平面/三线性双角色） | **`bench.Verify3D`：60 万采样 A/B 对照原 lambda 表达式，位级零差异** |
| 5 | `IrisComplex.interpolateGenerators` | `HashMap<NoiseKey,double[]>` → 线程内原始类型 `HiLoSums` 表（开放寻址 + 代际戳） | 同一采样顺序、同一累加表达式；溢出直算（等价于无记忆化的原始第二遍路径） |

## 数字（中位数，相对 round0 基线累计）

| 场景 | ns/op 基线 → 本轮 | 累计提速 | B/op 基线 → 本轮 | 累计分配降幅 |
|------|------------------|---------|------------------|-------------|
| cng-perlin2d | 78.1 → 67.4 | 1.16x | 32 → 0 | 100% |
| cng-noise3d | 225.6 → 207.4 | 1.09x | 40 → 0 | 100% |
| irare-pick | 273.2 → 243.4 | 1.12x | 32 → 0 | 100% |
| interp-bilinear-starcast6 | 4319 → 4376† | ~1.0x† | 3492 → **16** | 99.5% |
| interp-hermite | 3743 → 3666 | 1.02x | 2800 → **16** | 99.4% |
| worldcache2d | 13501 → 13597† | ~1.0x† | 8894 → **2268** | 74.5% |
| interp3d-trilinear（新） | — | — | — / **0** | — |
| interp3d-trilinear-starcast6（新） | — | — | — / **0** | — |
| **合计（17 场景 B/op 之和）** | | | **18818 → 4660** | **-75.2%** |

† 单线程 ±5% 抖动内。本轮主题是分配：每区块生成周期内的临时对象（varargs 数组、装箱、HashMap 节点、捕获 lambda）在全部测量热路上已消除或降至个数字节。

## 验证

- `verify.sh`：**17/17 场景 digest 位级一致**（含 2 个新增 3D 场景）。
- `bench.Verify3D`：3D 适配器 vs 原 lambda 链，600,000 采样位级零差异。
- 全量编译校验通过（1200+ 类）。

## 工程说明

- `iris.cache.dynamic` 系统属性随预分配一并移除（此前它只是关闭预分配的开关，现在恒为惰性；`iris.cache.fast` 仍有效）。
- 适配器池/memo 栈均为按需增长（初始 8 层，翻倍扩容），嵌套插值安全。
