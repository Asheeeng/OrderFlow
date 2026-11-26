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
