## 代码规范
- 项目有基础库 oraclebet-libs，涉及到基础功能（如共享数据模型、API 契约定义、路由、节点发现、WAL）必须从基础库引用，禁止自行复制或重写
  - oraclebet-shared: 共享工具类和通用数据模型
  - oraclebet-contracts: API 契约定义和数据传输对象
  - oraclebet-routing-core: 路由引擎核心
  - oraclebet-node-discovery: 节点发现 SPI
  - oraclebet-node-discovery-nacos: Nacos 节点发现实现
  - oraclebet-wal: Write-Ahead Logging 引擎

## 修改公共库流程
如果需求涉及修改 oraclebet-libs，必须按以下步骤操作：
1. 先在本地 `/Users/li/Documents/GitHub/OracleBetx-Project/oraclebet-libs` 修改代码
2. 本地 `mvn clean install` 安装到本地 Maven 仓库，让当前工程临时引用本地版本进行开发调试
3. 验证通过后，将 oraclebet-libs 的改动 commit & push 到远程仓库
4. 回到当前工程，更新 git submodule 引用到最新提交：`cd libs/oraclebet-libs && git pull origin main`
5. 重新编译当前工程，确保使用 submodule 版本构建成功
6. 提交当前工程的 submodule 引用变更并 push

## Java 构建环境
构建环境规范见 /Users/tong/Documents/GitHub/OracleBetX-Projects/OracleBetx-Skill/oraclebetx-java-build/SKILL.md，所有 mvn 命令必须按照该规范执行。
核心要点：必须使用 `SHELL=/bin/bash JAVA_HOME="/Users/tong/.sdkman/candidates/java/current" /opt/homebrew/bin/mvn` 执行 Maven 命令。

## JVM 参数（Chronicle Queue / WAL 依赖）

运行或测试时需要以下 JVM flags，否则会报 `InaccessibleObjectException`：

```
--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED
--add-exports=java.base/sun.nio.ch=ALL-UNNAMED
--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED
--add-opens=java.base/java.io=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
```

通过 MAVEN_OPTS 设置：
```bash
export MAVEN_OPTS="--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED"
```

或通过 `source env.sh`（项目公共环境变量脚本已包含此配置）。