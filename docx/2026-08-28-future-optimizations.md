# 2026-08-28 后续优化方向

按优先级排列，每项标注预估影响和实施成本。

## P0 — 立即可做（系统能力补全）

### 1. 切换 rerank 提供商
**问题**：MiniMax 公开 API 无 rerank 端点，当前层 3 一直走降级路径，等同于关闭。
**方案**：三选一：
- **Cohere** `rerank-multilingual-v3.0`：端点 `https://api.cohere.com/v1/rerank`，文档全、多语言质量高，免费额度够用
- **Jina AI** `jina-reranker-v2-base-multilingual`：端点 `https://api.jina.ai/v1/rerank`，价格友好
- **BGE-reranker-v2-m3 自部署**：用 FlagEmbedding 起 OpenAI 兼容服务，无外部依赖，本地推理

**实施**：`MiniMaxService.rerank()` 抽出 `RerankClient` 接口，新增 `CohereRerankClient` / `JinaRerankClient` 实现，按配置切换。`application.yml` 加 `rerank.provider: cohere|jina|bge`。

**影响**：top-5 命中率显著提升，特别是语义相近但关键词不重合的查询。
**成本**：低-中，主要是接新 API。

### 2. rerank 失败时告警
**问题**：当前 rerank 失败只 warn，无法触发告警。
**方案**：
- `RerankService` 失败时除了 warn，再发一个 `kafka.send("rag-monitor", "rerank_fallback")` 或 `metrics.counter("rerank_fallback").inc()`
- Prometheus + AlertManager：`rerank_fallback_total{reason="api_returned_null"} > 10/min` 时告警

**影响**：及时发现 rerank 服务异常，避免长期静默降级。
**成本**：低，加 Micrometer 即可。

## P1 — 体验优化（短期 1-2 周）

### 3. 候选不足时 LLM 改写 query
**问题**：空候选短路后返回固定文案，用户得自己换关键词，体验生硬。
**方案**：
- 短路时让 LLM 生成 3-5 个同义改写 query
- 逐个重新跑 embed + Milvus 检索
- 全部失败再返回固定文案
- 改写过程计入日志 `[FALLBACK] query_rewrite=true attempts=N`

**影响**：用户问得偏也能找到资料，召回率提升。
**成本**：中，主要是增加 LLM 调用（成本和时延）。

### 4. 引用计数容错
**问题**：今天发现 chat 业务异常时 references 仍计入引用计数，导致废文档 doc.referenceCount 虚高。
**方案**：`RAGService.ask()` catch 后判断 `answer.startsWith("调用 MiniMax API 业务异常")` → 跳过 `documentRepository.save(doc)`。
**影响**：refCount 数据真实，反映实际被引用的文档。
**成本**：极低。

### 5. dedup 配额自适应
**问题**：当前 per-doc=2 是写死。长文档（30+ 页 PDF 被切成 50+ chunk）会丢大量相关片段。
**方案**：根据 `items.size()` 动态调整：
- `items.size() < 20` → per-doc = 3
- `items.size() >= 20` → per-doc = 2
- 顶层 Milvus 召回时把 topK 调到 `topK * 3`（20→30），给 dedup 留缓冲

**影响**：长文档召回率提升，top-5 多样性改善。
**成本**：低，改几个配置项。

### 6. 多轮对话上下文
**问题**：当前每次问答独立，无法处理"那它的废止时间呢？"这种追问。
**方案**：
- `QaHistory` 已存历史，调 LLM 前从 sessionId 拉最近 3-5 轮
- Prompt 里加"对话历史"段落
- LLM 根据历史理解指代

**影响**：体验大幅提升，更接近真实助手。
**成本**：低，主要是改 RAGService 和 Prompt 模板。

### 7. 缓存层（Caffeine）
**问题**：相同问题每次都重新 Embedding + Milvus + LLM，浪费算力。
**方案**：
- 用 Caffeine 做内存缓存，key = question hash
- TTL 5-10 分钟（政策可能更新）
- 命中率估计 30-50%（用户经常问相似问题）

**影响**：响应时间从秒级降到毫秒级，省 LLM 调用费。
**成本**：低。

## P2 — 检索质量优化（中期）

### 8. 混合检索（BM25 + 向量）
**问题**：纯语义检索对政策编号（"2025年第89号"）、专有名词的精确匹配召回率低。
**方案**：
- 引入 Apache Lucene 9.x 做 BM25 索引
- 检索时两路召回，RRF（Reciprocal Rank Fusion）合并排序
- status 加权放在融合之后

**影响**：召回率明显提升，特别是含数字、编号的查询。
**成本**：中，需要引入新依赖。

### 9. 批量 Embedding
**问题**：上传 PDF 时一片一片向量化，串行慢。
**方案**：
- MiniMax Embedding API 支持 batch（body 传 `texts: [...]`），`embed()` 改为接受 `List<String>`
- 一次传 16-32 个 chunk，减少 HTTP 调用次数

**影响**：上传耗时减少 5-10x。
**成本**：低。

### 10. 引用片段精炼
**问题**：当前 reference.content 是 chunk 前 200 字符截断，可能截在关键条款中间。
**方案**：
- 检索阶段按"段落完整性"切分，不要在句子中间断
- 引用时找最相关的 1-2 句而不是前 200 字
- 或用 LLM 对 chunk 提取关键句

**影响**：用户看到的引用更精准。
**成本**：中。

### 11. rerank 入参与出参可观测性
**问题**：当前 MiniMaxService.rerank() 打印 `elapsedMs` 但没记入参大小、出参条数分布。
**方案**：
- `RerankService` 每次调用记录：候选数、topN、最终 score 分布、耗时
- 推到 Prometheus `histogram_quantile(0.99, rerank_latency)`
- 便于调参（threshold、per-doc 配额）

**影响**：调参有数据支撑。
**成本**：低。

## P3 — 高级能力（长期）

### 12. 多模态文档支持
扫描件 PDF、图片需要 OCR；当前 PDFBox 只能提文本（OCR 兜底已有但质量一般）。
**方案**：集成 PaddleOCR 或商用 OCR API（百度 / 腾讯 / 阿里）。
**成本**：高。

### 13. 政策时间线视图
按发布日期/废止日期组织政策，做成时间轴 UI。
**方案**：PostgreSQL 已有 publishDate/expireDate，加专门查询接口。
**成本**：低-中。

### 14. 答案可信度评估
LLM 可能胡说八道（幻觉），需要让模型在找不到时明确告知。
**方案**：
- 检查 retrieval 相似度，低于阈值直接返回"未找到相关政策"（空候选短路已经覆盖一半）
- 让 LLM 在答案里引用具体公告编号，未引用的视为不可信
- 收集"用户反馈不满意"的答案作为负样本做离线评估

**成本**：中。

### 15. A/B 测试框架
不同 Prompt、排序权重、rerank 提供商对答案质量的影响需要量化。
**方案**：
- 加 experiment_id 字段到 QaHistory
- 用户分层路由到不同配置
- 离线评估答案质量（命中率、引用准确率）

**成本**：高。

### 16. 自部署 rerank 模型（BGE / bce-reranker）
**动机**：避免外部 API 调用费 + 数据出境合规。
**方案**：
- FlagEmbedding 起 BGE-reranker-v2-m3 服务（OpenAI 兼容 `/v1/rerank`）
- Docker 容器化，Kubernetes 部署
- 复用现有 `RerankService`，只换 base-url

**影响**：长期降本 + 满足合规要求。
**成本**：中（GPU 资源 + 运维）。

## 立即推荐（成本-收益最佳）

| 优化项 | 预期收益 | 实施成本 | 建议优先级 |
|--------|---------|---------|-----------|
| 切换 rerank 提供商（#1） | 高 | 低 | ⭐⭐⭐ |
| rerank 失败告警（#2） | 中 | 极低 | ⭐⭐⭐ |
| 引用计数容错（#4） | 低 | 极低 | ⭐⭐⭐ |
| 缓存层（#7） | 高 | 低 | ⭐⭐⭐ |
| 多轮对话（#6） | 高 | 低 | ⭐⭐⭐ |
| 候选不足时 query 改写（#3） | 中 | 中 | ⭐⭐ |
| 混合检索（#8） | 高 | 中 | ⭐⭐ |
| dedup 配额自适应（#5） | 中 | 低 | ⭐⭐ |
| 批量 Embedding（#9） | 中 | 低 | ⭐⭐ |
| 自部署 rerank（#16） | 中 | 中 | ⭐ |
