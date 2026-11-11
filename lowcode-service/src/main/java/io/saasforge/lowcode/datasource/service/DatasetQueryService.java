package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.model.FieldResponse;
import io.saasforge.lowcode.datasource.model.FieldType;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.web.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Widget 只描述当前 Dataset 的 Field 与操作，不接收 SQL 或连接设置。 */
@Service
public class DatasetQueryService {
    private final DatasetService datasets;
    private final DataSourceService sources;

    public DatasetQueryService(DatasetService datasets, DataSourceService sources) {
        this.datasets = datasets;
        this.sources = sources;
    }

    public List<DatasetChoice> catalog(PageQuery page) {
        return datasets.list(page).stream()
                .map(d -> new DatasetChoice(
                        d.id(),
                        d.name(),
                        d.fields().stream()
                                .map(f -> new FieldChoice(
                                        f.name(),
                                        f.displayName(),
                                        f.type().name(),
                                        f.role().name(),
                                        f.date()))
                                .toList()))
                .toList();
    }

    public long count() {
        return datasets.count();
    }

    public record DatasetChoice(UUID id, String name, List<FieldChoice> fields) {}

    public record FieldChoice(String name, String displayName, String type, String role, boolean date) {}

    public Result query(UUID datasetId, Query query) {
        var dataset = datasets.get(datasetId);
        validateShape(query);
        Map<String, FieldResponse> fields = new HashMap<>();
        dataset.fields().forEach(field -> fields.put(field.name(), field));
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < query.dimensions().size(); i++) {
            var dimension = query.dimensions().get(i);
            var field = field(fields, dimension.field());
            if (dimension.dateGrouping() != null && !field.date()) {
                throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
            }
            columns.add(new Column(
                    "d" + i,
                    field.displayName(),
                    dimension.dateGrouping() == null ? field.type().name() : "STRING"));
        }
        for (int i = 0; i < query.measures().size(); i++) {
            var measure = query.measures().get(i);
            var field = field(fields, measure.field());
            if (measure.aggregation() != Aggregation.COUNT
                    && measure.aggregation() != Aggregation.COUNT_DISTINCT
                    && field.type() != FieldType.NUMBER) {
                throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
            }
            columns.add(new Column("m" + i, field.displayName() + " · " + measure.aggregation().label, "NUMBER"));
        }
        List<Filter> filters = new ArrayList<>();
        for (var filter : query.filters()) {
            var field = field(fields, filter.field());
            List<Object> values = new ArrayList<>();
            for (var value : filter.values()) {
                values.add(value == null ? null : value(field.type(), value));
            }
            if (filter.operator() == Operator.BETWEEN
                    && (values.contains(null) || compare(values.get(0), values.get(1)) > 0)) {
                throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
            }
            filters.add(new Filter(filter.field(), filter.operator(), values));
        }
        var validated = new Query(query.dimensions(), query.measures(), filters, query.sorts(), query.topN());
        var result = dataset.jsonPath() == null
                ? sources.aggregate(dataset.dataSourceId(), dataset.sql(), validated)
                : ApiDatasetAggregator.aggregate(
                        sources.previewDataset(dataset.dataSourceId(), dataset.sql(), dataset.jsonPath()),
                        dataset.fields(),
                        validated);
        return new Result(columns, result.rows(), result.truncated(), result.maxRows());
    }

    public static void validateShape(Query q) {
        if (q == null
                || q.dimensions() == null
                || q.measures() == null
                || q.filters() == null
                || q.sorts() == null
                || q.dimensions().size() > 8
                || q.measures().isEmpty()
                || q.measures().size() > 16
                || q.filters().size() > 20
                || q.sorts().size() > 24
                || (q.topN() != null && (q.topN() < 1 || q.topN() > 100000))) {
            throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
        }
        for (var d : q.dimensions()) {
            if (d == null || d.field() == null || d.field().isBlank()) {
                throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
            }
        }
        for (var m : q.measures()) {
            if (m == null || m.field() == null || m.field().isBlank() || m.aggregation() == null) {
                throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
            }
        }
        for (var f : q.filters()) {
            if (f == null
                    || f.field() == null
                    || f.field().isBlank()
                    || f.operator() == null
                    || f.values() == null
                    || f.values().isEmpty()
                    || f.values().size() > 1000
                    || (f.operator() == Operator.EQ && f.values().size() != 1)
                    || (f.operator() == Operator.BETWEEN && f.values().size() != 2)) {
                throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
            }
        }
        for (var s : q.sorts()) {
            if (s == null
                    || s.direction() == null
                    || s.column() < 0
                    || s.column() >= q.dimensions().size() + q.measures().size()) {
                throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
            }
        }
    }

    private static FieldResponse field(Map<String, FieldResponse> fields, String name) {
        var field = fields.get(name);
        if (field == null) {
            throw new ForgeException(DatasetErrorCode.FIELD_MISSING);
        }
        return field;
    }

    private static Object value(FieldType type, Object value) {
        try {
            return switch (type) {
                case NUMBER -> new BigDecimal(value.toString());
                case BOOLEAN -> {
                    if (!List.of("true", "false").contains(value.toString())) {
                        throw new IllegalArgumentException();
                    }
                    yield Boolean.valueOf(value.toString());
                }
                case DATE -> LocalDate.parse(value.toString());
                case DATETIME -> {
                    try {
                        yield OffsetDateTime.parse(value.toString());
                    } catch (DateTimeParseException e) {
                        yield LocalDateTime.parse(value.toString());
                    }
                }
                default -> {
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException();
                    }
                    yield value;
                }
            };
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object a, Object b) {
        if (a.getClass() != b.getClass()) {
            throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
        }
        return ((Comparable) a).compareTo(b);
    }

    public record Query(
            List<Dimension> dimensions, List<Measure> measures, List<Filter> filters, List<Sort> sorts, Integer topN) {}

    public record Dimension(String field, DateGrouping dateGrouping) {
        public Dimension(String field) {
            this(field, null);
        }
    }

    public enum DateGrouping {
        DAY,
        MONTH,
        YEAR
    }

    public record Measure(String field, Aggregation aggregation) {}

    public record Filter(String field, Operator operator, List<Object> values) {}
    /** column 是 dimensions 后接 measures 的零基索引，避免同一 Field 多次聚合时歧义。 */
    public record Sort(int column, Direction direction) {}

    public record Column(String id, String label, String type) {}

    public record Result(List<Column> columns, List<List<Object>> rows, boolean truncated, int maxRows) {}

    public enum Aggregation {
        SUM("求和"),
        AVG("平均"),
        COUNT("计数"),
        COUNT_DISTINCT("去重计数"),
        MAX("最大"),
        MIN("最小");
        final String label;

        Aggregation(String label) {
            this.label = label;
        }
    }

    public enum Operator {
        EQ,
        IN,
        BETWEEN
    }

    public enum Direction {
        ASC,
        DESC
    }
}
