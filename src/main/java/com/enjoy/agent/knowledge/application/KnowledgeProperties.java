package com.enjoy.agent.knowledge.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库处理参数配置。
 */
@ConfigurationProperties(prefix = "enjoy.knowledge")
public class KnowledgeProperties {

    /**
     * 单个切片的最大字符数。
     */
    private int chunkSize = 800;

    /**
     * 相邻切片的重叠字符数。
     */
    private int chunkOverlap = 100;

    /**
     * 最终注入模型的知识片段数量。
     */
    private int retrievalTopK = 4;

    /**
     * 向量召回阶段的候选数量。
     */
    private int recallTopK = 12;

    /**
     * 是否启用查询改写。
     */
    private boolean queryRewriteEnabled = true;

    /**
     * 查询改写时最多携带多少条最近会话消息。
     */
    private int queryRewriteMaxContextMessages = 6;

    /**
     * 是否返回检索调试信息。
     */
    private boolean retrievalDebugEnabled = true;

    /**
     * 百炼重排接口地址。
     */
    private String rerankBaseUrl = "https://dashscope.aliyuncs.com";

    /**
     * 当前知识库向量维度。
     */
    private int embeddingDimensions = 1024;

    /**
     * 单次调用上游 Embedding API 的最大文本条数。
     */
    private int embeddingBatchSize = 20;

    /** Maximum accepted source object size before it is buffered or parsed. */
    private long maxFileSizeBytes = 20L * 1024L * 1024L;

    /** Maximum number of PDF pages parsed for one document. */
    private int maxPdfPages = 500;

    /** Maximum extracted characters retained in memory. */
    private int maxExtractedTextChars = 2_000_000;

    /** Maximum number of chunks generated for one document. */
    private int maxChunksPerDocument = 10_000;

    /** FAILED documents still consume source storage and therefore count until deleted. */
    private long maxDocumentsPerTenant = 1_000;
    private long maxSourceBytesPerTenant = 2L * 1024L * 1024L * 1024L;
    private long maxChunksPerTenant = 200_000;
    private Duration extractionTimeout = Duration.ofMinutes(2);
    private int maxConcurrentExtractions = 4;
    private Duration extractionAdmissionTimeout = Duration.ofSeconds(1);

    private Duration rerankConnectTimeout = Duration.ofSeconds(3);
    private Duration rerankReadTimeout = Duration.ofSeconds(10);
    private int rerankMaxResponseBytes = 1024 * 1024;
    private Duration rerankRequestTimeout = Duration.ofSeconds(15);
    private Duration embeddingConnectTimeout = Duration.ofSeconds(3);
    private Duration embeddingReadTimeout = Duration.ofSeconds(20);
    private Duration embeddingRequestTimeout = Duration.ofSeconds(30);
    private int embeddingMaxResponseBytes = 8 * 1024 * 1024;
    private int maxActiveImportsPerTenant = 8;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("Knowledge chunk size must be positive");
        }
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("Knowledge chunk overlap must not be negative");
        }
        this.chunkOverlap = chunkOverlap;
    }

    public int getRetrievalTopK() {
        return retrievalTopK;
    }

    public void setRetrievalTopK(int retrievalTopK) {
        if (retrievalTopK < 1) {
            throw new IllegalArgumentException("Knowledge retrieval top-k must be positive");
        }
        this.retrievalTopK = retrievalTopK;
    }

    public int getRecallTopK() {
        return recallTopK;
    }

    public void setRecallTopK(int recallTopK) {
        if (recallTopK < 1) {
            throw new IllegalArgumentException("Knowledge recall top-k must be positive");
        }
        this.recallTopK = recallTopK;
    }

    public boolean isQueryRewriteEnabled() {
        return queryRewriteEnabled;
    }

    public void setQueryRewriteEnabled(boolean queryRewriteEnabled) {
        this.queryRewriteEnabled = queryRewriteEnabled;
    }

    public int getQueryRewriteMaxContextMessages() {
        return queryRewriteMaxContextMessages;
    }

    public void setQueryRewriteMaxContextMessages(int queryRewriteMaxContextMessages) {
        if (queryRewriteMaxContextMessages < 0) {
            throw new IllegalArgumentException("Knowledge query rewrite context size must not be negative");
        }
        this.queryRewriteMaxContextMessages = queryRewriteMaxContextMessages;
    }

    public boolean isRetrievalDebugEnabled() {
        return retrievalDebugEnabled;
    }

    public void setRetrievalDebugEnabled(boolean retrievalDebugEnabled) {
        this.retrievalDebugEnabled = retrievalDebugEnabled;
    }

    public String getRerankBaseUrl() {
        return rerankBaseUrl;
    }

    public void setRerankBaseUrl(String rerankBaseUrl) {
        this.rerankBaseUrl = rerankBaseUrl;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        if (embeddingDimensions < 1) {
            throw new IllegalArgumentException("Embedding dimensions must be positive");
        }
        this.embeddingDimensions = embeddingDimensions;
    }

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        if (embeddingBatchSize < 1) {
            throw new IllegalArgumentException("Embedding batch size must be positive");
        }
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        if (maxFileSizeBytes < 1) {
            throw new IllegalArgumentException("Knowledge maximum file size must be positive");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getMaxPdfPages() {
        return maxPdfPages;
    }

    public void setMaxPdfPages(int maxPdfPages) {
        if (maxPdfPages < 1) {
            throw new IllegalArgumentException("Knowledge maximum PDF pages must be positive");
        }
        this.maxPdfPages = maxPdfPages;
    }

    public int getMaxExtractedTextChars() {
        return maxExtractedTextChars;
    }

    public void setMaxExtractedTextChars(int maxExtractedTextChars) {
        if (maxExtractedTextChars < 1) {
            throw new IllegalArgumentException("Knowledge maximum extracted text characters must be positive");
        }
        this.maxExtractedTextChars = maxExtractedTextChars;
    }

    public int getMaxChunksPerDocument() {
        return maxChunksPerDocument;
    }

    public void setMaxChunksPerDocument(int maxChunksPerDocument) {
        if (maxChunksPerDocument < 1) {
            throw new IllegalArgumentException("Knowledge maximum chunks must be positive");
        }
        this.maxChunksPerDocument = maxChunksPerDocument;
    }

    public long getMaxDocumentsPerTenant() {
        return maxDocumentsPerTenant;
    }

    public void setMaxDocumentsPerTenant(long maxDocumentsPerTenant) {
        if (maxDocumentsPerTenant < 1) {
            throw new IllegalArgumentException("Knowledge tenant document quota must be positive");
        }
        this.maxDocumentsPerTenant = maxDocumentsPerTenant;
    }

    public long getMaxSourceBytesPerTenant() {
        return maxSourceBytesPerTenant;
    }

    public void setMaxSourceBytesPerTenant(long maxSourceBytesPerTenant) {
        if (maxSourceBytesPerTenant < 1) {
            throw new IllegalArgumentException("Knowledge tenant source byte quota must be positive");
        }
        this.maxSourceBytesPerTenant = maxSourceBytesPerTenant;
    }

    public long getMaxChunksPerTenant() {
        return maxChunksPerTenant;
    }

    public void setMaxChunksPerTenant(long maxChunksPerTenant) {
        if (maxChunksPerTenant < 1) {
            throw new IllegalArgumentException("Knowledge tenant chunk quota must be positive");
        }
        this.maxChunksPerTenant = maxChunksPerTenant;
    }

    public Duration getExtractionTimeout() {
        return extractionTimeout;
    }

    public void setExtractionTimeout(Duration extractionTimeout) {
        this.extractionTimeout = requirePositive(extractionTimeout, "document extraction timeout");
    }

    public int getMaxConcurrentExtractions() {
        return maxConcurrentExtractions;
    }

    public void setMaxConcurrentExtractions(int maxConcurrentExtractions) {
        if (maxConcurrentExtractions < 1) {
            throw new IllegalArgumentException("Knowledge concurrent extraction limit must be positive");
        }
        this.maxConcurrentExtractions = maxConcurrentExtractions;
    }

    public Duration getExtractionAdmissionTimeout() {
        return extractionAdmissionTimeout;
    }

    public void setExtractionAdmissionTimeout(Duration extractionAdmissionTimeout) {
        this.extractionAdmissionTimeout = requirePositive(
                extractionAdmissionTimeout,
                "document extraction admission timeout"
        );
    }

    public Duration getRerankConnectTimeout() {
        return rerankConnectTimeout;
    }

    public void setRerankConnectTimeout(Duration rerankConnectTimeout) {
        this.rerankConnectTimeout = requirePositive(rerankConnectTimeout, "rerank connect timeout");
    }

    public Duration getRerankReadTimeout() {
        return rerankReadTimeout;
    }

    public void setRerankReadTimeout(Duration rerankReadTimeout) {
        this.rerankReadTimeout = requirePositive(rerankReadTimeout, "rerank read timeout");
    }

    public int getRerankMaxResponseBytes() {
        return rerankMaxResponseBytes;
    }

    public void setRerankMaxResponseBytes(int rerankMaxResponseBytes) {
        if (rerankMaxResponseBytes < 1) {
            throw new IllegalArgumentException("Rerank maximum response bytes must be positive");
        }
        this.rerankMaxResponseBytes = rerankMaxResponseBytes;
    }

    public Duration getRerankRequestTimeout() {
        return rerankRequestTimeout;
    }

    public void setRerankRequestTimeout(Duration rerankRequestTimeout) {
        this.rerankRequestTimeout = requirePositive(rerankRequestTimeout, "rerank request timeout");
    }

    public Duration getEmbeddingConnectTimeout() {
        return embeddingConnectTimeout;
    }

    public void setEmbeddingConnectTimeout(Duration embeddingConnectTimeout) {
        this.embeddingConnectTimeout = requirePositive(embeddingConnectTimeout, "embedding connect timeout");
    }

    public Duration getEmbeddingReadTimeout() {
        return embeddingReadTimeout;
    }

    public void setEmbeddingReadTimeout(Duration embeddingReadTimeout) {
        this.embeddingReadTimeout = requirePositive(embeddingReadTimeout, "embedding read timeout");
    }

    public Duration getEmbeddingRequestTimeout() {
        return embeddingRequestTimeout;
    }

    public void setEmbeddingRequestTimeout(Duration embeddingRequestTimeout) {
        this.embeddingRequestTimeout = requirePositive(embeddingRequestTimeout, "embedding request timeout");
    }

    public int getEmbeddingMaxResponseBytes() {
        return embeddingMaxResponseBytes;
    }

    public void setEmbeddingMaxResponseBytes(int embeddingMaxResponseBytes) {
        if (embeddingMaxResponseBytes < 1) {
            throw new IllegalArgumentException("Embedding maximum response bytes must be positive");
        }
        this.embeddingMaxResponseBytes = embeddingMaxResponseBytes;
    }

    public int getMaxActiveImportsPerTenant() {
        return maxActiveImportsPerTenant;
    }

    public void setMaxActiveImportsPerTenant(int maxActiveImportsPerTenant) {
        if (maxActiveImportsPerTenant < 1) {
            throw new IllegalArgumentException("Knowledge active import limit must be positive");
        }
        this.maxActiveImportsPerTenant = maxActiveImportsPerTenant;
    }

    private Duration requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Knowledge " + name + " must be positive");
        }
        return value;
    }
}
