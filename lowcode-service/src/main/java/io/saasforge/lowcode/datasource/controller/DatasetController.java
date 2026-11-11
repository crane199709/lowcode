package io.saasforge.lowcode.datasource.controller;

import io.saasforge.lowcode.datasource.model.DatabaseSourceResponse;
import io.saasforge.lowcode.datasource.model.DatasetRequest;
import io.saasforge.lowcode.datasource.model.DatasetResponse;
import io.saasforge.lowcode.datasource.model.SqlPreviewResponse;
import io.saasforge.lowcode.datasource.service.DataSourceService;
import io.saasforge.lowcode.datasource.service.DatasetService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

@Tag(name = "数据集")
@RestController
@RequestMapping("/datasets")
class DatasetController {
    private final DataSourceService dataSourceService;
    private final DatasetService datasets;

    DatasetController(DataSourceService dataSourceService, DatasetService datasets) {
        this.dataSourceService = dataSourceService;
        this.datasets = datasets;
    }

    @Operation(summary = "列出 SQL 数据集可选的数据源，不包含连接与凭据")
    @GetMapping("/sources")
    @RequiresPermission("dataset:manage")
    public ApiResponse<List<DatabaseSourceResponse>> sources(PageQuery query) {
        return ApiResponse.page(
                dataSourceService.databaseSources(query), query.toPageMeta(dataSourceService.databaseSourceCount()));
    }

    @Operation(summary = "列出当前租户的数据集")
    @GetMapping
    @RequiresPermission("dataset:view")
    public ApiResponse<List<DatasetResponse>> list(PageQuery query) {
        return ApiResponse.page(datasets.list(query), query.toPageMeta(datasets.count()));
    }

    @Operation(summary = "查看数据集与 Field")
    @GetMapping("/{id}")
    @RequiresPermission("dataset:view")
    public ApiResponse<DatasetResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(datasets.get(id));
    }

    @Operation(summary = "保存 SQL 数据集，重新执行预览推断 Field")
    @PostMapping
    @RequiresPermission("dataset:manage")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DatasetResponse> create(@Valid @RequestBody DatasetRequest request) {
        return ApiResponse.created(datasets.create(request));
    }

    @Operation(summary = "修改 SQL 数据集与 Field")
    @PutMapping("/{id}")
    @RequiresPermission("dataset:manage")
    public ApiResponse<DatasetResponse> update(@PathVariable UUID id, @Valid @RequestBody DatasetRequest request) {
        return ApiResponse.ok(datasets.update(id, request));
    }

    @Operation(summary = "逻辑删除数据集")
    @DeleteMapping("/{id}")
    @RequiresPermission("dataset:manage")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        datasets.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "预览单条只读 SQL 并推断 Field")
    @PostMapping("/preview")
    @RequiresPermission("dataset:manage")
    public ApiResponse<SqlPreviewResponse> preview(@Valid @RequestBody SqlPreviewRequest request) {
        return ApiResponse.ok(dataSourceService.previewSql(request.dataSourceId(), request.sql()));
    }

    record SqlPreviewRequest(
            @NotNull UUID dataSourceId,
            @NotBlank @Size(max = 65536) String sql) {}
}
