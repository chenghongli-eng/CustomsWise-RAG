package com.customswise.rag.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义切片器：条款锚粗切 + 滑动窗口细切。
 *
 * <p>设计目标：替代旧版 {@code DocumentService.semanticChunking}（依赖 PDFBox 输出
 * {@code \n\n} 段落，而 PDFBox 几乎不产 {@code \n\n}，导致整本 PDF 退化为一个超长 chunk）。
 *
 * <p>两步策略：
 * <ol>
 *   <li><b>粗切</b>：按 {@link TextNormalizer} 注入的条款锚（"第 X 条 / 第 X 章"）
 *       切出"节"——假设 {@code \n\n第 X 条...} 是新条款边界</li>
 *   <li><b>细切</b>：每节内按 {@code chunkSize} 滑窗、{@code overlap} 衔接，得到最终 chunk</li>
 * </ol>
 *
 * <p>每个 chunk 附带 anchor（首个条款锚），用于检索结果回溯展示（"这段对应第三章 第十一条"）。
 */
public final class SemanticChunker {

    /** 默认 chunk 字符数（含中文，约一个长段落）。 */
    public static final int DEFAULT_CHUNK_SIZE = 500;
    /** 默认 overlap 字符数，保证跨 chunk 上下文衔接。 */
    public static final int DEFAULT_OVERLAP = 50;

    /** 条款锚边界：在 {@code \n\n} 之后紧跟"第 X 条 / 第 X 章"的位置切分。 */
    private static final Pattern SECTION_ANCHOR = Pattern.compile(
            "(?<=\\n\\n)(?=第[一二三四五六七八九十百千0-9]+[条章节])");

    /**
     * 切片结果 record。
     *
     * @param text       chunk 文本（已被 {@link TextNormalizer} 处理）
     * @param anchor     该 chunk 所属条款锚（如 "第三章 第十一条"）；无锚时为 ""
     * @param chunkIndex 在原文档中的全局顺序（0-based）
     */
    public record Chunk(String text, String anchor, int chunkIndex) {
    }

    /** 滑窗大小（字符）。 */
    private final int chunkSize;
    /** 滑窗 overlap（字符）。 */
    private final int overlap;

    /** 使用默认参数（chunkSize=500，overlap=50）。 */
    public SemanticChunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * 自定义滑窗参数。
     *
     * @param chunkSize 每个 chunk 的目标字符数，必须 &gt; 0
     * @param overlap   滑窗重叠字符数，必须 0 ≤ overlap &lt; chunkSize
     * @throws IllegalArgumentException 参数不合法时
     */
    public SemanticChunker(int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "chunkSize must > 0 and overlap >= 0 and overlap < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * 切片主入口。
     *
     * <p>对每节：长度 ≤ chunkSize 时直接成块；否则按 step = chunkSize-overlap 滑窗切。
     * anchor 取节内首个"第 X 条/章"作为该 chunk 的归属标识。
     *
     * @param normalizedText 已经 {@link TextNormalizer#normalize(String)} 处理过的文本
     * @return 按 chunkIndex 升序排列的 Chunk 列表；空输入返回空列表
     */
    public List<Chunk> split(String normalizedText) {
        List<Chunk> out = new ArrayList<>();
        if (normalizedText == null || normalizedText.isBlank()) {
            return out;
        }

        String[] sections = SECTION_ANCHOR.split(normalizedText);
        int step = chunkSize - overlap;
        int idx = 0;

        for (String section : sections) {
            if (section.isBlank()) {
                continue;
            }
            String anchor = extractAnchor(section);
            String trimmed = section.trim();

            if (trimmed.length() <= chunkSize) {
                out.add(new Chunk(trimmed, anchor, idx++));
                continue;
            }

            for (int i = 0; i < trimmed.length(); i += step) {
                int end = Math.min(i + chunkSize, trimmed.length());
                String slice = trimmed.substring(i, end);
                out.add(new Chunk(slice, anchor, idx++));
                if (end == trimmed.length()) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 从节文本中提取首个"第 X 条/章"作为该节 anchor。
     *
     * @param section 粗切出的节文本
     * @return 首个条款锚字符串；无匹配时返回 {@code ""}
     */
    private String extractAnchor(String section) {
        Matcher m = Pattern.compile("(第[一二三四五六七八九十百千0-9]+[条章节])").matcher(section);
        return m.find() ? m.group(1) : "";
    }
}
