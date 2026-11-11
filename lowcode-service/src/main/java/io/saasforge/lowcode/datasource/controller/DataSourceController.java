package io.saasforge.lowcode.datasource.controller;

import io.saasforge.lowcode.datasource.entity.DataSource;
import io.saasforge.lowcode.datasource.model.ConnectionSettings;
import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.lowcode.datasource.service.DataSourceService;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

@Tag(name = "数据源")
@RestController
@RequestMapping("/data-sources")
class DataSourceController {
    private final DataSourceService dataSourceService;

    DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Operation(summary = "登记数据源")
    @PostMapping
    @RequiresPermission("datasource:manage")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(action = "datasource.create", resourceType = "dataSource", resourceId = "#result.data().id()")
    public ApiResponse<DataSourceResponse> create(@Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.created(
                response(dataSourceService.create(request.name(), request.type(), request.settings())));
    }

    @Operation(summary = "分页列出当前租户的数据源，最近更新在前")
    @GetMapping
    @RequiresPermission("datasource:view")
    public ApiResponse<List<DataSourceResponse>> list(PageQuery query) {
        List<DataSourceResponse> items =
                dataSourceService.list(query).stream().map(this::response).toList();
        return ApiResponse.page(items, query.toPageMeta(dataSourceService.count()));
    }

    @Operation(summary = "读取数据源，不含密码")
    @GetMapping("/{id}")
    @RequiresPermission("datasource:view")
    public ApiResponse<DataSourceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(response(dataSourceService.get(id)));
    }

    @Operation(summary = "修改数据源，密码留空表示不修改")
    @PutMapping("/{id}")
    @RequiresPermission("datasource:manage")
    @AuditLog(action = "datasource.update", resourceType = "dataSource", resourceId = "#id")
    public ApiResponse<DataSourceResponse> update(
            @PathVariable UUID id, @Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.ok(
                response(dataSourceService.update(id, request.name(), request.type(), request.settings())));
    }

    @Operation(summary = "删除数据源")
    @DeleteMapping("/{id}")
    @RequiresPermission("datasource:manage")
    @AuditLog(action = "datasource.delete", resourceType = "dataSource", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        dataSourceService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "测试尚未保存的连接，失败时以错误码返回原因")
    @PostMapping("/connection-test")
    @RequiresPermission("datasource:manage")
    public ApiResponse<Void> testConnection(@Valid @RequestBody DataSourceRequest request) {
        dataSourceService.testConnection(request.type(), request.settings());
        return ApiResponse.ok();
    }

    @Operation(summary = "测试编辑中的已保存数据源，密码留空时用已保存的密码")
    @PostMapping("/{id}/connection-test")
    @RequiresPermission("datasource:manage")
    public ApiResponse<Void> testSavedConnection(@PathVariable UUID id, @Valid @RequestBody DataSourceRequest request) {
        dataSourceService.testConnection(id, request.type(), request.settings());
        return ApiResponse.ok();
    }

    private DataSourceResponse response(DataSource dataSource) {
        return new DataSourceResponse(
                dataSource.getId(),
                dataSource.getName(),
                DataSourceType.valueOf(dataSource.getType()),
                dataSource.getHost(),
                dataSource.getPort(),
                dataSource.getDatabaseName(),
                dataSource.getUsername(),
                dataSourceService.paramsOf(dataSource),
                dataSource.getCreatedAt(),
                dataSource.getUpdatedAt());
    }

    /**
     * 约束与 data_sources 表一致。主机只允许主机名或 IP 字面量的字符，排除 PostgreSQL URL 的多主机写法（逗号）与路径、参数分隔符。
     *
     * @param password 新建时必填；编辑时留空表示不修改
     * @param params 按类型白名单校验，不在白名单内的参数整体拒绝
     */
    record DataSourceRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull DataSourceType type,

            @NotBlank @Size(max = 253) @Pattern(regexp = "[A-Za-z0-9.:\\-]+")
            String host,

            @NotNull @Min(1) @Max(65535) Integer port,
            @NotBlank @Size(max = 128) String database,
            @NotBlank @Size(max = 128) String username,
            @Size(max = 256) String password,
            Map<String, String> params) {
        ConnectionSettings settings() {
            return new ConnectionSettings(host, port, database, username, password, params);
        }

        /** 不输出密码，避免请求被记录到日志。 */
        @Override
        public String toString() {
            return "DataSourceRequest[name=" + name + ", type=" + type + ", " + settings() + "]";
        }
    }

    /** 不含密码：任何接口都不回显密码，编辑表单的密码框始终为空。 */
    record DataSourceResponse(
            UUID id,
            String name,
            DataSourceType type,
            String host,
            Integer port,
            String database,
            String username,
            Map<String, String> params,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
