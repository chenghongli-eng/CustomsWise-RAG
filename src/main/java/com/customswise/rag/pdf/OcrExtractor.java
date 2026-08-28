package com.customswise.rag.pdf;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.*;

/**
 * OCR 兜底提取器：扫描件 / PDFBox 失败时降级到 tess4j。
 *
 * <p>系统依赖（运行时必须安装，否则 OCR 路径不可用）：
 * <ul>
 *   <li>Linux:  apt install tesseract-ocr tesseract-ocr-chi-sim</li>
 *   <li>macOS:  brew install tesseract tesseract-lang</li>
 *   <li>数据路径通过 {@code tesseract.data-path} 配置项指定（默认 /usr/share/tesseract-ocr/4.00/tessdata）</li>
 * </ul>
 *
 * <p>性能：单文档内页级 CompletableFuture 并发 OCR（pagePool 控制并发）。
 * 适用于小到中等页数文档；超大文档（>500 页）建议关闭并发（pagePool=1）避免内存爆。
 */
@Slf4j
@Component
public class OcrExtractor implements PdfExtractor {

    /** tessdata 目录（包含 .traineddata 文件）。 */
    @Value("${tesseract.data-path:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessDataPath;

    /** OCR 语言包（默认中文简体+英文）。 */
    @Value("${tesseract.language:chi_sim+eng}")
    private String language;

    /** 渲染 DPI（值越高识别精度越好，但耗时和内存增加）。 */
    @Value("${tesseract.ocr-dpi:200}")
    private int ocrDpi;

    /** 总开关：false 时直接返回 disabled 错误，让上游不要走 OCR。 */
    @Value("${tesseract.enabled:true}")
    private boolean enabled;

    /** 单页 OCR 超时（秒）；超时该页丢弃但不影响其它页。 */
    @Value("${tesseract.timeout-seconds:30}")
    private int timeoutSeconds;

    /** 页级 OCR 线程池：单文档内页之间并发；线程数 = max(2, CPU/2)。 */
    private final ExecutorService pagePool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "ocr-page-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });

    /**
     * 优先级 20：仅当 PDFBox 主路径失败或返回过短文本时被选中。
     */
    @Override
    public int priority() {
        return 20;
    }

    /**
     * 实现名："Ocr"。
     */
    @Override
    public String name() {
        return "Ocr";
    }

    /**
     * OCR 提取入口：渲染每页 → 并发 tesseract.doOCR → 拼接全文。
     *
     * <p>失败容忍：
     * <ul>
     *   <li>tesseract 初始化失败 → 直接返回 empty，让上层知道 OCR 不可用</li>
     *   <li>单页 OCR 超时/异常 → 跳过该页，继续下一页，整体结果可能部分缺失</li>
     *   <li>整体异常 → 返回 empty(error=msg)</li>
     * </ul>
     *
     * @param file PDF 文件绝对路径
     * @return ExtractionResult（usedOcr=true）；失败时 text=""、error 非空
     */
    @Override
    public ExtractionResult extract(Path file) {
        long t0 = System.currentTimeMillis();
        if (!enabled) {
            return ExtractionResult.empty(name(), System.currentTimeMillis() - t0,
                    "OCR disabled by config");
        }

        Tesseract tesseract;
        try {
            tesseract = new Tesseract();
            tesseract.setDatapath(tessDataPath);
            tesseract.setLanguage(language);
            tesseract.setOcrEngineMode(1);  // LSTM
        } catch (Exception e) {
            log.warn("[OCR] init failed: {}", e.getMessage());
            return ExtractionResult.empty(name(), System.currentTimeMillis() - t0,
                    "tesseract init: " + e.getMessage());
        }

        File f = file.toFile();
        try (PDDocument doc = Loader.loadPDF(f)) {
            int pages = doc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder sb = new StringBuilder();

            java.util.List<CompletableFuture<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < pages; i++) {
                final int pageIdx = i;
                futures.add(CompletableFuture.supplyAsync(() -> ocrPage(tesseract, renderer, pageIdx), pagePool));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    String pageText = futures.get(i).get(timeoutSeconds, TimeUnit.SECONDS);
                    if (pageText != null && !pageText.isBlank()) {
                        sb.append(pageText).append("\n\n");
                    }
                } catch (TimeoutException te) {
                    log.warn("[OCR] page {} timeout after {}s", i, timeoutSeconds);
                } catch (Exception e) {
                    log.warn("[OCR] page {} failed: {}", i, e.getMessage());
                }
            }

            String text = sb.toString().trim();
            return new ExtractionResult(
                    text, pages, true,
                    System.currentTimeMillis() - t0,
                    name(),
                    null);
        } catch (Exception e) {
            log.warn("[OCR] extract failed on {}: {}", file, e.getMessage());
            return ExtractionResult.empty(name(), System.currentTimeMillis() - t0, e.getMessage());
        }
    }

    /**
     * 单页 OCR：渲染 → doOCR。失败返回空串，让调用方跳过该页。
     *
     * @param tesseract 共享的 Tesseract 实例（线程不安全，但每个 page task 内只用一次）
     * @param renderer  PDF 渲染器
     * @param pageIdx   0-based 页码
     * @return 该页 OCR 文本；失败时返回 {@code ""}
     */
    private String ocrPage(Tesseract tesseract, PDFRenderer renderer, int pageIdx) {
        try {
            BufferedImage img = renderer.renderImageWithDPI(pageIdx, ocrDpi, ImageType.RGB);
            return tesseract.doOCR(img);
        } catch (TesseractException | java.io.IOException e) {
            log.warn("[OCR] page {} error: {}", pageIdx, e.getMessage());
            return "";
        }
    }
}
