package com.customswise.rag.service;

import com.customswise.rag.dto.QaRequest;
import com.customswise.rag.dto.QaResponse;
import com.customswise.rag.dto.RagItem;
import com.customswise.rag.entity.PolicyDocument;
import com.customswise.rag.entity.QaHistory;
import com.customswise.rag.repository.PolicyDocumentRepository;
import com.customswise.rag.repository.QaHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class RAGService {

    private final MiniMaxService miniMaxService;
    private final MilvusService milvusService;
    private final RerankService rerankService;
    private final PolicyDocumentRepository documentRepository;
    private final QaHistoryRepository qaHistoryRepository;
    private final ObjectMapper objectMapper;
    private final AnswerCredibilityService credibilityService;
    private final ExperimentService experimentService;

    @Value("${rag.top-k}")
    private int topK;

    @Value("${rag.rerank-top-k}")
    private int rerankTopK;

    @Value("${rag.current-policy-boost}")
    private float currentPolicyBoost;

    @Value("${rag.expired-policy-penalty}")
    private float expiredPolicyPenalty;

    /** 相似度阈值：低于此值的 chunk 直接丢弃（避免拉低 LLM 回答质量） */
    @Value("${rag.rerank.similarity-threshold:0.32}")
    private float similarityThreshold;

    /** 同 docId 最多保留的 chunk 数（解决滑窗重复） */
    @Value("${rag.rerank.max-chunks-per-document:2}")
    private int maxChunksPerDocument;

    /** Milvus 服务端 expr 过滤（true 时只召回 status="现行"） */
    @Value("${rag.rerank.server-side-status-filter:true}")
    private boolean serverSideStatusFilter;

    /** 送 rerank 前的候选上限（状态加权排序后截）。bge-reranker 是 O(n²)，
     * 太多候选会超时；上限给 rerankTopK×2 留余量。 */
    @Value("${rag.rerank.candidate-cap:10}")
    private int rerankCandidateCap;

    /** 自适应 dedup：召回量少时 per-doc=3，多时=2 */
    @Value("${rag.rerank.dedup-adaptive:true}")
    private boolean dedupAdaptive;

    /** Dynamic candidate-cap 总开关：true 时按 avgRawChars 选档；false 退化为固定 candidate-cap */
    @Value("${rag.rerank.dynamic-cap.enabled:true}")
    private boolean rerankDynamicCapEnabled;

    /** avgRaw < 此值 → 短文档档（多召回，质量优先） */
    @Value("${rag.rerank.dynamic-cap.short-threshold-chars:200}")
    private int rerankDynamicShortThresholdChars;

    /** avgRaw > 此值 → 长文档档（少召回，性能优先） */
    @Value("${rag.rerank.dynamic-cap.long-threshold-chars:400}")
    private int rerankDynamicLongThresholdChars;

    /** 短文档档 cap（avgRaw < short-threshold-chars） */
    @Value("${rag.rerank.dynamic-cap.short-cap:6}")
    private int rerankDynamicShortCap;

    /** 中等档 cap（short <= avgRaw <= long） */
    @Value("${rag.rerank.dynamic-cap.mid-cap:4}")
    private int rerankDynamicMidCap;

    /** 长文档档 cap（avgRaw > long-threshold-chars） */
    @Value("${rag.rerank.dynamic-cap.long-cap:3}")
    private int rerankDynamicLongCap;

    public RAGService(MiniMaxService miniMaxService,
                     MilvusService milvusService,
                     RerankService rerankService,
                     PolicyDocumentRepository documentRepository,
                     QaHistoryRepository qaHistoryRepository,
                     AnswerCredibilityService credibilityService,
                     ExperimentService experimentService) {
        this.miniMaxService = miniMaxService;
        this.milvusService = milvusService;
        this.rerankService = rerankService;
        this.documentRepository = documentRepository;
        this.qaHistoryRepository = qaHistoryRepository;
        this.objectMapper = new ObjectMapper();
        this.credibilityService = credibilityService;
        this.experimentService = experimentService;
    }

    /**
     * 问答。
     *
     * <p>流水线（粗召回 → 三层重排 → 喂 LLM）：
     * <ol>
     *   <li>embed(query) → Milvus 向量检索（服务端可选 status 过滤）</li>
     *   <li>层 1a：相似度阈值过滤</li>
     *   <li>层 1b：同 docId 去重（per-doc 配额）</li>
     *   <li>层 1c：状态加权排序（现行加分 / 废止减分）</li>
     *   <li>层 3：MiniMax rerank API 语义精排（失败时降级到层 1c 的结果）</li>
     *   <li>截 topK → context → LLM</li>
     * </ol>
     */
    @Cacheable(value = "qa", key = "#request.question + '|' + (#request.userConditions ?: '')")
    public QaResponse ask(QaRequest request) {
        if (!milvusService.isAvailable()) {
            return QaResponse.error("向量数据库（Milvus）未连接，RAG 功能暂不可用，请联系管理员启动 Milvus 服务");
        }

        // 1. 生成问题向量
        float[] queryVector = miniMaxService.embed(request.getQuestion());
        log.info("问题: {}", request.getQuestion());
        log.info("问题向量维度: {}", queryVector.length);

        // 2. 搜索（层 1：hybrid dense + BM25 sparse，服务端可选 status 过滤）
        String expr = serverSideStatusFilter ? "status == \"现行\"" : null;
        List<Map<String, Object>> raw = milvusService.hybridSearch(queryVector, request.getQuestion(), topK * 2, expr);
        log.info("检索到 {} 条结果 (expr={}, hybrid=dense+sparse)", raw.size(), expr == null ? "无" : expr);

        // 3. 转 RagItem + 阈值过滤
        List<RagItem> items = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            RagItem item = RagItem.fromMap(m);
            if (item.similarity() >= similarityThreshold) {
                items.add(item);
            }
        }
        log.info("层1a 阈值过滤: {} -> {} 条 (threshold={})", raw.size(), items.size(), similarityThreshold);

        // 4. 层 1b 同 docId 去重（自适应配额）
        int effectivePerDoc = (dedupAdaptive && items.size() < 20) ? 3 : maxChunksPerDocument;
        items = dedupByDocumentId(items, effectivePerDoc);
        log.info("层1b 同doc去重: per-doc={}（自适应={}）, 剩余 {} 条", effectivePerDoc, dedupAdaptive, items.size());

        // 短路：候选为空时直接返回，不再调用 LLM（避免空上下文浪费 token 拉低质量）
        if (items.isEmpty()) {
            log.info("[FALLBACK] short_circuit=true reason=no_candidates_after_filter threshold={} question=\"{}\"",
                    similarityThreshold, request.getQuestion());
            String answer = "未检索到相关政策资料。请换个关键词或上传相关政策文档。";
            String experimentId = experimentService.getGroup(request.getSessionId(), "default");
            saveHistory(request, answer, List.of(), null, experimentId);
            QaResponse response = new QaResponse();
            response.setAnswer(answer);
            response.setReferences(List.of());
            response.setConfidenceScore(0f);
            response.setConfidenceLevel("low");
            response.setCitationsPresent(false);
            return response;
        }

        // 5. 层 1c 状态加权排序（降级路径也是这个顺序）
        items = statusWeightedSort(items);

        // 5b. 候选上限：bge-reranker 是 cross-encoder O(n²)，太多候选推理爆 30s；按状态加权序截一刀
        //     Dynamic-cap：根据当前候选 chunks 的 avgRawChars 自动选档（短文档多召回、长文档少召回），
        //     最终 clamp 到配置的 candidate-cap 作为绝对上限，避免异常配置导致 rerank 超时
        int effectiveCap = computeEffectiveCap(items);
        if (items.size() > effectiveCap) {
            items = new ArrayList<>(items.subList(0, effectiveCap));
        }

        // 6. 层 3 MiniMax rerank（失败自动降级到层 1c）
        items = rerankService.rerank(request.getQuestion(), items, RagItem::text);

        // 记录 rerank 后 top1 相似度（用于可信度评估）
        float topSimilarity = items.isEmpty() ? 0f : items.get(0).similarity();

        // 7. 截 topK，转回 Map 喂给既有 context 构建
        List<Map<String, Object>> topResults = new ArrayList<>();
        for (int i = 0; i < Math.min(rerankTopK, items.size()); i++) {
            topResults.add(items.get(i).toMap());
        }
        log.info("最终 top{} 条:", rerankTopK);
        for (int i = 0; i < topResults.size(); i++) {
            Map<String, Object> r = topResults.get(i);
            float sim = ((Number) r.get("similarity")).floatValue();
            log.info("  top#{} - docId: {}, status: {}, similarity: {}",
                    i, r.get("document_id"), r.get("status"), String.format("%.4f", sim));
        }

        // 6. 构建上下文
        StringBuilder context = new StringBuilder();
        List<QaResponse.Reference> references = new ArrayList<>();

        // 记录本次引用了哪些文档（chat 失败时回滚计数）
        List<PolicyDocument> referencedDocs = new ArrayList<>();

        for (Map<String, Object> result : topResults) {
            String text = (String) result.get("text");
            String docId = (String) result.get("document_id");
            String status = (String) result.getOrDefault("status", "现行");

            context.append("【").append(status).append("】").append(text).append("\n\n");

            // 获取文档信息
            try {
                Long docIdLong = Long.parseLong(docId);
                PolicyDocument doc = documentRepository.findById(docIdLong).orElse(null);
                if (doc != null) {
                    QaResponse.Reference ref = new QaResponse.Reference();
                    ref.setDocumentId(doc.getId());
                    ref.setTitle(doc.getTitle());
                    ref.setDocumentNumber(doc.getDocumentNumber());
                    ref.setStatus(doc.getStatus());
                    ref.setContent(text.length() > 200 ? text.substring(0, 200) + "..." : text);
                    references.add(ref);

                    // 更新引用计数
                    doc.setReferenceCount(doc.getReferenceCount() + 1);
                    documentRepository.save(doc);
                    referencedDocs.add(doc);
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid document_id: {}", docId);
            }
        }

        // 7. 组装Prompt（含历史上下文）
        String historyContext = buildHistoryContext(request.getSessionId());
        String prompt = buildPrompt(request.getQuestion(), request.getUserConditions(), context.toString(), historyContext);

        // 8. 调用LLM
        String answer;
        AnswerCredibilityService.CredibilityResult credibility = null;
        String experimentId = experimentService.getGroup(request.getSessionId(), "default");
        try {
            answer = miniMaxService.chat(prompt);
        } catch (MiniMaxException e) {
            // chat() 内部已打 [FALLBACK] chat_error=true 标记；这里只补一行汇总日志并返回业务文案
            log.error("[FALLBACK] chat_error=true reason={} question=\"{}\"",
                    e.getClass().getSimpleName(), request.getQuestion());
            // 回滚已计数的引用（chat 失败时不应计入）
            for (PolicyDocument doc : referencedDocs) {
                doc.setReferenceCount(Math.max(0, doc.getReferenceCount() - 1));
                documentRepository.save(doc);
            }
            referencedDocs.clear();
            answer = "调用 MiniMax API 业务异常（" + e.getClass().getSimpleName() + "），请稍后重试。";
        }

        // 9. 可信度评估 + 追加免责声明
        credibility = credibilityService.evaluate(topSimilarity, references, answer);
        answer += credibilityService.disclaimer(credibility.level());

        // 10. 保存问答历史
        saveHistory(request, answer, references, credibility, experimentId);

        // 11. 返回结果
        QaResponse response = new QaResponse();
        response.setAnswer(answer);
        response.setReferences(references);
        response.setConfidenceScore(credibility.score());
        response.setConfidenceLevel(credibility.level());
        response.setCitationsPresent(credibility.citationsPresent());

        return response;
    }

    /**
     * Dynamic candidate-cap：根据"状态加权排序后剩余候选"的 avgRawChars 自动选档。
     *
     * <p>档位规则：
     * <ul>
     *   <li>avgRaw &lt; short-threshold-chars → 短文档档（short-cap，多召回，质量优先）</li>
     *   <li>avgRaw &gt; long-threshold-chars → 长文档档（long-cap，少召回，性能优先）</li>
     *   <li>其它 → 中等档（mid-cap）</li>
     * </ul>
     *
     * <p>无论动态档位怎么选，最终 clamp 到 {@code rerankCandidateCap} 作为绝对上限。
     * 这样：
     * <ul>
     *   <li>想用短文档档 cap=6 真正生效 → 把 candidate-cap 提到 6+</li>
     *   <li>保守起见 candidate-cap=4 → 所有档位实际都被压到 4，动态档只决定下限</li>
     * </ul>
     *
     * <p>关闭动态档（enabled=false）或入参为空 → 退化为固定 candidate-cap。
     */
    private int computeEffectiveCap(List<RagItem> items) {
        if (!rerankDynamicCapEnabled || items == null || items.isEmpty()) {
            return rerankCandidateCap;
        }
        // 用 top-K 估算 avgRaw（K = configuredCap），而非全 items 平均。
        // 原因：状态加权排序后长 chunk 会被排到前面，全 items 平均会被短 chunk 稀释，
        //      导致系统性低估 rerank 实际负载。top-K 永远偏向保守（高估负载→选更激进 cap→更安全）。
        int refK = Math.min(items.size(), rerankCandidateCap);
        double avgRawChars = items.stream()
                .limit(refK)
                .mapToInt(i -> i.text() == null ? 0 : i.text().length())
                .average()
                .orElse(0);
        int tierCap;
        String tier;
        if (avgRawChars < rerankDynamicShortThresholdChars) {
            tierCap = rerankDynamicShortCap;
            tier = "short";
        } else if (avgRawChars > rerankDynamicLongThresholdChars) {
            tierCap = rerankDynamicLongCap;
            tier = "long";
        } else {
            tierCap = rerankDynamicMidCap;
            tier = "mid";
        }
        int effectiveCap = Math.min(tierCap, rerankCandidateCap);
        log.info("[BGE_RERANK_CAP] tier={} avgRawChars={} ({}/{} items) tierCap={} effectiveCap={}",
                tier, (int) avgRawChars, refK, items.size(), tierCap, effectiveCap);
        return effectiveCap;
    }

    /**
     * 同 docId 去重：每个 documentId 最多保留 {@code maxPerDoc} 个 chunk，按相似度降序截断。
     *
     * <p>输出顺序保持入参相对顺序（按相似度先排好的顺序）。
     *
     * @param items    入参候选（已按相似度降序）
     * @param maxPerDoc 每个文档最多保留的 chunk 数；&lt;=0 表示不去重
     * @return 去重后的列表
     */
    private List<RagItem> dedupByDocumentId(List<RagItem> items, int maxPerDoc) {
        if (maxPerDoc <= 0) {
            return items;
        }
        Map<String, Integer> docCount = new HashMap<>();
        List<RagItem> out = new ArrayList<>(items.size());
        for (RagItem item : items) {
            String docId = item.documentId();
            int count = docCount.getOrDefault(docId, 0);
            if (count >= maxPerDoc) {
                continue;
            }
            out.add(item);
            docCount.put(docId, count + 1);
        }
        return out;
    }

    /**
     * 状态加权排序：现行政策 × currentPolicyBoost，已废止 × expiredPolicyPenalty。
     *
     * <p>同时承担"rerank 降级路径"——当 MiniMax rerank 不可用时，调用方拿到的是这个顺序。
     */
    private List<RagItem> statusWeightedSort(List<RagItem> items) {
        List<RagItem> copy = new ArrayList<>(items);
        copy.sort((a, b) -> {
            float simA = a.similarity();
            float simB = b.similarity();
            if ("现行".equals(a.status())) simA *= currentPolicyBoost;
            else simA *= expiredPolicyPenalty;
            if ("现行".equals(b.status())) simB *= currentPolicyBoost;
            else simB *= expiredPolicyPenalty;
            return Float.compare(simB, simA);
        });
        return copy;
    }

    /**
     * 构建Prompt
     */
    private String buildPrompt(String question, String userConditions, String context, String historyContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的跨境电商海关政策助手。请根据以下参考资料回答用户的问题。\n\n");

        if (historyContext != null && !historyContext.isEmpty()) {
            prompt.append("【对话历史】\n").append(historyContext).append("\n\n");
        }

        if (userConditions != null && !userConditions.isEmpty()) {
            prompt.append("用户条件：").append(userConditions).append("\n\n");
        }

        prompt.append("【参考资料】\n").append(context).append("\n\n");

        prompt.append("【问题】").append(question).append("\n\n");

        prompt.append("【回答要求】\n");
        prompt.append("1. 优先参考标注为【现行】的政策\n");
        prompt.append("2. 如果参考了【已废止】的政策，必须明确提醒用户该政策已废止\n");
        prompt.append("3. 必须列出引用的政策来源（标题、公告编号）\n");
        prompt.append("4. 如果没有找到相关资料，请如实告知，不要编造\n");
        prompt.append("5. 强调本回答仅供参考，不构成报关法律依据\n\n");

        prompt.append("【回答】");

        return prompt.toString();
    }

    /**
     * 从数据库拉取最近 N 轮对话历史，拼成一段文字供 LLM 理解上下文。
     */
    private String buildHistoryContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        try {
            Page<QaHistory> page = qaHistoryRepository.findBySessionIdOrderByCreatedAtDesc(
                    sessionId, PageRequest.of(0, 3, Sort.by(Sort.Direction.ASC, "createdAt")));
            if (page.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (QaHistory h : page.getContent()) {
                sb.append("用户问：").append(h.getUserQuery()).append("\n");
                sb.append("助手答：").append(h.getAiResponse()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[HISTORY] 拉取对话历史失败 sessionId={}: {}", sessionId, e.getMessage());
            return "";
        }
    }

    /**
     * 保存问答历史
     */
    private void saveHistory(QaRequest request, String answer, List<QaResponse.Reference> references,
                             AnswerCredibilityService.CredibilityResult credibility, String experimentId) {
        try {
            QaHistory history = new QaHistory();
            history.setSessionId(request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString());
            history.setUserQuery(request.getQuestion());
            history.setUserConditions(request.getUserConditions());
            history.setAiResponse(answer);
            history.setReferences(references);
            history.setCreatedAt(LocalDateTime.now());
            if (credibility != null) {
                history.setConfidenceScore(credibility.score());
                history.setConfidenceLevel(credibility.level());
                history.setCitationsPresent(credibility.citationsPresent());
            }
            history.setExperimentId(experimentId);
            qaHistoryRepository.save(history);
        } catch (Exception e) {
            log.error("Failed to save QA history", e);
        }
    }

    /**
     * 获取问答历史
     */
    public Page<QaHistory> getHistory(String sessionId, int page, int size) {
        if (sessionId != null && !sessionId.isEmpty()) {
            return qaHistoryRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(page, size));
        }
        return qaHistoryRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }
}
