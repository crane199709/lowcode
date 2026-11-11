package io.saasforge.lowcode.screen.controller;

import io.saasforge.lowcode.screen.entity.Screen;
import io.saasforge.lowcode.screen.service.ScreenService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "大屏")
@RestController
@RequestMapping("/screens")
class ScreenController {
    private final ScreenService screenService;

    ScreenController(ScreenService screenService) {
        this.screenService = screenService;
    }

    @Operation(summary = "在当前租户下新建大屏")
    @PostMapping
    @RequiresPermission("screen:edit")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ScreenResponse> create(@Valid @RequestBody ScreenRequest request) {
        return ApiResponse.created(ScreenResponse.from(screenService.create(request.name())));
    }

    @Operation(summary = "分页列出当前成员数据范围内的大屏，最近更新在前")
    @GetMapping
    @RequiresPermission("screen:view")
    ApiResponse<List<ScreenResponse>> list(PageQuery query) {
        List<ScreenResponse> items =
                screenService.list(query).stream().map(ScreenResponse::from).toList();
        return ApiResponse.page(items, query.toPageMeta(screenService.count()));
    }

    @Operation(summary = "读取大屏")
    @GetMapping("/{id}")
    @RequiresPermission("screen:view")
    ApiResponse<ScreenResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(ScreenResponse.from(screenService.get(id)));
    }

    @Operation(summary = "修改大屏名称")
    @PutMapping("/{id}")
    @RequiresPermission("screen:edit")
    ApiResponse<ScreenResponse> update(@PathVariable UUID id, @Valid @RequestBody ScreenRequest request) {
        return ApiResponse.ok(ScreenResponse.from(screenService.rename(id, request.name())));
    }

    @Operation(summary = "删除大屏")
    @DeleteMapping("/{id}")
    @RequiresPermission("screen:edit")
    ApiResponse<Void> delete(@PathVariable UUID id) {
        screenService.delete(id);
        return ApiResponse.ok();
    }

    /** 约束与 screens 表一致，违反时由 SDK 返回 400 request.invalid 并逐项列出字段，不必等数据库报错。 */
    record ScreenRequest(@NotBlank @Size(max = 120) String name) {}

    /**
     * @param ownerId 创建大屏的 Membership，系统创建的大屏为 null
     * @param departmentPath 创建人主部门的路径，创建人未分配部门时为 null
     */
    record ScreenResponse(
            UUID id,
            String name,
            UUID ownerId,
            String departmentPath,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        static ScreenResponse from(Screen screen) {
            return new ScreenResponse(
                    screen.getId(),
                    screen.getName(),
                    screen.getOwnerId(),
                    screen.getDepartmentPath(),
                    screen.getCreatedAt(),
                    screen.getUpdatedAt());
        }
    }
}
