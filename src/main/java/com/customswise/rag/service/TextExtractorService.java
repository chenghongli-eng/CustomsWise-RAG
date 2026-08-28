package com.customswise.rag.service;

import com.customswise.rag.pdf.ExtractionResult;
import com.customswise.rag.pdf.PdfExtractorFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * PDF 文本提取服务（块 C+D 演进后的版本）。
 *
 * <p>职责单一：委托给 {@link PdfExtractorFactory} 选择最佳提取器并返回结构化结果。
 * 业务层（IngestionService / MigrationService）只看到 ExtractionResult，无需关心
 * 内部是 PDFBox 还是 OCR 在跑。
 *
 * <p>调用方约定：
 * <ol>
 *   <li>返回值 {@link ExtractionResult#isEmpty()} 为 true 时业务层应终止并 mark FAILED</li>
 *   <li>文本规范化（去页眉/页码/合并空白）由调用方调用 {@link com.customswise.rag.pdf.TextNormalizer#normalize(String)} 完成，
 *       本服务只返回原始提取文本</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextExtractorService {

    /** 提取策略工厂（Spring 自动注入所有 PdfExtractor 实现，按 priority 选择）。 */
    private final PdfExtractorFactory factory;

    /**
     * 从文件路径提取文本。委托给工厂：先 PDFBox，过短/异常则降级 OCR。
     *
     * @param filePath PDF 文件绝对路径
     * @return ExtractionResult（含 text / pages / usedOcr / elapsedMs / extractor / error）
     * @throws IOException 当工厂自身 IO 异常（非 PDF 解析异常）时抛出
     */
    public ExtractionResult extractFromPath(Path filePath) throws IOException {
        return factory.extract(filePath);
    }

    /**
     * 兼容旧 API（DocumentService.extractFromPdf 删除前调用过）：返回纯文本，失败时返回空串。
     *
     * <p>新代码请使用 {@link #extractFromPath(Path)} 以便拿到完整 ExtractionResult
     * （usedOcr / error 等诊断字段）。
     *
     * @param file PDF 文件
     * @return 提取到的纯文本；提取失败时返回 {@code ""}
     * @throws IOException 当工厂自身 IO 异常时抛出
     */
    public String extractFromPdf(File file) throws IOException {
        ExtractionResult r = factory.extract(file.toPath());
        return r.text();
    }
}