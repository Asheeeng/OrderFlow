# 配置模型

在 Docker 部署既有的 `.env.docker` 中追加下列配置。宿主机版本使用自己的 `.env`。两个模型可以来自不同提供商，URL 填兼容 API 基路径，例如 OpenAI-compatible 的 `/v1` 或 Model Studio 的 `/compatible-mode/v1`。

```dotenv
AI_ENABLED=true
AI_BASE_URL=
AI_API_KEY=
AI_CHAT_MODEL=
AI_EMBEDDING_BASE_URL=
AI_EMBEDDING_API_KEY=
AI_EMBEDDING_MODEL=
AI_EMBEDDING_VERSION=1
AI_DAILY_CALL_LIMIT=300
AI_USER_MINUTE_LIMIT=20
```

聊天模型须支持 Chat Completions Function Calling。Embedding 接口须返回有限、非零的稠密数组，维度不超过 4096。若切换 Embedding 模型或同名模型更新了向量空间，提高 `AI_EMBEDDING_VERSION` 后增量构建会重建所有受影响片段。价格与地区端点按提供商账户要求核实。[Model Studio Chat](https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope) · [Function Calling](https://www.alibabacloud.com/help/en/model-studio/qwen-function-calling) · [Embedding](https://www.alibabacloud.com/help/en/model-studio/embedding-interfaces-compatible-with-openai)

修改环境文件后使用原启动命令重建应用。无配置时三个入口显示未启用，业务服务及数据库初始化照常运行。配置发生网络故障时 AI 请求单独报错。模型密钥只供后端运行时读取，请避免分享展开后的 Compose 配置。Mock Overlay 仅供单独实验项目使用，其 HTTP 端点与固定凭据没有接入外部模型。

## 网页配置中心

管理员登录后打开 **AI 配置中心**。选择阿里云百炼时会填充官网兼容 API 基路径、`qwen4-max` 和 `qwen3.7-text-embedding-flash`。输入一次 API Key、勾选启用，点击 **保存并立即应用**。可以用一键测试分别检查文本生成、真实协议的 Function Calling、Embedding 和当前知识库。在首次使用或切换 Embedding 空间后，点击 **重建不兼容向量** 启用客服检索。模型下拉项为候选，实际可用性以账户权限和连接测试为准。[百炼聊天](https://docs.modelstudio.console.alibabacloud.com/en/model-studio/qwen-api-via-openai-chat-completions) · [百炼 Embedding](https://docs.modelstudio.console.alibabacloud.com/en/model-studio/embedding)

硅基流动预设 `https://api.siliconflow.cn/v1` 与 `BAAI/bge-m3`，聊天模型从其控制台确认。自定义兼容端点可给 Chat 和 Embedding 分别设置接口与密钥。更换接口地址需要重新输入该接口的密钥，避免沿用旧密钥发往新地址。[硅基流动快速上手](https://docs.siliconflow.cn/docs/userguide/quickstart)

### 保存及生效

数据库配置优先。只有数据库中尚无配置记录时，后端才读取环境变量。空密钥输入保留已有值，按钮可独立清除密钥并停用 AI。启用配置的保存会先检测文本、完整工具握手和向量，失败时原配置仍可用。通过后新请求立即使用新的配置修订和额度，已开始请求使用原快照。工具验证要求模型按 schema 给出本次随机参数、接收同一调用 ID 对应的回执并回传，普通 HTTP 200 无法通过。Embedding 的接口、模型 ID、显式版本、实测维度共同定义向量空间。只切换聊天模型时可继续使用原向量。供应商在同一模型 ID 下升级模型权重时，应提升 Embedding 版本。

调用用量取自 `ai_request_logs`。成功率与平均耗时基于已结束的提供商请求，Token 样本只采用提供商返回的数据。连接测试会调用已填写的模型并计入额度，服务器不自动挑选替代模型。

### 密钥、地址及备份恢复

真实密钥只提交到后端，用随机 nonce 的 AES-256-GCM 加密后写入主库。GET 接口只提供配置状态和固定遮罩。部署初始化在独立 `ai_config_key` 命名卷生成主密钥，后端只读挂载。宿主机路径为 `.runtime/ai-master.key`。Docker 重建应用容器时按原项目名复用卷。请和数据库备份配对保存主密钥卷的加密离线副本，将其与数据库备份分开保存。灾后先恢复原卷和权限（Docker 文件属主 UID 10001、权限 0600），再用原项目名与数据库备份启动。主密钥丢失后旧密钥无法解密，应从配套备份恢复。常规版本升级无需导出密钥。

网页持久化配置使用公网 DNS 名称的 HTTPS 接口（443）。连接器校验实际参与连接的 DNS 结果，拦截私网、环回、链路本地和其它特殊地址，保持原域名的 TLS 校验并拒绝重定向。用于验收的固定本地 Mock 仅在显式测试 Overlay 开启。正式环境请用受信任的管理入口输入密钥，自定义接口只填经授权的模型服务。

导出 Docker 备份时，可先用原项目参数运行 `docker compose ps -a -q init` 获取初始化容器 ID，再使用 `docker cp <init-ID>:/run/orderflow-ai/master.key <受保护的离线路径>/ai-master.key` 复制文件。请将密钥备份放在仓库之外、限制为运维账号可读，并对离线介质加密。恢复时将其放回原项目的 `ai_config_key` 卷中的 `master.key`，核对属主和权限后再启动。避免用终端输出或截图保存密钥。
