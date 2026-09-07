package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.credential.application.CredentialApplicationService;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialMetadataProjection;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.support.skill.ConfirmableSupportSkill;
import com.enjoy.agent.support.skill.SupportActionPreview;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 删除当前用户凭证的确认型 Skill；模型和确认卡片永远接触不到密文或历史掩码。 */
@Component
public class ApiKeyDeleteSupportSkill implements ConfirmableSupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "api_key.delete", "1.0.0", "删除 API Key",
            "永久删除当前用户自己的上游模型凭证；执行前必须明确确认",
            "{\"type\":\"object\",\"properties\":{\"resourceId\":{\"type\":\"integer\"}},"
                    + "\"required\":[\"resourceId\"],\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"deleted\":{\"type\":\"boolean\"},"
                    + "\"resourceId\":{\"type\":\"integer\"}}}",
            SupportSkillRisk.DESTRUCTIVE, "CURRENT_USER", true, 800
    );

    private final CredentialRepository repository;
    private final CredentialApplicationService applicationService;
    private final SupportSkillPayloadWriter payloadWriter;

    public ApiKeyDeleteSupportSkill(
            CredentialRepository repository,
            CredentialApplicationService applicationService,
            SupportSkillPayloadWriter payloadWriter
    ) {
        this.repository = repository;
        this.applicationService = applicationService;
        this.payloadWriter = payloadWriter;
    }

    @Override
    public SupportSkillManifest manifest() { return MANIFEST; }

    @Override
    @Transactional(readOnly = true)
    public SupportActionPreview preview(
            SupportSkillExecutionContext context,
            Map<String, Object> requestedArguments
    ) {
        Long resourceId = requireResourceId(requestedArguments);
        CredentialMetadataProjection credential = requireCredential(context.userId(), resourceId);
        String name = payloadWriter.safeText(credential.name(), 128);
        return new SupportActionPreview(
                Map.of("resourceId", resourceId),
                "确认删除 API Key",
                "将永久删除你的凭证「" + name + "」(#" + resourceId + ", "
                        + credential.provider().name() + ")。正在被模型配置使用的凭证无法删除。",
                credential.updatedAt()
        );
    }

    @Override
    public SupportSkillResult executeConfirmed(
            SupportSkillExecutionContext context,
            Map<String, Object> normalizedArguments,
            SupportActionPreview originalPreview
    ) {
        Long resourceId = requireResourceId(normalizedArguments);
        CredentialMetadataProjection current = requireCredential(context.userId(), resourceId);
        requireUnchanged(current.updatedAt(), originalPreview.targetUpdatedAt());
        String name = payloadWriter.safeText(current.name(), 128);
        applicationService.deleteCredential(resourceId);
        return new SupportSkillResult(
                payloadWriter.write(Map.of("deleted", true, "resourceId", resourceId, "name", name)),
                "已删除 API Key 凭证「" + name + "」(#" + resourceId + ")"
        );
    }

    private CredentialMetadataProjection requireCredential(Long userId, Long resourceId) {
        return repository.findSupportMetadataByIdAndUserId(resourceId, userId)
                .orElseThrow(() -> new ApiException(
                        "CREDENTIAL_NOT_FOUND", "API Key 不存在或不属于当前用户", HttpStatus.NOT_FOUND));
    }

    private Long requireResourceId(Map<String, Object> arguments) {
        try {
            Object value = arguments == null ? null : arguments.get("resourceId");
            Long id = value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (RuntimeException ex) {
            throw new ApiException("SUPPORT_SKILL_ARGUMENT_INVALID", "请选择要删除的 API Key", HttpStatus.BAD_REQUEST);
        }
    }

    private void requireUnchanged(Instant current, Instant expected) {
        if (expected != null && !expected.equals(current)) {
            throw new ApiException(
                    "SUPPORT_ACTION_TARGET_CHANGED",
                    "API Key 在确认期间已经发生变化，请重新发起删除",
                    HttpStatus.CONFLICT
            );
        }
    }
}
