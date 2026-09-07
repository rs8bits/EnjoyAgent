package com.enjoy.agent.credential.infrastructure.persistence;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.credential.domain.enums.CredentialType;
import java.time.Instant;

/**
 * 凭证的非秘密元数据投影。该类型故意没有 secretCiphertext、secretMasked 或 baseUrl
 * 字段，因此客服查询生成的 SQL 不会把这些值读入进程。
 */
public record CredentialMetadataProjection(
        Long id,
        String name,
        CredentialProvider provider,
        CredentialType credentialType,
        CredentialStatus status,
        Instant updatedAt
) {
}
