package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.chat.application.ChatPromptMessage;
import com.enjoy.agent.chat.domain.enums.ChatMessageRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.application.ModelGatewayInvocationException;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private ModelGatewayService modelGatewayService;

    private QueryRewriteService queryRewriteService;
    private KnowledgeProperties knowledgeProperties;

    @BeforeEach
    void setUp() {
        knowledgeProperties = new KnowledgeProperties();
        knowledgeProperties.setQueryRewriteEnabled(true);
        knowledgeProperties.setQueryRewriteMaxContextMessages(4);
        queryRewriteService = new QueryRewriteService(modelGatewayService, knowledgeProperties);
    }

    @Test
    void shouldRewriteQuestionWithRecentContext() {
        PreparedModelConfig chatModelConfig = preparedChatModelConfig();
        List<ChatPromptMessage> history = List.of(
                new ChatPromptMessage(ChatMessageRole.USER, "我在看 EnjoyAgent 的 MCP 模块"),
                new ChatPromptMessage(ChatMessageRole.ASSISTANT, "好的，我们可以继续分析"),
                new ChatPromptMessage(ChatMessageRole.USER, "它支持哪些认证方式")
        );
        when(modelGatewayService.generateText(eq(chatModelConfig), anyString(), anyString()))
                .thenReturn("EnjoyAgent 的 MCP 模块支持哪些认证方式");

        QueryRewriteResult result = queryRewriteService.rewrite(chatModelConfig, null, history, "它支持哪些认证方式");

        assertThat(result.originalQuery()).isEqualTo("它支持哪些认证方式");
        assertThat(result.rewrittenQuery()).isEqualTo("EnjoyAgent 的 MCP 模块支持哪些认证方式");
        assertThat(result.rewriteApplied()).isTrue();
        verify(modelGatewayService).generateText(eq(chatModelConfig), anyString(), anyString());
    }

    @Test
    void shouldFallbackToOriginalQueryWhenRewriteFails() {
        PreparedModelConfig chatModelConfig = preparedChatModelConfig();
        List<ChatPromptMessage> history = List.of(
                new ChatPromptMessage(ChatMessageRole.USER, "先说知识库"),
                new ChatPromptMessage(ChatMessageRole.USER, "它怎么切片")
        );
        when(modelGatewayService.generateText(eq(chatModelConfig), anyString(), anyString()))
                .thenThrow(new ModelGatewayInvocationException(
                        "MODEL_INVOCATION_FAILED",
                        "boom",
                        HttpStatus.BAD_GATEWAY,
                        CredentialProvider.OPENAI,
                        "qwen-plus",
                        null,
                        1L,
                        100L,
                        null
                ));

        QueryRewriteResult result = queryRewriteService.rewrite(chatModelConfig, null, history, "它怎么切片");

        assertThat(result.originalQuery()).isEqualTo("它怎么切片");
        assertThat(result.rewrittenQuery()).isEqualTo("它怎么切片");
        assertThat(result.rewriteApplied()).isFalse();
    }

    @Test
    void shouldSkipRewriteWhenThereIsNoEnoughContext() {
        PreparedModelConfig chatModelConfig = preparedChatModelConfig();

        QueryRewriteResult result = queryRewriteService.rewrite(
                chatModelConfig,
                null,
                List.of(new ChatPromptMessage(ChatMessageRole.USER, "它支持私有部署吗")),
                "它支持私有部署吗"
        );

        assertThat(result.originalQuery()).isEqualTo("它支持私有部署吗");
        assertThat(result.rewrittenQuery()).isEqualTo("它支持私有部署吗");
        assertThat(result.rewriteApplied()).isFalse();
        verifyNoInteractions(modelGatewayService);
    }

    @Test
    void shouldUseSessionMemorySummaryWhenRecentHistoryIsShort() {
        PreparedModelConfig chatModelConfig = preparedChatModelConfig();
        when(modelGatewayService.generateText(eq(chatModelConfig), anyString(), anyString()))
                .thenReturn("EnjoyAgent 项目是否支持私有部署");

        QueryRewriteResult result = queryRewriteService.rewrite(
                chatModelConfig,
                "- 用户目标\n- 了解 EnjoyAgent 的部署方式",
                List.of(new ChatPromptMessage(ChatMessageRole.USER, "它支持私有部署吗")),
                "它支持私有部署吗"
        );

        assertThat(result.originalQuery()).isEqualTo("它支持私有部署吗");
        assertThat(result.rewrittenQuery()).isEqualTo("EnjoyAgent 项目是否支持私有部署");
        assertThat(result.rewriteApplied()).isTrue();
        verify(modelGatewayService).generateText(eq(chatModelConfig), anyString(), anyString());
    }

    private PreparedModelConfig preparedChatModelConfig() {
        return new PreparedModelConfig(
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                CredentialSource.USER,
                1L,
                "ciphertext",
                null,
                null
        );
    }
}
