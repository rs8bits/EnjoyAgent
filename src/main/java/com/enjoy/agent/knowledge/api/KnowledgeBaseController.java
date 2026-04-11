package com.enjoy.agent.knowledge.api;

import com.enjoy.agent.knowledge.api.request.CreateKnowledgeBaseRequest;
import com.enjoy.agent.knowledge.api.request.UpdateKnowledgeBaseRequest;
import com.enjoy.agent.knowledge.api.response.KnowledgeBaseResponse;
import com.enjoy.agent.knowledge.api.response.KnowledgeDocumentResponse;
import com.enjoy.agent.knowledge.application.KnowledgeBaseApplicationService;
import com.enjoy.agent.knowledge.application.KnowledgeDocumentApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库与文档接口。
 */
@Tag(name = "Knowledge", description = "知识库管理、文档上传与查询接口")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseApplicationService knowledgeBaseApplicationService;
    private final KnowledgeDocumentApplicationService knowledgeDocumentApplicationService;

    public KnowledgeBaseController(
            KnowledgeBaseApplicationService knowledgeBaseApplicationService,
            KnowledgeDocumentApplicationService knowledgeDocumentApplicationService
    ) {
        this.knowledgeBaseApplicationService = knowledgeBaseApplicationService;
        this.knowledgeDocumentApplicationService = knowledgeDocumentApplicationService;
    }

    /**
     * 创建知识库。
     */
    @Operation(summary = "创建知识库", description = "在当前租户下创建一个绑定 embedding 模型的知识库")
    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> createKnowledgeBase(@Valid @org.springframework.web.bind.annotation.RequestBody CreateKnowledgeBaseRequest request) {
        return ApiResponse.success(knowledgeBaseApplicationService.createKnowledgeBase(request), "Knowledge base created");
    }

    /**
     * 查询知识库列表。
     */
    @Operation(summary = "知识库列表", description = "查询当前租户下的知识库列表")
    @GetMapping
    public ApiResponse<List<KnowledgeBaseResponse>> listKnowledgeBases() {
        return ApiResponse.success(knowledgeBaseApplicationService.listKnowledgeBases());
    }

    /**
     * 查询知识库详情。
     */
    @Operation(summary = "知识库详情", description = "按 ID 查询当前租户下的知识库")
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> getKnowledgeBase(@PathVariable Long id) {
        return ApiResponse.success(knowledgeBaseApplicationService.getKnowledgeBase(id));
    }

    /**
     * 更新知识库。
     */
    @Operation(summary = "更新知识库", description = "更新知识库名称、描述、embedding 模型配置和启用状态")
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> updateKnowledgeBase(
            @PathVariable Long id,
            @Valid @org.springframework.web.bind.annotation.RequestBody UpdateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeBaseApplicationService.updateKnowledgeBase(id, request), "Knowledge base updated");
    }

    /**
     * 删除知识库。
     */
    @Operation(summary = "删除知识库", description = "删除一个空的、未被 Agent 绑定的知识库")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable Long id) {
        knowledgeBaseApplicationService.deleteKnowledgeBase(id);
        return ApiResponse.success(null, "Knowledge base deleted");
    }

    /**
     * 向知识库上传文档。
     */
    @Operation(
            summary = "上传文档",
            description = "上传 txt、md、pdf 文档到知识库，并同步完成文本提取、切片和向量化",
            requestBody = @RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(type = "object")
                    )
            )
    )
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeDocumentResponse> uploadDocument(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.success(knowledgeDocumentApplicationService.uploadDocument(id, file), "Document uploaded");
    }

    /**
     * 查询知识库文档列表。
     */
    @Operation(summary = "文档列表", description = "查询某个知识库下的文档列表")
    @GetMapping("/{id}/documents")
    public ApiResponse<List<KnowledgeDocumentResponse>> listDocuments(@PathVariable Long id) {
        return ApiResponse.success(knowledgeDocumentApplicationService.listDocuments(id));
    }

    /**
     * 查询知识库文档详情。
     */
    @Operation(summary = "文档详情", description = "按 ID 查询某个知识库下的文档")
    @GetMapping("/{id}/documents/{documentId}")
    public ApiResponse<KnowledgeDocumentResponse> getDocument(
            @PathVariable Long id,
            @PathVariable Long documentId
    ) {
        return ApiResponse.success(knowledgeDocumentApplicationService.getDocument(id, documentId));
    }

    /**
     * 删除知识库文档。
     */
    @Operation(summary = "删除文档", description = "删除知识库文档、对应切片与 MinIO 对象")
    @DeleteMapping("/{id}/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(
            @PathVariable Long id,
            @PathVariable Long documentId
    ) {
        knowledgeDocumentApplicationService.deleteDocument(id, documentId);
        return ApiResponse.success(null, "Document deleted");
    }

    /**
     * 为已有文档重建 Elasticsearch 检索索引。
     */
    @Operation(summary = "重建文档检索索引", description = "把 READY 文档的现有切片重新同步到 Elasticsearch 检索索引")
    @PostMapping("/{id}/documents/{documentId}/reindex-search")
    public ApiResponse<KnowledgeDocumentResponse> reindexDocument(
            @PathVariable Long id,
            @PathVariable Long documentId
    ) {
        return ApiResponse.success(
                knowledgeDocumentApplicationService.reindexDocument(id, documentId),
                "Document search index rebuilt"
        );
    }
}
