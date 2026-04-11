package com.enjoy.agent.knowledge.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 检索配置。
 */
@ConfigurationProperties(prefix = "enjoy.knowledge.search")
public class KnowledgeSearchProperties {

    /**
     * 是否启用 Elasticsearch 混合检索。
     */
    private boolean enabled;

    /**
     * Elasticsearch HTTP 地址。
     */
    private String baseUrl = "http://localhost:9200";

    /**
     * Elasticsearch 用户名，可为空。
     */
    private String username;

    /**
     * Elasticsearch 密码，可为空。
     */
    private String password;

    /**
     * 知识切片索引名。
     */
    private String indexName = "knowledge-chunk-v1";

    /**
     * 语义召回候选数。
     */
    private int denseTopK = 12;

    /**
     * 关键词召回候选数。
     */
    private int lexicalTopK = 12;

    /**
     * RRF 融合常数。
     */
    private int rrfK = 60;

    /**
     * 连接超时秒数。
     */
    private int connectTimeoutSeconds = 5;

    /**
     * 读超时秒数。
     */
    private int readTimeoutSeconds = 20;

    /**
     * 建索引时的 analyzer。
     */
    private String indexAnalyzer = "standard";

    /**
     * 搜索时的 analyzer。
     */
    private String searchAnalyzer = "standard";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public int getDenseTopK() {
        return denseTopK;
    }

    public void setDenseTopK(int denseTopK) {
        this.denseTopK = denseTopK;
    }

    public int getLexicalTopK() {
        return lexicalTopK;
    }

    public void setLexicalTopK(int lexicalTopK) {
        this.lexicalTopK = lexicalTopK;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public String getIndexAnalyzer() {
        return indexAnalyzer;
    }

    public void setIndexAnalyzer(String indexAnalyzer) {
        this.indexAnalyzer = indexAnalyzer;
    }

    public String getSearchAnalyzer() {
        return searchAnalyzer;
    }

    public void setSearchAnalyzer(String searchAnalyzer) {
        this.searchAnalyzer = searchAnalyzer;
    }
}
