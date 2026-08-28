package com.customswise.rag.pdf;

import java.nio.file.Path;

/**
 * PDF 文本提取器抽象。块 C+D 不同实现可热插拔。
 *
 * <p>实现列表（按 priority 升序尝试）：
 * <ul>
 *   <li>{@link PdfBoxExtractor} (priority=10) —— PDFBox 3.x 主路径</li>
 *   <li>{@link OcrExtractor} (priority=20) —— tess4j 兜底（扫描件）</li>
 * </ul>
 *
 * <p>调用方：{@link PdfExtractorFactory} 按 priority 选择首个可用的实现，
 * 并根据返回结果决定是否降级到下一优先级。
 */
public interface PdfExtractor {
    /**
     * 从文件路径提取文本。
     *
     * @param file PDF 文件绝对路径
     * @return ExtractionResult。失败时 text=""、error 非空，调用方据此判断降级
     */
    ExtractionResult extract(Path file);

    /**
     * 优先级：数字越小越优先被选择。
     *
     * <p>{@link PdfExtractorFactory} 默认选 priority 最小的作为主路径。
     *
     * @return 优先级整数
     */
    int priority();

    /**
     * 实现名（用于日志）。
     *
     * <p>建议与类名简短形式一致（如 "PdfBox" / "Ocr"），
     * 多个实现被组合调用时会拼成 "PdfBox+Ocr" 形式记录。
     *
     * @return 实现名
     */
    String name();
}
