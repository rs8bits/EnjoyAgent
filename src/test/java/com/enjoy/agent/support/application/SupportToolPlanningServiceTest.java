package com.enjoy.agent.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelGatewayConversationMessage;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.ModelGatewayToolCall;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillRegistry;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 只覆盖规划器最关键的输出边界；完整模型与 Skill 联调留给客服编排测试。 */
@ExtendWith(MockitoExtension.class)
class SupportToolPlanningServiceTest {

    @Mock
    private SupportSkillRegistry skillRegistry;

    @Mock
    private SupportModelResolver modelResolver;

    @Mock
    private ModelGatewayService modelGatewayService;

    private SupportToolPlanningService service;

    @BeforeEach
    void setUp() {
        SupportProperties properties = new SupportProperties();
        properties.setMaxSkillsPerTurn(3);
        properties.setMaxInputTokenUpperBound(16_000);
        properties.setMaxAssistantChars(8_000);
        service = new SupportToolPlanningService(
                skillRegistry,
                modelResolver,
                modelGatewayService,
                properties,
                new ObjectMapper()
        );
        when(skillRegistry.manifests()).thenReturn(List.of(readManifest()));
        when(modelResolver.requireModel()).thenReturn(new PreparedSupportModel(
                9L,
                org.mockito.Mockito.mock(PreparedModelConfig.class)
        ));
        when(modelGatewayService.estimateInputTokenUpperBound(
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class), anyList(), anyList()))
                .thenReturn(1_000);
    }

    @Test
    void plan_shouldReturnDirectAnswerWhenModelDoesNotSelectTool() {
        when(modelGatewayService.generateChatCompletion(
                org.mockito.ArgumentMatchers.any(PreparedModelConfig.class),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class), anyList(), anyList()))
                .thenReturn(completion("这是普通回答", List.of()));

        SupportToolPlan plan = service.plan(prompt());

        assertThat(plan.type()).isEqualTo(SupportToolPlanType.DIRECT_ANSWER);
        assertThat(plan.directAnswer()).isEqualTo("这是普通回答");
        assertThat(plan.toolCalls()).isEmpty();
        assertThat(plan.modelUsage().officialModelConfigId()).isEqualTo(9L);
    }

    @Test
    void plan_shouldCanonicalizeObjectArguments() {
        when(modelGatewayService.generateChatCompletion(
                org.mockito.ArgumentMatchers.any(PreparedModelConfig.class),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class), anyList(), anyList()))
                .thenReturn(completion(null, List.of(
                        new ModelGatewayToolCall("call-1", "agent_search", "{\"z\":2,\"a\":1}")
                )));

        SupportToolPlan plan = service.plan(prompt());

        assertThat(plan.type()).isEqualTo(SupportToolPlanType.TOOL_CALLS);
        assertThat(plan.toolCalls()).containsExactly(
                new SupportPlannedToolCall("call-1", "agent.search", "{\"a\":1,\"z\":2}")
        );
    }

    @Test
    void plan_shouldRejectToolOutsideRegistryWhitelist() {
        when(modelGatewayService.generateChatCompletion(
                org.mockito.ArgumentMatchers.any(PreparedModelConfig.class),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class), anyList(), anyList()))
                .thenReturn(completion(null, List.of(
                        new ModelGatewayToolCall("call-1", "system.shell", "{}")
                )));

        assertThatThrownBy(() -> service.plan(prompt()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SUPPORT_TOOL_NOT_ALLOWED"));
    }

    @Test
    void plan_shouldRejectNestedDuplicatedInvocationEvenWhenFieldOrderDiffers() {
        when(modelGatewayService.generateChatCompletion(
                org.mockito.ArgumentMatchers.any(PreparedModelConfig.class),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class), anyList(), anyList()))
                .thenReturn(completion(null, List.of(
                        new ModelGatewayToolCall("call-1", "agent_search", "{\"b\":2,\"a\":1}"),
                        new ModelGatewayToolCall("call-2", "agent_search", "{\"a\":1,\"b\":2}")
                )));

        assertThatThrownBy(() -> service.plan(prompt()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SUPPORT_TOOL_CALL_DUPLICATED"));
    }

    @Test
    void plan_shouldRejectIdentityFieldHiddenInNestedObject() {
        when(modelGatewayService.generateChatCompletion(
                org.mockito.ArgumentMatchers.any(PreparedModelConfig.class),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class), anyList(), anyList()))
                .thenReturn(completion(null, List.of(
                        new ModelGatewayToolCall(
                                "call-1", "agent_search", "{\"context\":{\"target_tenant_id\":99}}")
                )));

        assertThatThrownBy(() -> service.plan(prompt()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SUPPORT_TOOL_ARGUMENTS_INVALID"));
    }

    private SupportPromptContext prompt() {
        return new SupportPromptContext(
                "你是安全客服。",
                null,
                null,
                List.of(ModelGatewayConversationMessage.user("查询 Agent")),
                100
        );
    }

    private SupportSkillManifest readManifest() {
        return new SupportSkillManifest(
                "agent.search",
                "1.0.0",
                "查询 Agent",
                "查询当前工作区 Agent",
                "{\"type\":\"object\"}",
                "{\"type\":\"object\"}",
                SupportSkillRisk.READ,
                "TENANT_MEMBER",
                false,
                2_000
        );
    }

    private ModelGatewayChatCompletion completion(
            String content,
            List<ModelGatewayToolCall> calls
    ) {
        return new ModelGatewayChatCompletion(
                content,
                calls,
                CredentialProvider.OPENAI,
                "gpt-test",
                CredentialSource.PLATFORM,
                null,
                10L,
                100,
                20,
                120
        );
    }
}
