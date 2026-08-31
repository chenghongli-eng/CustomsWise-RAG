package com.customswise.rag.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.*;

/**
 * PaddleOCR HTTP 兜底提取器：扫描件 / PDFBox 失败后，优先于 tess4j 被调用。
 *
 * <p>架构与 BGE-rerank 一致：Python PaddleOCR 服务运行在本地端口，
 * Java 通过 HTTP 调用，不引入重型 Java OCR 依赖。
 *
 * <p>Python 服务侧接口：
 * POST /ocr  {{"image": "<base64 png>"} }  →  {{"text": "...", "elapsed_ms": 123}}
 *
 * <p>优先级 15：介于 PDFBox(10) 和 Tesseract(20) 之间。
 */
@Slf4j
@Component
public class PaddleOcrExtractor implements PdfExtractor {

    @Value("${paddleocr.enabled:true}")
    private boolean enabled;

    @Value("${paddleocr.url:http://127.0.0.1:8002/ocr}")
    private String ocrUrl;

    @Value("${paddleocr.timeout-ms:10000}")
    private int timeoutMs;

    @Value("${paddleocr.dpi:250}")
    private int ocrDpi;

    /** Python 服务请求超时（毫秒）。 */
    @Value("${paddleocr.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final ExecutorService pagePool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "paddle-ocr-page-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });

    public PaddleOcrExtractor(
            @Qualifier("minimaxHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public String name() {
        return "PaddleOcr";
    }

    @Override
    public ExtractionResult extract(Path file) {
        long t0 = System.currentTimeMillis();
        if (!enabled) {
            return ExtractionResult.empty(name(), System.currentTimeMillis() - t0,
                    "PaddleOCR disabled by config");
        }

        File f = file.toFile();
        try (PDDocument doc = Loader.loadPDF(f)) {
            int pages = doc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder sb = new StringBuilder();

            java.util.List<CompletableFuture<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < pages; i++) {
                final int pageIdx = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> ocrPage(renderer, pageIdx), pagePool));
            }

            int timeoutSec = Math.max(5, timeoutMs / 1000);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    String pageText = futures.get(i).get(timeoutSec, TimeUnit.SECONDS);
                    if (pageText != null && !pageText.isBlank()) {
                        sb.append(pageText).append("\n\n");
                    }
                } catch (TimeoutException te) {
                    log.warn("[PADDLE_OCR] page {} timeout after {}s", i, timeoutSec);
                } catch (Exception e) {
                    log.warn("[PADDLE_OCR] page {} failed: {}", i, e.getMessage());
                }
            }

            String text = sb.toString().trim();
            return new ExtractionResult(
                    text, pages, true,
                    System.currentTimeMillis() - t0,
                    name(),
                    null);
        } catch (Exception e) {
            log.warn("[PADDLE_OCR] extract failed on {}: {}", file, e.getMessage());
            return ExtractionResult.empty(name(), System.currentTimeMillis() - t0, e.getMessage());
        }
    }

    /**
     * 单页 OCR：渲染为 PNG base64 → POST /ocr → 解析 text。
     *
     * @return 该页识别文本；失败时返回 ""
     */
    private String ocrPage(PDFRenderer renderer, int pageIdx) {
        try {
            BufferedImage img = renderer.renderImageWithDPI(pageIdx, ocrDpi, ImageType.RGB);
            String base64 = pngBase64(img);

            var body = new java.util.HashMap<String, String>();
            body.put("image", base64);

            HttpPost post = new HttpPost(ocrUrl);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

            RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                    .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                    .build();
            post.setConfig(config);

            String jsonResponse = httpClient.execute(post, response ->
                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));

            JsonNode root = objectMapper.readTree(jsonResponse);
            String text = root.path("text").asText("");
            if (text.isBlank()) {
                return "";
            }
            log.debug("[PADDLE_OCR] page {} done, chars={}", pageIdx, text.length());
            return text;

        } catch (Exception e) {
            log.warn("[PADDLE_OCR] page {} error: {}", pageIdx, e.getMessage());
            return "";
        }
    }

    private String pngBase64(BufferedImage img) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
