# 性能优化 · 第 12 轮:后处理阶段(Perfection 竞态修复 + Post 不变量提升)

**日期**:2026-08-23 · **分支**:`perf/optimization`
**环境**:JDK 25 · 32 逻辑处理器 · **36 → 37 场景**(perfection-modify,A/B 自证后并入 golden)

## 本轮主旨

审计引擎流水线中尚未覆盖的最后两个修改器(`IrisPerfectionModifier`、`IrisPostModifier`)。
Perfection(装饰物支撑修整,每区块多遍扫描)发现**一处真实并发缺陷**:

1. **多核模式任务从不等待**:`burst.queue()` 提交 16 条异步任务后,`while(changed)` 循环
   既不等待 future 也不感知完成——直接退出并把 hunk 交还给下一引擎阶段
   (insertMatter/custom)。后台任务与后续阶段**并发改写同一区块 hunk**,且共享的
   `surfaces`/`ceilings` 列表被 16 条并发任务同时 clear/add(数据竞态)。
   对照:`IrisDepositModifier`/`IrisCustomModifier` 均正确调用 `burst.complete()`,
   证实 Perfection 属疏漏而非约定。
2. `ceilings` 列表只写不读(死代码)。

红线优先(安全性/稳定性):修复竞态;性能侧做 Post 的不变量提升。单核路径
(队列内联执行)行为逐位不变,由新场景 digest 证明。

## 新场景

| 场景 | 配置 | digest 证明 |
|------|------|------------|
| `perfection-modify` | 真实 `IrisPerfectionModifier.onModify`(单核确定性队列;Engine 代理仅 hard-map getMetrics/burst→MultiBurst.burst)。地形:20-49 高石+草皮列,三类装饰物——草上合法 poppy(保留)、悬空 poppy(首遍移除)、poppy 压 poppy(首遍移除顶层,触发第二遍确认循环) | hunk.listen 的 AIR 清除写序;跨迭代/跨种子恒定 |

## 改动清单(2 个生产文件)

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `IrisPerfectionModifier.onModify` | 每 while 遍末尾补 `burst.complete()`(单核 no-op,多核等待本遍任务);`surfaces` 移入任务 lambda(每任务局部,消除跨任务竞态);`ceilings` 死列表删除 | 单核:complete() 为 no-op、列表本就按列 clear、ceilings 无读者——写序逐位不变(digest 证明)。多核:从竞态未定义行为变为与单核等价(各任务列带不相交) |
| 2 | `IrisPostModifier.onModify/post` | AtomicInteger 循环变量 → 普通 int;`isPostProcessingWalls()/isPostProcessingSlabs()/getFluidHeight()` 提出 per 列循环为参数 | 维度标志为引擎级常量;循环次序与取值不变。此文件无场景覆盖(需 IrisComplex 代理),属平凡变换 |

**负优化教训(已回退)**:曾把 getHeight 顶扫描与 transition 扫描融合为单遍
(从 hunk 顶开始)——实测隔离 **-6%**(310→336µs)。原因:旧 getHeight 只扫空气
半段、transition 只扫实体半段,合计本就一列一遍,融合无节省,反而恶化了内联
画像。回退后时间持平。融合版在所有空气列上的 surfaces=[] vs [0] 边缘差异亦随之
消失。

## 结果

### 隔离跑(3 预热 + 9 迭代,后 5 次中位数)

| 场景 | A ns/op | B ns/op | 提升 | A B/op | B B/op |
|------|--------:|--------:|-----:|-------:|-------:|
| perfection-modify | 308 307 | 310 176 | 1.00×(持平) | 2 424 | 3 800(+1 376) |

- +1 376 B/op 是竞态修复的价格(每任务每遍局部列表,多核正确性所必需);
  在 300 µs 级 op 上对 GC 的影响可忽略。
- **digest 逐迭代 9/9 一致**(A↔B);全套 36 旧场景位级一致。

### 全套跑

perfection-modify 全套 ~370 µs(画像污染,隔离为准);其余 36 场景 digest
全部位级一致,时间波动带内(±10%)。

## 结论与后续

- 本轮以正确性为主收益:多核模式下 Perfection 不再与后续阶段竞态改写 hunk、
  不再泄漏未等待的任务波;单核行为零变化。
- Post/Perfection 的剩余成本是语义必需的全 hunk 扫描(装饰物支撑检查)。
- Custom 修改器审计通过(已用 complete() 正确模式,CUSTOM_ACTIVE 门控)。
- R13 候选:processZone 的 generateLayers/铺墙路径(需真实 IrisBiome 层配置的
  场景);KMap 顺序等价预容量;用户服务器端到端实测仍是最大缺口。
