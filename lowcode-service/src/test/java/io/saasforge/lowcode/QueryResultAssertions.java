package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** SQL 方言和 API 在公开查询结果上使用同一组手工算定的期望值。 */
final class QueryResultAssertions {
    static final String SALES_JSON = """
            [{"region":"east","amount":1},{"region":"east","amount":2},
             {"region":"east","amount":2.0},{"region":"east","amount":3},
             {"region":"east","amount":null},{"region":"west","amount":4},
             {"region":null,"amount":3}]
            """;
    static final String SALES_POSTGRES_SQL = """
            SELECT * FROM (VALUES ('east',1.0),('east',2.0),('east',2.00),('east',3.0),
            ('east',NULL),('west',4.0),(NULL,3.0)) sales(region,amount)
            """;
    static final String DATES_JSON = """
            [{"day":"2024-02-29","amount":1},{"day":"2024-03-01","amount":2},
             {"day":"2025-02-01","amount":4},{"day":null,"amount":8},
             {"day":"2025-01-01T00:30:00+01:00","amount":16},
             {"day":"2024-02-29T23:30:00","amount":32}]
            """;

    static void sales(List<?> rows) {
        rows(rows, new Object[] {"east", 8, 2, 4, 3, 3, 1}, new Object[] {"west", 4, 4, 1, 1, 4, 4}, new Object[] {
            null, 3, 3, 1, 1, 3, 3
        });
    }

    static void allNull(List<?> rows) {
        rows(rows, new Object[] {null, null, 0, 0, null, null});
    }

    static void dateDay(List<?> rows) {
        rows(
                rows,
                new Object[] {"2024-02-29", 33},
                new Object[] {"2024-03-01", 2},
                new Object[] {"2024-12-31", 16},
                new Object[] {"2025-02-01", 4},
                new Object[] {null, 8});
    }

    static void dateMonth(List<?> rows) {
        rows(
                rows,
                new Object[] {"2024-02", 33},
                new Object[] {"2024-03", 2},
                new Object[] {"2024-12", 16},
                new Object[] {"2025-02", 4},
                new Object[] {null, 8});
    }

    static void dateYear(List<?> rows) {
        rows(rows, new Object[] {"2024", 51}, new Object[] {"2025", 4}, new Object[] {null, 8});
    }

    static void rows(List<?> actual, Object[]... expected) {
        assertThat(actual).hasSize(expected.length);
        for (int row = 0; row < expected.length; row++) {
            List<?> values = (List<?>) actual.get(row);
            assertThat(values).as("row %s", row).hasSize(expected[row].length);
            for (int column = 0; column < expected[row].length; column++) {
                Object value = values.get(column);
                Object literal = expected[row][column];
                if (literal instanceof Number) {
                    assertThat(value)
                            .as("row %s column %s in %s", row, column, Arrays.toString(expected[row]))
                            .isInstanceOf(Number.class);
                    assertThat(new BigDecimal(value.toString())).isEqualByComparingTo(literal.toString());
                } else {
                    assertThat(value).as("row %s column %s", row, column).isEqualTo(literal);
                }
            }
        }
    }
}
