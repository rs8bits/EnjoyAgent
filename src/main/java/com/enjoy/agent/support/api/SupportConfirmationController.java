package com.enjoy.agent.support.api;

import com.enjoy.agent.shared.api.ApiResponse;
import com.enjoy.agent.support.api.response.SupportActionExecutionResponse;
import com.enjoy.agent.support.api.response.SupportConfirmationRequiredEvent;
import com.enjoy.agent.support.application.SupportActionConfirmationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户对客服写操作进行明确确认或取消；接口不接受可被篡改的业务参数。 */
@Tag(name = "Support", description = "平台 AI 客服与安全的资产操作")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/support/confirmations")
public class SupportConfirmationController {

    private final SupportActionConfirmationService confirmationService;

    public SupportConfirmationController(SupportActionConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @Operation(summary = "查询待确认的客服操作", description = "用于刷新页面后恢复确认卡片")
    @GetMapping("/pending")
    public ApiResponse<List<SupportConfirmationRequiredEvent>> listPending() {
        return ApiResponse.success(confirmationService.listPending());
    }

    @Operation(summary = "确认客服操作", description = "重新校验当前身份和提案后执行一次")
    @PostMapping("/{id}/confirm")
    public ApiResponse<SupportActionExecutionResponse> confirm(@PathVariable UUID id) {
        return ApiResponse.success(confirmationService.confirm(id));
    }

    @Operation(summary = "取消客服操作")
    @PostMapping("/{id}/cancel")
    public ApiResponse<SupportActionExecutionResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.success(confirmationService.cancel(id));
    }
}
