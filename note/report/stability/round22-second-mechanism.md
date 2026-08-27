# Round 22 — 第二机制追查：dump 后置性证伪 + 读侧短缺签名（阶段性收束）

日期：2026-08-27 · 状态：单元层无法进一步收窄，需专门二分会话

## 本轮证据

1. **r21b dump 字节同源探针**（`VerifySliceProbe`，全量扫描 4 dump）：所有 BlockData 与
   MatterCavern 切片**逐字节完全自洽**（varlong 数量精确等于 `dataLength(bits(paletteSize+1))`，
   无缺口、无多余）——早前 Dissect2 报告的"错位"部分是解剖器自身少算 33 字节头部
   （类名 UTF+2 varint）的**假警报**。
2. **dump 后置性**：`IOWorker` 的 dump 在 panic 恢复后的 finally 里对通道**二次读取**——若板盘在
   失败读与 dump 之间被再次保存，dump 呈现的是**新版（干净）文件**。结合 1：**活读看到的坏字节
   已被后续覆写销毁**——这解释了"确定性复现的 dump"与"字节级干净"的矛盾。
3. **活体短缺签名**（server17 修复 jar 的 panic 上下文，当前值）：reader 停在切片声明范围内
   **短 86~3684 字节**处（`current < end`）——即**写方偶发产出 content < size 的切片**。
4. **写侧逐项复核**：`size=bytes.size(); bytes.writeTo(dos)` 同缓冲自洽；缓冲为每次调用局部；
   DataContainer.writeDos 有容器写锁——**单元层（1089 场景 + 1.77 亿并发操作）无法复现**。

## 收窄后的结构性候选

- **同一 chunk/matter 的并发序列化**（两个 `worker.write` 调用点：Mantle:458 保存路径与
  Mantle:600 卸载路径是否可并发触达同一 plate）+ Matter 级共享状态（`getSliceTypes` 双调用、
  `trimSlices`）在两写者间无互斥——容器锁只保护容器内部，不保护 Matter 切片表与 chunk 写流程。
- 时序窗口与 R35 加速后的写盘节奏变化相关（未证实因果）。

## 下一会话的明确实验

1. **真机二分臂**：pre-R35 jar（`git show 241094121^` 构建）vs 当前 jar，各一次全新世界完整预生成，
   对比 panic 率与短缺字节数分布——直接判定 R35 相关性。
2. **MantleChunk 层并发压榨器**：carve 写线程 + 双写入口（保存+卸载）并发同 chunk，检测
   content<size 输出。
3. 加固方向（若并发证实）：MantleChunk.write 按 chunk 加锁或 Matter 写流程快照化。

## 交付物

- `VerifySliceProbe`（字节同源探针，含 33 字节头部教训）；round21 报告与本报告；
  r21b dump 归档已在库（`benchmark/results/r21b/`）。
