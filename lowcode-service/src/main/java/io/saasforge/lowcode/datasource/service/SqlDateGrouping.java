package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.lowcode.datasource.model.FieldType;

/** 无时区值按日历分组；offset/zone 时间按 UTC，连接会话也固定为 UTC。 */
final class SqlDateGrouping {
    static final String INVALID = "!INVALID_DATE!";
    private static final String ISO =
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}(T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})?)?$";

    private SqlDateGrouping() {}

    static String expression(
            DataSourceType type, String field, FieldType fieldType, DatasetQueryService.DateGrouping grouping) {
        boolean text = fieldType != FieldType.DATE && fieldType != FieldType.DATETIME;
        String format = switch (grouping) {
            case DAY -> "YYYY-MM-DD";
            case MONTH -> "YYYY-MM";
            case YEAR -> "YYYY";
        };
        String percent = switch (grouping) {
            case DAY -> "%Y-%m-%d";
            case MONTH -> "%Y-%m";
            case YEAR -> "%Y";
        };
        String value = field;
        String valid = "1=1";
        String label;
        switch (type) {
            case POSTGRESQL -> {
                if (text) {
                    String normalized = "regexp_replace(" + field + ",'[.][0-9]+','')";
                    value = "CASE WHEN pg_input_is_valid(" + normalized + ",'timestamptz') THEN CAST(" + normalized
                            + " AS timestamptz) ELSE NULL END";
                    valid = field + " ~ '" + ISO + "'";
                }
                label = "TO_CHAR(" + value + ",'" + format + "')";
            }
            case MYSQL -> {
                if (text) {
                    String local = "STR_TO_DATE(CASE WHEN LENGTH(" + field + ")=10 THEN CONCAT(" + field
                            + ",' 00:00:00') ELSE LEFT(REPLACE(" + field + ",'T',' '),19) END,'%Y-%m-%d %H:%i:%s')";
                    value = "CONVERT_TZ(" + local + ",CASE WHEN " + field
                            + " REGEXP '[+-][0-9]{2}:[0-9]{2}$' THEN RIGHT(" + field
                            + ",6) ELSE '+00:00' END,'+00:00')";
                    valid = field + " REGEXP '" + ISO + "' AND DAY(" + local + ") <= DAY(LAST_DAY(" + local + "))";
                }
                label = "DATE_FORMAT(" + value + ",'" + percent + "')";
            }
            case SQLSERVER -> {
                String normalized = field;
                if (text) {
                    // 日分组不需要小数秒；datetimeoffset(7) 会把 23:59:59.999999999 舍入到次日。
                    normalized = "CASE WHEN CHARINDEX('.'," + field + ")>0 THEN LEFT(" + field + ",CHARINDEX('.',"
                            + field + ")-1)+CASE WHEN RIGHT(" + field + ",1)='Z' THEN 'Z' WHEN RIGHT(" + field
                            + ",6) LIKE '[+-][0-9][0-9]:[0-9][0-9]' THEN RIGHT(" + field + ",6) ELSE '' END ELSE "
                            + field + " END";
                }
                value = "SWITCHOFFSET(TRY_CONVERT(datetimeoffset(7)," + normalized + ",127),'+00:00')";
                if (text) {
                    // TRY_CONVERT 负责日期有效性；形状检查拒绝依赖区域设置的日期文本。
                    valid = field + " LIKE '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]%' AND (LEN(" + field
                            + ")=10 OR SUBSTRING(" + field + ",11,1)='T')";
                }
                int length = switch (grouping) {
                    case DAY -> 10;
                    case MONTH -> 7;
                    case YEAR -> 4;
                };
                label = "LEFT(CONVERT(varchar(10)," + value + ",23)," + length + ")";
            }
            case ORACLE -> {
                if (text) {
                    String normalized = "CASE WHEN LENGTH(" + field + ")=10 THEN " + field
                            + "||'T00:00:00+00:00' ELSE REPLACE(" + field + ",'Z','+00:00')||CASE WHEN REGEXP_LIKE("
                            + field + ",'(Z|[+-][0-9]{2}:[0-9]{2})$') THEN '' ELSE '+00:00' END END";
                    value = "CASE WHEN INSTR(" + field + ",'.')>0 THEN TO_TIMESTAMP_TZ(" + normalized
                            + " DEFAULT NULL ON CONVERSION ERROR,'YYYY-MM-DD\"T\"HH24:MI:SS.FFTZH:TZM') ELSE TO_TIMESTAMP_TZ("
                            + normalized
                            + " DEFAULT NULL ON CONVERSION ERROR,'YYYY-MM-DD\"T\"HH24:MI:SSTZH:TZM') END";
                    valid = "REGEXP_LIKE(" + field + ",'" + ISO + "')";
                } else {
                    value = "CAST(" + field + " AS TIMESTAMP WITH TIME ZONE)";
                }
                label = "TO_CHAR(((" + value + ") AT TIME ZONE 'UTC'),'" + format + "','NLS_DATE_LANGUAGE=English')";
            }
            case CLICKHOUSE -> {
                if (text) {
                    value = "parseDateTime64BestEffortOrNull(" + field + ",9,'UTC')";
                    valid = "match(" + field + ",'" + ISO
                            + "') AND formatDateTime(parseDateTime64BestEffortOrNull(substring(" + field
                            + ",1,19),9,'UTC'),'%Y-%m-%d','UTC')=substring(" + field + ",1,10)";
                }
                label = "formatDateTime(" + value + ",'" + percent + "','UTC')";
            }
            default -> throw new IllegalArgumentException();
        }
        if (!text) {
            return label;
        }
        // 非法分组排在最前，即使 Top N 也不会隐藏坏日期；仅对 WHERE 后的实际输入检查。
        return "CASE WHEN " + field + " IS NULL THEN NULL WHEN " + valid + " AND " + value + " IS NOT NULL THEN "
                + label + " ELSE '" + INVALID + "' END";
    }
}
