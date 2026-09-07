package com.enjoy.agent.support.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.support.domain.entity.SupportSkillCall;
import com.enjoy.agent.support.domain.entity.SupportTurn;
import com.enjoy.agent.support.domain.enums.SupportSkillCallStatus;
import com.enjoy.agent.support.domain.enums.SupportTurnStatus;
import com.enjoy.agent.support.infrastructure.persistence.SupportSkillCallRepository;
import com.enjoy.agent.support.infrastructure.persistence.SupportTurnRepository;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把每次 Skill 尝试写成独立、可审计的短事务。 */
@Service
public class SupportSkillCallArchiveService {

    private final SupportSkillCallRepository skillCallRepository;
    private final SupportTurnRepository turnRepository;

    public SupportSkillCallArchiveService(
            SupportSkillCallRepository skillCallRepository,
            SupportTurnRepository turnRepository
    ) {
        this.skillCallRepository = skillCallRepository;
        this.turnRepository = turnRepository;
    }

    @Transactional
    public UUID start(
            AuthenticatedUser user,
            UUID turnId,
            UUID ownerToken,
            SupportSkillManifest manifest,
            String archivedRequestPayload
    ) {
        SupportTurn turn = requireOwnedTurn(user, turnId);
        if (turn.getStatus() != SupportTurnStatus.RUNNING
                || ownerToken == null
                || !ownerToken.equals(turn.getOwnerToken())) {
            throw new ApiException(
                    "SUPPORT_TURN_OWNERSHIP_LOST",
                    "客服请求执行权已失效，请刷新历史记录",
                    HttpStatus.CONFLICT
            );
        }
        SupportSkillCall call = new SupportSkillCall();
        call.setId(UUID.randomUUID());
        call.setConversation(turn.getConversation());
        call.setTurn(turn);
        call.setTenant(turn.getConversation().getTenant());
        call.setUser(turn.getConversation().getUser());
        // 同一个幂等 Turn 失败后可以重试；旧调用记录必须保留，因此从历史最大序号
        // 继续递增，而不是再次从 1 开始触发唯一键冲突。
        call.setCallIndex(skillCallRepository.findMaxCallIndexByTurnId(turnId) + 1);
        call.setSkillName(manifest.name());
        call.setSkillVersion(manifest.version());
        call.setStatus(SupportSkillCallStatus.RUNNING);
        call.setRequestPayload(archivedRequestPayload);
        return skillCallRepository.saveAndFlush(call).getId();
    }

    @Transactional
    public void complete(
            AuthenticatedUser user,
            UUID callId,
            String archivedResultPayload,
            String displaySummary,
            long durationMs
    ) {
        SupportSkillCall call = requireOwnedCall(user, callId);
        if (call.getStatus() != SupportSkillCallStatus.RUNNING) {
            return;
        }
        call.setStatus(SupportSkillCallStatus.SUCCESS);
        call.setResultPayload(archivedResultPayload);
        call.setDisplaySummary(truncate(displaySummary, 1_000));
        call.setDurationMs(Math.max(0, durationMs));
        call.setFinishedAt(Instant.now());
    }

    @Transactional
    public void fail(
            AuthenticatedUser user,
            UUID callId,
            String errorCode,
            String safeErrorMessage,
            long durationMs
    ) {
        SupportSkillCall call = requireOwnedCall(user, callId);
        // 终态不可被后续 socket 异常倒写。例如 Skill 已成功归档，但向浏览器发送
        // skill_completed 时断连，此时仍应保留真实的 SUCCESS 审计事实。
        if (call.getStatus() != SupportSkillCallStatus.RUNNING) {
            return;
        }
        call.setStatus(SupportSkillCallStatus.FAILED);
        call.setErrorCode(truncate(errorCode, 64));
        call.setErrorMessage(truncate(safeErrorMessage, 512));
        call.setDurationMs(Math.max(0, durationMs));
        call.setFinishedAt(Instant.now());
    }

    private SupportTurn requireOwnedTurn(AuthenticatedUser user, UUID turnId) {
        return turnRepository.findByIdAndConversation_Tenant_IdAndConversation_User_Id(
                        turnId, user.tenantId(), user.userId())
                .orElseThrow(() -> new ApiException(
                        "SUPPORT_TURN_NOT_FOUND", "客服请求不存在", HttpStatus.NOT_FOUND));
    }

    private SupportSkillCall requireOwnedCall(AuthenticatedUser user, UUID callId) {
        return skillCallRepository.findByIdAndTenant_IdAndUser_Id(callId, user.tenantId(), user.userId())
                .orElseThrow(() -> new ApiException(
                        "SUPPORT_SKILL_CALL_NOT_FOUND", "客服查询记录不存在", HttpStatus.NOT_FOUND));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
