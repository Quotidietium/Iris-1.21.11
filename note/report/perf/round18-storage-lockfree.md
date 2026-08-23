# 性能优化 · 第 18 轮：存储底座——hunk 容器读路径无锁化 + 位宽增长重打包

**日期**：2026-08-24 · **分支**：`perf/optimization`
**环境**：JDK 25 · 32 逻辑处理器 · **golden 49 场景全部位级一致**

## 本轮主旨

R17 之后对四个最重生产路径场景（carve-modify / matter-roundtrip / cave-carve /
perfection-modify）逐一 JFR 重剖析（R16 工具，精确名字过滤）。发现此前面轮次未触及的
**存储底座问题**：`DataContainer`（所有 palette hunk / Matter slice / worldcache 的
底层容器）的**每次单格读都要加解锁一把 ReentrantReadWriteLock 读锁**——仅 unlock 的
`AQLS.signalNext` 路径就占 carve-modify 执行样本 29.4%。本轮消除该锁（seqlock 化），
并把 palette 位宽增长的全量拷贝改为字级流式重打包。

## 1. 剖析发现（4 场景，修正代理伪影后）

| 场景 | 发现 | 定性 | 处置 |
|------|------|------|------|
| carve-modify | `DataContainer.get` 读锁 unlock（signalNext）29.4% 叶帧；整个 get 路径（含锁）44.6% | **真实生产成本**（每 cavern 格 1 次 iterate 读 + 4 次邻居探测，全部穿锁） | **本轮修复** |
| matter-roundtrip | `DataBits.setBits`（palette 位宽增长全量拷贝）16.6% 执行 / `DataContainer.get` 读回 16.7%；`HashPalette.id` 处 Integer 装箱 32.6% 分配 | setBits 与读回为真；装箱经栈核查 = `Proxy24.hashCode()` 伪影（生产为预计算字段） | **本轮修复**（前两者） |
| cave-carve | `MantleWriter` 构造器每 op 预取 169 区块（KMap 装箱 + future/semaphore）~35% 分配；`PaletteHunk.iterateSync` 31.5%（每格穿锁的 get） | 构造器预取为真实成本但生产半径更小（见 §6）；iterateSync 受益于锁消除 | iterateSync **随本轮修复**；构造器搁置 |
| perfection-modify | 92% 样本在 `$Proxy24.getMaterial` / `Bukkit$BlockDataHandler` / `StringLatin1.hashCode` | 纯 bench 代理伪影（R16 已记录，生产 `CraftBlockData.getMaterial` 为字段读） | 不动 |

## 2. 改动一：`DataContainer.get` 无锁化（seqlock 快照校验）

原实现每次 get 都 `read.lock(); …; read.unlock()`。RW 锁读侧在无争用时也要走
AQLS acquireShared CAS + releaseShared 的 signalNext 检查——比容器查询本身还贵。

新实现（`core/.../hunk/bits/DataContainer.java`）：

- 新增 `volatile int structureVersion`。**仅有的两处成对交换 (data, palette) 引用**
  （`updateBits` 的位宽增长交换、`trim` 的规范化重建）在写锁内以
  `version++（变奇）→ 交换 → version++（变偶）` 双段围栏。
- `get` 读循环：读版本（须偶）→ 快照 `d=data; p=palette` → `d.get(position)` +
  `p.get(id)` → 复核版本未变则返回，否则重试。删除 `read` 锁字段。

**内存序论证**（红线核心，完整版见提交信息）：

1. *唯一撕裂窗口*是读到"新 data + 旧 palette"混合对（两字段先后赋值）。两处交换
   均有版本围栏，读者复核未变 ⇒ 快照对一致。交换只安装**全新对象**（无 ABA；
   `setBits(bits==当前)` 返回 this 的场景引用不变、语义不变）。
2. *格写*（`set`，写锁内）不需要围栏：`AtomicLongArray` 每 long 原子；palette id
   **只增不改值**；新 id 的 palette 条目先于格写发布，读者经格写 volatile 读建立
   happens-before 后必然可见。
3. *锁内不变量*不变：写者间互斥保留（`DataBits.set` 是 get-then-set RMW，必须互斥），
   `writeDos`/`trim` 语义不变；读者从"阻塞到写完"变为"读到一致快照"，值空间等价。
   R16 划定的"DataBits volatile 语义不动"红线未被触碰——没有任何 volatile 被降级，
   反而新增了围栏。

### 2.1 竞争验证器 `VerifyContainerRace`（bench 侧，非 golden）

4 族 × 4096 格互斥标记（16384 palette 条目 → 全程位宽交换 + trim 重映射风暴），
2 写者整版翻转 + 1 trimmer（`writeDos→trim`）+ 6 读者断言"每格只出现本格合法值"
（撕裂读必然映射出他格值）。**3 遍各 ~28 亿次读，0 违规**；跑后全格一致性通过。

## 3. 改动二：`DataBits.setBits` 增长路径字级重打包

原实现增长拷贝逐格 `get(i)`/`set(i)`——每格两次 `Validate.inclusiveBetween` +
魔数除法 cellIndex。新实现（仅 `newBits > bits` 的增长方向）：源 long 一次读出、
逐槽移位抽取，目的 long 就地累积、满字一次写入——**值序与原逐格循环完全相同**
（源字前向消费、目的字按同一索引序填充）；位提取用 `>>>`（掩码后与原 `>>` 逐位等价，
论证见提交信息）。窄化方向保留原逐格路径（含 Validate 语义）。

## 4. 正确性（红线）

- **全套 49/49 场景 digest 位级一致**（round18-a）。
- 隔离 A/B 12 场景（carve-modify、matter-roundtrip、cave-carve、datacontainer-get/set、
  mantlechunk-set、worldcache2d(-hit)、deposit-place、object-place、layers-gen、ctx-fill）
  **9/9 迭代逐位一致**（A=round17 基线 worktree，B=本轮）。
- `VerifyContainerRace` 3×28 亿读 0 撕裂读。
- object-place 首遍隔离 0.90×经双遍复跑为 1.05-1.17×（进程间波动，R16 已知特性）。

## 5. 性能（隔离 A/B 中位数，后 5/9）

| 场景 | r17 | r18 | 提速 | 备注 |
|------|----:|----:|-----:|------|
| carve-modify | 352218 / 346075 | 253609 / 222193 | **1.39× / 1.56×** | 两遍独立隔离 |
| matter-roundtrip | 671904 / 606151 | 546532 / 484083 | **1.23× / 1.25×** | 读回无锁 + 增长重打包 |
| cave-carve | 53427 | 43525 | **1.23×** | iterate/邻居探测穿锁消除 |
| datacontainer-get | 13.3 | 8.8 | **1.51×** | 纯容器读 |
| datacontainer-set / mantlechunk-set | — | — | 0.98-1.00× | 预期持平（写锁保留） |
| worldcache2d / -hit / deposit / layers-gen / ctx-fill | — | — | 0.97-1.04× | 噪声带 |
| object-place（复跑双遍） | 32000/30561 | 27440/29079 | 1.05-1.17× | |

全套表（round17-b vs round18-a）出现未触及场景 ±20% 漂移（deposit 0.62、
perfection 0.74 等），与隔离结果（1.00/0.98）矛盾——**全套数字只作方向参考，
计时结论以隔离表为准**（R14 起确立的 JIT 画像方法论；本机当日全套漂移偏大）。

## 6. 负结果与搁置项（防未来误优化）

1. **增长松量化（bit slack）被否决**：预研发现 trim 早退条件依赖
   `data.getBits() == bits(size+1)` 的不变量；松量后 writeDos 需额外一次规范化重建，
   对 9-32 个条目的常见小调色板（块切片）反而 2 次拷贝 > 原 1 次。字级重打包是
   无序列化影响的统一赢面。
2. **`IrisCarveModifier.walls` KMap 不可原语化**：CHM 迭代序决定 RNG 消耗序
   （R11 已证），换原始键结构必然改变输出。红线内不可动。
3. **MantleWriter 构造器预取**：`getChunks` 是 future 异步的（消费者可在 IO 线程
   并发回调），非多核路径的 temp KMap 必须保留；生产每区块一次 writer、半径更小，
   收益缩水，搁置。
4. **perfection-modify**：bench 代理伪影主导（92%），离线无可测真实热点。

## 7. 工具与事故记录

- `VerifyContainerRace`（并发正确性压测，独立 main，不进 golden）。
- 事故：一次 `build.sh | tail -1` 管道掩盖了 javac 失败退出码（`DataContainer.Writable`
  错误限定名），产生 442 个类的半成品 classes 目录。**确认无计时污染**（期间未跑任何
  基准）；教训：构建管道必须检查 `PIPESTATUS` 或去掉 tail。

## 结论

- 存储底座读路径锁消除 + 增长重打包落地：**49/49 位级一致**、84 亿次竞争读零违规；
  carve-modify **1.4-1.56×**、matter-roundtrip **1.23-1.25×**、cave-carve **1.23×**、
  纯容器读 **1.51×**。所有 palette hunk 的读（mantle 读取、修饰器、序列化读回、
  iterate、worldcache）每读省一对锁开销。
- 剩余大项不变：用户服务器端到端实测。
