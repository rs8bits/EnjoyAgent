package com.enjoy.agent.chat.api;

import com.enjoy.agent.chat.api.request.CreateChatSessionRequest;
import com.enjoy.agent.chat.api.request.SendChatMessageRequest;
import com.enjoy.agent.chat.api.response.ChatMessageResponse;
import com.enjoy.agent.chat.api.response.ChatSessionResponse;
import com.enjoy.agent.chat.api.response.ChatTurnResponse;
import com.enjoy.agent.chat.application.ChatApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天运行时接口。
 */
@Tag(name = "Chat", description = "会话管理与基础聊天接口")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatApplicationService chatApplicationService;

    public ChatController(ChatApplicationService chatApplicationService) {
        this.chatApplicationService = chatApplicationService;
    }

    /**
     * 创建聊天会话。
     */
    @Operation(summary = "创建会话", description = "基于某个 Agent 创建一个新的聊天会话")
    @PostMapping("/sessions")
    public ApiResponse<ChatSessionResponse> createSession(@Valid @RequestBody CreateChatSessionRequest request) {
        return ApiResponse.success(chatApplicationService.createSession(request), "Chat session created");
    }

    /**
     * 查询聊天会话列表。
     */
    @Operation(summary = "会话列表", description = "查询当前租户下的聊天会话列表，可按 Agent 过滤")
    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionResponse>> listSessions(
            @Parameter(description = "按 Agent 过滤，可选")
            @RequestParam(required = false) Long agentId
    ) {
        return ApiResponse.success(chatApplicationService.listSessions(agentId));
    }

    /**
     * 查询会话详情。
     */
    @Operation(summary = "会话详情", description = "查询当前租户下某个聊天会话的详情")
    @GetMapping("/sessions/{id}")
    public ApiResponse<ChatSessionResponse> getSession(@PathVariable Long id) {
        return ApiResponse.success(chatApplicationService.getSession(id));
    }

    /**
     * 删除会话。
     */
    @Operation(summary = "删除会话", description = "删除某个会话及其消息、记忆和相关日志")
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable Long id) {
        chatApplicationService.deleteSession(id);
        return ApiResponse.success(null, "Chat session deleted");
    }

    /**
     * 查询会话消息列表。
     */
    @Operation(summary = "消息列表", description = "查询某个会话的完整消息历史")
    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<ChatMessageResponse>> listMessages(@PathVariable Long id) {
        return ApiResponse.success(chatApplicationService.listMessages(id));
    }

    /**
     * 发送用户消息并同步获取模型回复。
     */
    @Operation(summary = "发送消息", description = "向指定会话发送一条用户消息，并返回模型回复")
    @PostMapping("/sessions/{id}/messages")
    public ApiResponse<ChatTurnResponse> sendMessage(
            @PathVariable Long id,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        return ApiResponse.success(chatApplicationService.sendMessage(id, request), "Chat completed");
    }

    /**
     * 发送用户消息并以流式方式返回模型回复。
     */
    @Operation(summary = "流式发送消息", description = "向指定会话发送一条用户消息，并通过 SSE 流式返回模型回复")
    @PostMapping(path = "/sessions/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable Long id,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        return chatApplicationService.streamMessage(id, request);
    }
}
