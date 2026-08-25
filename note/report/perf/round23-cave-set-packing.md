# 性能优化 · 第 23 轮：cave 写路径集合代数紧凑化（packed-long 球填充）

**日期**：2026-08-25 · **分支**：`perf/optimization`
**环境**：JDK 25.0.4 · 32 逻辑处理器 · **golden 49/49 位级一致**

## 本轮主旨

cave-carve（R11 后 12 轮未碰的生成侧场景）的写路径
`MantleWriter.setNoiseMasked` 是 `Set<IrisPosition>` 的三层集合代数
（cleanup 折线插值 → getMasked 球填充+mask 探测 → removeIf 噪声过滤）。
每个球内单元分配 2 个 IrisPosition（探测 + 保留）加 HashSet Node。

**前置否决（perfection 谓词链）**：perfection-modify 剖析显示 Iris 帧
84% 是 B.isAir/isFluid/isDecorant（每块 2-3 次 getMaterial）。合并为单次
getMaterial + 枚举比较的实施经隔离 A/B 测得**无信号**（时间同带、B/op
恒等）——JIT 内联 + 公共子表达式消除已自动合并同对象重复 getMaterial。
按 R19/R20 纪律（无信号变更回退、热路径体量是资产）当场回退。同轮
JFR 剖析证明该场景 86% 执行是 bench 桩伪影（BlockData 动态代理 +
Bukkit 枚举 switch），离线时间测量对生产成本结构代表性有限。

**cave-carve 剖析不可行记录**：场景单次测量体仅 5.8ms（120 ops ×
~49µs），JFR 执行样本 26-37 个，无法定位。改用代码级分配审计 +
单元等价性证明。

## 1. 实施

`setNoiseMasked` 的 filled=true 分支（IrisCave.generate 的唯一生产
调用形态）切换到 packed-long 紧凑实现：

- **packCell/unpackCell**：坐标 21+21+21 位打包进 long（±1M 范围），
  符号扩展经"左移到 int 顶再算术右移"手工完成。
- **mask 预转换**：相对偏移 mask 集（KSet<IrisPosition>）一次性打包成
  `LongOpenHashSet`；球内探测从 new IrisPosition + HashSet.contains
  变为 long 打包 + 开地址探查，**每单元零分配**。
- **cells 集**：保留格存 long（原 IrisPosition + KSet Node 全消）。
- **噪声过滤与写入**：LongIterator 遍历 + remove，逐格解码写 mantle。
- filled=false（getHollowed 空心路径，无生产调用方）保留原实现。

**顺序无关性论证**（digest 红线的构造性保证）：CNG.noise 纯函数性经
实验证实（重复/交错调用同值）；data 函数 `(x,y,z) -> y<=h ? w : c`
纯；mantle 写入幂等（Set 去重后每格一次）；digest 按最终状态以固定
i/j/k 序 fold。遍历序变化（KSet 桶序 → long 开地址序）不可观测。

## 2. 过程事故与修复（记录为方法论）

首版 unpack 的 Y/Z 用了 `((int)(p << 22)) >> 11` 形式——**long 左移
后 cast 只保留低 32 位**，字段被推出 int 边界，Y/Z 解码恒错。cave-carve
digest 全量 mismatch 当场暴露。第二版修复 Y/Z 但 X 的 `(int)(p >> 42)`
依赖"符号位在 bit63"的假设——pack 布局 21×3=63 位，X 符号位实际在
bit62，算术右移永远补 0。`bench.VerifyCaveSet` 的 pack 往返扫描
（8.1M 组合）捕获后修复（X 同样走显式左移/算术右移符号扩展）。

**教训**：位域编解码必须配机器验证（往返扫描），人工移位推理在
符号扩展上两次翻车；集合代数等价性测试（同 pack 对比）会掩盖
unpack 错误——两者要分开测。

## 3. 验证与测量

`bench.VerifyCaveSet`（新增，入库）：
- CNG.noise 纯度：repeat/interleaved 同值 ✓
- 集合代数等价：200 随机形状（tips/mask/radius 组合）新旧链产出
  格子集逐位相同 ✓
- pack 往返：±1000 三维扫描 8.1M 组合零错误 ✓

cave-carve 同时段对照跑（base 侧本时段重跑，消除 ~1.2× 双模态漂移；
此前两个 base 批次间自身相差 2.7×，跨时段对比不可用）：

| seed | B/op base | B/op new | 时间方向 |
|---|---|---|---|
| 0-8 | 29746-35405 | 27229-30927 | **9/9 下降（-4.9%~-13.3%，中位 -8.4%）** |
| — | — | — | 时间 5/9 快 4/9 慢（随机混合，无净效应） |

digest：9/9 与 base 逐 seed 相同（a4290c77…/194c97c7…/dd9d5c4a…/
5efe8d46…/977dc4da…）；全量 golden 49/49 位级一致。

## 4. 未触碰项与原因

- `cleanup`（折线插值出 tips）：产出规模小（数十/调），保留
  List→KSet 形态；pack 化收益 ~2KB/op 且增加复刻风险。
- filled=false 的 getHollowed 空心链：无生产调用方，保留原实现
  （避免无基准覆盖的路径改写）。
- 时间读数：同带对照下方向随机混合，按 R20 方法论不做声明
  （分配消除 -8.4% 是漂移免疫的净收益；compact 路径的 pack+探查
  与原 new+HashSet.add 的 CPU 成本相当，无回归信号——4/9 慢于
  base 的散布在场景 ±30% 方差内）。

## 5. 结论

- cave-carve 分配 30-35KB → 27-31KB/op（-8.4% 中位）；与 perfection
  否决记录共同构成本轮"生成侧写路径残留"的完整审计。
- bench 新增 `VerifyCaveSet` 等价性证明设施（CNG 纯度 + 集合代数 +
  位域往返三层验证），位域类优化的机器验证模板。
- cave-carve 剩余分配主体转移至 worm/points 生成与递归 doCarving
  （cave 配置解析层），离线测量的下一站。
