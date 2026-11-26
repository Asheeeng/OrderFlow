# OrderFlow 快速开始

完整部署只需要 Docker Desktop 与 Git。Docker 负责构建前后端、启动 MySQL 主从和 Redis，并导入合成数据。首次启动默认导入百万模式，过程受镜像下载速度、可用内存和磁盘影响。Navicat 可按需安装，用于课堂检查。

先启动 Docker Desktop，确认使用 Linux 容器，再克隆项目：

```bash
git clone https://github.com/Asheeeng/OrderFlow.git
cd OrderFlow
git switch --detach v1.0.1
```

也可从 [GitCode 镜像](https://gitcode.com/susuis/OrderFlow) 克隆。分平台安装和故障排查见 [Windows 部署](DEPLOY_WINDOWS.md)、[macOS 部署](DEPLOY_MACOS.md)。

| 操作 | Windows PowerShell | macOS Terminal |
|---|---|---|
| 启动，默认百万模式 | `.\start.ps1` | `./scripts/docker.sh start` |
| 查看状态 | `.\status.ps1` | `./scripts/docker.sh status` |
| 查看初始化进度 | `.\status.ps1 -InitLogs` | `./scripts/docker.sh logs init` |
| 显示演示密码 | `.\status.ps1 -Password demo` | `./scripts/docker.sh password demo` |
| 显示 Navicat 密码 | `.\status.ps1 -Password navicat` | `./scripts/docker.sh password navicat` |
| 停止并保留数据 | `.\stop.ps1` | `./scripts/docker.sh stop` |

打开 [系统首页](http://127.0.0.1:5173/)。演示账号为 `demo_customer`、`demo_merchant`、`demo_admin`，三者使用本机部署生成的同一个演示密码。查看密码的命令会在当前终端明文显示对应一项，适合在本机私下操作。

| Navicat 连接 | 主机 | 端口 | 用户 | 数据库 |
|---|---|---:|---|---|
| 主库 | `127.0.0.1` | 13316 | `of_inspect` | `orderflow` |
| 从库 | `127.0.0.1` | 13307 | `of_inspect` | `orderflow` |

常驻服务显示 healthy 时可以演示，`init` 显示 `Exited (0)` 表示初始化成功完成。Navicat 检查语句见 [Navicat 指南](NAVICAT_GUIDE.md)。已有宿主机版或端口被占用时，先按 [端口和实例隔离](DEPLOY_DOCKER.md#端口和实例隔离) 做准备。
