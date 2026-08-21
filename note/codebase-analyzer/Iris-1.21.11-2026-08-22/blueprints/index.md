# Skill Blueprint 索引

> 项目：Iris-1.21.11 · 生成时间：2026-08-22 · 来源：codebase-analyzer

| # | Blueprint | 组件 | AI 等级 | 优先级 | 文件 |
|---|-----------|------|---------|--------|------|
| 1 | pack-doc-generator | 数据包模型文档 + JSON Schema 生成（140 类） | 🤖 完全 AI 化 | 🥇 Quick Win | [01-pack-doc-generator.md](01-pack-doc-generator.md) |
| 2 | crash-log-analyzer | 调试产物（异常/区块错误/线程转储）聚合归因 | 🤖 完全 AI 化 | 🥇 Quick Win | [02-crash-log-analyzer.md](02-crash-log-analyzer.md) |
| 3 | nms-porting-assistant | 新 MC 版本 NMS 绑定移植草稿 | 🧑‍💻 AI 辅助 | 🥈 Strategic | [03-nms-porting-assistant.md](03-nms-porting-assistant.md) |
| 4 | test-scaffold-generator | util 纯逻辑类 JUnit5 测试脚手架 | 🤖 生成 + 🧑‍💻 抽检 | 🥇 Quick Win | [04-test-scaffold-generator.md](04-test-scaffold-generator.md) |

## 实施路线图

### 立即实施（Quick Win）
1. **pack-doc-generator** — 无外部输入依赖，纯读仓库即可产出；文档与 Schema 双收益（包作者体验 + 编辑器校验）。
2. **crash-log-analyzer** — 产物格式由 `Iris.java:353-421` 完全确定，落地即提升排障效率。
3. **test-scaffold-generator** — 需先给 `core/build.gradle.kts` 补 test 配置；从 P0 包（noise/stream）起步。

### 规划实施（Strategic）
4. **nms-porting-assistant** — 触发时机是下一个 MC 版本发布；平时可先用 v1_21_R6→R7 的历史 diff 做"演练回放"验证 Skill 准确率。

### 明确不做（人工主导，见 04 报告 §3）
- IrisComplex 噪声流图调参、Mantle 并发结构、Safeguard 授权逻辑。

---

> 每个 Blueprint 文件包含创建对应 Skill 所需的完整设计规格（触发词、接口契约、依赖清单、工作流、Constraints、示例）。
> 使用 `skill-for-skills` 加载对应 Blueprint 即可生成标准 SKILL.md。
