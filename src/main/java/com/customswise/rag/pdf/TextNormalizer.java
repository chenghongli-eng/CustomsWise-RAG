package com.customswise.rag.pdf;

import java.util.regex.Pattern;

/**
 * 文本规范化：在切片前对 PDF 原始输出做清理与段落重组。
 *
 * <p>处理目标：
 * <ol>
 *   <li>去除页眉页脚、页码（"第 X 页 共 Y 页"）</li>
 *   <li>合并连续空白字符</li>
 *   <li>在"第 X 条 / 第 X 章"等条款锚前注入段落分隔 {@code \n\n}</li>
 * </ol>
 *
 * <p>为什么条款锚前要注入 {@code \n\n}：PDFBox 几乎不产段落分隔（用空格衔接），下游
 * {@link SemanticChunker} 的条款锚粗切依赖 {@code \n\n} 边界。这里一次性补齐，
 * 让 chunker 能正确切出"第 X 条 / 第 X 章"为单位的节。
 */
public final class TextNormalizer {

    /** 单独成行的页码（含 -12- / 12 / — 12 — 形式），多行模式。 */
    private static final Pattern PAGE_NUMBER_LINE = Pattern.compile("(?m)^\\s*[-—]?\\s*\\d+\\s*[-—]?\\s*$");
    /** 页脚 "第 X 页" / "第 X 页 共 Y 页"。 */
    private static final Pattern PAGE_FOOTER = Pattern.compile("第\\s*\\d+\\s*页\\s*(共\\s*\\d+\\s*页)?");
    /** 连续空格 / 制表符 → 单空格。 */
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]+");
    /** 连续 3+ 换行 → 双换行（段落分隔）。 */
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");
    /** 条款锚："第 X 条 / 第 X 章 / 第 X 节"，前一个字符不能是中文（避免误命中"第二章第三节"中的"第三节"）。 */
    private static final Pattern CLAUSE_ANCHOR = Pattern.compile(
            "(?m)(?<![\\u4e00-\\u9fa5])(第[一二三四五六七八九十百千0-9]+[条章节])");

    private TextNormalizer() {
    }

    /**
     * 对 PDF 原始文本执行规范化。
     *
     * @param raw PDF 提取器输出的原始字符串（含页眉页脚/多空格/无段落分隔）
     * @return 规范化后的文本；null/空入参返回 {@code ""}
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = PAGE_NUMBER_LINE.matcher(raw).replaceAll("");
        s = PAGE_FOOTER.matcher(s).replaceAll("");
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        s = CLAUSE_ANCHOR.matcher(s).replaceAll("\n\n$1");
        return s.trim();
    }
}
