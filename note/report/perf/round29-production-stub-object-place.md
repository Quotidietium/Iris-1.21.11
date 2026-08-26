# 性能优化第 29 轮：object-place 生产形状桩 + 真实成本结构下的分配清扫

日期：2026-08-27 · 分支：`perf/optimization` · 主题：兑现 R25 遗留的"object-place 剖析失真于代理桩"——构建字段读型生产形状 BlockData 桩，重剖析真实成本结构，并基于它实施下一轮 object-place 分配优化。

## 第一部分：测量基建修正——BenchBlockData 生产形状桩

R25 发现 bench 桩 BlockData 为 JDK 动态代理：每次 `getMaterial()`（全仓 140 个调用点）都要付 Handler.invoke 反射分发 + 方法名字符串 switch，占 object-place 执行样本约 40%。生产 CraftBlockData 是字段读。

**新桩**（`benchmark/stubs/org/bukkit/BenchBlockData.java`）：具体类，`getMaterial()`/`getAsString()` 为 final 字段读；与旧代理**逐方法语义对等**（身份串构造、hashCode、equals、matches、merge、clone 独立实例、默认值），palette 去重与全部 digest 折叠值不变——换桩后 golden 49/49 位级一致验证通过。

**测量重定基**（非代码改进，是历史读数的修正系数）：

| 场景 | 代理桩读数 | 字段桩读数 | 修正量 |
|---|---|---|---|
| object-place | 24397 ns / 44712 B | 12135 ns / 12816 B | **时间 2.01×、B/op -71%**（旧桩虚增 B/op 3.5×） |
| object-place-stilt | 31645 ns / 45192 B | 20085 ns / 13416 B | 时间 1.57×、B/op -70% |
| matter-roundtrip | 132184 B/op | 87784 B/op | B/op -34% |
| mantlechunk-set | 16 B/op | 0 B/op | 归零 |

此前各轮 object-place 相关的分配结论（如 R21 的 -11%）分母都被代理桩虚增——按本桩读数重新理解。

## 第二部分：真实成本结构（JFR，字段桩下）

执行：57.3% place 主循环本体、9.2% VectorMap 迭代、8.4% isLocked + 6.6% canRotateX（旋转谓词链）、2.8% isVineBlock、0.9% clone。
分配（采样计数）：**ArrayList$Itr 5152（每方块迭代空 edit 列表！）**、**BlockPosition 4800（每方块 new 给 listener）**、BenchBlockData.clone 4364（R21 已文档化的正确性依赖，不动）、CHM EntryIterator/MapEntry ~1180。

两个真优化点（第三个 clone 不动）：

1. **空 edit 列表每方块分配 Itr**：`for (IrisObjectReplace j : config.getEdit())` 在列表为空时仍分配 ArrayList$Itr（KList 继承 ArrayList）。修复：循环外提升 + `isEmpty` 守卫。rng 中立：空列表从不消费 rng（`rng.chance` 只在非空时调用），行为零变化。
2. **每方块 `new BlockPosition` 给 listener**：生产仅两个 listener（MantleObjectComponent 写 matter、PlannedStructure 写 jigsaw 数据），都立即 `b.getX()/getY()/getZ()` 拆回 int。修复：新函数接口 `ObjectPlaceListener.onPlace(int x, int y, int z, BlockData)` 替代 `BiConsumer<BlockPosition, BlockData>`；IrisObject.place 第 8 参类型更新，3 个调用方（MantleObjectComponent、PlannedStructure、bench 记录器；CommandObject 传 null 不受影响）同步适配。core 内部 API 变更（红线允许：行为一致而非签名一致），BlockPosition 类保留他处使用。

## 结果（同桩同时窗 A/B，B/op 声明、时间在漂移带不声明）

| 场景 | 字段桩（修复前） | 修复后 | 代码修复效果 | 对旧代理桩读数总效果 |
|---|---|---|---|---|
| object-place | 12135 ns / 12816 B | 11738 ns / **5088 B** | **B/op -60.3%**（时间 0.967× 不声明） | 时间 0.481×、B/op 0.114× |
| object-place-stilt | 20085 ns / 13416 B | 20105 ns / **5688 B** | **B/op -57.6%**（时间 1.001× 持平） | 时间 0.635×、B/op 0.126× |

修复后 JFR 分配形状：BlockPosition **0**、ArrayList$Itr 125（噪声级，场景外路径）、BenchBlockData.clone 成为唯一大头（每方块 1 次，正确性依赖：加载缓存共享 + 旋转/编辑路径会 mutate）。

**golden 49/49 位级一致**（`round29-stubcheck.csv` 换桩验证 + `round29-post2.csv` 修复验证；中途一次 run 出现 plate-io 场景行缺失的瞬态文件异常，复跑 + 隔离跑均正常且 digest 一致，判定为基础设施毛刺非代码问题）。

## 过程事故与教训

1. **测量桩的成本结构必须接近生产**——代理桩把 object-place 的时间/分配各虚增 2×/3.5×，直接导致 R25 对旋转谓词链占比的高估（40% 伪影内含代理分发）。R25 的 RotationPlan 负结果结论（switch 分派劣化）在机理层面仍然成立（同桩双臂 A/B），但"谓词链占 10.8%"的样本占比在新桩下重估为 15%（相对占比上升，因分母变小）。
2. 修 API 时全仓 grep 调用方不够——编译器才是全集（PlannedStructure 的调用点是编译错误暴露的，不是 grep 找到的；grep 模式漏了 jigsaw 路径）。

## 下一轮候选（记录）

- VectorMap 游标迭代的 CHM EntryIterator/MapEntry 残留（~1046+369 采样，第二大分配源）——查 cursorIterator 内部为何仍走 Entry 视图。
- 旋转谓词链在新成本结构下占 15%——R25 的计划化已证伪，但"spinx/spinz 的每块重算"类方向未试（需先证 rng 中立）。
- d.clone() 为剩余 B/op 大头但属正确性依赖，除非引入"不可变对象标记 + 共享放置"机制（改动面大，风险高，暂不动）。

## 改动清单

| commit | 内容 |
|---|---|
| `401d50215` | BenchBlockData 字段读桩 + Bukkit 工厂切换（测量重定基） |
| `21244a3c2` | ObjectPlaceListener int 化 + 空 edit 跳过（B/op -60.3%/-57.6%） |
| docs | 本报告 + README/release note/memory |
