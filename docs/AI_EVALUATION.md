# AI 效果评测

本次使用真实 Java 应用、MySQL 主从与 Redis。Mock 接口由确定性 HTTP fixture 返回合成聊天和 Embedding 协议响应，不能用于评判真实模型检索精度、意图理解或 Token 成本。原始测试问题和预期位于 `tests/ai_cases.py`，每例实际输出、耗时和依据见 [验收记录](../evidence/v1.1.0/README.md)。

| 套件 | 检查方法 |
|---|---|
| Agent | 固定问题到协议请求，逐个核对工具、参数与独立真实 SQL。测试多工具、空区间、非法 SQL 工具、错误参数、超次、超时、503 重试、未受支持数字和跨账户会话 |
| RAG | 四篇文档、十个向量，返回的文档和标题逐一与预期比较。验证实际片段 ID、余弦阈值、不相关问题、伪造引用、增量更新和配置版本不兼容 |
| 商家 | 创建不同起始库存与实付销量的样本，比较库存、近窗销量、预计天数与业务表，检验取消单、跨商家和无依据建议 |

真实提供商检查可在小组授权的独立部署运行 `tests/ai_real_smoke.py --live-provider`。该命令会使用配置中的付费额度。完整真实验收还需把固定评测问题提交给实际模型，逐项人工核对证据、引用和库存建议。费用按真实供应商价与 Token 用量计算。缺少配置时真实评测保持待完成。

## 复现本地 Mock

先按部署文档准备单独的百万档项目。以下示例在仓库根目录执行，项目名与本地环境文件需与测试环境匹配：

```bash
docker compose -p orderflow-v110-verify --env-file .env.docker.v110 \
  -f compose.docker.yml -f tests/compose.ai-mock.yml up -d --wait

docker compose -p orderflow-v110-verify --env-file .env.docker.v110 \
  -f compose.docker.yml -f tests/compose.ai-mock.yml run --no-deps --rm \
  -e OF_TEST_API_BASE=http://frontend/api -v "$PWD/tests:/tests:ro" \
  --entrypoint python init -u /tests/ai_acceptance.py mock
```

该套件会创建明确标识的合成订单和库存样本，并模拟一个知识文档哈希失配。真实模型复测时从独立的正常 Compose 部署启用真实配置，移除 Mock Overlay；启动命令相同。可以同样用 init 执行 `/tests/ai_real_smoke.py --live-provider`，验证网络、工具握手与引用，输出会包含实际提供商返回的调用状态和 Token 数。
