package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.shared.exception.ApiException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 文档文本提取器。
 */
@Component
public class DocumentTextExtractor {

    private final KnowledgeProperties properties;
    private final Semaphore extractionSlots;

    public DocumentTextExtractor(KnowledgeProperties properties) {
        this.properties = properties;
        this.extractionSlots = new Semaphore(properties.getMaxConcurrentExtractions(), true);
    }

    /**
     * 从上传文件中提取纯文本，MVP 先支持 txt、md、pdf。
     */
    public String extractText(String fileName, String contentType, byte[] fileBytes) {
        return extractText(fileName, contentType, new ByteArrayInputStream(fileBytes));
    }

    /**
     * Streams text/PDF input and enforces page/character quotas while parsing.
     */
    public String extractText(String fileName, String contentType, InputStream inputStream) {
        acquireExtractionSlot();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        final Future<String> extraction;
        try {
            extraction = executor.submit(() -> {
                try {
                    return extractTextInternal(fileName, contentType, inputStream);
                } finally {
                    // A parser that ignores interruption retains its permit until it really exits,
                    // bounding CPU amplification even though Java cannot safely kill an in-process thread.
                    extractionSlots.release();
                }
            });
        } catch (RuntimeException ex) {
            extractionSlots.release();
            executor.shutdownNow();
            throw ex;
        }
        try {
            return extraction.get(properties.getExtractionTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            extraction.cancel(true);
            throw new ApiException(
                    "DOCUMENT_EXTRACTION_TIMEOUT",
                    "Document extraction exceeded the configured wall-clock limit",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        } catch (InterruptedException ex) {
            extraction.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiException(
                    "DOCUMENT_EXTRACTION_INTERRUPTED",
                    "Document extraction was interrupted",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ApiException(
                    "DOCUMENT_TEXT_EXTRACT_FAILED",
                    "Failed to extract document text",
                    HttpStatus.BAD_REQUEST
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private void acquireExtractionSlot() {
        try {
            if (!extractionSlots.tryAcquire(
                    properties.getExtractionAdmissionTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                throw new ApiException(
                        "DOCUMENT_EXTRACTION_CAPACITY_EXCEEDED",
                        "Document extraction capacity is temporarily exhausted",
                        HttpStatus.TOO_MANY_REQUESTS
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(
                    "DOCUMENT_EXTRACTION_INTERRUPTED",
                    "Document extraction admission was interrupted",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private String extractTextInternal(String fileName, String contentType, InputStream inputStream) {
        String extension = resolveExtension(fileName);

        try {
            BufferedInputStream buffered = inputStream instanceof BufferedInputStream existing
                    ? existing
                    : new BufferedInputStream(inputStream, 8192);
            byte[] sample = sample(buffered, 8192);
            if ("pdf".equals(extension) || isPdfContentType(contentType)) {
                requirePdfSignature(sample);
                return extractPdfText(buffered);
            }
            if ("txt".equals(extension) || "md".equals(extension) || isPlainTextContentType(contentType)) {
                requireTextSignature(sample);
                return readText(buffered);
            }
        } catch (DocumentQuotaExceededException ex) {
            throw new ApiException(ex.code, ex.getMessage(), HttpStatus.PAYLOAD_TOO_LARGE);
        } catch (IOException ex) {
            throw new ApiException("DOCUMENT_TEXT_EXTRACT_FAILED", "Failed to extract document text", HttpStatus.BAD_REQUEST);
        }

        throw new ApiException("DOCUMENT_TYPE_NOT_SUPPORTED", "Only txt, md and pdf are supported in MVP", HttpStatus.BAD_REQUEST);
    }

    /**
     * 提取 PDF 文本。
     */
    private String extractPdfText(InputStream inputStream) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())) {
            if (document.getNumberOfPages() > properties.getMaxPdfPages()) {
                throw new DocumentQuotaExceededException(
                        "DOCUMENT_PDF_PAGE_LIMIT_EXCEEDED",
                        "PDF contains more pages than allowed"
                );
            }
            PDFTextStripper textStripper = new PDFTextStripper() {
                @Override
                public void processPage(PDPage page) throws IOException {
                    requireNotInterrupted();
                    super.processPage(page);
                }

                @Override
                protected void processOperator(Operator operator, java.util.List<COSBase> operands)
                        throws IOException {
                    requireNotInterrupted();
                    super.processOperator(operator, operands);
                }

                @Override
                protected void writeString(String text, java.util.List<TextPosition> textPositions)
                        throws IOException {
                    requireNotInterrupted();
                    super.writeString(text, textPositions);
                }

                @Override
                protected void processTextPosition(TextPosition text) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new ExtractionInterruptedException();
                    }
                    super.processTextPosition(text);
                }
            };
            LimitedStringWriter writer = new LimitedStringWriter(properties.getMaxExtractedTextChars());
            textStripper.setPageEnd("\n\f\n");
            textStripper.writeText(document, writer);
            return writer.toString();
        }
    }

    private String readText(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                requireNotInterrupted();
                if ((long) text.length() + read > properties.getMaxExtractedTextChars()) {
                    throw new DocumentQuotaExceededException(
                            "DOCUMENT_TEXT_LIMIT_EXCEEDED",
                            "Document contains more text than allowed"
                    );
                }
                text.append(buffer, 0, read);
            }
        }
        return text.toString();
    }

    private void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Document extraction was interrupted");
        }
    }

    private byte[] sample(BufferedInputStream inputStream, int maximumBytes) throws IOException {
        inputStream.mark(maximumBytes + 1);
        byte[] sample = inputStream.readNBytes(maximumBytes);
        inputStream.reset();
        return sample;
    }

    private void requirePdfSignature(byte[] sample) {
        if (sample.length < 5
                || sample[0] != '%'
                || sample[1] != 'P'
                || sample[2] != 'D'
                || sample[3] != 'F'
                || sample[4] != '-') {
            throw new ApiException(
                    "DOCUMENT_TYPE_MISMATCH",
                    "PDF file signature does not match its declared type",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private void requireTextSignature(byte[] sample) {
        int suspiciousControls = 0;
        for (byte value : sample) {
            int unsigned = value & 0xff;
            if (unsigned == 0) {
                throw new ApiException(
                        "DOCUMENT_BINARY_CONTENT_REJECTED",
                        "Text document contains binary NUL bytes",
                        HttpStatus.BAD_REQUEST
                );
            }
            if (unsigned < 0x09 || (unsigned > 0x0d && unsigned < 0x20)) {
                suspiciousControls++;
            }
        }
        if (sample.length > 0 && suspiciousControls * 20 > sample.length) {
            throw new ApiException(
                    "DOCUMENT_BINARY_CONTENT_REJECTED",
                    "Text document appears to contain binary content",
                    HttpStatus.BAD_REQUEST
            );
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

    private static final class LimitedStringWriter extends Writer {

        private final int maximumCharacters;
        private final StringBuilder value = new StringBuilder();

        private LimitedStringWriter(int maximumCharacters) {
            this.maximumCharacters = maximumCharacters;
        }

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Document extraction was interrupted");
            }
            if ((long) value.length() + length > maximumCharacters) {
                throw new DocumentQuotaExceededException(
                        "DOCUMENT_TEXT_LIMIT_EXCEEDED",
                        "Document contains more text than allowed"
                );
            }
            value.append(chars, offset, length);
        }

        @Override
        public void flush() {
            // No external resource.
        }

        @Override
        public void close() {
            // No external resource.
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private static final class DocumentQuotaExceededException extends RuntimeException {

        private final String code;

        private DocumentQuotaExceededException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class ExtractionInterruptedException extends RuntimeException {

        private ExtractionInterruptedException() {
            super("Document extraction was interrupted");
        }
    }
}
