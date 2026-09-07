package com.enjoy.agent.support.api;

import com.enjoy.agent.shared.api.ApiResponse;
import com.enjoy.agent.support.api.request.SendSupportMessageRequest;
import com.enjoy.agent.support.api.response.SupportConversationResponse;
import com.enjoy.agent.support.api.response.SupportMessageSliceResponse;
import com.enjoy.agent.support.application.SupportApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 当前用户、当前租户唯一的 AI 客服接口。 */
@Tag(name = "Support", description = "平台 AI 客服与安全的资产查询")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/support")
public class SupportController {

    private final SupportApplicationService supportApplicationService;

    public SupportController(SupportApplicationService supportApplicationService) {
        this.supportApplicationService = supportApplicationService;
    }

    @Operation(summary = "获取唯一客服会话", description = "按当前认证 userId + tenantId 原子创建或复用")
    @GetMapping("/conversation")
    public ApiResponse<SupportConversationResponse> getConversation() {
        return ApiResponse.success(supportApplicationService.getConversation());
    }

    @Operation(summary = "读取客服历史", description = "按稳定的消息 sequence 向前游标分页")
    @GetMapping("/conversation/messages")
    public ApiResponse<SupportMessageSliceResponse> listMessages(
            @Parameter(description = "只返回该 sequence 之前的消息；首次加载不传")
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(defaultValue = "30") int limit
    ) {
        return ApiResponse.success(supportApplicationService.listMessages(beforeSequence, limit));
    }

    @Operation(summary = "流式发送客服消息", description = "先返回 accepted，再输出路由、Skill 和模型事件")
    @PostMapping(path = "/conversation/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SendSupportMessageRequest request
    ) {
        return supportApplicationService.streamMessage(idempotencyKey, request);
    }
}
