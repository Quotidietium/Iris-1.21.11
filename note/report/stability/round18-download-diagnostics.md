# 稳定性/安全性审计 第 18 轮：在线包下载失败的诊断质量（round17 遗留项闭环）

日期：2026-08-27 · 分支：`perf/optimization`（已合并 master，本轮起分支继续领先）
前置：round17 冒烟发现 "Failed to find pack at <有效URL>"，列为待查项。
验证：`benchmark/build.sh` 编译（1274 类）+ golden 49/49 位级一致（`results/audit-r18.csv`，1+1）。

## 根因（离线决定性复现）

用与产品完全相同的 `downloadToFile` 代码直连该 URL（JDK 21）：

```
javax.net.ssl.SSLHandshakeException: PKIX path building failed:
  sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path
```

`curl -v https://github.com` 显示 **github.com 被本机 hosts 解析到 127.0.0.1**（本地加速代理劫持）：
- curl 走 Windows schannel 证书库 → 信任本地代理 → 200 OK（这就是 round17 里"资产存在且可下载"的来源）；
- Java 走自身 cacerts 严格 PKIX → 代理自签证书不在库 → 握手失败 → `getNonCachedFile` 返回 null。

**结论：不是 Iris 的下载 bug，是本机环境特性**（对所有 Java 程序，GitHub 域名在这台机器上都不可 TLS 直连；未被劫持的 fill.papermc.io 可正常下载——冒烟中 Paper jar 就是 Java 直连取的，逻辑自洽）。真机服务器无 hosts 劫持时，cacerts 内置的 GitHub 根证书链正常工作。

## 修复的产品缺陷：失败反馈误导（行为中立，仅诊断质量）

原失败链把 TLS/超时/404 全部折叠成两种误导消息：
- `Failed to find pack at <url>` + `Make sure you specified the correct repo and branch!`——TLS 失败被指向"检查 repo 拼写"，排障方向完全错误（round17 的调查就被带偏）；
- `Failed to download 'key' from ?.`——连 URL 都是占位符，无任何原因。

修复（3 处）：
1. `Iris.getNonCachedFile` 失败时 console 输出真实原因（`Download failed for <name> (<url>): SSLHandshakeException: PKIX ...`）；
2. `downloadSearch`/`downloadRelease` 的 sender 消息附带异常类名+消息；
3. `downloadInternal` 的 null 分支改为 `Failed to download pack at <url>` 并提示"看上方 Download failed 的真实原因（网络/TLS），或核对 repo/branch"——区分"下载失败"与"下载成功但文件缺失"两种情况。

成功路径与失败结果零变化（仅消息文本与可见性）——golden 49/49。

## 附带更正

- round17 的"待实机复查项：在线包下载误判"**闭环**：真机无需复查此路径的"bug"（无 bug），但改进后的错误消息使任何环境下的真实失败原因（代理 TLS/超时/DNS）一目了然。
- 本机（及类似加速代理环境的开发者机）上做包下载测试需注意：Java 程序对 GitHub 域不可用是预期行为；冒烟的包预置方式（手动解压到 `plugins/Iris/packs/`）是正确做法。

## 改动

| commit | 内容 |
|---|---|
| fix(R18) | getNonCachedFile 真实原因上浮 + StudioSVC 三处消息修正 |
| docs | 本报告 + round17 更正注记 + memory |
