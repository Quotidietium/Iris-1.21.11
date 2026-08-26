# 稳定性/安全性审计 第 15 轮：printStackTrace 格式债务批量清理

日期：2026-08-27
分支：`perf/optimization`
前置：R1-R14（债务项自 R9-11 起记录："全库 227 处 printStackTrace 统一为 Iris.reportError"）
范围：`core/src/main/java` 全部裸 `printStackTrace()`（223 处 / 92 文件 + MultiBurst 行内 lambda 1 处）。**nms 模块 99 处不在本轮**（无离线编译门禁，留待 gradle 门禁批次）。带参数的 `printStackTrace(pw)`（Iris.java ×2、IrisLogger ×1）为正确用法不动。
验证：`benchmark/build.sh` 全量编译（1274 类）；golden 49/49 位级一致（`results/audit-r15.csv`，1+1 慢速期配方）。

## 转换内容（94 文件，+119/−230 行）

| 类别 | 数量 | 处理 |
|---|---|---|
| 独立 `X.printStackTrace();` | ~146 处 | → `Iris.reportError(X);`（21 文件补 `import com.volmit.iris.Iris;`） |
| 相邻重复（上一行已 reportError 同变量） | 76 处 | 删除 printStackTrace 行 |
| **同 catch 块内间隔重复**（reportError 与 printStackTrace 之间隔其他语句，或顺序颠倒） | 45 处（26 文件） | 第一遍替换产生重复上报后由第二遍**作用域感知折叠**消除（花括号作用域跟踪，同 scope 同变量只保留首个 reportError） |
| MultiBurst FJP 未捕获异常 handler 行内 lambda | 1 处 | `(t, e) -> Iris.reportError(e)` |

抽查确认（VolmitPlugin 6 处 / Mantle 3 处 / J 2 处）：全部为真实重复对（含 `w(...)` 日志行隔开的与顺序颠倒的），折叠后每个 catch 恰好一次上报。

## 语义说明

`Iris.reportError(e)` = Sentry 上报（Bindings.capture）+ debug 开启时全栈写入 `debug/caught-exceptions/*.txt` + debug 日志行。**替换后 console 不再出现原始 stderr 栈**（debug 关闭时）——排障信息不丢失（文件 + Sentry 齐全），console 噪音下降；Sentry 事件从"约半数 catch 缺失"变为全覆盖。

## 过程事故与教训

1. **批量脚本的两遍必要性**：第一遍只看紧邻上一行，漏掉 45 处间隔/颠倒重复——替换后形成双倍 Sentry 上报。第二遍作用域折叠前先跑编译器抓 import（13 文件首遍 import 插入被 CRLF 行尾挡住）再跑抽查，三重防线（编译器 + 折叠器 + 人工抽查）才把机械替换做干净。
2. **门禁链条的 && 陷阱**：一次 `rm 失败 && build` 跳过了重建，golden 跑了旧 classes 还全绿——若无察觉即成假门禁。修复：门禁链条中的每步显式 `;` + 逐项断言（"written" 计数、BUILD OK 行）。

## 累计统计（R1-R15）

- 65 项审计修复 + 本轮 222 处格式统一（净 +102 个 reportError 覆盖、121 处重复删除）
- 编译：1274 类；回归：golden 49/49 位级一致
- 报告：`round1` … `round14`、本文件

## 剩余

- nms 模块 99 处 printStackTrace（需 gradle 编译门禁，独立批次）
- 合并前事务：服务器实测清单、版本号、Release 发布
