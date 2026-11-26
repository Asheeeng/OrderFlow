# OrderFlow 全 Docker 部署

全容器版使用 [compose.docker.yml](../compose.docker.yml) 与 `.env.docker`，默认 Compose 项目名为 `orderflow-docker`。`docker-compose.yml` 和 `.env` 供已验收的宿主机版使用。使用终端脚本启动时，脚本会明确传入 Compose 文件、项目名和环境文件。

## 容器启动顺序

1. `primary`、`replica` 和 `redis` 启动。MySQL 健康检查通过管理 socket 验证已进入开放 3306 的正式实例，Redis 检查认证后的 PING。
2. 一次性 `init` 服务等待主从健康，建立库、索引、过程、触发器及账号，配置 GTID 自动定位与从库只读，再导入指定种子。关系数据分批提交，重复执行复用已有合成数据，部分导入从已提交进度续跑。
3. `backend` 等待 `init` 正常退出，同时检查三项依赖健康。它通过 Compose 服务名访问 `primary:3306`、`replica:3306` 和 `redis:6379`。
4. `frontend` 在后端健康后启动。Nginx 在同源地址提供 Vue，并将 `/api/` 转发至 `backend:8080`。它的健康检查走过 Nginx、Spring Boot 和主库。

MySQL 管理账号经专用 Unix socket 卷供初始化容器使用，浏览器和 Navicat 使用各自的应用或只读账号。JDBC 只连接 Compose 网络中的 MySQL 服务。主从、Web 三项宿主机端口只绑定 `127.0.0.1`，后端与 Redis 的连接走 Compose 内网。依赖顺序参见 [Docker Compose 官方说明](https://docs.docker.com/compose/how-tos/startup-order/)。

## 端口和实例隔离

默认端口保留 Web 5173、MySQL 主库 13316 和从库 13307。Docker 版 REST API 走 Web 端口下的 `/api`。现有 V1.0 实例占用端口时，先准备另一份环境文件，再修改其中的三个端口。原 `.env` 与任何已有数据库卷均无须改动。

macOS 示例：

```bash
export ORDERFLOW_PROJECT=orderflow-docker-small
export ORDERFLOW_ENV_FILE=.env.docker.small
./scripts/docker.sh prepare small
# 在 .env.docker.small 中改为 WEB_PORT=5273、
# MYSQL_PRIMARY_PORT=14316、MYSQL_REPLICA_PORT=14307。
./scripts/docker.sh start
./scripts/docker.sh status
```

Windows 示例：

```powershell
.\start.ps1 -PrepareOnly -Mode small -ProjectName orderflow-docker-small -EnvFile .env.docker.small
notepad .env.docker.small
# 改为 WEB_PORT=5273、MYSQL_PRIMARY_PORT=14316、MYSQL_REPLICA_PORT=14307 后保存。
.\start.ps1 -ProjectName orderflow-docker-small -EnvFile .env.docker.small
.\status.ps1 -ProjectName orderflow-docker-small -EnvFile .env.docker.small
```

对该实例查看密码、日志或停止时，使用相同的项目名和环境文件。其他实例的文件名使用 `.env.docker.<实例名>`，使其继续受 Git 忽略规则保护。不同实例各自拥有独立的 MySQL、Redis 与 socket 命名卷。

## 密码、数据模式及重启

启动首次生成环境文件。macOS 以 0600 权限写入。Windows 脚本在 NTFS 上把新文件 ACL 限定到当前用户。文件只保存在本机并被 Git 忽略。日常凭据使用 `status.ps1 -Password demo|navicat` 或 `scripts/docker.sh password demo|navicat` 单项查看。请妥善备份环境文件与卷，数据卷已有账号时使用原密码。

默认 `million`，包含 400,000 笔历史订单和 1,000,000 行明细。小档为 400 笔和 1,000 行明细。脚本在既有环境文件遇到不同模式时会退出，数据库端也会校验已有数据模式。切换档位通过独立项目和环境文件完成。

停止命令调用 Compose stop 并保留卷。再次启动时，初始化任务检查原有账号与数据，常驻容器恢复到健康状态。初始化耗时较长时可用日志命令查看进度，完成时会出现 `READY: mode=...`。重启时看到 `historical data already present` 表示直接使用已有数据。

排障时可选用以下只读命令：

```bash
docker compose -p orderflow-docker -f compose.docker.yml --env-file .env.docker ps -a
docker compose -p orderflow-docker -f compose.docker.yml --env-file .env.docker logs --tail=100 init backend
```

启动报地址已占用时请先更换对应的新实例端口。报 `Access denied` 时应核对该项目的环境文件、密码和数据卷是否成套。出现 GTID 等待超时可查看 `replica` 与 `init` 日志，修复后可以再次启动。保留现有数据库时，请避免运行 Compose 的 `down -v`。

## 架构兼容与验收

前后端 Dockerfile 的构建阶段在 BuildKit 的构建平台运行，产物为静态网页和 Java 17 JAR。最终运行阶段选取目标平台的 Nginx 或 Temurin。Python 初始化服务在目标架构安装依赖。项目选用同时提供 Linux ARM64 和 AMD64 的基础镜像。Windows 需要通过 WSL 2 运行 Linux 容器。平台验收边界、实际数据量和运行记录见 [V1.0.1 验收证据](../evidence/v1.0.1/README.md)。

复现百万档验收请在已独立启动的测试项目运行 `tests/compose_acceptance.sh`，并传入其 `ORDERFLOW_PROJECT` 和 `ORDERFLOW_ENV_FILE`。该脚本会创建合成测试业务并重新创建测试容器来验证卷持久化。

## V1.1 可选 AI 模块

沿用相同项目、数据库与数据卷。主库初始化会执行增量 SQL 并等待复制，原业务数据继续复用。模型密钥通过既有环境文件仅注入运行中的后端，构建无需密钥。升级和模型设置见 [升级指南](UPGRADE_V1.1.md) 与 [模型配置](AI_CONFIG.md)。
