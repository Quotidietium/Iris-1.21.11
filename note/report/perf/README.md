# 性能报告索引（note/report/perf）

| 报告 | 内容 |
|------|------|
| [round0-baseline.md](round0-baseline.md) | 基线测量 + 代码级热点证据 + 方法论 |
| [round1-cpu-hotpath.md](round1-cpu-hotpath.md) | CPU 热点：CNG 定参/插值备忘/implode 缓存（11.4×）|
| [round2-memory.md](round2-memory.md) | 内存/分配：合计 -75.2%，3D 0 B/op |
| [round3-concurrency.md](round3-concurrency.md) | 并发/共享缓存：Caffeine、并行扩展 2.3×→3.6× |
| [round4-datastructures.md](round4-datastructures.md) | 数据结构/装箱：条带原始键缓存重写，命中 2.8×、8 线程 raster 62.8×、分配再 -53% |
| [round5-terrain-actuator.md](round5-terrain-actuator.md) | 地形执行器：列循环不变量提升 + 矿石预检，1.55×/列 |
| [round6-dispatch.md](round6-dispatch.md) | 派发扁平化：<1%（当时的结论——离线可测面收敛） |
| [round7-matter-mantle.md](round7-matter-mantle.md) | Matter/Mantle 存储层 + HyperLock：推翻"需服务器"判断（Bukkit 代理桩），锁 **26.8×**、容器写 **2.62×**、序列化往返 **1.26×/-64% 分配** |
| [round8-object-place.md](round8-object-place.md) | 对象放置写路径：IrisObject.place 五层防御克隆链塌缩——树放置 **2.44×**、stilt **2.11×**，分配 -33%/-41% |
| [round9-decorator.md](round9-decorator.md) | 装饰器路径：partOf 分区缓存 + gRNG/选择循环零分配化 + 白名单流消除——选择 **1.11×**、分配 -38%~-53%；JIT 画像伪回归鉴别方法 |
| [round10-deposit.md](round10-deposit.md) | 沉积放置写路径：toDeepSlateOre 急切 BlockData 表（消除每块 Material.values() ~8KB 克隆）+ VectorMap 单遍历 + hunk 双读提升——**8.08× / -99.4% 分配** |
| [round11-carve.md](round11-carve.md) | 洞穴雕刻双向：IrisMatter.slice 捕获 lambda 消除（JFR 57% 权重，外溢惠及沉积 -56.6%）+ CarveModifier 列数组/首触序重建 + 形状栅格 pow 备忘——carve 读 **1.07× / -64.4% 分配** |
| [round12-postperfection.md](round12-postperfection.md) | 后处理阶段：Perfection 多核竞态修复（burst.complete 缺失 + 共享列表）+ Post 不变量提升——时间持平，正确性收益；负优化（扫描融合）鉴别并回退 |
| [round13-heightlayers.md](round13-heightlayers.md) | 高度采样与铺层：AtomicCache.peek() 命中路径加固（98 处 aquire-lambda 模式清扫热链）+ 层循环提升——持平（EA 已消除小链路分配；对内联退化画像免疫），覆盖封口 39 场景 |
| [round14-coroutines.md](round14-coroutines.md) | 每区块协程开销消除：引擎 4 个 Kotlin 类 Java 化——ChunkContext 1536 launch/区块→96 行任务（**4.23×**）、FlaggedChunk 256 Mutex/块→1 锁、MatterGenerator 去 runBlocking；stub 全删、golden 42 场景 |
| [round15-fillfanout-ioaudit.md](round15-fillfanout-ioaudit.md) | 预填充扇出调参（每流任务 160.7µs vs 96 行任务 95.9µs，负结果）+ 组件复用预填充值不可行证明（context 属中心区块）+ plate-io 封口 Mantle IO（42.4ms/op，双写为崩溃持久性设计）——golden 43 场景，离线可测面收敛 |
| [round16-hotpath-reprofiling.md](round16-hotpath-reprofiling.md) | 全面重剖析（JFR 工具化，两次剖析污染自我纠错）+ 每块分配/查找清扫：VectorMap 游标化、layer 每层单 RNG、CNG 固定元数 fit、7 站点 peek-first——layers 1.09×/-69% B、stilt 1.10×/-35% B；**R15 plate-io golden 跨进程缺口封死**（createdAt 墙钟根因）；2D 噪声实走 3D 内核记录为永久禁区 |
| [round17-taskgranularity.md](round17-taskgranularity.md) | 饱和池形状基准确立（ctx-fill-par，8 并发区块）+ 预填充任务粒度调优：96→**24 任务/区块**（每任务 4 行）——单区块 **1.31×**、预生成预填充吞吐 **1.25×**，48/48 位级一致；g8 饱和崩塌（0.63×）复现 R15 钉死机理于粗粒度；cc+g24 混合负结果（105µs vs 82.5µs，机制优势不跨粒度叠加）golden 49 场景 |
| [round18-storage-lockfree.md](round18-storage-lockfree.md) | 存储底座：DataContainer 每格读写锁消除（seqlock 快照校验，读锁 unlock 独占 carve-modify 29.4% 样本）+ DataBits 位宽增长字级重打包——carve-modify **1.4-1.56×**、matter-roundtrip **1.23×**、cave-carve **1.23×**、容器读 **1.51×**；VerifyContainerRace 竞争压测 3×28 亿读 0 撕裂；49/49 位级一致 |
| [round19-lookup-residue.md](round19-lookup-residue.md) | 读路径残留：MantleChunk.get 停止物化空切片（R11 section 级修正补全到 slice 级）+ carve 迭代器 section/slice 备忘——carve-modify **1.09×**；palette id 末值备忘测得 set 路径 10% 回归（内联预算机理）当场回退 |
| [round20-palette-read-residue.md](round20-palette-read-residue.md) | palette 读路径 volatile 探针移除（HashPalette/LinearPalette 每次格读的 size.get）+ air/fluid 材料谓词瘦身——datacontainer-get **1.22×**（两会话一致）；isSolid/isOre IntSet 化无信号回退；carve-modify 净效应被主机双模态漂移（~1.2×、5-10min 周期）淹没按零计；**golden 双环境态多值基线治理**（同日二次翻转证据链） |
| [round21-object-transform-inplace.md](round21-object-transform-inplace.md) | 对象放置残留：rotate/translate 每块防御克隆消除（就地变换，调用方审计证明不可观测）——object-place 分配 **-11.0%**、stilt **-14.2%**（B/op 漂移免疫）；时间读数在漂移带内不做声明；place 每块 `d.clone()` 确认为正确性依赖不动 |
| [round22-carve-iterate-native.md](round22-carve-iterate-native.md) | carve 迭代链原生化（`Consumer4I` + `Hunk.iterateSyncInts` 五实现 + `MantleChunk.iterateInts`，遍历序不变）+ CaveZone 复用——carve-modify 分配 **-7.1%**（29/29 样本），与 JFR 分配占比 7.24% 精确吻合；walls/positions CHM 因 rng 顺序依赖结构性保留；前置证伪 VectorMap `entrySet().forEach` 方向（CHM.forEach 本就零分配，EntrySetView 每条目 new MapEntry） |
| [round23-cave-set-packing.md](round23-cave-set-packing.md) | cave 写路径集合代数 packed-long 化（setNoiseMasked filled 链：mask 预转换 + LongOpenHashSet 球填充，零 IrisPosition/Node）——cave-carve 分配 **-8.4% 中位（9/9 seed）**，CNG 纯度+集合等价 200 试验+位域往返 8.1M 组合三层机器验证；perfection 谓词合并无信号（JIT CSE 已消除）当场回退；位域 unpack 两次符号扩展事故与修复记录 |
| [round24-mantlewriter-constructor.md](round24-mantlewriter-constructor.md) | MantleWriter 构造器装箱中转消除（单线程路径临时 KMap 169×Long+Node+putAll → 直接 Long2ObjectOpenHashMap 原生 put）——cave-carve 分配 **-34~40%（9/9 seed，两轮累计约 -45%）**；R23 剖析 lambda$new$0 21% 分配之谜底；worm 重构因容量常数差致方向混合按纪律回退（初始容量/对象布局是可测量的记录） |
| [round25-rotation-plan-rejected.md](round25-rotation-plan-rejected.md) | **负结果轮**：RotationPlan 计划化（place 级旋转预解析）经 60000 位级验证 + 同时段 A/B 测得 object-place **9/9 更慢（中位 +8.6%）** 当场回退——剖析样本占比≠可回收成本（分支预测完美的谓词链是被剖析器看见的便宜代码），与 R6/R23 同族教训第三账；object-place 重剖析遗留发现（String 伪影 40%、CursorIterator 本质） |
| [round26-memory-boundedness.md](round26-memory-boundedness.md) | **内存有界性轮**（实机泄漏反馈驱动）：Mantle 板块驻留硬上限（超限时强制标记最旧板块卸载，绕过 4s 闲置底线——软限在持续生成下永不触发，实测 126 板块/243MB 线性增长 vs 修复后恒 6/13.7MB）+ 持续堆压 backstop（>45% 两 tick 压至 limit=1）+ **三处隐藏生产 bug**：CavernMatter null NPE 致板块永不能写盘、R24 引入的 MantleWriter 单线程路径 ioBurst 并发 put 损坏 fastutil 表（丢条目→pin 永久泄漏）、carve/custom/构造器 4 条 use() 泄漏路径 try/finally 封堵；新验证器 `bench.VerifyMemoryBound`（settle 零钉住=泄漏端到端审计）；golden 49/49 位级一致，B/op 零回归 |
| [round27-burst-exception-propagation.md](round27-burst-exception-propagation.md) | **稳定性基建轮**：BurstExecutor.complete 吞异常修复——join-all 屏障（R12 契约）+ 首失败解包重抛 + futures 恒清理（幂等重试）+ 逐 future 捕 Throwable（**JDK 陷阱：ForkJoinTask.get() 直接重抛反射重建的未检查异常、不包 ExecutionException 且丢身份**）；29 处调用方审计全分类安全（多核失败首次与单核同等可见：红陶标记路径）；`bench.VerifyBurstException`；golden 49/49，B/op 全 1.000× |
| [round28-trim-zero-limit-semantics.md](round28-trim-zero-limit-semantics.md) | **语义修正轮**：Mantle.trim 除零怪语义——limit=0 时 (loaded-0)/0.0=Infinity（loaded=0 时 NaN）被 Math.max 顶到 4000ms floor，`trim(0,0)` 的 flush-now 意图静默变 flush-after-4s（IrisPregenerator 停止路径 + R26 settle 曾被迫 sleep 4.5s 规避）；limit<=0 现显式跳过超限修正，VerifyMemoryBound 去 sleep 双臂立即清零；golden 49/49 |
| [round29-production-stub-object-place.md](round29-production-stub-object-place.md) | **测量修正+分配清扫轮**：BenchBlockData 字段读桩替代 JDK 动态代理（R25 的 40% 剖析伪影；**测量重定基：object-place 时间 2.01×/B/op -71%、matter-roundtrip B/op -34% 为历史读数修正系数**）；真实成本结构下两个每方块分配源消除——空 edit 列表 Itr + listener BlockPosition（ObjectPlaceListener int 化，MantleObjectComponent/PlannedStructure 两生产 listener 均立即拆箱）；object-place B/op **-60.3%**、stilt **-57.6%**（同桩 A/B）；golden 49/49 |

- 原始数据:`benchmark/results/round*.csv`(每场景 5 次测量)
- 金样本:`benchmark/golden/golden.csv`(**49 场景**固定种子摘要,行为一致性的判定基准)
- 复现:`bash benchmark/build.sh && bash benchmark/run.sh <csv> 3 5 && bash benchmark/verify.sh benchmark/golden/golden.csv <csv>`
- 隔离跑(排除 JIT 画像污染):`java -Dbench.filter=<子串> ... bench.Benchmark <csv> 3 9`(见 round9 方法论节)
- 版本改动汇总：[../../release/3.9.3-1.20.1-1.21.11.md](../../release/3.9.3-1.20.1-1.21.11.md)
