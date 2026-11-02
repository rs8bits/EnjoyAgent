package com.enjoy.agent.modelgateway.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.security.OutboundUrlPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ModelContextTrustTest {
  @Test
  void memoryAndRetrievedTextNeverEnterSystemInstructionChannel() throws Exception {
    var service =
        new ModelGatewayService(
            mock(AesCryptoService.class),
            new ModelGatewayProperties(),
            new ObjectMapper(),
            mock(OutboundUrlPolicy.class));
    var method =
        ModelGatewayService.class.getDeclaredMethod(
            "buildPrompt",
            PreparedModelConfig.class,
            CredentialSource.class,
            String.class,
            String.class,
            String.class,
            List.class,
            List.class);
    method.setAccessible(true);
    Prompt prompt =
        (Prompt)
            method.invoke(
                service,
                null,
                CredentialSource.PLATFORM,
                "trusted instructions",
                "memory_attack",
                "retrieval_attack",
                List.of(ModelGatewayConversationMessage.user("current question")),
                List.of());
    assertThat(
            prompt.getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(m -> m.getText()))
        .allMatch(t -> !t.contains("memory_attack") && !t.contains("retrieval_attack"));
    assertThat(
            prompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(m -> m.getText()))
        .anyMatch(t -> t.contains("memory_attack") && t.contains("不可信"))
        .anyMatch(t -> t.contains("retrieval_attack") && t.contains("不可信"));
  }
}
