# Round 21 — MatterCavern/BlockData 板盘切片错位：确定性复现与逐层排除（进行中）

日期：2026-08-27 · 基线：发布版 3.9.4 jar（与 Release 附件同字节）· 状态：根因收窄中

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

## 对 3.9.4 的影响评估

- 频率：140k 区块 12~280 次（0.01~0.2%），恢复路径=跳切片/跳 section 重生成，预生成可完成。
- 影响：受影响 section 的洞穴/方块 matter 数据丢失（该 section 重生成时会以空 matter 处理——
  洞穴在该 section 内可能表现为实心）。
- **不是 R32-R35 的已知回归形状**（容器级已排除；读端代码与 3.9.3 逐字相同），但 R35 加速写盘
  后时间窗变化可能改变触发频率。用户实测卡片中 panic 行数观察项权重提高。
