# Round 23 — 真机二分锁定：R35 trim 脏标志跳过=板盘损坏触发器（已回退修复）

日期：2026-08-27 · 状态：触发器经双臂实证锁定；无条件 trim 修复已提交（11b50ad95）；
最终完整验证臂运行中（跑至 `Completed Turbo Gen`）

## 二分实验设计

同机同条件（全新世界 + PregenBoot radius 3000 + `-Xmx8G` 生产形态）三个 jar 臂：

| 臂 | jar | 时长/板盘 | panic |
|---|---|---|---|
| A（对照） | 3.9.4 发布 + trim 去重修复（含脏标志跳过） | 66min / 100 | **10** |
| B（pre-R35） | `4ff4214e4` 构建（R32-R34，无 R35） | 57min / 144 | **0** |
| C（分肢） | 当前 master 但 trim 无条件（保留 Varint/DataBits 快路径） | 74min / 146 | **0** |

**判定：R35 的 `if (dirty) trim()` 跳过是损坏触发器**（B、C 双臂同零 vs A 同期即现）。
R35 其余两改动（Varint 单字节快速路径、DataBits 缓冲外提）在 C 臂 74 分钟零 panic 中**洗清**。

## 机理（触发器已证，交错细节待绘）

脏标志跳过使 writeDos 在**未经 trim 归一化**的状态下序列化容器。短缺签名（content 比 size 少
86~3684B）与「跳过归一化直接序列化」之间的精确交错路径尚未绘制（容器锁、每调用局部缓冲在单元层
均自洽——1.77 亿并发操作无法复现），但触发器经两臂独立证实。**红线决策：正确性优先于性能，
永久回退跳过**；交错路径的完整绘制留档为后续审计项（不阻塞修复发布）。

## 修复内容（11b50ad95）

- `DataContainer.writeDos`：`if (dirty) trim()` → 无条件 `trim()`（恢复 R35 前语义）；
- 保留 round21 的 trim 去重位宽修复（0fb2dbcaf，独立正确性缺陷）；
- R35 收益部分让渡：plate-io 20.8ms（脏跳过）→ ~48.7ms（当前机器状态中位；pre-R35 同场景
  39.0ms——Varint 快路径收益保留，抵消每写 trim 扫描），B/op 中性。

## 门禁

VerifyTrimDup PASS ｜ VerifyObjectIOB PASS ｜ golden 56/56 逐位一致 ｜ gradle BUILD SUCCESSFUL

## 后续

1. 最终验证臂（本 jar，全新世界，跑至预生成完成）panic 必须保持 0 → 通过后发 3.9.5 补丁版；
2. 交错路径完整绘制（学术性收尾，不阻塞）；
3. 用户实测卡片回归（heap 曲线 + panic 行数）。
