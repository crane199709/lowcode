package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.TokenMgrException;
import net.sf.jsqlparser.statement.select.Select;

/** 语法树确认单条查询，词法检查排除 WITH 内写语句与 SELECT INTO；字符串和注释不参与关键字检查。 */
final class SqlQueryGuard {
    private static final Set<String> WRITES = Set.of(
            "INSERT",
            "UPDATE",
            "DELETE",
            "MERGE",
            "CREATE",
            "ALTER",
            "DROP",
            "TRUNCATE",
            "INTO",
            "COPY",
            "CALL",
            "EXEC",
            "EXECUTE",
            "GRANT",
            "REVOKE",
            "OUTFILE",
            "DUMPFILE",
            "LOCK");

    private SqlQueryGuard() {}

    static void check(String sql, DataSourceType type) {
        try {
            var statements = CCJSqlParserUtil.parseStatements(
                    sql, parser -> parser.withSquareBracketQuotation(type == DataSourceType.SQLSERVER));
            if (statements.size() != 1 || !(statements.get(0) instanceof Select)) {
                throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
            }
            var parser = CCJSqlParserUtil.newParser(sql).withSquareBracketQuotation(type == DataSourceType.SQLSERVER);
            var first = parser.getNextToken();
            if (!first.image.equalsIgnoreCase("SELECT") && !first.image.equalsIgnoreCase("WITH")) {
                throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
            }
            for (var token = first; token.kind != 0; token = parser.getNextToken()) {
                if (WRITES.contains(token.image.toUpperCase(Locale.ROOT))) {
                    throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
                }
            }
        } catch (JSQLParserException | TokenMgrException e) {
            throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
        }
    }
}
