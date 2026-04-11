package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.shared.exception.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 文档文本提取器。
 */
@Component
public class DocumentTextExtractor {

    /**
     * 从上传文件中提取纯文本，MVP 先支持 txt、md、pdf。
     */
    public String extractText(String fileName, String contentType, byte[] fileBytes) {
        String extension = resolveExtension(fileName);

        try {
            if ("pdf".equals(extension) || isPdfContentType(contentType)) {
                return extractPdfText(fileBytes);
            }
            if ("txt".equals(extension) || "md".equals(extension) || isPlainTextContentType(contentType)) {
                return new String(fileBytes, StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new ApiException("DOCUMENT_TEXT_EXTRACT_FAILED", "Failed to extract document text", HttpStatus.BAD_REQUEST);
        }

        throw new ApiException("DOCUMENT_TYPE_NOT_SUPPORTED", "Only txt, md and pdf are supported in MVP", HttpStatus.BAD_REQUEST);
    }

    /**
     * 提取 PDF 文本。
     */
    private String extractPdfText(byte[] fileBytes) throws IOException {
        try (PDDocument document = PDDocument.load(fileBytes)) {
            PDFTextStripper textStripper = new PDFTextStripper();
            return textStripper.getText(document);
        }
    }

    /**
     * 解析文件扩展名。
     */
    private String resolveExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 判断是否是 PDF 类型。
     */
    private boolean isPdfContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf");
    }

    /**
     * 判断是否是纯文本类型。
     */
    private boolean isPlainTextContentType(String contentType) {
        return contentType != null && (
                contentType.toLowerCase(Locale.ROOT).contains("text/plain")
                        || contentType.toLowerCase(Locale.ROOT).contains("text/markdown")
        );
    }
}
