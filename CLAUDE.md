## 代码规范
- 项目有基础库 oraclebet-libs，涉及到基础功能（如共享数据模型、API 契约定义、路由、节点发现、WAL）必须从基础库引用，禁止自行复制或重写
  - oraclebet-shared: 共享工具类和通用数据模型
  - oraclebet-contracts: API 契约定义和数据传输对象
  - oraclebet-routing-core: 路由引擎核心
  - oraclebet-node-discovery: 节点发现 SPI
  - oraclebet-node-discovery-nacos: Nacos 节点发现实现
  - oraclebet-wal: Write-Ahead Logging 引擎
