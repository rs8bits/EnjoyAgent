package com.enjoy.agent.support.skill;

import com.enjoy.agent.shared.exception.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Skill 白名单注册表。
 *
 * <p>Spring 会自动注入所有 SupportSkill Bean。构造时检查名称重复、风险级别和确认
 * 契约，让配置错误在启动阶段暴露，而不是在一次真实客服请求中才出现。</p>
 */
@Component
public class SupportSkillRegistry {

    private static final Set<String> KNOWN_PERMISSIONS = Set.of(
            "AUTHENTICATED", "CURRENT_USER", "TENANT_MEMBER", "OWNER", "ADMIN");

    private final Map<String, SupportSkill> skills;

    public SupportSkillRegistry(List<SupportSkill> discoveredSkills) {
        Map<String, SupportSkill> registry = new LinkedHashMap<>();
        for (SupportSkill skill : discoveredSkills) {
            SupportSkillManifest manifest = skill.manifest();
            boolean readSkill = manifest.risk() == SupportSkillRisk.READ;
            if (readSkill && manifest.confirmationRequired()) {
                throw new IllegalStateException(
                        "READ support skill must not require confirmation: " + manifest.name());
            }
            if (!readSkill
                    && (!manifest.confirmationRequired() || !(skill instanceof ConfirmableSupportSkill))) {
                throw new IllegalStateException(
                        "Mutating support skill must implement ConfirmableSupportSkill and require confirmation: "
                                + manifest.name());
            }
            if (manifest.name() == null || manifest.name().isBlank()
                    || manifest.version() == null || manifest.version().isBlank()
                    || !KNOWN_PERMISSIONS.contains(manifest.requiredPermission())
                    || manifest.maxModelResultChars() <= 0) {
                throw new IllegalStateException(
                        "Invalid support skill manifest: " + manifest.name());
            }
            if (registry.putIfAbsent(manifest.name(), skill) != null) {
                throw new IllegalStateException("Duplicated support skill name: " + manifest.name());
            }
        }
        this.skills = Map.copyOf(registry);
    }

    public SupportSkill require(String name) {
        SupportSkill skill = skills.get(name);
        if (skill == null) {
            throw new ApiException(
                    "SUPPORT_SKILL_NOT_AVAILABLE",
                    "所需客服能力暂不可用",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        return skill;
    }

    public List<SupportSkillManifest> manifests() {
        return skills.values().stream().map(SupportSkill::manifest).toList();
    }
}
