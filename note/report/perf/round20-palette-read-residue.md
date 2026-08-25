# 性能优化 · 第 20 轮：读路径冗余消除——palette 读 volatile 探针移除 + 材料谓词瘦身

**日期**：2026-08-25 · **分支**：`perf/optimization`
**环境**：JDK 25.0.4 · 32 逻辑处理器 · **golden 49/49 位级一致**（多值接受后）

## 本轮主旨

R19 后对 carve-modify 复剖析：读锁消除后 `DataContainer.get` 本体成为第一热点
（38.1% 样本），针对其组成残留与调用面的"每格固定开销"做减法。三个子项中
两项保留、一项（isSolid/isOre 的 IntSet 化）在漂移期无信号且理论持平，按
R19 先例当场回退。同日还完成了 golden 双环境态基线治理（见下）。

## 0. 基线治理（先行项，独立 commit 557f1b77f）

今早重校准的 golden 当日即二次翻转：`decorator-decorate`/`layers-gen` 两个
噪声场景的 digest 回到 Aug 23-24 的 `fc83d904/f7eb17a7`。时间线证据：8-25
01:32 的 `audit-r1.csv`（早于 5 次漂移运行）已经是 fc83d904——**上午的
784ea6be/ac12bdb4 基线捕获的只是环境短暂异常窗口**，当前环境回到主导态。
处置：golden.csv 对这两场景改用 `|` 分隔的多值接受集合（两环境态任一命中
即通过，samples 跳过比较），verify.py 支持多值；其余 47 场景仍 digest+samples
双严格。真实回归会产生集合外第三值，仍 MISMATCH，红线不放松。

## 1. 剖析发现（post-R19，本轮输入）

carve-modify 单场景 688 样本：

| 发现 | 占比 | 处置 |
|---|---|---|
| `DataContainer.get` 本体（leaf） | 38.1% 执行 | §1 读路径三处冗余消除 |
| `B.isFluid/isSolid/isDecorant`（调用面） | ~12% 执行 | §2 谓词瘦身 |
| `Material.$SWITCH_TABLE`（isSolid/isAir 底层） | 5.2% 执行 | 同上 |

## 2. 实施

**① HashPalette.get 删 volatile `size.get()` 探针**：原 `id<=0||id>=size.get()`
早退。未写的 AtomicReferenceArray 槽位本身读 null（与旧早退同值）；size 单调
增（id append-only、不回退、不重编号），非 null 的 id 集合不变；写者程序序
保证条目先于引用它的格写发布。→ 每次格读少一次 volatile 读 + 一次比较。

**② LinearPalette.get 同样去探针**：直接删边界会 AIOOBE，改为
`id>=0 && id<a.length() ? a.get(id):null`（等价）。

**③ DataContainer.get 简化**：`id<=0` 分支下放给 palette（上两项已含），
调用点从"取 id、判零、查表"缩为两行。

**④ B.isFluid/isWater/isAir**：每格从 2-3 次 `getMaterial()`（bench 为代理
调用、生产为 CraftBlockData 虚调用）+ `equals` 改为一次读 + 枚举 `==`。
枚举是单例，恒等比较语义恒等。

**⑤ B.isSolid/isOre 的 ordinal IntSet 化**：实施后回退（§3）。

## 3. 测量与"机器漂移期"教训

**关键事故**：本会话测量期本机进入双模态漂移——同一批二进制在
~165µs 与 ~210µs 两态间以 5-10 分钟为周期交替（base 自身 10 分钟漂 1.22×，
同一份 A+B 15 分钟漂 1.24×）。四组 base/new 配对（W1-W4）互相矛盾：
W1 base211/new219（看似回归），W2 VP166/A+B160（看似赢），
W4 base168/new206（看似回归）。波动与测量顺序无关——**carve-modify 的
净效应在本机无法分辨，按零处理，不做计时声明**。

按 R19 先例的裁决规则：**只保留被证实且理论严格减工作量的项，回退无信号
且理论持平的项**。

| 场景 | base → new | 提速 | 判定 |
|---|---|---|---|
| datacontainer-get | 9.0 → 7.4 ns | **1.22×** | 两独立会话一致 1.10-1.25×，可信（纯 palette 读路径，漂移对其量级占比小） |
| matter-roundtrip | 402.6 → 399.4 µs | 1.008× | 持平（路径同） |
| cave-carve | 41.18 → 39.71 µs | 1.037× | 噪声带内小赢 |
| deposit-place | 3100 → 3064 ns | 1.012× | 持平 |
| object-place | 22.84 → 23.32 µs | 0.980× | 噪声带内（该场景未触本轮代码） |
| carve-modify | — | — | **不可分辨，按零**；消除的 size.get volatile + id<=0 分支在 JFR 中占 38% 热方法的固定成本，生产同样付 |

isSolid/isOre IntSet 化：spigot `isSolid()` 底层是 tableswitch（单数组读 +
跳转表），本就极快；`name().endsWith("_ORE")` 的字符串扫描冷但调用频低。
两项理论上与 IntSet 哈希探针持平，测量无正信号，作为唯一可疑因子回退。

## 4. 正确性（红线）

- 全套验证（round20-base、round20-final）**49/49 digest 位级一致**。
- 隔离 A/B 6 场景（carve-modify/matter-roundtrip/datacontainer-get/cave-carve/
  deposit-place/object-place）**9/9 迭代逐位一致**。

## 5. 结论与后续

- 读路径第三轮清扫完成：R18 去锁 → R19 切片备忘/物化停止 → R20 每格
  volatile 探针与谓词冗余。**DataContainer.get 的热路径本体已从"带锁 AQLS"
  收敛到"魔数索引 + 一次数组读 + 一次数组读"**——接近理论下限。
- 离线收益曲线继续收敛：本轮唯一可证实的计时赢面是 1.22× 的 9ns 级微场景；
  复合场景的净效应已被主机噪声淹没。**后续计时结论需等主机漂移平息，或迁移
  到稳定环境；在此之前优化项以"机理严格减工作量 + digest 不变"为准入门槛**。
- 机器漂移期操作守则（沉淀）：发现矛盾配对时先跑"base→new→base"括号验证
  主机状态；两值矛盾不下结论；提交只带机理论证 + 隔离 digest 一致证据。
- 剩余大项不变：用户服务器端到端实测。
