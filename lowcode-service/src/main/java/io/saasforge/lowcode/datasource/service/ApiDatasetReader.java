package io.saasforge.lowcode.datasource.service;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
import io.saasforge.lowcode.datasource.config.SqlDatasetProperties;
import io.saasforge.lowcode.datasource.model.DatasetPreviewResponse;
import io.saasforge.lowcode.datasource.model.FieldResponse;
import io.saasforge.lowcode.datasource.model.FieldRole;
import io.saasforge.lowcode.datasource.model.FieldType;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** API 与 SQL 共用 Field 模型和最大行数；只从返回范围内的样本推断，避免无界展开。 */
@Component
class ApiDatasetReader {
    private final JsonMapper json;
    private final SqlDatasetProperties properties;

    ApiDatasetReader(JsonMapper json, SqlDatasetProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    JsonPath compile(String path) {
        if (path == null || !path.startsWith("$") || path.endsWith("[")) {
            throw new ForgeException(DatasetErrorCode.JSON_PATH_INVALID);
        }
        try {
            return JsonPath.compile(path);
        } catch (JsonPathException | IllegalArgumentException e) {
            throw new ForgeException(DatasetErrorCode.JSON_PATH_INVALID);
        }
    }

    DatasetPreviewResponse preview(byte[] body, JsonPath path) {
        Object document;
        try {
            document = json.readerFor(Object.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(body);
        } catch (JacksonException e) {
            throw new ForgeException(DatasetErrorCode.API_JSON_INVALID);
        }
        Object selected;
        try {
            selected = path.read(document);
        } catch (JsonPathException | IllegalArgumentException e) {
            throw new ForgeException(DatasetErrorCode.JSON_PATH_INVALID);
        }
        if (!(selected instanceof List<?> items)) {
            throw new ForgeException(DatasetErrorCode.API_RESULT_NOT_ARRAY);
        }
        var sample = items.subList(0, Math.min(items.size(), properties.maxRows()));
        Map<String, FieldType> types = new LinkedHashMap<>();
        for (var item : sample) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new ForgeException(DatasetErrorCode.API_ROWS_INVALID);
            }
            for (var entry : row.entrySet()) {
                String name = (String) entry.getKey();
                if (name.isBlank()) {
                    throw new ForgeException(DatasetErrorCode.FIELD_INVALID);
                }
                FieldType type = typeOf(entry.getValue());
                // NULL 不覆盖已知类型；异构非空值保守归为 OTHER，不把文本当作数值。
                if (!types.containsKey(name) || types.get(name) == null) {
                    types.put(name, type);
                } else if (type != null && types.get(name) != type) {
                    types.put(name, FieldType.OTHER);
                }
            }
        }
        var fields = types.entrySet().stream()
                .map(entry -> {
                    FieldType type = entry.getValue() == null ? FieldType.OTHER : entry.getValue();
                    return new FieldResponse(
                            entry.getKey(),
                            entry.getKey(),
                            type,
                            type == FieldType.NUMBER ? FieldRole.MEASURE : FieldRole.DIMENSION,
                            false);
                })
                .toList();
        List<List<Object>> rows = new ArrayList<>();
        for (var item : sample) {
            var row = (Map<?, ?>) item;
            List<Object> values = new ArrayList<>();
            for (String name : types.keySet()) {
                Object value = row.get(name);
                values.add(
                        value instanceof Map<?, ?> || value instanceof List<?>
                                ? json.writeValueAsString(value)
                                : value);
            }
            rows.add(values);
        }
        return new DatasetPreviewResponse(fields, rows, items.size() > properties.maxRows(), properties.maxRows());
    }

    private static FieldType typeOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return FieldType.NUMBER;
        }
        if (value instanceof String) {
            return FieldType.STRING;
        }
        if (value instanceof Boolean) {
            return FieldType.BOOLEAN;
        }
        return FieldType.OTHER;
    }
}
