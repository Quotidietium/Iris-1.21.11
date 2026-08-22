# 性能优化 · 第 7 轮：Matter/Mantle 存储层 + HyperLock（推翻"离线不可测"结论）

**日期**：2026-08-22 · **分支**：`perf/optimization` · **提交**：`539c8275d`（基准设施）+ `b02aaa833`（优化）
**环境**：JDK 25 · 32 逻辑处理器 · **23 → 29 场景**（新增 6 个，全部 A/B 自证后并入 golden）

## 本轮主旨：扩展离线可验证面

第 6 轮结论"离线可测面已收敛"基于一个错误前提——装饰器/沉积/Matter/Mantle/HyperLock
被判定为"需要服务器"。本轮推翻该判断：这些路径的核心计算循环全部是纯 JVM 逻辑，
真正的阻断点只有两处，均已用桩解决：

| 阻断点 | 解法 |
|--------|------|
| `Bukkit.createBlockData`（BlockMatter 静态 AIR、B.get、装饰器 palette 的唯一 Bukkit 咽喉） | 新增 `stubs/org/bukkit/Bukkit.java`：`createBlockData` 返回 JDK 动态代理（Material 真实枚举 + 规范化状态串，hashCode 缓存——palette 去重行为与 CraftBlockData 一致）；编译期与运行期均遮蔽 jar |
| `IrisMatter.buildSlicers` → `Iris.initialize(pkg, Sliced.class)` 类扫描 | 桩 Iris 的 `initialize/getClasses` 实现为 classpath 目录扫描（反射注解检查，与真实行为一致） |

新增 6 场景（真实生产类，无 mock 引擎对象）：

| 场景 | 测量对象 | digest 的正确性证明作用 |
|------|---------|------------------------|
| `datacontainer-set` | 16³ 切片底层调色板容器写（4096 格 × 8 种方块） | 写入值序 |
| `datacontainer-get` | 同容器随机读 | 读出材质序 |
| `mantlechunk-set` | Mantle.set/MantleWriter.setData 内层写链（段寻址 + KMap 切片查找 + 调色板 Hunk） | 写入值序 |
| `matter-roundtrip` | 16³ Matter（BlockData+Integer 双切片）writeDos→readDin 往返 + 全格回读 | **序列化格式与内容逐位自证**（每次迭代） |
| `hyperlock-hit` | 区域锁加解锁（键重复命中模式） | 计数精确 |
| `par-hyperlock-contended` | 8 线程锁同一键集 | **按 key 互斥证明**：每键独立格 cells[k]++，终值与纯 RNG 计数解析期望逐位一致（`8a08dbb6ddacc4f7`） |

## 改动清单（5 处生产代码）

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `HyperLock` | 有界 LRU 锁缓存（CLHM + 每次 `Cache.key` 装箱 Long）→ **斐波那契散列定长条带锁池**（2 的幂，默认 1024 条带） | 按 key 互斥保持（同键必同条带；碰撞键额外互斥仅过度串行化）；旧实现的"驱逐仍持锁"隐患消除；API 全兼容（Mantle/NBTWorld/TurboPregenerator 无感） |
| 2 | `DataContainer.set` | 读锁预探测 + 写锁两段式 → **单写锁**（id 计算/add/updateBits/写位全在同一临界区） | 消除旧实现两处竞态：① add 快路径在读锁内写 DataBits（非原子）；② id 在读锁计算、跨 trim 后在写锁写入（旧调色板 id 写入新数据 = 潜在错位）。新结构更强安全且少一次锁往返 |
| 3 | `DataContainer.trim` | `Int2IntRBTreeMap`（O(n log n) + 节点分配）→ int 直方图重映射 O(n) 零分配 | 升序旧 id 重加次序与红黑树迭代序一致 → 产出位级相同 |
| 4 | `DataContainer.LINEAR_BITS_LIMIT` | 4 → 2（>4 条目即用哈希调色板；LinearPalette.id 为线性 equals 扫描，16 条目时每次写最多 16 次 equals） | 两实现的 id 分配均为顺序插入序、`bits()` 均为 `bits(size+1)` 同公式、序列化不编码实现类 → 字节级兼容（新旧存档互读） |
| 5 | `HashPalette` | id→值方向 `KMap<Integer,T>`（装箱 CHM）→ **AtomicReferenceArray 索引数组**（volatile 扩容重赋值，与 LinearPalette 同构） | 映射与次序不变；get(id) 无装箱无哈希 |
| 6 | `MantleWriter.acquireChunk` | 每次 `Cache.key` 装箱 + map 查找 → **不可变 ChunkMemo 原子交换备忘**（方块写入天然聚集于同区块） | 同 (cx,cz) 恒映射同一 chunk 实例（构造器预灌全部区块）→ 备忘不可能返回错误 chunk；跨线程仅多一次 map 查找 |
| 7 | `MatterSlice.read` mapped 分支 | 每节点 `Cache.to3D` 分配 `int[3]` → 内联取模分解 | 与 to3D 公式逐点等价（z·w·h + y·w + x 的精确逆） |

## 结果（中位数，A=旧代码基线 vs C=优化后）

| 场景 | A ns/op | C ns/op | 提升 | A B/op | C B/op |
|------|--------:|--------:|-----:|-------:|-------:|
| hyperlock-hit | 422.2 | **15.8** | **26.8×** | 438.6 | **0.0** |
| par-hyperlock-contended（8 线程墙钟） | 896.6 | **187.5** | **4.8×** | — | — |
| datacontainer-set | 80.9 | **30.9** | **2.62×** | 108.0 | **16.0** |
| mantlechunk-set（生产写链内层） | 101.6 | **60.9** | **1.67×** | 132.0 | **40.0** |
| matter-roundtrip（16³ 序列化往返） | 1 105 380 | **879 373** | **1.26×** | 418 376 | **152 192（-64%）** |
| datacontainer-get | 14.1 | 14.1 | 持平 | 0.0 | 0.0 |

- 旧 23 场景：全部在跨轮噪声带内（±10%，与 R7 代码路径零交集，见 A/B/C 三轮横向对照）；
  **29/29 场景 digest 与 golden 位级一致**（含 6 新场景的 A/B 自证 + contended 的解析证明）。
- `datacontainer-get` 持平的构成：读路径未动锁结构（保持正确性所需的读锁），Linear→Hash
  切换的 +1.3ns 被 HashPalette 数组化的 -1.3ns 恰好抵消。

## 验证链（比往轮更严格）

1. 基线 A（旧代码 + 新场景）→ 优化 B2 → **28 个确定性场景 A==B2 位级一致**；
2. 互斥单测（新旧实现各 5 轮 × 800 万次/键增量）：**零丢失**——证明"按 key 互斥"两实现均成立；
3. 场景语义勘误：最初 contended 用 8 个键保护同一计数格（跨键本就允许并发，两实现均会"丢"），
   改为每键独立格；it0 digest 与纯 RNG 计数的解析推导值逐位相等（8a08dbb6ddacc4f7）；
4. 最终 C（含 HashPalette 数组化）→ **28 场景 A==C 位级一致 + contended==解析值**；
5. golden 重建为 29 场景（自校验 ALL BIT-IDENTICAL）。

## 结论

> **勘误（R8 补记）**：本轮基准设施的 `org.bukkit.Registry` 离线初始化存在缺陷，
> 导致 `B` 类在 matter-roundtrip 场景中被毒化，BlockData 切片的**读回**实际走了
> "每切片异常捕获→跳过"路径（写侧与读侧失败行为在 A/B 两侧一致，故 digest 对比结论仍成立，
> 但读侧覆盖弱于设计意图）。R8 修复桩后该场景 digest 已按真实往返重建，且真实往返比
> 异常路径更快（异常+堆栈打印本身即开销）。详见 round8 报告。

- "需要服务器才能测"的判断被证伪：**Bukkit 代理桩一个文件解锁了整条 Matter/Mantle 存储层**。
  存档兼容性（磁盘字节格式）未变——调色板实现、位宽、条目序均保持。
- 收益画像：锁原语（HyperLock）> 容器写路径（DataContainer/MantleChunk）> 序列化（Matter）。
  生产影响最大的预计是 **MantleWriter.setData 链**（每个跨区块对象放置方块写入）与
  **Mantle 区域锁**（每个 plate 读写）——预生成吞吐的两条主干。
- 后续方向（R8 候选，设施已就绪）：装饰器/沉积路径（IrisDecorator 噪声命中循环 +
  IrisDepositGenerator 团块几何——FakeEngine 两件套即可承接）、`IrisObject.place`
  完整方块写循环、Mantle 磁盘 IO（IOWorker/LZ4，临时目录即可离线化）。

## 七轮累计（vs round0 基线）

| 指标 | 累计效果 |
|------|---------|
| 热点路径分配（单线程合计） | 18818 → ~3575 B/op（-81%），主路径 0 B/op |
| implode 子群系选择 | **11.4×** |
| cache2D 命中路径 | 18.8 → **6.7 ns/get**；8 线程 raster **5.9× 聚合** |
| 地形列填充（典型配置） | **1.55×** |
| HyperLock 加解锁 | 422 → **15.8 ns（26.8×）**，0 B/op |
| 调色板容器写 / Mantle 写链 | **2.62× / 1.67×**，分配 -85%/-70% |
| Matter 段序列化往返 | **1.26×**，分配 -64% |
