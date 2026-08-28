package com.customswise.rag.pdf;

/**
 * PDF 文本提取结果（record，Java 16+ 不可变载体）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code text}：规范化前的原始提取文本（仍含页眉/页码等噪声，由 TextNormalizer 处理）</li>
 *   <li>{@code pages}：PDF 总页数（PDFBox / OCR 各自统计）</li>
 *   <li>{@code usedOcr}：true 表示走 OCR 路径（含 fallback 链）</li>
 *   <li>{@code elapsedMs}：本次 extract 耗时</li>
 *   <li>{@code extractor}：实现名（"PdfBox" / "Ocr" / "PdfBox+Ocr"）</li>
 *   <li>{@code error}：可读错误信息；null 表示成功</li>
 * </ul>
 */
public record ExtractionResult(
        String text,
        int pages,
        boolean usedOcr,
        long elapsedMs,
        String extractor,
        String error
) {
    /**
     * 是否无可用文本（null 或全空白字符）。
     *
     * @return true 当 text 为 null 或仅含空白
     */
    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    /**
     * 工厂方法：构造一个"提取失败"的结果（text=""，error 非空）。
     *
     * @param extractor 失败的实现名
     * @param elapsedMs 已耗时（用于失败路径的耗时统计）
     * @param error     失败原因
     * @return 失败状态的 ExtractionResult
     */
    public static ExtractionResult empty(String extractor, long elapsedMs, String error) {
        return new ExtractionResult("", 0, false, elapsedMs, extractor, error);
    }
}
