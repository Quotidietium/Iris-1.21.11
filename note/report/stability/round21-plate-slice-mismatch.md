# Round 21 — 板盘切片尺寸错位：根因闭环与修复（终版）

日期：2026-08-27 · 状态：**根因确定 + 修复已提交（0fb2dbcaf）+ 真机验证臂运行中**

## TL;DR

用发布版 jar + 全新世界 + `debug=true` + `dumpMantleOnError=true` 重跑完整预生成（radius 3000），
**panic 复现且首次捕获异常栈与板盘字节转储**。dump 解剖证明：**round20 的"上游瞬态"分类被推翻——
这是确定性的写侧缺陷**（读方当时看到的文件字节本身内部不一致）。随后两层单元排除（单线程 1089 场景、
并发 1.77 亿操作）证明 **DataContainer 本体（含 R31/R35 全部改动）在容器级完全一致**——缺陷在
MantleChunk/板盘级的跨对象并发组合，待下一层复现。

## 复现与捕获

- 发布版 3.9.4（javap 验证过三轮改动的同一 jar）+ virgin testworld + PregenBoot radius 3000。
- 异常栈（`plugins/Iris/debug/caught-exceptions/`，debug 模式产物）：
  `java.io.IOException: Matter slice read size mismatch!` @ `Matter.readDin:155`，线程 `Iris IO 9`，
  经 `IOWorker.read → TectonicPlate.<init> → MantleChunk.<init> → Matter.readDin`——**正常加载路径**，
  HyperLock 与通道信号量全程在位（排除无锁旁路）。
- 6 个失败板盘的完整字节转储（`plugins/Iris/dump/*.bin`，读时通道二次读取=读方视角）。

## dump 解剖（VerifyDumpDissect / VerifyDumpDissect2）

**6/6 dump 离线确定性复现错误**（生产读器解析即触发 hasError），且各文件恰好消费到自身末尾
（框架级自洽，错位在切片内部）。逐类修正节点解码后：

- String / MatterMarker / MatterUpdate 切片错位全部闭合（= 解剖器节点格式假设错误，非文件问题）。
- **每个 dump 恰好剩一个 `BlockData` 切片错位**，形状高度一致：
  `paletteSize=13..31`，按 `bits(paletteSize+1)` 读方应读 256/342 个 varlong，
  **但磁盘上该区域实际多出 171~385 字节的 varlong**——与「写方数组位宽比 paletteSize 推导值
  高一档（bits+1，数组多 60~130 个 long）」的算术精确吻合。
- 结论：**写侧在同一个 writeDos 里发出了 (paletteSize=13..31, 按 5/6 位宽 dump 的数组)** ——
  `data.getBits()` 与 `palette.bits()` 在写盘瞬间不一致。

## 逐层排除（全部机器验证，工具入库）

| 层 | 工具 | 结果 |
|---|---|---|
| 单线程容器状态机（填充/覆写/多次写/变异 ×1089 组合，字节契约+往返尺寸） | `VerifyContainerBits` | **PASS**（零分歧） |
| 并发容器（3 写线程随机 set 跨位宽边界 + 串行 writeDos 循环，1.77 亿 op） | `VerifyContainerRace2` | **PASS**（零分歧） |
| 离线解析 panic 当次运行产出的全部 256 板盘（round20，非 debug） | `VerifyPlateParse` | 全部干净（坏中间态被后续覆写修复） |

DataContainer 的锁纪律、R35 脏标志、R31 预置、setBits 重打包、trim 双路径——**在容器边界内全部一致**。

## 剩余嫌疑面（下一层复现目标）

1. **MantleChunk/Matter 级跨对象并发**：`MantleChunk.write`（unload 路径）与 carve 写入
   （MantleWriter → section/slice）在**同一 Matter 不同对象**上的交错——pin 纪律声称互斥，
   但 dump 证明不一致状态真实出现过。候选洞：pin 覆盖不全的 matter 写入路径
   （装饰/post-modifier 直写 matter 而不经 MantleWriter？）。
2. `Matter.writeDos` 的 `getSliceTypes()` 双调用（count 与迭代间集合可变）——本次 dump 未命中
   该形状（sliceCount 与流一致），但仍是真实缺陷面。
3. 复现策略：MantleChunk 级并发压榨器（carve 线程 + unload/write 线程同一 chunk 集合），或
   真机逐轮二分（关 R35 脏标志 / 回退 R34 / R33，每臂一次完整预生成 ~2h）。

## 根因（终局）

**palette 中的重复值**：dump 解码显示每个坏切片的 palette 恰好含一对**字符串完全相同**的条目
（全部为栅栏族 MultipleFacing 状态）。机制链：

1. 某处代码**原地修改了已进入 palette 的 BlockData**（未 clone）——其 hashCode 随状态漂移，
   在 HashPalette 的 CHM 中变成"桶孤儿"（条目仍物理存在于旧哈希桶）；
2. 之后同值方块再入 palette 时 `computeIfAbsent` 按新哈希查旧桶未命中 → **追加第二个 id**——
   byId 数组中两个 id 持有 equals 相等的值；
3. **trim() 的算术缺陷**：`bits = bits(distinct + 1)` 用**去重前**的使用计数分配重打包数组，
   而 `trimmed.add()` 按 CHM 值语义**去重**——重复对收敛为单条目后 `trimmed.size() < distinct`，
   于是 `palette.size()`（写盘的 paletteSize）与 `data.getBits()`（数组的实际位宽）**错开一档**；
4. 读方按 `bits(paletteSize+1)` 推导 varlong 数量 → 永远少读 → "Matter slice read size mismatch"
   → 跳切片/跳 section → 洞穴数据丢失。**修复前该机制在单元级复现为 left over=540B/196 varlongs
   ——与真实 dump 的 171~385B 残留同形状。**

排除项（全部机器验证）：真机 CraftBlockData equals/hashCode 一致（服务器内插件实测栅栏态
equals=true/hashEq=true/clone 一致/CHM 无重复键）；DataContainer 容器级单线程 1089 场景与
并发 1.77 亿操作零分歧（排除 R31/R35 改动本身）。

### 修复（0fb2dbcaf）

`DataContainer.trim()`：重打包 DataBits 的位宽改用**去重后**的 `bits(trimmed.size() + 1)`
（构建 trimmed 之后计算）；升 id 重映射顺序与无重复场景的输出完全不变（golden 56/56 逐位一致）。
`VerifyTrimDup`：可变哈希键在单元级完整复现「原地变更→CHM 孤儿→重复 id→trim 错位」，
**修复前 FAIL / 修复后 PASS** 的判别证据入库。

### 遗留（后续审计项）

- 找到那个「未 clone 就原地修改 palette 驻留 BlockData」的调用点（栅栏族状态是线索——
  MultipleFacing 的 setFace 变更；R34 修复过 stilt 路径，dump 证明仍有别处在漏）；
- `Matter.writeDos` 的 `getSliceTypes()` 双调用仍是真实缺陷面（本次未触发）。

## 对 3.9.4 的影响与发布动作

- 该缺陷**上游自 trim 实现以来即存在**（非 R32-R35 引入；R34 的 memo 使共享实例更常见、
  可能放大了触发面）。频率 0.01~0.2% section，影响=该 section 洞穴数据丢失后重生成。
- 修复验证臂（全新世界 + 修复 jar 完整预生成）运行中；通过后发布补丁版。

## 验证臂结果（终局更正）：trim 修复必要但不充分

修复 jar（含 0fb2dbcaf）+ 全新世界完整预生成 ~66 分钟（100 板盘）：**panic 仍出现（10 次），
新 dump 4 个全部确定性复现、形状同族**（BlockData 切片 declared 数据比 reader 推导多
138~288B；新样本 `benchmark/results/r21b/`）。结论：

1. **trim 去重算术缺陷真实且已修**（单元判别 FAIL/PASS + 无重复场景 golden 逐位不变）——保留；
2. **存在第二个产生"多余 varlong 字节"的机制**，未定位。剩余候选：
   - R35 的 `DataBits.write`/`Varint` 多字节路径（digest 一致性已在 plate-io 场景证明，但该场景
     的 palette 形状可能未覆盖出错值域）；
   - 写流并发交错（同一 dos 被两个序列化路径交错写入）；
   - `length` 与数组代际错配（写侧 data 数组比 reader 推导多一代）。
3. 下一实验（明确）：构造「与失败 dump 逐字节同源」的单元复现——把失败切片的字节区域直接喂给
   `DataContainer` 写读对照，或在 MantleChunk 层做 carve/unload 并发压榨；以及真机二分臂
   （pre-R35 jar vs R35 jar 各一次完整预生成对比 panic 率）。
4. **发布决策**：补丁版暂缓（修复不完整）；3.9.4 已发布版本的影响评估不变（0.01~0.2%、
   恢复路径完备、预生成可完成）。用户实测卡片的 panic 行数观察项保持高权重。

