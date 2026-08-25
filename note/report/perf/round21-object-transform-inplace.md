# 性能优化 · 第 21 轮：对象放置残留——rotate/translate 每块防御克隆消除

**日期**：2026-08-25 · **分支**：`perf/optimization`
**环境**：JDK 25.0.4 · 32 逻辑处理器 · **golden 49/49 位级一致**

## 本轮主旨

object-place-stilt 是 R8(克隆链塌缩)后无人触碰的重量级场景。JFR 剖析
（3951 样本）定位 R8 未覆盖的残留克隆：`IrisObjectRotation`/`IrisObjectTranslate`
自身的每块防御性 `clone()`。

## 1. 剖析发现

| 发现 | 占比 | 处置 |
|---|---|---|
| `Vector.clone` leaf | 9.1% 执行 | §2 就地变换 |
| `IrisObjectRotation.rotate` 分配 | 21.1% 分配样本（0.79 MB) | 同上 |
| `IrisObject.place` 本体 leaf | 29.1% 执行 | 本轮无安全切入（见 §4) |
| bench 桩伪影（`Bukkit.proxy` 9.6%、`AbstractStringBuilder` 5.5%、`$Proxy24.getMaterial`) | — | 记录，非生产成本 |

## 2. 实施

**`IrisObjectRotation.rotate(BlockVector, spinx, spiny, spinz)`**：入口
`b.clone()` 删除，旋转变换改为就地（`v = b`）。

**`IrisObjectTranslate.translate` 两个重载**：`clone().add(...)` → 就地 `add`。

**调用方审计（契约论证）**：全仓（core + nms）向量 rotate 的生产调用点仅
IrisObject(963/1095 热循环 + 677/706/728/755/780 的 fresh 向量）与
IrisObjectTranslate 内部；translate 调用点仅 IrisObject 三处。热循环中的
用法是纯赋值链 `i = translate(rotate(gVec, ...))`——rotate 返回的向量被
translate 就地修改后赋给 `i`,**原值在调用后无任何读者**，克隆不可观测。
bench 的 tree 配置 "Y-axis spin active"，旋转路径被 golden digest 覆盖——
49/49 位级一致即数值等价证明。

## 3. 测量

| 场景 | 时间 | 分配（B/op) | digest |
|---|---|---|---|
| object-place | 0.979×(9/9 一致方向） | 50392 → 44832(**-11.0%**) | 9/9 一致 |
| object-place-stilt | 1.009×(9/9 一致方向） | 56512 → 48504(**-14.2%**) | 9/9 一致 |

时间读数仍卡在本机漂移带（两场景方向相反、幅度 ~2%，与 R20 观察到的
~1.2× 双模态漂移同尺度）；**B/op 是漂移免疫指标**，-11%/-14% 与"每块
少一次 24B BlockVector 克隆"的机理吻合，可信。时间净效应不做声明。

## 4. 未触碰项与原因

- `IrisObject.place` 本体（29.1% leaf)：内层是 ~120 行状态机（replace
  编辑、heightmap、warp、vine、marker、listener),R8 已塌缩克隆链；剩余
  是条件密布的真实工作，无"纯减工作量"型切入点，拆分重构的收益不可证。
- `data = d.clone()`(place 每块一次）：旋转/编辑/vine 等路径会 mutate
  BlockData，原对象属于加载缓存（多放置共享）,**克隆是正确性依赖**，不动。

## 5. 结论

- 对象放置路径第三轮（R8 克隆链 → R16 分配清扫 → R21 变换就地化）后，
  每块固定分配降至 ~45-48 KB/op，剩余主体是 `RecordingPlacer`/hunk 写入与
  BlockData 自身。
- 漂移期方法论再验证：B/op 中位数（分配采样不受 CPU 频率/电源漂移影响）
  是本轮唯一采用的量化指标；时间比值在 ±5% 内不做结论。
- 剩余大项不变：用户服务器端到端实测。
