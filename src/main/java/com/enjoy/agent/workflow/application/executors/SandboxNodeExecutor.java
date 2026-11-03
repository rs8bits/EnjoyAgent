package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.sandbox.application.*;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SandboxNodeExecutor implements WorkflowNodeExecutor {
  private final SandboxRuntimeService runtime;
  private final ObjectMapper json;

  public SandboxNodeExecutor(SandboxRuntimeService runtime, ObjectMapper json) {
    this.runtime = runtime;
    this.json = json;
  }

  public String nodeType() {
    return "SANDBOX";
  }

  public NodeExecutionResult execute(NodeExecutionContext ctx) {
    if (ctx.executionBudget() != null) ctx.executionBudget().beforeToolCall();
    WorkflowCheckpointService.checkActive();
    try {
      Map<String, Object> raw =
          json.readValue(ctx.node().getConfigJson(), new TypeReference<>() {});
      var config =
          json.convertValue(resolve(raw, ctx, 0), new TypeReference<Map<String, Object>>() {});
      var parsed = json.convertValue(config, SandboxTaskService.CreateRequest.class);
      var request =
          new SandboxTaskService.CreateRequest(
              parsed.operation(),
              parsed.code(),
              parsed.fileIds(),
              ctx.node().getNodeKey(),
              parsed.timeoutSeconds(),
              parsed.arguments());
      Object execution = ctx.context().get("workflow_execution_id");
      if (!(execution instanceof Number number)) throw invalid("工作流执行标识缺失");
      String checkpoint =
          Objects.toString(ctx.context().get("workflow_checkpoint_key"), ctx.node().getNodeKey());
      var detail =
          runtime.executeWorkflow(
              ctx.tenantId(),
              ctx.userId(),
              ctx.accountingTurn() == null ? null : ctx.accountingTurn().userMessageId(),
              number.longValue(),
              number + ":" + checkpoint,
              request,
              () -> {
                if (ctx.executionBudget() != null) ctx.executionBudget().checkActive();
                WorkflowCheckpointService.checkActive();
              });
      if (!detail.task().status().equals("SUCCEEDED"))
        throw invalid("沙箱执行未成功：" + detail.task().status() + "，任务 " + detail.task().id());
      String result = runtime.modelResult(detail);
      if (ctx.executionBudget() != null) ctx.executionBudget().recordToolResult(result, result);
      var artifacts =
          detail.artifacts().stream()
              .map(
                  file ->
                      Map.<String, Object>of(
                          "fileId",
                          file.id().toString(),
                          "name",
                          file.name(),
                          "mediaType",
                          file.mediaType(),
                          "sizeBytes",
                          file.sizeBytes()))
              .toList();
      return new NodeExecutionResult(
          null,
          Map.of(
              "sandbox_result",
              result,
              "task_id",
              detail.task().id().toString(),
              "artifact_ids",
              detail.artifacts().stream().map(file -> file.id().toString()).toList(),
              "artifacts",
              artifacts));
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw invalid("沙箱节点配置无效");
    }
  }

  private Object resolve(Object value, NodeExecutionContext ctx, int depth) {
    if (depth > 16) throw invalid("参数嵌套过深");
    if (value instanceof String text)
      return WorkflowVariableResolver.resolveTypedTemplate(
          text, ctx.context(), json, 100000, () -> invalid("参数过长"));
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(key.toString(), resolve(item, ctx, depth + 1)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(item -> resolve(item, ctx, depth + 1)).toList();
    return value;
  }

  private ApiException invalid(String message) {
    return new ApiException("SANDBOX_NODE_FAILED", message, HttpStatus.BAD_REQUEST);
  }
}
