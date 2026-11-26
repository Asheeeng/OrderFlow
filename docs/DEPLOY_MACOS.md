# macOS 部署 OrderFlow

## 推荐方式：全 Docker

安装与你的 Mac 架构对应的 [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/) 和 Git，随后打开 Docker Desktop。Apple Silicon 使用 arm64 发行版，Intel 使用 amd64 发行版。

```bash
git clone https://github.com/Asheeeng/OrderFlow.git
cd OrderFlow
git switch --detach v1.0.1
./scripts/docker.sh start
```

启动后打开 [OrderFlow](http://127.0.0.1:5173/)。凭据和运行状态通过 `./scripts/docker.sh password demo`、`./scripts/docker.sh password navicat` 和 `./scripts/docker.sh status` 查看。用 `./scripts/docker.sh stop` 停止并保留卷。小数据模式首次运行可用 `./scripts/docker.sh start small`。

全容器默认项目为 `orderflow-docker`，原有宿主机版为 `orderflow`。两个实例都采用相同的默认端口。与现有 V1.0 并行时，先为新实例改用备用端口，具体见 [端口和实例隔离](DEPLOY_DOCKER.md#端口和实例隔离)。

## 兼容方式：保留原宿主机工作流

宿主机工作流继续使用 `docker-compose.yml`、原有的 `.env`、`orderflow` 项目和已有数据卷。Spring Boot 和 Vite 在本机运行，MySQL 和 Redis 在原 Docker 项目中运行。原脚本入口仍为：

```bash
scripts/start.sh million
scripts/stop.sh
```

此方式需要 Java 17、Maven、Node.js、npm、Python、OpenSSL 和 Docker。恢复细节仍见 [实验手册](MYSQL_EXPERIMENTS.md)。原 `.env`、Navicat 密码及已有数据可沿用。需要与全 Docker 同时使用时，只在新 Docker 环境文件设备用端口。实际 ARM64 验收使用了独立项目，既有 V1.0 实例继续运行。
