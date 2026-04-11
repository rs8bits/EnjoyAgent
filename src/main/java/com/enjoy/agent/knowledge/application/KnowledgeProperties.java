package com.enjoy.agent.knowledge.application;

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

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getRetrievalTopK() {
        return retrievalTopK;
    }

    public void setRetrievalTopK(int retrievalTopK) {
        this.retrievalTopK = retrievalTopK;
    }

    public int getRecallTopK() {
        return recallTopK;
    }

    public void setRecallTopK(int recallTopK) {
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
        this.embeddingDimensions = embeddingDimensions;
    }
}
