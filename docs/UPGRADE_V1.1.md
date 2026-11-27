# 保留数据升级至 V1.1

仓库基线为 `v1.0.1`。本次工作区没有发现 v1.0.2 标签或国内镜像分发流程，现阶段使用既有 Compose 本地构建。私有 ACR 分发可在 CI 中以同一 Dockerfile 建立 ARM64/AMD64 构建并采用授权镜像地址，运行密钥仍由 `.env.docker` 注入。

1. 确认当前部署项目名、配置文件和端口，完成备份并保存原环境文件。保持原 Compose 项目和命名卷。
2. 获取经核验的 V1.1 候选代码。正式标签仅在真实模型门槛通过后发布。在原项目目录升级，不重新生成 `.env.docker`。
3. Windows 运行 `.\start.ps1`，macOS 运行 `./scripts/docker.sh start`。使用自定义项目时仍加原来的项目与环境文件参数。
4. 初始化只在主库执行 `database/migrations/V110__ai.sql` 并等待从库 GTID。历史数据完整时原生成器报告 `historical data already present`。AI 配置可晚于升级。
5. 查看状态和 init 日志，核对主从行数及业务流程。已有 Navicat 凭据及部署端口继续使用原配置。

宿主机部署继续用 `scripts/start.sh million`。原 `v1.0.1`应用可重新从原标签构建，附加 AI 表由旧业务忽略。如果恢复旧版应用，应复用原 `.env.docker` 和卷。禁止为升级或回退运行 `down -v`。独立百万档测试已核验升级前后的原核心数据。

### AI 配置中心迁移

初始化附加执行 `database/migrations/V120__ai_settings.sql`，同时创建独立的加密主密钥卷。停启及应用容器重建应复用该卷，保留原数据库和端口。首次升级需要运行既有启动命令，之后网页保存配置无需重启容器。首次网页保存之前继续读取旧环境配置。[配置优先级与备份恢复](AI_CONFIG.md)
