package com.enjoy.agent.support.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 客服运行参数。
 *
 * <p>这些上限是客服域自己的硬预算，不复用模型网关面向通用 Agent 的大窗口上限。
 * 这样即使某个模型支持几十万 token，客服仍只发送回答当前问题所需的最小上下文。</p>
 */
@Validated
@ConfigurationProperties(prefix = "enjoy.support")
public class SupportProperties {

    private boolean enabled = true;
    private Long officialModelConfigId;

    @Min(1_000)
    @Max(20_000)
    private int maxUserMessageChars = 4_000;

    @Min(2)
    @Max(40)
    private int recentMessageLimit = 12;

    /** ModelGateway 估算的是 UTF-8 字节级保守上界，因此数值会高于真实 token 数。 */
    @Min(2_000)
    @Max(100_000)
    private int maxInputTokenUpperBound = 16_000;

    @Min(256)
    @Max(20_000)
    private int maxToolResultChars = 4_000;

    /** 客服单次生成 token 上限，会与官方模型自身上限取较小值。 */
    @Min(128)
    @Max(8_192)
    private int maxOutputTokens = 2_048;

    /** 流式累计字符的最后防线，防止异常供应商无限输出。 */
    @Min(512)
    @Max(100_000)
    private int maxAssistantChars = 8_000;

    @Min(1)
    @Max(5)
    private int maxSkillsPerTurn = 3;

    @Min(1)
    @Max(50)
    private int skillPageSize = 10;

    @Min(10_000)
    @Max(600_000)
    private long emitterTimeoutMs = 300_000;

    /** 写操作确认卡片的有效分钟数；过期后必须让模型重新生成操作预览。 */
    @Min(1)
    @Max(1_440)
    private int confirmationTtlMinutes = 10;

    @NotBlank
    private String projectName = "EnjoyAgent";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getOfficialModelConfigId() {
        return officialModelConfigId;
    }

    public void setOfficialModelConfigId(Long officialModelConfigId) {
        this.officialModelConfigId = officialModelConfigId;
    }

    public int getMaxUserMessageChars() {
        return maxUserMessageChars;
    }

    public void setMaxUserMessageChars(int maxUserMessageChars) {
        this.maxUserMessageChars = maxUserMessageChars;
    }

    public int getRecentMessageLimit() {
        return recentMessageLimit;
    }

    public void setRecentMessageLimit(int recentMessageLimit) {
        this.recentMessageLimit = recentMessageLimit;
    }

    public int getMaxInputTokenUpperBound() {
        return maxInputTokenUpperBound;
    }

    public void setMaxInputTokenUpperBound(int maxInputTokenUpperBound) {
        this.maxInputTokenUpperBound = maxInputTokenUpperBound;
    }

    public int getMaxToolResultChars() {
        return maxToolResultChars;
    }

    public void setMaxToolResultChars(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getMaxAssistantChars() {
        return maxAssistantChars;
    }

    public void setMaxAssistantChars(int maxAssistantChars) {
        this.maxAssistantChars = maxAssistantChars;
    }

    public int getMaxSkillsPerTurn() {
        return maxSkillsPerTurn;
    }

    public void setMaxSkillsPerTurn(int maxSkillsPerTurn) {
        this.maxSkillsPerTurn = maxSkillsPerTurn;
    }

    public int getSkillPageSize() {
        return skillPageSize;
    }

    public void setSkillPageSize(int skillPageSize) {
        this.skillPageSize = skillPageSize;
    }

    public long getEmitterTimeoutMs() {
        return emitterTimeoutMs;
    }

    public void setEmitterTimeoutMs(long emitterTimeoutMs) {
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    public int getConfirmationTtlMinutes() {
        return confirmationTtlMinutes;
    }

    public void setConfirmationTtlMinutes(int confirmationTtlMinutes) {
        this.confirmationTtlMinutes = confirmationTtlMinutes;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }
}
