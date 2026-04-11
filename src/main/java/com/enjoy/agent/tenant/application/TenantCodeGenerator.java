package com.enjoy.agent.tenant.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 租户编码生成器。
 */
@Component
public class TenantCodeGenerator {

    private static final int MAX_CODE_LENGTH = 64;
    private static final int RANDOM_SUFFIX_LENGTH = 6;
    private final TenantRepository tenantRepository;

    public TenantCodeGenerator(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * 基于租户名称生成一个尽量可读且唯一的编码。
     */
    public String generate(String tenantName) {
        String baseCode = slugify(tenantName);
        if (baseCode.isBlank()) {
            baseCode = "tenant";
        }

        int maxBaseLength = MAX_CODE_LENGTH - RANDOM_SUFFIX_LENGTH - 1;
        if (baseCode.length() > maxBaseLength) {
            baseCode = baseCode.substring(0, maxBaseLength);
        }

        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = baseCode + "-" + randomSuffix();
            if (!tenantRepository.existsByCode(candidate)) {
                return candidate;
            }
        }

        throw new ApiException(
                "TENANT_CODE_GENERATION_FAILED",
                "Failed to generate a unique tenant code",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    /**
     * 把名称转成 URL 友好的 slug。
     */
    private String slugify(String input) {
        String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");

        return normalized.isBlank() ? "tenant" : normalized;
    }

    /**
     * 生成随机短后缀，降低编码冲突概率。
     */
    private String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_SUFFIX_LENGTH);
    }
}
