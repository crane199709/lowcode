package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.lowcode.datasource.model.FieldResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

/** 仅拼接已验证 Field 和枚举；Tenant 的取值始终通过数据库参数传递。 */
final class SqlAggregation {
    private SqlAggregation() {}

    record Prepared(String sql, List<Object> values) {}

    static Prepared build(DataSourceType type, String sql, DatasetQueryService.Query q, List<FieldResponse> fields) {
        List<Object> values = new ArrayList<>();
        List<String> selections = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < q.dimensions().size(); i++) {
            String expression = identifier(type, q.dimensions().get(i).field());
            var dimension = q.dimensions().get(i);
            if (dimension.dateGrouping() != null) {
                var fieldType = fields.stream()
                        .filter(f -> f.name().equals(dimension.field()))
                        .findFirst()
                        .orElseThrow()
                        .type();
                expression = SqlDateGrouping.expression(type, expression, fieldType, dimension.dateGrouping());
            }
            selections.add(expression + " AS d" + i);
            groups.add(expression);
        }
        for (int i = 0; i < q.measures().size(); i++) {
            var m = q.measures().get(i);
            String field = identifier(type, m.field());
            String expression;
            if (type == DataSourceType.CLICKHOUSE) {
                expression = switch (m.aggregation()) {
                    case COUNT_DISTINCT -> "uniqExact(" + field + ")";
                    case COUNT -> "count(" + field + ")";
                    case SUM -> "sumOrNull(" + field + ")";
                    case AVG -> "avgOrNull(" + field + ")";
                    case MAX -> "maxOrNull(" + field + ")";
                    case MIN -> "minOrNull(" + field + ")";
                };
            } else if (m.aggregation() == DatasetQueryService.Aggregation.COUNT_DISTINCT) {
                expression = "COUNT(DISTINCT " + field + ")";
            } else {
                // SQL Server 整数 AVG 会截断、SUM 可能溢出；十进制乘法保留整数精度及平均值的小数。
                if (type == DataSourceType.SQLSERVER
                        && (m.aggregation() == DatasetQueryService.Aggregation.AVG
                                || m.aggregation() == DatasetQueryService.Aggregation.SUM)) {
                    field = "(" + field + " * 1.0)";
                }
                expression = m.aggregation().name() + "(" + field + ")";
            }
            selections.add(expression + " AS m" + i);
        }
        String source = sql;
        String prefix = "";
        if (type == DataSourceType.SQLSERVER) {
            try {
                Select select = (Select) CCJSqlParserUtil.parse(sql, p -> p.withSquareBracketQuotation(true));
                if (select.getWithItemsList() != null) {
                    prefix = "WITH "
                            + String.join(
                                    ",",
                                    select.getWithItemsList().stream()
                                            .map(Object::toString)
                                            .toList()) + " ";
                    select.setWithItemsList(null);
                }
                // ORDER BY 不带 TOP/OFFSET 时仅影响来源顺序，对外层聚合无语义作用，且派生表不允许。
                if (!(select instanceof PlainSelect plain && plain.getTop() != null)
                        && select.getOffset() == null
                        && select.getFetch() == null) {
                    select.setOrderByElements(null);
                }
                source = select.toString();
            } catch (JSQLParserException e) {
                throw new IllegalArgumentException(e);
            }
        }
        StringBuilder statement = new StringBuilder("SELECT ");
        statement
                .append(String.join(",", selections))
                .append(" FROM (\n")
                .append(source)
                .append("\n) dataset_rows");
        List<String> conditions = new ArrayList<>();
        for (var f : q.filters()) {
            String field = identifier(type, f.field());
            var nonNull = f.values().stream().filter(Objects::nonNull).toList();
            String condition;
            if (f.operator() == DatasetQueryService.Operator.BETWEEN) {
                condition = field + " BETWEEN " + bind(type, values, nonNull.get(0)) + " AND "
                        + bind(type, values, nonNull.get(1));
            } else if (nonNull.isEmpty()) {
                condition = field + " IS NULL";
            } else {
                condition = f.operator() == DatasetQueryService.Operator.EQ
                        ? field + " = " + bind(type, values, nonNull.get(0))
                        : field + " IN ("
                                + String.join(
                                        ",",
                                        nonNull.stream()
                                                .map(v -> bind(type, values, v))
                                                .toList()) + ")";
                if (nonNull.size() != f.values().size()) {
                    condition = "(" + condition + " OR " + field + " IS NULL)";
                }
            }
            conditions.add(condition);
        }
        if (!conditions.isEmpty()) {
            statement.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (!groups.isEmpty()) {
            statement.append(" GROUP BY ").append(String.join(",", groups));
        }
        String top = "";
        if (type == DataSourceType.SQLSERVER && q.topN() != null) {
            top = "TOP (?) ";
            values.add(0, q.topN());
        }
        statement = new StringBuilder(prefix + "SELECT " + top + "* FROM (" + statement + ") grouped_rows");
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < q.dimensions().size(); i++) {
            if (q.dimensions().get(i).dateGrouping() != null) {
                orders.add("CASE WHEN d" + i + " = '!INVALID_DATE!' THEN 0 ELSE 1 END ASC");
            }
        }
        var sorted = new HashSet<Integer>();
        for (var sort : q.sorts()) {
            order(type, orders, alias(q, sort.column()), sort.direction());
            sorted.add(sort.column());
        }
        for (int i = 0; i < q.dimensions().size(); i++) {
            if (!sorted.contains(i)) {
                order(type, orders, "d" + i, DatasetQueryService.Direction.ASC);
            }
        }
        if (!orders.isEmpty()) {
            statement.append(" ORDER BY ").append(String.join(",", orders));
        }
        if (q.topN() != null && type != DataSourceType.SQLSERVER) {
            statement
                    .append(type == DataSourceType.ORACLE ? " FETCH FIRST " : " LIMIT ")
                    .append(bind(type, values, q.topN()));
            if (type == DataSourceType.ORACLE) {
                statement.append(" ROWS ONLY");
            }
        }
        return new Prepared(statement.toString(), values);
    }

    private static String alias(DatasetQueryService.Query q, int column) {
        return column < q.dimensions().size()
                ? "d" + column
                : "m" + (column - q.dimensions().size());
    }

    private static void order(
            DataSourceType type, List<String> orders, String alias, DatasetQueryService.Direction direction) {
        if (type == DataSourceType.MYSQL || type == DataSourceType.SQLSERVER) {
            orders.add("CASE WHEN " + alias + " IS NULL THEN 1 ELSE 0 END ASC");
            orders.add(alias + " " + direction);
        } else {
            orders.add(alias + " " + direction + " NULLS LAST");
        }
    }

    private static String identifier(DataSourceType type, String field) {
        return switch (type) {
            case MYSQL, CLICKHOUSE -> "`" + field.replace("`", "``") + "`";
            case SQLSERVER -> "[" + field.replace("]", "]]") + "]";
            default -> "\"" + field.replace("\"", "\"\"") + "\"";
        };
    }

    private static String bind(DataSourceType type, List<Object> values, Object value) {
        int index = values.size();
        values.add(value);
        if (type != DataSourceType.CLICKHOUSE) {
            return "?";
        }
        String dataType;
        if (value instanceof java.math.BigDecimal decimal) {
            dataType = "Decimal(76," + Math.max(0, decimal.scale()) + ")";
        } else if (value instanceof Number) {
            dataType = "UInt32";
        } else if (value instanceof Boolean) {
            dataType = "Bool";
        } else {
            dataType = "String";
        }
        return "{p" + index + ":" + dataType + "}";
    }
}
