package com.customswise.rag.pdf;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * PDF 提取策略选择工厂。
 *
 * <p>策略：先按 {@link PdfExtractor#priority()} 选最小值（{@link PdfBoxExtractor}）作为主路径，
 * 判定为"扫描件/提取失败"时按 priority 升序尝试 fallback（含 {@link OcrExtractor}）。
 *
 * <p>判定为"扫描件/提取失败"的启发式（任一满足即触发 fallback）：
 * <ol>
 *   <li>PDFBox 返回 error 字段非空（异常降级）</li>
 *   <li>返回 text 为空（null 或全空白）</li>
 *   <li>text 长度 &lt; 50 字符（绝对下界，任何 PDF 应至少有目录）</li>
 *   <li>text 长度 &lt; pages × 10（按页数线性放宽容忍度，避免误降级长 PDF）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfExtractorFactory {

    /** Spring 注入的所有 PdfExtractor 实现，按 priority 自动选主/降级。 */
    private final List<PdfExtractor> extractors;

    /**
     * 提取入口：自动选择最佳路径。
     *
     * @param file PDF 文件绝对路径
     * @return ExtractionResult；OCR 成功时 usedOcr=true、extractor="PdfBox+Ocr"
     */
    public ExtractionResult extract(Path file) {
        PdfExtractor primary = extractors.stream()
                .min(Comparator.comparingInt(PdfExtractor::priority))
                .orElseThrow(() -> new IllegalStateException("no PdfExtractor available"));

        ExtractionResult r = primary.extract(file);
        log.info("EXTRACT phase=primary extractor={} pages={} chars={} elapsedMs={} error={}",
                r.extractor(), r.pages(), r.text().length(), r.elapsedMs(), r.error());

        if (looksLikeScan(r)) {
            PdfExtractor ocr = extractors.stream()
                    .filter(e -> !e.name().equals(primary.name()))
                    .min(Comparator.comparingInt(PdfExtractor::priority))
                    .orElse(null);
            if (ocr != null) {
                ExtractionResult ocrR = ocr.extract(file);
                log.info("EXTRACT phase=fallback extractor={} chars={} elapsedMs={} error={}",
                        ocrR.extractor(), ocrR.text().length(), ocrR.elapsedMs(), ocrR.error());
                if (!ocrR.isEmpty()) {
                    return new ExtractionResult(
                            ocrR.text(), ocrR.pages(), true,
                            r.elapsedMs() + ocrR.elapsedMs(),
                            primary.name() + "+" + ocr.name(),
                            null);
                }
            }
        }
        return r;
    }

    /**
     * 是否疑似扫描件。详见类注释中的判定规则。
     */
    private boolean looksLikeScan(ExtractionResult r) {
        if (r.error() != null && !r.error().isBlank()) {
            return true;
        }
        if (r.isEmpty()) {
            return true;
        }
        if (r.text().length() < 50) {
            return true;
        }
        return r.pages() > 0 && r.text().length() < r.pages() * 10;
    }
}
