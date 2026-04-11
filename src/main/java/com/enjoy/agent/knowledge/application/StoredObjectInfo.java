package com.enjoy.agent.knowledge.application;

/**
 * MinIO 中已存储对象的信息。
 */
public record StoredObjectInfo(
        String bucket,
        String objectKey
) {
}
