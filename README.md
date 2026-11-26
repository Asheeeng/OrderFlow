# OrderFlow

OrderFlow 是一个本地电商订单与库存系统，覆盖顾客购买、商家履约和管理员运营。MySQL 主库保存业务写入，只读从库提供历史统计，Redis 缓存商品展示字段。历史业务数据和所有验收账户均为合成数据。

## V1.0.1 全 Docker 快速启动

部署只需要 **Docker Desktop 和 Git**。默认导入百万级合成数据。首次构建与导入完成后，打开 [OrderFlow](http://127.0.0.1:5173/)。

| 系统 | 启动 | 停止 | 查看状态 |
|---|---|---|---|
| Windows PowerShell | `.\start.ps1` | `.\stop.ps1` | `.\status.ps1` |
| macOS | `./scripts/docker.sh start` | `./scripts/docker.sh stop` | `./scripts/docker.sh status` |

三类演示密码及 Navicat 连接方式见 [快速开始](docs/QUICK_START.md)。从零安装请参阅 [Windows](docs/DEPLOY_WINDOWS.md) 或 [macOS](docs/DEPLOY_MACOS.md)，部署结构和已有实例共存方式见 [Docker 部署](docs/DEPLOY_DOCKER.md)。全 Docker 使用独立项目和 `.env.docker`。默认沿用原 Navicat 端口，与宿主机版并行时需要为新项目选空闲端口。平台验收及实际日志见 [V1.0.1 验收记录](evidence/v1.0.1/README.md)。

## 宿主机版启动与验收

环境需要 Docker Desktop 或 Docker Engine + Compose v2、Java 17、Maven 3.9、Node.js 22 及 npm、Python 3.11 以上和 OpenSSL。恢复实验另需 MySQL 官方客户端的 `mysqlbinlog`。浏览器测试默认使用本机 Google Chrome，也可通过 `OF_BROWSER_CHANNEL` 指定已安装的浏览器通道。

```bash
# 新环境直接初始化百万数据、构建并启动。首次拉取镜像和导入需要等待。
scripts/start.sh million

# 快速开发模式适用于新环境。
scripts/start.sh small

# 完整验收。自动化业务测试创建带 TEST 或 qa 标记的合成记录。
scripts/accept.sh

# 结束运行并保留 Docker 数据卷。
scripts/stop.sh
```

已有数据时可直接运行 `scripts/start.sh` 恢复服务。首次创建的 `.env` 含独立随机密码。修改监听端口请在启动前按本机情况设置。保留当前数据时，使用独立 Compose 项目与不同端口搭建另一套种子模式。

| 服务 | 默认地址 |
|---|---|
| Web | http://127.0.0.1:5173 |
| Spring Boot REST | http://127.0.0.1:8080/api |
| MySQL 主库 | `127.0.0.1:13316/orderflow`，server_id 71 |
| MySQL 从库 | `127.0.0.1:13307/orderflow`，server_id 72 |
| Redis | `127.0.0.1:16390` |

| 角色 | 测试用户名 | 本地密码来源 |
|---|---|---|
| 顾客 | `demo_customer` | `.env` 的 `DEMO_PASSWORD` |
| 商家 | `demo_merchant` | 同上 |
| 管理员 | `demo_admin` | 同上 |

Navicat 主从连接都用 `of_inspect` 与 `.env` 的 `DB_INSPECT_PASSWORD`。具体检查语句见 [Navicat 检查指南](docs/NAVICAT_GUIDE.md)。

## 源码与材料

| 路径 | 内容 |
|---|---|
| [backend](backend) | Java 17、Spring Boot 3、MyBatis-Plus、Spring JDBC、HikariCP 和 Spring Data Redis |
| [frontend](frontend) | Vue 3、TypeScript、Element Plus、ECharts 和 Playwright |
| [database/schema](database/schema) | 建库建表、过程、触发器与只读检查过程 |
| [database/indexes](database/indexes) | 普通及联合索引 SQL |
| [database/seed](database/seed) | Faker 合成数据、固定依赖和事务导入 |
| [database/experiments](database/experiments) | 核心统计、完整性、执行计划与双会话 SQL |
| [database/replication](database/replication) | GTID 复制和账户权限 SQL |
| [database/backup](database/backup) | 隔离环境的备份恢复业务样本 |
| [tests](tests) | REST、约束及并发验收 |
| [evidence](evidence) | 执行日志、精确行数及浏览器截图 |
| [docs](docs) | 架构图、ER 图、数据字典、复现手册、答辩题库 |

依赖版本见 [Maven POM](backend/pom.xml)、[npm 锁文件](frontend/package-lock.json)、[Python 依赖](database/seed/requirements.txt) 与 [Compose](docker-compose.yml)。业务日志记录使用 UTC，用户面向的验收日期使用北京时间。

## Git 同步

主分支 `main`。已关联 [GitHub 主仓库](https://github.com/Asheeeng/OrderFlow) 和 [GitCode 镜像](https://gitcode.com/susuis/OrderFlow)。GitCode 已有 SSH 权限的环境可将该仓库的推送地址设为 SSH，同时保留 HTTPS 拉取地址：

```bash
git remote set-url --push gitcode git@gitcode.com:susuis/OrderFlow.git
scripts/git-sync.sh --check
scripts/git-sync.sh --push
```

`--check` 核验远程 `main` 和全部标签是否一致。推送模式先检查工作区、远端祖先关系和标签冲突，再在两个远端分别进行原子推送预检与实际推送。两个托管服务各自执行写入，其中一端失败时脚本会报告出错的远端，可修复后再次核验。项目使用 MIT 许可证。
