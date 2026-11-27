# Windows 从零部署 OrderFlow

## 1. 启用 WSL 2 与虚拟化

建议使用满足 Docker Desktop 当前系统要求的 Windows 11。打开任务管理器的“性能 → CPU”，检查“虚拟化”是否启用。如果显示禁用，按电脑厂商文档在 UEFI 开启虚拟化。Windows 版本、版本号和 WSL 支持要求以 [Docker Desktop Windows 安装文档](https://docs.docker.com/desktop/setup/install/windows-install/) 为准。

以管理员身份打开 Windows PowerShell。尚未启用 WSL 时运行：

```powershell
wsl --install --no-distribution
```

按提示重启 Windows，再打开 PowerShell，运行：

```powershell
wsl --update
wsl --set-default-version 2
wsl --status
wsl --version
```

使用 Docker Desktop 的 WSL 2 引擎，可以直接在 Windows PowerShell 部署。日常启动命令在 Windows 执行，安装额外 Linux 发行版为可选操作。参见 [Microsoft WSL 安装](https://learn.microsoft.com/zh-cn/windows/wsl/install) 和 [Docker WSL 2 引擎说明](https://docs.docker.com/desktop/features/wsl/)。

## 2. 安装 Docker Desktop 和 Git

1. 从 [Docker Desktop 官方页面](https://docs.docker.com/desktop/setup/install/windows-install/) 下载与你电脑架构对应的安装包。安装时选择 WSL 2 后端，安装后打开 Docker Desktop 并完成首次设置。
2. 在 Docker Desktop 的“Settings → General”确认启用了 WSL 2 引擎。让 Docker 运行 Linux 容器。在 PowerShell 执行 `docker info --format '{{.OSType}}'`，预期结果为 `linux`。
3. 从 [Git 官方下载页](https://git-scm.com/install/windows) 安装 Git for Windows。关闭并重新打开 PowerShell，使 PATH 生效。
4. 运行 `git --version` 和 `docker compose version`。Compose 应支持 `docker compose up --wait`。首跑请预留磁盘空间，并给 Docker 至少约 4 GB 可用内存。电脑资源允许时，可分配更多内存改善导入速度。

## 3. 克隆并运行

建议在当前用户目录下使用本机 NTFS 路径，便于 PowerShell 限制初始化文件的访问权限。用普通 PowerShell 运行：

```powershell
Set-Location $HOME
git clone https://github.com/Asheeeng/OrderFlow.git
Set-Location OrderFlow
git switch --detach v1.0.1
.\start.ps1
```

首次启动会构建三个应用镜像、创建独立卷并生成百万数据。另开一个 PowerShell 窗口，进入同一目录后用 `.\status.ps1 -InitLogs` 查看进度。启动脚本打印系统地址后，用 `.\status.ps1` 检查状态。

如果 PowerShell 阻止执行脚本，确认已审阅本仓库脚本后，可在当前窗口使用：

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\start.ps1
```

该参数只影响此次 PowerShell 进程。电脑受组织策略管理时，按管理员要求处理策略问题。状态和停止脚本亦可用相同方式调用。

## 4. 登录系统与连接 Navicat

打开 [系统首页](http://127.0.0.1:5173/)。在本机用 `.\status.ps1 -Password demo` 获取演示密码，再用 `demo_customer`、`demo_merchant` 或 `demo_admin` 登录。测试三个角色时可以使用独立浏览器窗口或无痕上下文。

安装 Navicat 后建两个类型为 MySQL 的连接。两边都填主机 `127.0.0.1`、用户名 `of_inspect`，密码取 `.\status.ps1 -Password navicat`。主库端口填 `13316`，从库端口填 `13307`，默认数据库均为 `orderflow`。点击测试连接后分别在查询窗口运行：

```sql
SELECT @@server_id, @@super_read_only;
SELECT COUNT(*) FROM orderflow.order_items;
```

主库实例号为 71，从库为 72 且只读值为 1。百万模式首次启动后明细精确计数为 1,000,000，新增演示订单会使其增大。进一步检查见 [Navicat 指南](NAVICAT_GUIDE.md)。

## 5. 端口冲突与停启

如果端口被占用，在首次启动前运行 `.\start.ps1 -PrepareOnly`，然后用 `notepad .env.docker` 修改 `WEB_PORT`、`MYSQL_PRIMARY_PORT`、`MYSQL_REPLICA_PORT` 中冲突的项。保留文件里的随机密码。修改端口后再次运行 `.\start.ps1`，并使用对应的新端口。

`.\stop.ps1` 停止服务，命名数据卷继续保留。再次运行 `.\start.ps1` 会重新确认主从和种子状态，已有数据保留。首次想用小数据时可运行 `.\start.ps1 -Mode small`。要同时保有两种模式，请用 [独立实例配置](DEPLOY_DOCKER.md#端口和实例隔离)。

Windows 说明依据 Docker Desktop 与 Microsoft 的官方流程。实际测试平台和 PowerShell 验证边界见 [V1.0.1 验收证据](../evidence/v1.0.1/README.md)。

## 已部署 V1.0.1 的组员

保留原 `.env.docker`、项目名及命名卷。获取候选代码后，在原项目目录重新运行 `.\start.ps1`。原自定义路径和端口按之前参数运行。升级与模型启用可以分开进行。[保留数据升级](UPGRADE_V1.1.md)


### 代理软件与 Fake-IP 排查

如果 AI 配置中心提示 DNS 返回代理 Fake-IP，请在代理软件的 DNS 设置中将 `dashscope.aliyuncs.com` 加入 Fake-IP 排除列表（业务空间使用其他域名时也需加入对应域名），应用配置后重新解析并重试。Clash/Mihomo 类配置可在 `dns.fake-ip-filter` 中添加该域名；具体操作以所用软件界面为准。macOS 和 Windows 均适用。Docker Desktop 使用自己的 DNS 转发，宿主机解析恢复后仍需检查后端容器内解析结果；必要时仅重建后端容器。不要将 198.18.0.0/15 加入安全白名单，也不要固定服务商 IP。
