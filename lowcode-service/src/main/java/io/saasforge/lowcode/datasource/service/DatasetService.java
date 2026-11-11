package io.saasforge.lowcode.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.lowcode.datasource.entity.Dataset;
import io.saasforge.lowcode.datasource.mapper.DatasetMapper;
import io.saasforge.lowcode.datasource.model.DatasetRequest;
import io.saasforge.lowcode.datasource.model.DatasetResponse;
import io.saasforge.lowcode.datasource.model.FieldOverrideRequest;
import io.saasforge.lowcode.datasource.model.FieldResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.web.PageQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DatasetService {
    private static final TypeReference<List<FieldResponse>> FIELDS = new TypeReference<>() {};
    private final DatasetMapper mapper;
    private final DataSourceService sources;
    private final JsonMapper json;

    DatasetService(DatasetMapper mapper, DataSourceService sources, JsonMapper json) {
        this.mapper = mapper;
        this.sources = sources;
        this.json = json;
    }

    public DatasetResponse create(DatasetRequest request) {
        var dataset = prepare(request);
        mapper.insert(dataset);
        return get(dataset.getId());
    }

    public DatasetResponse update(UUID id, DatasetRequest request) {
        require(id);
        var dataset = prepare(request);
        dataset.setId(id);
        if (mapper.updateById(dataset) == 0) {
            throw notFound();
        }
        return get(id);
    }

    public DatasetResponse get(UUID id) {
        return response(require(id));
    }

    public List<DatasetResponse> list(PageQuery query) {
        return mapper.selectPage(query.size(), query.offset()).stream()
                .map(this::response)
                .toList();
    }

    public long count() {
        return mapper.selectCount(new LambdaQueryWrapper<>());
    }

    public void delete(UUID id) {
        if (mapper.deleteById(id) == 0) {
            throw notFound();
        }
    }

    private Dataset require(UUID id) {
        var dataset = mapper.selectById(id);
        if (dataset == null) {
            throw notFound();
        }
        return dataset;
    }

    private Dataset prepare(DatasetRequest request) {
        // 每次保存都重新执行查询；客户端不能伪造 Field 的存在性与结果类型。
        var inferred = sources.previewDataset(request.dataSourceId(), request.sql(), request.jsonPath())
                .fields();
        Map<String, FieldOverrideRequest> overrides = new HashMap<>();
        if (request.fields() != null) {
            for (var field : request.fields()) {
                if (field.displayName().isBlank() || overrides.put(field.name(), field) != null) {
                    throw new ForgeException(DatasetErrorCode.FIELD_INVALID);
                }
            }
        }
        var fields = inferred.stream()
                .map(field -> {
                    var override = overrides.remove(field.name());
                    return override == null
                            ? field
                            : new FieldResponse(
                                    field.name(),
                                    override.displayName(),
                                    field.type(),
                                    override.role(),
                                    override.date());
                })
                .toList();
        if (!overrides.isEmpty()) {
            throw new ForgeException(DatasetErrorCode.FIELD_INVALID);
        }
        var dataset = new Dataset();
        dataset.setName(request.name());
        dataset.setDataSourceId(request.dataSourceId());
        dataset.setSqlQuery(request.sql() == null || request.sql().isBlank() ? "" : request.sql());
        dataset.setJsonPath(request.jsonPath() == null || request.jsonPath().isBlank() ? null : request.jsonPath());
        dataset.setFields(json.writeValueAsString(fields));
        return dataset;
    }

    private DatasetResponse response(Dataset dataset) {
        // 数据源的引用保护由工单 16 实现；已删除的数据源仍允许查看数据集定义。
        String sourceName;
        try {
            sourceName = sources.get(dataset.getDataSourceId()).getName();
        } catch (ForgeException e) {
            if (!DataSourceErrorCode.NOT_FOUND.code().equals(e.errorCode().code())) {
                throw e;
            }
            sourceName = null;
        }
        return new DatasetResponse(
                dataset.getId(),
                dataset.getName(),
                dataset.getDataSourceId(),
                sourceName,
                dataset.getSqlQuery(),
                dataset.getJsonPath(),
                json.readValue(dataset.getFields(), FIELDS),
                dataset.getCreatedAt(),
                dataset.getUpdatedAt());
    }

    private static ForgeException notFound() {
        return new ForgeException(DatasetErrorCode.NOT_FOUND);
    }
}
