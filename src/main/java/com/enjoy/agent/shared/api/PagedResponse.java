package com.enjoy.agent.shared.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(name = "PagedResponse", description = "分页返回结构")
public record PagedResponse<T>(
        @Schema(description = "当前页数据")
        List<T> items,
        @Schema(description = "当前页码（从 0 开始）")
        int page,
        @Schema(description = "每页大小")
        int size,
        @Schema(description = "总数据条数")
        long total,
        @Schema(description = "总页数")
        int totalPages
) {

    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
