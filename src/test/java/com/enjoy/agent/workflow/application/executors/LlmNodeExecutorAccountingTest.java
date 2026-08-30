package com.enjoy.agent.workflow.application.executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.modelgateway.application.ModelCallAccountingService;
import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmNodeExecutorAccountingTest {

    @Mock
    private ModelGatewayService gatewayService;

    @Mock
    private ModelCallAccountingService accountingService;

    @Mock
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private PreparedChatTurn sourceTurn;

    @Mock
    private BillingUsageApplicationService billingUsageApplicationService;

    @Test
    void recordsEveryWorkflowLlmCompletionAgainstTheActualNodeModel() {
        PreparedModelConfig config = new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "gpt-test",
                CredentialSource.USER,
                9L,
                "ciphertext",
                new BigDecimal("0.2"),
                512,
                null,
                "https://api.openai.com",
                null,
                null,
                null
        );
        when(sourceTurn.tenantId()).thenReturn(1L);
        when(sourceTurn.userId()).thenReturn(2L);
        when(sourceTurn.agentId()).thenReturn(3L);
        when(sourceTurn.sessionId()).thenReturn(4L);
        when(sourceTurn.userMessageId()).thenReturn(5L);
        when(sourceTurn.historyMessages()).thenReturn(List.of());
        when(sourceTurn.mcpTools()).thenReturn(List.of());
        when(sourceTurn.mcpToolApprovalTokens()).thenReturn(List.of());
        ModelGatewayChatCompletion completion = new ModelGatewayChatCompletion(
                "answer", List.of(), CredentialProvider.OPENAI, "gpt-test", CredentialSource.USER,
                9L, 20L, 3, 4, 7);
        when(gatewayService.generateChatCompletion(any(), anyString(), any(), any(), anyList(), anyList()))
                .thenReturn(completion);
        when(billingUsageApplicationService.beginOfficialModelCall(any(), any(), anyInt()))
                .thenReturn(77L);
        WorkflowNode node = new WorkflowNode();
        node.setId(10L);
        node.setNodeKey("llm");
        node.setName("LLM");
        node.setNodeType(WorkflowNodeType.LLM);
        node.setConfigJson("{}");
        LlmNodeExecutor executor = new LlmNodeExecutor(
                gatewayService, accountingService, modelConfigRepository, new ObjectMapper(),
                billingUsageApplicationService);

        NodeExecutionResult result = executor.execute(new NodeExecutionContext(
                1L, 2L, 3L, 4L, node, config, new HashMap<>(), sourceTurn));

        ArgumentCaptor<PreparedChatTurn> turnCaptor = ArgumentCaptor.forClass(PreparedChatTurn.class);
        verify(accountingService).recordSuccess(
                turnCaptor.capture(), org.mockito.Mockito.same(completion), org.mockito.ArgumentMatchers.eq(77L));
        assertThat(turnCaptor.getValue().modelConfig()).isEqualTo(config);
        assertThat(result.output()).containsEntry("llm_output", "answer");
    }

    @Test
    void safelyResolvesNodeAndUserInputWhilePreservingUnknownTemplates() {
        PreparedModelConfig config = preparedConfig();
        ModelGatewayChatCompletion completion = completion();
        when(gatewayService.generateChatCompletion(any(), anyString(), any(), any(), anyList(), anyList()))
                .thenReturn(completion);
        WorkflowNode node = llmNode(
                "{\"systemPrompt\":\"Previous={{ node_input }}; User={{user_input}}; "
                        + "Score={{node_outputs.fetch.payload.score}}/{{#fetch.payload.score#}}; "
                        + "Unknown={{secret}}\"}");
        Map<String, Object> context = new HashMap<>();
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("text", "$1\\node");
        upstream.put("count", 2);
        context.put("node_input", upstream);
        context.put("user_input", "$2\\user");
        context.put("node_outputs", Map.of(
                "fetch", Map.of("payload", Map.of("score", 7))));
        LlmNodeExecutor executor = new LlmNodeExecutor(
                gatewayService, accountingService, modelConfigRepository, new ObjectMapper(),
                billingUsageApplicationService);

        executor.execute(new NodeExecutionContext(
                1L, 2L, 3L, 4L, node, config, context));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(gatewayService).generateChatCompletion(
                any(), promptCaptor.capture(), any(), any(), anyList(), anyList());
        assertThat(promptCaptor.getValue()).isEqualTo(
                "Previous={\"text\":\"$1\\\\node\",\"count\":2}; "
                        + "User=$2\\user; Score=7/7; Unknown={{secret}}");
    }

    @Test
    void rejectsResolvedSystemPromptAboveTheHardLimitBeforeModelOrBillingCalls() {
        PreparedModelConfig config = preparedConfig();
        WorkflowNode node = llmNode("{\"systemPrompt\":\"{{node_input}}\"}");
        LlmNodeExecutor executor = new LlmNodeExecutor(
                gatewayService, accountingService, modelConfigRepository, new ObjectMapper(),
                billingUsageApplicationService);

        assertThatThrownBy(() -> executor.execute(new NodeExecutionContext(
                1L, 2L, 3L, 4L, node, config,
                new HashMap<>(Map.of("node_input", "x".repeat(20_001))))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("WORKFLOW_SYSTEM_PROMPT_TOO_LONG"));
        verifyNoInteractions(gatewayService, accountingService, billingUsageApplicationService);
    }

    private PreparedModelConfig preparedConfig() {
        return new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "gpt-test",
                CredentialSource.USER,
                9L,
                "ciphertext",
                new BigDecimal("0.2"),
                512,
                null,
                "https://api.openai.com",
                null,
                null,
                null
        );
    }

    private ModelGatewayChatCompletion completion() {
        return new ModelGatewayChatCompletion(
                "answer", List.of(), CredentialProvider.OPENAI, "gpt-test", CredentialSource.USER,
                9L, 20L, 3, 4, 7);
    }

    private WorkflowNode llmNode(String configJson) {
        WorkflowNode node = new WorkflowNode();
        node.setId(10L);
        node.setNodeKey("llm");
        node.setName("LLM");
        node.setNodeType(WorkflowNodeType.LLM);
        node.setConfigJson(configJson);
        return node;
    }
}
