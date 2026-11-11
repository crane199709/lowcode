package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.model.DatasetPreviewResponse;
import io.saasforge.lowcode.datasource.model.FieldResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** API 无法下推查询；仅对完整的有界输入计算，绝不把样本总和当作真实总和。 */
final class ApiDatasetAggregator {
    private ApiDatasetAggregator() {}

    static DatasetPreviewResponse aggregate(
            DatasetPreviewResponse input, List<FieldResponse> fields, DatasetQueryService.Query query) {
        if (input.truncated()) {
            throw new ForgeException(DatasetErrorCode.API_AGGREGATION_TOO_MANY_ROWS, input.maxRows());
        }
        Map<String, FieldResponse> savedFields = new HashMap<>();
        fields.forEach(field -> savedFields.put(field.name(), field));
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < input.fields().size(); i++) {
            indexes.put(input.fields().get(i).name(), i);
        }
        Map<List<Object>, List<MeasureState>> groups = new LinkedHashMap<>();
        if (query.dimensions().isEmpty()) {
            groups.put(
                    List.of(),
                    query.measures().stream()
                            .map(measure -> new MeasureState(measure.aggregation()))
                            .toList());
        }
        for (var row : input.rows()) {
            if (!query.filters().stream().allMatch(filter -> matches(row, indexes, savedFields, filter))) {
                continue;
            }
            List<Object> key = new ArrayList<>();
            for (var dimension : query.dimensions()) {
                Object raw = cell(row, indexes, savedFields, dimension.field());
                key.add(
                        dimension.dateGrouping() == null
                                ? canonical(raw)
                                : bucket(
                                        raw,
                                        dimension.dateGrouping(),
                                        savedFields.get(dimension.field()).displayName()));
            }
            var states = groups.computeIfAbsent(
                    key,
                    ignored -> query.measures().stream()
                            .map(measure -> new MeasureState(measure.aggregation()))
                            .toList());
            for (int i = 0; i < query.measures().size(); i++) {
                states.get(i)
                        .add(cell(
                                row,
                                indexes,
                                savedFields,
                                query.measures().get(i).field()));
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (var group : groups.entrySet()) {
            List<Object> row = new ArrayList<>(group.getKey());
            group.getValue().forEach(state -> row.add(state.result()));
            rows.add(row);
        }
        rows.sort((left, right) -> {
            for (var sort : query.sorts()) {
                Object a = left.get(sort.column());
                Object b = right.get(sort.column());
                int comparison = compareNullable(a, b);
                if (comparison != 0) {
                    return a != null && b != null && sort.direction() == DatasetQueryService.Direction.DESC
                            ? -comparison
                            : comparison;
                }
            }
            for (int i = 0; i < query.dimensions().size(); i++) {
                int comparison = compareNullable(left.get(i), right.get(i));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        });
        if (query.topN() != null && rows.size() > query.topN()) {
            rows = new ArrayList<>(rows.subList(0, query.topN()));
        }
        return new DatasetPreviewResponse(List.of(), rows, false, input.maxRows());
    }

    private static Object bucket(Object value, DatasetQueryService.DateGrouping grouping, String fieldLabel) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ForgeException(DatasetErrorCode.QUERY_DATE_INVALID, fieldLabel);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            try {
                var parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(text, ZonedDateTime::from, LocalDateTime::from);
                // 有时区值采用 UTC；无时区值没有可推断的源时区，保留自身日历日期。
                date = parsed instanceof ZonedDateTime zoned
                        ? zoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDate()
                        : LocalDate.from(parsed);
            } catch (DateTimeParseException invalid) {
                throw new ForgeException(DatasetErrorCode.QUERY_DATE_INVALID, fieldLabel);
            }
        }
        return switch (grouping) {
            case DAY -> date.toString();
            case MONTH -> date.format(DateTimeFormatter.ofPattern("uuuu-MM"));
            case YEAR -> date.format(DateTimeFormatter.ofPattern("uuuu"));
        };
    }

    private static boolean matches(
            List<Object> row,
            Map<String, Integer> indexes,
            Map<String, FieldResponse> fields,
            DatasetQueryService.Filter filter) {
        Object actual = cell(row, indexes, fields, filter.field());
        return switch (filter.operator()) {
            case EQ -> Objects.equals(actual, canonical(filter.values().get(0)));
            case IN -> filter.values().stream().anyMatch(value -> Objects.equals(actual, canonical(value)));
            case BETWEEN ->
                actual != null
                        && compareNullable(actual, canonical(filter.values().get(0))) >= 0
                        && compareNullable(actual, canonical(filter.values().get(1))) <= 0;
        };
    }

    private static Object cell(
            List<Object> row, Map<String, Integer> indexes, Map<String, FieldResponse> fields, String name) {
        Integer index = indexes.get(name);
        Object raw = index == null ? null : row.get(index);
        if (raw == null) {
            return null;
        }
        var field = fields.get(name);
        boolean valid = switch (field.type()) {
            case NUMBER -> raw instanceof Number;
            case STRING -> raw instanceof String;
            case BOOLEAN -> raw instanceof Boolean;
            default -> true;
        };
        if (!valid) {
            throw new ForgeException(DatasetErrorCode.QUERY_VALUE_INVALID, field.displayName());
        }
        return canonical(raw);
    }

    private static Object canonical(Object value) {
        return value instanceof Number ? new BigDecimal(value.toString()).stripTrailingZeros() : value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareNullable(Object left, Object right) {
        if (left == null || right == null) {
            return left == right ? 0 : left == null ? 1 : -1;
        }
        if (left.getClass() != right.getClass() || !(left instanceof Comparable)) {
            throw new ForgeException(DatasetErrorCode.QUERY_INVALID);
        }
        return ((Comparable) left).compareTo(right);
    }

    private static final class MeasureState {
        private final DatasetQueryService.Aggregation aggregation;
        private long count;
        private BigDecimal sum;
        private BigDecimal min;
        private BigDecimal max;
        private final Set<Object> distinct = new HashSet<>();

        private MeasureState(DatasetQueryService.Aggregation aggregation) {
            this.aggregation = aggregation;
        }

        private void add(Object value) {
            if (value == null) {
                return;
            }
            count++;
            if (aggregation == DatasetQueryService.Aggregation.COUNT_DISTINCT) {
                distinct.add(canonical(value));
            } else if (aggregation != DatasetQueryService.Aggregation.COUNT) {
                if (!(value instanceof Number)) {
                    throw new ForgeException(DatasetErrorCode.QUERY_FAILED);
                }
                BigDecimal number = new BigDecimal(value.toString());
                sum = sum == null ? number : sum.add(number);
                min = min == null || number.compareTo(min) < 0 ? number : min;
                max = max == null || number.compareTo(max) > 0 ? number : max;
            }
        }

        private Object result() {
            return switch (aggregation) {
                case COUNT -> count;
                case COUNT_DISTINCT -> distinct.size();
                case SUM -> sum;
                case AVG -> sum == null ? null : sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128);
                case MAX -> max;
                case MIN -> min;
            };
        }
    }
}
