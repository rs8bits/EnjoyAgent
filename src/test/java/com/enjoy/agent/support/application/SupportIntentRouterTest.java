package com.enjoy.agent.support.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.enjoy.agent.support.domain.enums.SupportRouteType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupportIntentRouterTest {

    private SupportIntentRouter router;

    @BeforeEach
    void setUp() {
        SupportProperties properties = new SupportProperties();
        properties.setMaxSkillsPerTurn(3);
        router = new SupportIntentRouter(properties);
    }

    @Test
    void pureGreeting_shouldUseRuleWithoutCallingModelOrSkill() {
        SupportIntentDecision result = router.route(" 你好！ ");

        assertThat(result.routeType()).isEqualTo(SupportRouteType.RULE);
        assertThat(result.intentCode()).isEqualTo("GREETING");
        assertThat(result.skillNames()).isEmpty();
    }

    @Test
    void greetingWithBusinessRequest_mustNotBeSwallowedByGreetingRule() {
        SupportIntentDecision result = router.route("你好，帮我查询当前工作区的 Agent");

        assertThat(result.routeType()).isEqualTo(SupportRouteType.LLM);
        assertThat(result.skillNames()).isEmpty();
    }

    @Test
    void assetOverview_shouldBeDelegatedToTheBoundedLlmPlanner() {
        SupportIntentDecision result = router.route("给我看看全部资产概览");

        assertThat(result.routeType()).isEqualTo(SupportRouteType.LLM);
        assertThat(result.skillNames()).isEmpty();
    }

    @Test
    void complexProjectQuestion_shouldInjectProjectInfoSkill() {
        SupportIntentDecision result = router.route("EnjoyAgent 的知识和计费能力分别有哪些？");

        assertThat(result.routeType()).isEqualTo(SupportRouteType.LLM);
        assertThat(result.skillNames()).isEmpty();
    }

    @Test
    void mutationRequest_shouldGoToPlannerAndWaitForConfirmation() {
        SupportIntentDecision result = router.route("你好，帮我删除 Agent ID 12");

        assertThat(result.routeType()).isEqualTo(SupportRouteType.LLM);
        assertThat(result.intentCode()).isEqualTo("GENERAL_SUPPORT");
        assertThat(result.skillNames()).isEmpty();
    }

    @Test
    void rechargeStatusQuery_shouldNotBeMistakenForRechargeCreation() {
        SupportIntentDecision result = router.route("我要查询充值申请的审核状态");

        assertThat(result.routeType()).isEqualTo(SupportRouteType.LLM);
        assertThat(result.skillNames()).isEmpty();
    }

    @Test
    void howToQuestion_shouldRemainAnInformationalQuestion() {
        SupportIntentDecision result = router.route("如何创建一个 Agent？");

        assertThat(result.routeType()).isEqualTo(SupportRouteType.LLM);
        assertThat(result.skillNames()).isEmpty();
    }
}
