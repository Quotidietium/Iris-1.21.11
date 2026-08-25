# 构建环境记录（2026-08-26，perf/optimization 分支，3.9.3 打包）

产物：`build/Iris-3.9.3-1.20.1-1.21.11.jar`（3.4 MB，SlimJar 结构，运行时依赖由 `slimjar.dat` 首次启动下载）
验证：主类 / plugin.yml（version 3.9.3-1.20.1-1.21.11）/ 11 个 NMS 绑定（v1_20_R1~v1_21_R7）/ agent.jar / kotlin script templates / `BuildConstants.COMMIT=b3d2fc0d7`（与 HEAD 一致）均齐备；仓库源码零改动。

## 环境结论

1. **JDK**：必须用 `C:/Program Files/Java/latest/jdk-21`（21.0.10）。PATH 上的 Oracle javapath 指向 JDK 25.0.4，其 TLS 在本机异常（services.gradle.org PKIX 失败/读超时），JDK 21 的 TLS 正常。构建全程以 `JAVA_HOME` 指向 jdk-21。
2. **Gradle**：wrapper 钉的 8.14.2 分发未缓存且无法经 JDK25 下载；改用本机已缓存的 **Gradle 9.6.1**（`~/.gradle/wrapper/dists/gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle`）直接构建成功。buildSrc 的 `kotlin("jvm") version embeddedKotlinVersion` 随 Gradle 版本走（9.6.1 = kotlin 2.3.21），首次需联网补插件 marker 与依赖。
3. **国内镜像**：`~/.gradle/init.d/aliyun-mirrors.gradle` 注入阿里云镜像（public/central/gradle-plugin/google）于所有仓库之前，原仓库保留兜底（papermc/jitpack/codemc 等专属件无镜像）。构建命令参考：
   ```
   gradle iris "-Dorg.gradle.java.installations.paths=C:/Program Files/Java/latest/jdk-21,C:/Program Files/Java/latest/jdk-25" \
     "-Dorg.gradle.jvmargs=-Xmx3072m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -Dsun.net.client.defaultConnectTimeout=15000 -Dsun.net.client.defaultReadTimeout=30000"
   ```
4. **SlimJar pinger 挂死**：`HttpURLPinger` 用无超时的 `HttpsURLConnection` 逐个探测外部仓库，慢仓库导致构建无限挂起。解法：daemon JVM 加 `-Dsun.net.client.defaultConnectTimeout/defaultReadTimeout`（见上命令），探测失败快速跳过。
5. **SpecialSource 损坏陷阱**：NMSTools 的 `RemapTask` 下载 `SpecialSource-<v>.jar` 到 `nms/*/build/tools/` 后只查 `exists()`，构建中途被杀会留下**残缺 jar**（完整 2325073 B；损坏件 1.2~1.6 MB），之后 remap 永久报 `java.exe exit 1`（ClassNotFoundException，子进程输出被吞）。修复：删损坏件或从阿里云 `net/md-5/SpecialSource/1.11.4/SpecialSource-1.11.4-shaded.jar` 重下。排查手段：`--debug` 日志中 `DefaultExecHandle` 行可拿到完整子进程命令，手动复现即见真错。
6. 其余依赖（spigot `remapped-mojang`/maps 等 NMSTools 专属件）只能走 `repo.codemc.org`，无镜像，首次构建慢属正常；已入缓存后不再重复下载。
