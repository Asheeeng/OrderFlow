# OrderFlow V1.0.1 部署验收

验收日期为 2026-11-26（北京时间）。实际运行环境为 Apple Silicon macOS 15.5、Docker Desktop 的 Linux ARM64 引擎、Docker Compose v5.4.0。验收全程使用独立项目 `orderflow-v101-verify` 和 `orderflow-v101-small`，与原宿主机版使用不同端口和命名卷。

| 项目 | 实测结果 |
|---|---|
| 全 Docker 构建和健康门控 | 多阶段构建 Nginx 前端和 Spring Boot 后端，另构建 Python 初始化镜像。初始化正常退出后后端才启动，前端等待后端健康。最终五个常驻服务健康 |
| 默认 million | 第一次完整初始化的 13 张业务表共 3,212,935 行，其中订单 400,000、明细 1,000,000。主从精确计数一致，两边 9 项完整性检查均为 0 |
| 重复初始化和模式保护 | 再次运行 init 保留每张表的行数。向同一实例请求 small 会失败，业务数据完整保留 |
| small 冷启动 | 独立空卷启动全部服务，400 笔历史订单入库，主从明细各 1,000 行，Nginx 路径返回 HTTP 200 |
| 复制及读写分离 | 主库 71，从库 72、`super_read_only=1`，GTID 自动定位、TLS、接收和应用线程正常，采样延迟 0 秒。统计实际使用从库 |
| 业务路径和浏览器 | 经 Nginx 验证三角色、授权、下单、支付、发货、收货、取消、库存并发及 Redis；Chrome 三项通过 |
| 停止与容器重建 | 停止独立测试项目并重建全部容器后，主从业务行数、测试订单 400006、从库只读与 Redis AOF 测试标记均保留 |
| Navicat 连接参数 | 在备用端口以相同 MySQL TCP 参数核验读库、触发器和读写权限。默认项目保留 13316、13307 和只读检查账号 |
| 原 V1.0 环境 | 前后比对 `orderflow` 项目的三个容器身份、主从订单明细行数、Web HTTP 和 `v1.0.0` 指向，均一致 |

首次并行构建时，旧健康检查经 TCP 使用 root，收到 MySQL 1130。已改为通过管理 socket 确认正式 MySQL 对外监听 3306。前一次启动仍读入旧配置而重建了测试数据库容器，这次中断保留了已提交的 88,000 笔订单；切换到最终配置后从 88,001 续跑直至 400,000。独立 small 冷启动随后通过。

## 平台边界

- **实际全栈验证** macOS Apple Silicon，Linux ARM64 容器。
- **AMD64 架构核验** 基础镜像清单包含 Linux AMD64，三个 Dockerfile 用 BuildKit 以 AMD64 为目标执行配置检查并通过。该检查没有在 AMD64 硬件上运行整套应用。
- **Windows 实机** 当前无 Windows 实机。WSL 2 启用、Docker Desktop 安装、路径权限和 PowerShell 在 Windows 上调用 Docker 的流程由 [Windows 部署指南](../../docs/DEPLOY_WINDOWS.md) 覆盖。

## 证据文件

- [启动门控](start-order.txt) · [百万档和重建持久化](compose-million.txt) · [small 冷启动](small.txt)
- [Chrome 用例](browser.txt) · [Navicat 等效 TCP](navicat-tcp.txt)
- [V1.0 隔离与默认端口](isolation.txt) · [AMD64 目标和镜像清单](amd64-build-check.txt)

通过 [全 Docker 部署指南](../../docs/DEPLOY_DOCKER.md) 与 `tests/compose_acceptance.sh` 可复现验收。
