package com.customswise.rag.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 基于 Apache PDFBox 3.x 的提取器（主路径）。
 *
 * <p>改进点（vs DocumentService 旧版）：
 * <ol>
 *   <li>{@code setSortByPosition(true)}：解决多栏排版顺序错乱</li>
 *   <li>不使用旧 {@code PDFTextStripper} 的低层 writeString 重写，依赖 PDFBox 自带的页眉/页脚启发式</li>
 *   <li>异常不外抛（包括 IOException / fontbox 字体子集化错误），返回
 *       {@link ExtractionResult#empty}，让 {@link PdfExtractorFactory} 降级 OCR</li>
 * </ol>
 *
 * <p>priority=10，被 {@link PdfExtractorFactory} 优先选择。
 */
@Slf4j
@Component
public class PdfBoxExtractor implements PdfExtractor {

    /**
     * 优先级 10：被 {@link PdfExtractorFactory} 选为主路径。
     */
    @Override
    public int priority() {
        return 10;
    }

    /**
     * 实现名："PdfBox"。
     */
    @Override
    public String name() {
        return "PdfBox";
    }

    /**
     * 提取入口：用 PDFBox 3.x 加载并 {@code getText}。
     *
     * <p>{@code setSortByPosition(true)} 让 PDFBox 按 Y/X 坐标排序后再输出，
     * 解决多栏排版的阅读顺序问题（PDF 默认按内容流顺序输出，多栏会交错）。
     *
     * <p>异常处理：IOException（文件读不到、PDF 损坏）和其它 RuntimeException
     * （含 fontbox 字体子集化错误，常见于 WPS 导出 PDF）均不外抛，
     * 返回空 ExtractionResult 让工厂降级 OCR。
     *
     * @param file PDF 文件绝对路径
     * @return ExtractionResult；失败时 text=""、error 非空
     */
    @Override
    public ExtractionResult extract(Path file) {
        long t0 = System.currentTimeMillis();
        File f = file.toFile();
        try (PDDocument doc = Loader.loadPDF(f)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return new ExtractionResult(
                    text == null ? "" : text,
                    doc.getNumberOfPages(),
                    false,
                    System.currentTimeMillis() - t0,
                    name(),
                    null);
        } catch (IOException e) {
            log.warn("PdfBoxExtractor failed on {}: {}", file, e.getMessage());
            return ExtractionResult.empty(name(), System.currentTimeMillis() - t0, e.getMessage());
        } catch (Exception e) {
            // fontbox / WPS 字体子集化错误等都走这里
            log.warn("PdfBoxExtractor unexpected error on {}: {}", file, e.getMessage());
            return ExtractionResult.empty(name(), System.currentTimeMillis() - t0, e.getMessage());
        }
    }
}
