# 2026-08-29 后续优化方向

按优先级排列，每项标注预估影响和实施成本。

> ✅ = 今日已完成

## P0 — 立即可做（系统能力补全）

### 1. 候选不足时 LLM 改写 query
**问题**：空候选短路后返回固定文案，用户得自己换关键词，体验生硬。
**方案**：
- 短路时让 LLM 生成 3-5 个同义改写 query
- 逐个重新跑 embed + Milvus 检索
- 全部失败再返回固定文案
- 改写过程计入日志 `[FALLBACK] query_rewrite=true attempts=N`

**影响**：用户问得偏也能找到资料，召回率提升。
**成本**：中，主要是增加 LLM 调用（成本和时延）。

### 2. rerank 失败时告警
**问题**：当前 rerank 失败只 warn，无法触发告警。
**方案**：
- `RerankService` 失败时除了 warn，再发一个 `metrics.counter("rerank_fallback").inc()`
- Prometheus + AlertManager：`rerank_fallback_total{reason="api_returned_null"} > 10/min` 时告警

**影响**：及时发现 rerank 服务异常，避免长期静默降级。
**成本**：低，加 Micrometer 即可。

## P1 — 体验优化（短期 1-2 周）

### 3. 批量 Embedding
**问题**：上传 PDF 时一片一片向量化，串行慢。
**方案**：
- MiniMax Embedding API 支持 batch（body 传 `texts: [...]`），`embed()` 改为接受 `List<String>`
- 一次传 16-32 个 chunk，减少 HTTP 调用次数

**影响**：上传耗时减少 5-10x。
**成本**：低。

### 4. 引用片段精炼
**问题**：当前 reference.content 是 chunk 前 200 字符截断，可能截在关键条款中间。
**方案**：
- 检索阶段按"段落完整性"切分，不要在句子中间断
- 引用时找最相关的 1-2 句而不是前 200 字
- 或用 LLM 对 chunk 提取关键句

**影响**：用户看到的引用更精准。
**成本**：中。

### 5. 批量文档同一 session 上传后自动进入多轮问答
**问题**：用户上传一批文档后，希望针对这些文档连续追问，但当前每次问答独立。
**方案**：
- DocumentController 上传后返回一串 `documentIds`
- 前端带着这串 `documentIds` 和同一个 `sessionId` 发 QA 请求
- RAG 检索时支持限定 `document_id IN (...)` 只在这批上传文档中检索

**影响**：批量上传 + 追问场景体验闭环。
**成本**：中，Milvus 检索表达式加一层 docId IN filter。

## P2 — 检索质量优化（中期）

### 6. 混合检索（BM25 + 向量）
**问题**：纯语义检索对政策编号（"2025年第89号"）、专有名词的精确匹配召回率低。
**方案**：
- 引入 Apache Lucene 9.x 做 BM25 索引
- 检索时两路召回，RRF（Reciprocal Rank Fusion）合并排序
- status 加权放在融合之后

**影响**：召回率明显提升，特别是含数字、编号的查询。
**成本**：中，需要引入新依赖。

### 7. rerank 入参与出参可观测性 ✅ 已做
BGE rerank 调用日志记录 candidates 数、分数分布、耗时，调参有数据支撑。

## P3 — 高级能力（长期）

### 8. 多模态文档支持（扫描件 OCR）
**问题**：扫描件 PDF、图片需要 OCR；当前 PaddleOCR 兜底已有但依赖 Python 服务部署。
**方案**（已完成架构）：
- `PaddleOcrExtractor` (priority=15) 已接入，`PdfExtractorFactory` 策略模式自动降级
- 用户需部署 PaddleOCR Python 服务（端口 8002）

**待优化**：评估 PaddleOCR 识别准确率，若仍不够可考虑：
- 更高 DPI（当前 250）
- 图像预处理（去噪、二值化、倾斜校正）
- 商业 OCR API（腾讯云/阿里云 OCR）

### 9. 政策时间线视图
**问题**：用户想按时间维度浏览政策演变。
**方案**：PostgreSQL 已有 `publishDate`/`expireDate`，加专门查询接口，按时间轴组织政策。

**影响**：用户体验提升，便于了解政策历史。
**成本**：低-中。

### 10. 答案可信度评估 ✅ 已做
`AnswerCredibilityService` 基于 topSimilarity + citation 检查评估置信度，medium/low 等级追加免责声明。

### 11. A/B 测试框架 ✅ 已做
`ExperimentService` sessionId 哈希 sticky 分组，`QaHistory.experimentId` 记录供离线分析。

## 立即推荐（成本-收益最佳）

| 优化项 | 预期收益 | 实施成本 | 状态 |
|--------|---------|---------|------|
| 候选不足时 query 改写（#1） | 中 | 中 | 待做 |
| rerank 失败告警（#2） | 中 | 极低 | 待做 |
| 批量 Embedding（#3） | 中 | 低 | 待做 |
| 引用片段精炼（#4） | 中 | 中 | 待做 |
| 批量文档限定检索（#5） | 高 | 中 | 待做 |
| 混合检索 BM25（#6） | 高 | 中 | 待做 |
| 政策时间线视图（#9） | 中 | 低-中 | 待做 |
| PaddleOCR 质量优化（#8） | 中 | 低 | 待做 |
