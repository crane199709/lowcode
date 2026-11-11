package io.saasforge.lowcode.datasource.controller;

import io.saasforge.lowcode.datasource.entity.DataSource;
import io.saasforge.lowcode.datasource.model.ApiAuthType;
import io.saasforge.lowcode.datasource.model.ApiConnectionTestResponse;
import io.saasforge.lowcode.datasource.model.ApiMethod;
import io.saasforge.lowcode.datasource.model.ColumnResponse;
import io.saasforge.lowcode.datasource.model.DataSourceRequest;
import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.lowcode.datasource.model.TableResponse;
import io.saasforge.lowcode.datasource.service.DataSourceService;
import io.saasforge.lowcode.datasource.service.DeletionReferences;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
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
        return ApiResponse.created(response(createDataSource(request)));
    }

    @Operation(summary = "分页列出当前租户的数据源，最近更新在前")
    @GetMapping
    @RequiresPermission("datasource:view")
    public ApiResponse<List<DataSourceResponse>> list(PageQuery query) {
        List<DataSourceResponse> items =
                dataSourceService.list(query).stream().map(this::response).toList();
        return ApiResponse.page(items, query.toPageMeta(dataSourceService.count()));
    }

    @Operation(summary = "读取数据源，不含密码与请求头值")
    @GetMapping("/{id}")
    @RequiresPermission("datasource:view")
    public ApiResponse<DataSourceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(response(dataSourceService.get(id)));
    }

    @Operation(summary = "浏览当前数据库账号可见的表与 schema")
    @GetMapping("/{id}/tables")
    @RequiresPermission("datasource:view")
    public ApiResponse<List<TableResponse>> tables(@PathVariable UUID id) {
        return ApiResponse.ok(dataSourceService.tables(id));
    }

    @Operation(summary = "按精确的 catalog、schema、表名浏览列名与数据库类型")
    @GetMapping("/{id}/columns")
    @RequiresPermission("datasource:view")
    public ApiResponse<List<ColumnResponse>> columns(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "") String catalog,
            @RequestParam(defaultValue = "") String schema,
            @RequestParam String table) {
        return ApiResponse.ok(dataSourceService.columns(id, new TableResponse(catalog, schema, table)));
    }

    @Operation(summary = "修改数据源，密码与请求头值留空表示不修改")
    @PutMapping("/{id}")
    @RequiresPermission("datasource:manage")
    @AuditLog(action = "datasource.update", resourceType = "dataSource", resourceId = "#id")
    public ApiResponse<DataSourceResponse> update(
            @PathVariable UUID id, @Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.ok(response(updateDataSource(id, request)));
    }

    @Operation(summary = "列出阻止删除的 Dataset，不包含查询定义")
    @GetMapping("/{id}/references")
    @RequiresPermission("datasource:manage")
    public ApiResponse<DeletionReferences> references(@PathVariable UUID id) {
        return ApiResponse.ok(dataSourceService.references(id));
    }

    @Operation(summary = "删除数据源")
    @DeleteMapping("/{id}")
    @RequiresPermission("datasource:manage")
    @AuditLog(action = "datasource.delete", resourceType = "dataSource", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        dataSourceService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "测试尚未保存的连接，失败时以错误码返回原因；API 返回目标接口的状态码")
    @PostMapping("/connection-test")
    @RequiresPermission("datasource:manage")
    public ApiResponse<ApiConnectionTestResponse> testConnection(@Valid @RequestBody DataSourceRequest request) {
        if (request.type() == DataSourceType.API) {
            return ApiResponse.ok(dataSourceService.testApiConnection(request.apiSettings()));
        }
        dataSourceService.testConnection(request.type(), request.databaseSettings());
        return ApiResponse.<ApiConnectionTestResponse>ok(null);
    }

    @Operation(summary = "测试编辑中的已保存数据源，密码与请求头值留空时用已保存的值")
    @PostMapping("/{id}/connection-test")
    @RequiresPermission("datasource:manage")
    public ApiResponse<ApiConnectionTestResponse> testSavedConnection(
            @PathVariable UUID id, @Valid @RequestBody DataSourceRequest request) {
        if (request.type() == DataSourceType.API) {
            return ApiResponse.ok(dataSourceService.testApiConnection(id, request.apiSettings()));
        }
        dataSourceService.testConnection(id, request.type(), request.databaseSettings());
        return ApiResponse.<ApiConnectionTestResponse>ok(null);
    }

    private DataSource createDataSource(DataSourceRequest request) {
        if (request.type() == DataSourceType.API) {
            return dataSourceService.createApi(request.name(), request.apiSettings());
        }
        return dataSourceService.createDatabase(request.name(), request.type(), request.databaseSettings());
    }

    private DataSource updateDataSource(UUID id, DataSourceRequest request) {
        if (request.type() == DataSourceType.API) {
            return dataSourceService.updateApi(id, request.name(), request.apiSettings());
        }
        return dataSourceService.updateDatabase(id, request.name(), request.type(), request.databaseSettings());
    }

    private DataSourceResponse response(DataSource dataSource) {
        DataSourceType type = DataSourceType.valueOf(dataSource.getType());
        if (type == DataSourceType.API) {
            ApiAuthType authType = ApiAuthType.valueOf(dataSource.getApiAuth());
            return new DataSourceResponse(
                    dataSource.getId(),
                    dataSource.getName(),
                    type,
                    null,
                    null,
                    null,
                    authType == ApiAuthType.BASIC ? dataSource.getUsername() : null,
                    null,
                    new ApiConnectionResponse(
                            dataSource.getApiUrl(),
                            ApiMethod.valueOf(dataSource.getApiMethod()),
                            dataSource.getApiBody(),
                            authType,
                            dataSource.getApiHeaderName()),
                    dataSource.getCreatedAt(),
                    dataSource.getUpdatedAt());
        }
        return new DataSourceResponse(
                dataSource.getId(),
                dataSource.getName(),
                type,
                dataSource.getHost(),
                dataSource.getPort(),
                dataSource.getDatabaseName(),
                dataSource.getUsername(),
                dataSourceService.paramsOf(dataSource),
                null,
                dataSource.getCreatedAt(),
                dataSource.getUpdatedAt());
    }

    /** 不含密码与请求头值：任何接口都不回显它们，编辑表单的对应输入框始终为空。 */
    record DataSourceResponse(
            UUID id,
            String name,
            DataSourceType type,
            String host,
            Integer port,
            String database,
            String username,
            Map<String, String> params,
            ApiConnectionResponse api,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    /** API 请求配置的响应模型，不含请求头值与 Basic 密码。 */
    record ApiConnectionResponse(String url, ApiMethod method, String body, ApiAuthType authType, String headerName) {}
}
