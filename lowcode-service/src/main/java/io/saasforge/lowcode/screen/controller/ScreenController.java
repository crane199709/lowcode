package io.saasforge.lowcode.screen.controller;

import io.saasforge.lowcode.screen.entity.Screen;
import io.saasforge.lowcode.screen.model.BindingDataset;
import io.saasforge.lowcode.screen.model.ScreenDraft;
import io.saasforge.lowcode.screen.model.WidgetData;
import io.saasforge.lowcode.screen.service.ScreenQueryService;
import io.saasforge.lowcode.screen.service.ScreenService;
import io.saasforge.sdk.audit.AuditLog;
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
    private final ScreenQueryService queries;

    ScreenController(ScreenService screenService, ScreenQueryService queries) {
        this.queries = queries;
        this.screenService = screenService;
    }

    @Operation(summary = "在当前租户下新建大屏")
    @PostMapping
    @RequiresPermission("screen:edit")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ScreenResponse> create(@Valid @RequestBody ScreenRequest request) {
        return ApiResponse.created(response(screenService.create(request.name(), request.draft())));
    }

    @Operation(summary = "分页列出当前成员数据范围内的大屏，最近更新在前")
    @GetMapping
    @RequiresPermission("screen:view")
    ApiResponse<List<ScreenResponse>> list(PageQuery query) {
        List<ScreenResponse> items =
                screenService.list(query).stream().map(this::response).toList();
        return ApiResponse.page(items, query.toPageMeta(screenService.count()));
    }

    @Operation(summary = "读取大屏")
    @GetMapping("/{id}")
    @RequiresPermission("screen:view")
    ApiResponse<ScreenResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(response(screenService.readable(id)));
    }

    @Operation(summary = "修改大屏名称与草稿")
    @PutMapping("/{id}")
    @RequiresPermission("screen:edit")
    ApiResponse<ScreenResponse> update(@PathVariable UUID id, @Valid @RequestBody ScreenRequest request) {
        return ApiResponse.ok(response(screenService.update(id, request.name(), request.draft())));
    }

    @Operation(summary = "读取大屏草稿")
    @GetMapping("/{id}/draft")
    @RequiresPermission("screen:edit")
    ApiResponse<ScreenDraft> draft(@PathVariable UUID id) {
        return ApiResponse.ok(screenService.draft(screenService.get(id)));
    }

    @Operation(summary = "保存大屏草稿")
    @PutMapping("/{id}/draft")
    @RequiresPermission("screen:edit")
    ApiResponse<ScreenDraft> saveDraft(@PathVariable UUID id, @Valid @RequestBody ScreenDraft draft) {
        return ApiResponse.ok(screenService.saveDraft(id, draft));
    }

    @Operation(summary = "复制整个大屏为当前成员的草稿")
    @PostMapping("/{id}/copy")
    @RequiresPermission("screen:edit")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ScreenResponse> copy(@PathVariable UUID id) {
        return ApiResponse.created(response(screenService.copy(id)));
    }

    @Operation(summary = "发布当前已保存的草稿，覆盖上一份快照")
    @PostMapping("/{id}/publish")
    @RequiresPermission("screen:publish")
    @AuditLog(action = "screen.publish", resourceType = "screen", resourceId = "#id")
    ApiResponse<ScreenService.PublishedScreen> publish(@PathVariable UUID id) {
        return ApiResponse.ok(screenService.publish(id));
    }

    @Operation(summary = "读取当前成员数据范围内的大屏已发布快照")
    @GetMapping("/{id}/published")
    @RequiresPermission("screen:view")
    ApiResponse<ScreenService.PublishedScreen> published(@PathVariable UUID id) {
        return ApiResponse.ok(screenService.published(id));
    }

    @Operation(summary = "列出编辑器可绑定的数据集与当前字段，不返回查询或连接信息")
    @GetMapping("/{id}/draft/datasets")
    @RequiresPermission("screen:edit")
    ApiResponse<List<BindingDataset>> datasets(@PathVariable UUID id, PageQuery page) {
        return ApiResponse.page(queries.catalog(id, page), page.toPageMeta(queries.datasetCount()));
    }

    @Operation(summary = "按已发布大屏的组件配置取数")
    @GetMapping("/{id}/published/widgets/{widgetId}/query")
    @RequiresPermission("screen:view")
    ApiResponse<WidgetData> query(@PathVariable UUID id, @PathVariable String widgetId) {
        return ApiResponse.ok(queries.published(id, widgetId));
    }

    @Operation(summary = "预览编辑中的组件，仅允许当前可编辑大屏")
    @PostMapping("/{id}/draft/query")
    @RequiresPermission("screen:edit")
    ApiResponse<WidgetData> previewQuery(@PathVariable UUID id, @Valid @RequestBody ScreenDraft.Widget widget) {
        return ApiResponse.ok(queries.preview(id, widget));
    }

    private ScreenResponse response(Screen screen) {
        boolean editable = screenService.canEdit();
        ScreenDraft definition = editable
                ? screenService.draft(screen)
                : screenService.published(screen).definition();
        return new ScreenResponse(
                screen.getId(),
                editable ? screen.getName() : screen.getPublishedName(),
                editable ? definition : null,
                definition.designWidth(),
                definition.designHeight(),
                screen.getPublishedAt(),
                screen.getOwnerId(),
                screen.getDepartmentPath(),
                screen.getCreatedAt(),
                editable ? screen.getUpdatedAt() : screen.getPublishedAt());
    }

    @Operation(summary = "删除大屏")
    @DeleteMapping("/{id}")
    @RequiresPermission("screen:edit")
    ApiResponse<Void> delete(@PathVariable UUID id) {
        screenService.delete(id);
        return ApiResponse.ok();
    }

    /** 约束与 screens 表一致，违反时由 SDK 返回 400 request.invalid 并逐项列出字段，不必等数据库报错。 */
    record ScreenRequest(
            @NotBlank @Size(max = 120) String name, @Valid ScreenDraft draft) {}

    /**
     * @param ownerId 创建大屏的 Membership，系统创建的大屏为 null
     * @param departmentPath 创建人主部门的路径，创建人未分配部门时为 null
     */
    record ScreenResponse(
            UUID id,
            String name,
            ScreenDraft draft,
            int designWidth,
            int designHeight,
            OffsetDateTime publishedAt,
            UUID ownerId,
            String departmentPath,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
