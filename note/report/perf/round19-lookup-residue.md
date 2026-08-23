# 性能优化 · 第 19 轮：读路径残留——空切片物化停止 + 迭代器切片备忘（palette 备忘负结果）

**日期**：2026-08-24 · **分支**：`perf/optimization`
**环境**：JDK 25 · 32 逻辑处理器 · **golden 49 场景全部位级一致**

## 本轮主旨

R18 之后对 matter-roundtrip / carve-modify 复剖析，针对"每次查找/每格的映射开销"
提出三项候选，实施后经隔离 A/B **二分鉴别**：两项保留（carve-modify 1.09×，
读路径物化消除），一项测得 10% 回归当场回退（R12 先例的重演，机理值得记录）。

## 剖析发现（post-R18，本轮输入）

| 发现 | 占比 | 处置 |
|---|---|---|
| `HashPalette.id` 内 CHM.get（fill 路径每格） | matter-roundtrip 27.7% 执行 | 末值备忘——**负结果，回退**（§3） |
| `Matter.slice` 邻居探测每格 4 次切片映射查找（carve-modify） | 9.8% 执行 | 迭代器 section 备忘——**保留**（§1） |
| `MantleChunk.get` 用创建型 `slice(type)`——**读路径物化空切片**（R11 修了 section 级，slice 级漏网） | 隐藏成本 | `getSlice` 判空——**保留**（§2） |

## 1. `IrisCarveModifier` 邻居探测切片备忘（保留，carve-modify 1.09×）

`onModify` 迭代器每 cavern 格 4 次 `mc.get(±x/±z)` 邻居探测，每次内部 = section
数组读 + 切片 map 查找。`MantleChunk.iterate` 按 section 逐层遍历，section 在长
游程内不变——新增 per-onModify `CavernMemo{section, slice}` 持有者（onModify 每
区块单线程），探测退化为 int 比较 + 切片 get。语义与 `MantleChunk.get` 逐一
相同（缺 section/缺切片读 null）。

## 2. `MantleChunk.get` 停止物化空切片（保留，防御性）

原 `matter.slice(type).get(...)` 中 `slice(type)` 是创建型查找：读一个"有 matter
但无此切片类型"的 section 会在**读路径**上分配并插入空 MatterSlice。空切片读值
（null）与无切片完全一致——改 `getSlice` 判空后读结果零变化，同时消除读路径上
的 map 变更与分配。R11"停止物化空 section"补全到 slice 级。bench 中 carve 场景
的 section 恰好都有 cavern 切片（探测 y 与 cavern 同层），故该收益在生产只读
路径（如 `isCarved` 探测无 cavern 的 section）体现，bench 不可见。

## 3. Palette `id` 末值备忘——负结果与机理（回退）

设计：`HashPalette`/`LinearPalette` 增 `lastT/lastId`（`id()` 唯一调用方
`DataContainer.set` 持写锁，单写者；实例内 id append-only，备忘不可能答错）。
预期生产命中形态：IrisCave 复用同一 `MatterCavern` 实例写数千格、地形 stone/dirt
连续段。

隔离实测（8 场景 9/9 digest 一致前提下）：**carve-modify 0.88×、
matter-roundtrip 0.90×——set 热路径整体回归 ~10%**，而两者在 bench 中备忘命中率
都接近零（随机填充）。回退备忘后即刻恢复（carve-modify 224.7µs）。机理判定：
`id()`/`add()` 体量增长把 `DataContainer.set`（及其 CHM 查找链）推出 JIT 内联
预算——每 set 付出真实调用开销，远超备忘省下的比较。这与 R16"EA 覆盖随调用点
复杂度退化"同族：**热路径方法的体量本身就是性能资产，微优化不许推高它**。
（object-place 的 B/op ±3312 漂移在全套与隔离跑中方向相反，亦为内联画像上下文
依赖的佐证。）

## 4. 正确性（红线）

- 全套验证（round19-a）**49/49 digest 位级一致**。
- 隔离 A/B：8 场景（含备忘版）+ 回退后 4 场景复测，**9/9 迭代逐位一致**。

## 5. 性能（隔离 A/B 中位数，后 5/9，最终代码 vs R18 基线）

| 场景 | R18 | R19 | 提速 |
|------|----:|----:|-----:|
| carve-modify | 244561 / 240060 | 224681 | **1.09×** |
| cave-carve | 41184 | 40663 | 1.01× |
| object-place | 27076 | 27234 | 0.99× |
| matter-roundtrip | 549973 | 496453 | 持平（代码路径与 R18 一致，带宽内波动） |
| datacontainer-set / deposit-place / mantlechunk-set | — | — | 0.96-1.01×（噪声带） |

## 6. 结论

- 读路径残留清扫完成两项：迭代器切片备忘（carve-modify **1.09×**）+ 空切片物化
  停止（生产防御性收益）；palette 备忘按证据回退并沉淀"内联预算"机理。
- 离线可测面收敛确认：剖析驱动的单项收益已降至 ~10% 量级，且伴随画像敏感风险。
- 剩余大项不变：用户服务器端到端实测。
