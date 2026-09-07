package com.enjoy.agent.support.application;

import com.enjoy.agent.modelgateway.application.ModelGatewayChatCompletion;
import com.enjoy.agent.modelgateway.application.ModelGatewayService;
import com.enjoy.agent.modelgateway.application.ModelGatewayToolCall;
import com.enjoy.agent.modelgateway.application.ModelGatewayToolDefinition;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 使用客服官方模型完成一次“回答还是调用 Skill”的规划。
 *
 * <p>本服务只负责规划，不执行任何 Skill，也不会自行重试或递归调用模型。这样的边界
 * 便于学习和审计：外层编排器拿到 {@link SupportToolPlan} 后，查询 Skill 可以直接走
 * 现有安全执行链，写入类 Skill 则只能先生成用户确认记录。</p>
 *
 * <p>模型输出永远是不可信输入。本服务在返回工具调用前会完成白名单、数量、JSON
 * 对象格式、重复调用和身份字段检查。租户、用户、会话等身份只能由服务端执行上下文
 * 注入，绝不能接受模型生成的值。</p>
 */
@Service
public class SupportToolPlanningService {

    /**
     * 这是附加在客服安全提示词后的“单次规划协议”。工具选择使用模型原生 tool call，
     * 不要求模型在普通文本里手写一套容易解析错误的 JSON 协议。
     */
    private static final String PLANNING_INSTRUCTION = """

            当前步骤只负责决定是否调用客服 Skill，请严格遵守：
            1. 涉及当前用户或当前工作区的资产、余额、充值申请等实时数据时，选择最匹配的 Skill，不得凭记忆猜测。
            2. 用户明确要求充值申请、删除等写操作时，可以选择对应 Skill；系统会先展示确认卡片，未经用户点击确认不会执行。
            3. 一次最多选择 3 个必要的 Skill，不得以相同参数重复调用同一 Skill。
            4. 工具参数只填写业务参数，严禁填写 tenantId、userId、conversationId、角色、权限或其他认证身份字段。
            5. 不需要 Skill 时直接回答用户；需要 Skill 时只发出工具调用，不要声称工具已经执行成功。
            """;

    /** 无论配置如何变化，学习版规划器都不允许一次请求无限扩张。 */
    private static final int ABSOLUTE_MAX_TOOL_CALLS = 3;

    /** 工具参数是模型输出，不允许用超大 JSON 消耗解析和归档资源。 */
    private static final int MAX_TOOL_ARGUMENTS_CHARS = 4_000;

    /**
     * 所有会改变服务端身份或权限边界的字段名。比较前会去掉大小写、下划线、短横线
     * 等分隔符，因此 {@code tenant_id}、{@code Tenant-Id} 同样会被拒绝。
     */
    private static final Set<String> FORBIDDEN_IDENTITY_FIELDS = Set.of(
            "tenant", "tenantid",
            "user", "userid",
            "conversation", "conversationid",
            "turn", "turnid",
            "role", "systemrole",
            "permission", "permissions", "requiredpermission",
            "ownerid", "owneruserid",
            "creatorid", "createdby", "operatorid", "principalid",
            "authenticateduser", "authentication", "authorization"
    );

    private final SupportSkillRegistry skillRegistry;
    private final SupportModelResolver modelResolver;
    private final ModelGatewayService modelGatewayService;
    private final SupportProperties properties;
    private final ObjectMapper objectMapper;

    public SupportToolPlanningService(
            SupportSkillRegistry skillRegistry,
            SupportModelResolver modelResolver,
            ModelGatewayService modelGatewayService,
            SupportProperties properties,
            ObjectMapper objectMapper
    ) {
        this.skillRegistry = skillRegistry;
        this.modelResolver = modelResolver;
        this.modelGatewayService = modelGatewayService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 对已经过 {@link SupportContextAssembler} 安全检查和预算裁剪的上下文规划一次。
     *
     * <p>集成方需要注意：直接回答仍应在写入 SSE 和数据库前经过现有
     * {@code AFTER_MODEL} Hook；工具调用则应继续走 {@code BEFORE_SKILL}、
     * {@code AFTER_SKILL_RAW} 等完整 Skill 安全链。本组件不重复外层已有职责。</p>
     *
     * @param promptContext 当前安全上下文，包含摘要、最近消息及可选的已检查资料
     * @return 互斥的直接回答或工具调用列表，以及本次规划模型用量
     */
    public SupportToolPlan plan(SupportPromptContext promptContext) {
        if (promptContext == null || !StringUtils.hasText(promptContext.systemPrompt())
                || promptContext.messages() == null
                || promptContext.messages().isEmpty()) {
            throw new ApiException(
                    "SUPPORT_TOOL_PLANNING_CONTEXT_INVALID",
                    "客服规划上下文不完整，请稍后重试",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }

        PreparedSupportModel supportModel = modelResolver.requireModel();
        List<SupportSkillManifest> manifests = skillRegistry.manifests().stream()
                .sorted(java.util.Comparator.comparing(SupportSkillManifest::name))
                .toList();
        if (manifests.isEmpty()) {
            throw new ApiException(
                    "SUPPORT_TOOL_NOT_CONFIGURED",
                    "客服能力尚未配置，请联系管理员",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }

        ToolCatalog toolCatalog = buildToolCatalog(manifests);
        List<ModelGatewayToolDefinition> definitions = toolCatalog.definitions();
        String plannerSystemPrompt = promptContext.systemPrompt() + PLANNING_INSTRUCTION;

        // SupportContextAssembler 的估算尚未包含工具 Schema；这里在真正调用前再计算
        // 一次，保证新增工具定义不会悄悄突破客服自己的小上下文预算。
        int estimatedInputTokens = modelGatewayService.estimateInputTokenUpperBound(
                plannerSystemPrompt,
                promptContext.sessionMemorySummary(),
                promptContext.retrievalContext(),
                promptContext.messages(),
                definitions
        );
        if (estimatedInputTokens > properties.getMaxInputTokenUpperBound()) {
            throw new ApiException(
                    "SUPPORT_TOOL_PLANNING_CONTEXT_BUDGET_EXCEEDED",
                    "当前问题在工具规划上下文预算内无法处理，请缩短问题后重试",
                    HttpStatus.BAD_REQUEST
            );
        }

        ModelGatewayChatCompletion completion = modelGatewayService.generateChatCompletion(
                supportModel.modelConfig(),
                plannerSystemPrompt,
                promptContext.sessionMemorySummary(),
                promptContext.retrievalContext(),
                promptContext.messages(),
                definitions
        );
        SupportModelUsage usage = toUsage(supportModel, completion);
        List<ModelGatewayToolCall> rawCalls = completion.toolCalls() == null
                ? List.of()
                : completion.toolCalls();

        if (rawCalls.isEmpty()) {
            String directAnswer = completion.content() == null ? "" : completion.content().trim();
            if (!StringUtils.hasText(directAnswer)) {
                throw invalidModelPlan(
                        "SUPPORT_TOOL_PLAN_EMPTY",
                        "客服模型未返回有效回答，请稍后重试"
                );
            }
            if (directAnswer.codePointCount(0, directAnswer.length())
                    > properties.getMaxAssistantChars()) {
                throw invalidModelPlan(
                        "SUPPORT_TOOL_PLAN_ANSWER_TOO_LONG",
                        "客服回答超过安全长度上限，请缩小问题范围后重试"
                );
            }
            return SupportToolPlan.directAnswer(directAnswer, usage);
        }

        int allowedCalls = Math.min(ABSOLUTE_MAX_TOOL_CALLS, properties.getMaxSkillsPerTurn());
        if (rawCalls.size() > allowedCalls) {
            throw invalidModelPlan(
                    "SUPPORT_TOOL_CALL_LIMIT_EXCEEDED",
                    "客服模型选择的操作过多，请缩小问题范围后重试"
            );
        }
        return SupportToolPlan.toolCalls(validateCalls(rawCalls, toolCatalog.manifestsByModelName()), usage);
    }

    private ToolCatalog buildToolCatalog(List<SupportSkillManifest> manifests) {
        List<ModelGatewayToolDefinition> definitions = new ArrayList<>(manifests.size());
        Map<String, SupportSkillManifest> manifestsByModelName = new LinkedHashMap<>();
        for (SupportSkillManifest manifest : manifests) {
            // OpenAI 兼容协议的函数名只允许字母、数字、下划线和短横线；领域内 Skill
            // 使用 agent.search 这样的点号名称更易读，因此仅在模型协议边界转成
            // agent_search，模型返回后再映射回原始 Skill 名称。
            String modelToolName = toModelToolName(manifest.name());
            if (manifestsByModelName.putIfAbsent(modelToolName, manifest) != null) {
                throw new ApiException(
                        "SUPPORT_TOOL_NAME_COLLISION",
                        "客服工具名称配置冲突，请联系管理员",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }
            String description = "Skill=" + manifest.name() + "；" + manifest.description()
                + "；风险等级=" + manifest.risk().name()
                + (manifest.confirmationRequired() ? "；执行前必须由用户确认" : "");
            definitions.add(new ModelGatewayToolDefinition(
                    modelToolName,
                    description,
                    manifest.inputSchemaJson()
            ));
        }
        return new ToolCatalog(List.copyOf(definitions), Map.copyOf(manifestsByModelName));
    }

    private String toModelToolName(String skillName) {
        StringBuilder modelName = new StringBuilder(skillName == null ? 0 : skillName.length());
        if (skillName != null) {
            for (int index = 0; index < skillName.length(); index++) {
                char character = skillName.charAt(index);
                boolean allowed = character >= 'a' && character <= 'z'
                        || character >= 'A' && character <= 'Z'
                        || character >= '0' && character <= '9'
                        || character == '_'
                        || character == '-';
                modelName.append(allowed ? character : '_');
            }
        }
        if (modelName.isEmpty() || modelName.length() > 64) {
            throw new ApiException(
                    "SUPPORT_TOOL_MANIFEST_INVALID",
                    "客服工具名称配置无效，请联系管理员",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        return modelName.toString();
    }

    private List<SupportPlannedToolCall> validateCalls(
            List<ModelGatewayToolCall> rawCalls,
            Map<String, SupportSkillManifest> manifestsByName
    ) {
        List<SupportPlannedToolCall> checkedCalls = new ArrayList<>(rawCalls.size());
        Set<String> callIds = new HashSet<>();
        Set<String> invocationFingerprints = new HashSet<>();

        for (ModelGatewayToolCall rawCall : rawCalls) {
            if (rawCall == null || !StringUtils.hasText(rawCall.id())) {
                throw invalidModelPlan(
                        "SUPPORT_TOOL_CALL_ID_INVALID",
                        "客服模型返回了无效工具调用，请稍后重试"
                );
            }
            if (!callIds.add(rawCall.id())) {
                throw invalidModelPlan(
                        "SUPPORT_TOOL_CALL_ID_DUPLICATED",
                        "客服模型返回了重复工具调用，请稍后重试"
                );
            }
            SupportSkillManifest manifest = StringUtils.hasText(rawCall.name())
                    ? manifestsByName.get(rawCall.name())
                    : null;
            if (manifest == null) {
                // 错误消息不回显模型生成的名称，避免不可信内容进入日志或 SSE。
                throw invalidModelPlan(
                        "SUPPORT_TOOL_NOT_ALLOWED",
                        "客服模型选择了未授权能力，请稍后重试"
                );
            }

            String canonicalArguments = validateAndCanonicalizeArguments(rawCall.argumentsJson());
            String fingerprint = manifest.name() + "\n" + canonicalArguments;
            if (!invocationFingerprints.add(fingerprint)) {
                throw invalidModelPlan(
                        "SUPPORT_TOOL_CALL_DUPLICATED",
                        "客服模型重复选择了相同操作，请重新描述需求"
                );
            }
            checkedCalls.add(new SupportPlannedToolCall(
                    rawCall.id(),
                    manifest.name(),
                    canonicalArguments
            ));
        }
        return List.copyOf(checkedCalls);
    }

    private String validateAndCanonicalizeArguments(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            throw invalidArguments("客服模型返回了空工具参数，请稍后重试");
        }
        if (argumentsJson.codePointCount(0, argumentsJson.length()) > MAX_TOOL_ARGUMENTS_CHARS) {
            throw invalidArguments("客服模型返回的工具参数过长，请缩小问题范围后重试");
        }
        try {
            JsonNode parsed = objectMapper.readTree(argumentsJson);
            if (parsed == null || !parsed.isObject()) {
                throw invalidArguments("客服工具参数必须是 JSON 对象，请稍后重试");
            }
            rejectIdentityFields(parsed);
            return objectMapper.writeValueAsString(canonicalize(parsed));
        } catch (JsonProcessingException ex) {
            throw invalidArguments("客服模型返回了无法解析的工具参数，请稍后重试");
        }
    }

    /**
     * 递归检查对象和数组，防止把身份字段藏在嵌套结构中。最终具体 Skill 仍需按自己的
     * JSON Schema 校验业务字段，本检查只负责不容妥协的服务端身份边界。
     */
    private void rejectIdentityFields(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (isForbiddenIdentityField(normalizeFieldName(entry.getKey()))) {
                    throw invalidArguments("客服工具参数不得包含用户、租户、会话或权限字段");
                }
                rejectIdentityFields(entry.getValue());
            });
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(this::rejectIdentityFields);
        }
    }

    private boolean isForbiddenIdentityField(String normalized) {
        if (FORBIDDEN_IDENTITY_FIELDS.contains(normalized)) {
            return true;
        }
        // 也拒绝 targetTenantId、requestedUserId 这类带业务前缀的变体，避免模型
        // 通过改字段名绕过服务端身份注入边界。
        return normalized.endsWith("tenantid")
                || normalized.endsWith("userid")
                || normalized.endsWith("conversationid")
                || normalized.endsWith("turnid")
                || normalized.endsWith("systemrole")
                || normalized.endsWith("permission")
                || normalized.endsWith("permissions")
                || normalized.endsWith("ownerid")
                || normalized.endsWith("creatorid")
                || normalized.endsWith("operatorid")
                || normalized.endsWith("principalid");
    }

    private String normalizeFieldName(String fieldName) {
        String lowerCase = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lowerCase.length());
        for (int index = 0; index < lowerCase.length(); index++) {
            char character = lowerCase.charAt(index);
            if (character >= 'a' && character <= 'z' || character >= '0' && character <= '9') {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    /**
     * 对 JSON 对象字段递归排序。这样 {@code {"a":1,"b":2}} 与字段顺序相反的
     * 等价参数会得到相同指纹，重复调用检查不会被 JSON 字段顺序绕过。
     */
    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> sorted.set(name, canonicalize(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.elements().forEachRemaining(value -> array.add(canonicalize(value)));
            return array;
        }
        return node.deepCopy();
    }

    private SupportModelUsage toUsage(
            PreparedSupportModel supportModel,
            ModelGatewayChatCompletion completion
    ) {
        return new SupportModelUsage(
                completion.provider().name(),
                completion.modelName(),
                supportModel.officialModelConfigId(),
                completion.latencyMs(),
                completion.promptTokens(),
                completion.completionTokens(),
                completion.totalTokens()
        );
    }

    private ApiException invalidArguments(String publicMessage) {
        return invalidModelPlan("SUPPORT_TOOL_ARGUMENTS_INVALID", publicMessage);
    }

    private ApiException invalidModelPlan(String code, String publicMessage) {
        return new ApiException(code, publicMessage, HttpStatus.BAD_GATEWAY);
    }

    private record ToolCatalog(
            List<ModelGatewayToolDefinition> definitions,
            Map<String, SupportSkillManifest> manifestsByModelName
    ) {
    }
}
