package com.enjoy.agent.support.skill;

import com.enjoy.agent.shared.exception.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 必须经过显式用户确认才能执行的客服 Skill。
 *
 * <p>把确认型 Skill 与普通查询 Skill 分成两个接口，是为了让“忘记判断确认状态”在
 * 代码结构上更难发生。编排阶段只能调用 {@link #preview}；确认接口验证数据库中的
 * 提案以后，才允许调用 {@link #executeConfirmed}。</p>
 */
public interface ConfirmableSupportSkill extends SupportSkill {

    SupportActionPreview preview(
            SupportSkillExecutionContext context,
            Map<String, Object> requestedArguments
    );

    SupportSkillResult executeConfirmed(
            SupportSkillExecutionContext context,
            Map<String, Object> normalizedArguments,
            SupportActionPreview originalPreview
    );

    /**
     * 通用只读执行入口永远不能执行写 Skill；即使编排器将来误调用，也会 fail-closed。
     */
    @Override
    default SupportSkillResult execute(SupportSkillExecutionContext context, SupportSkillRequest request) {
        throw new ApiException(
                "SUPPORT_CONFIRMATION_REQUIRED",
                "该操作需要你明确确认后才能执行",
                HttpStatus.CONFLICT
        );
    }
}
