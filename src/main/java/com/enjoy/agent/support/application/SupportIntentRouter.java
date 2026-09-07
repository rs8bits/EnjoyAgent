package com.enjoy.agent.support.application;

import com.enjoy.agent.support.domain.enums.SupportRouteType;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 低延迟、可测试的第一层意图路由。
 *
 * <p>问候规则采用整句匹配。因此“你好，帮我查 Agent”不会被问候规则截走，而会
 * 继续进入 LLM 工具规划。阶段二只让这里处理完全确定的寒暄和简短项目介绍；业务
 * 意图与 Skill 参数统一交给模型原生 Tool Calling，避免规则和模型维护两套语义。</p>
 */
@Component
public class SupportIntentRouter {

    public static final String AGENT_SEARCH = "agent.search";
    public static final String KNOWLEDGE_BASE_SEARCH = "knowledge_base.search";
    public static final String API_KEY_SEARCH = "api_key.search";
    public static final String WALLET_SUMMARY = "wallet.get_summary";
    public static final String RECHARGE_ORDER_SEARCH = "recharge_order.search";
    public static final String PROJECT_INFO_SEARCH = "project_info.search";

    private static final Pattern EDGE_PUNCTUATION = Pattern.compile(
            "^[\\s，。！？、,.!?~～]+|[\\s，。！？、,.!?~～]+$"
    );
    private static final Set<String> GREETINGS = Set.of(
            "你好", "您好", "嗨", "hello", "hi", "早上好", "下午好", "晚上好"
    );
    private static final Set<String> THANKS = Set.of("谢谢", "感谢", "多谢", "thanks", "thankyou");
    private static final Set<String> GOODBYES = Set.of("再见", "拜拜", "bye", "goodbye");
    private static final Set<String> SIMPLE_PROJECT_QUESTIONS = Set.of(
            "enjoyagent是什么", "介绍一下enjoyagent", "这个项目是什么", "这个平台是什么", "你是谁"
    );

    private final SupportProperties properties;

    public SupportIntentRouter(SupportProperties properties) {
        this.properties = properties;
    }

    public SupportIntentDecision route(String userMessage) {
        String normalized = normalize(userMessage);
        if (GREETINGS.contains(normalized)) {
            return rule("GREETING", "问候", "你好！我是 EnjoyAgent AI 客服。你可以问我当前工作区的 Agent、知识库，也可以查询你的凭证、钱包和充值申请。");
        }
        if (THANKS.contains(normalized)) {
            return rule("THANKS", "致谢", "不客气。如果还想查询 Agent、知识库、API Key 元数据、余额或充值申请，直接告诉我即可。");
        }
        if (GOODBYES.contains(normalized)) {
            return rule("GOODBYE", "结束会话", "好的，随时再来。你的客服历史会保留在当前工作区的唯一会话中。");
        }
        if (SIMPLE_PROJECT_QUESTIONS.contains(normalized)) {
            return rule(
                    "PROJECT_INTRO",
                    "项目介绍",
                    properties.getProjectName() + " 是一个用于创建和运行 AI Agent 的平台，提供模型配置、知识库、工作流、MCP 工具与用量钱包等能力。"
            );
        }

        // 不在规则层猜测“查还是删”“数字是 ID 还是金额”。模型只能从白名单 Manifest
        // 中选择工具，执行器随后还会做参数、权限、风险与确认校验。
        return new SupportIntentDecision("GENERAL_SUPPORT", "通用咨询", SupportRouteType.LLM, null, List.of());
    }

    private SupportIntentDecision rule(String code, String name, String answer) {
        return new SupportIntentDecision(code, name, SupportRouteType.RULE, answer, List.of());
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : EDGE_PUNCTUATION.matcher(value).replaceAll("");
        return normalized.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

}
