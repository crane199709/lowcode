package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.sdk.core.api.ForgeException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.TokenMgrException;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

/** 按语法节点检查写操作，避免把 AS update 这样的 Field 名误当成写语句。 */
final class SqlQueryGuard {
    private SqlQueryGuard() {}

    static void check(String sql, DataSourceType type) {
        try {
            var statements = CCJSqlParserUtil.parseStatements(
                    sql, parser -> parser.withSquareBracketQuotation(type == DataSourceType.SQLSERVER));
            if (statements.size() != 1 || !(statements.get(0) instanceof Select select)) {
                throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
            }
            var first = CCJSqlParserUtil.newParser(sql).getNextToken();
            if (!first.image.equalsIgnoreCase("SELECT") && !first.image.equalsIgnoreCase("WITH")) {
                throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
            }
            // DeParser 的查询/表达式访问器会共同遍历 CTE 与子查询；输出不用于执行，保留用户原 SQL 的方言写法。
            var visitor = new ReadOnlySelect();
            var deparser = new StatementDeParser(new ExpressionDeParser(), visitor, new StringBuilder());
            if (select.getWithItemsList() != null) {
                for (var item : select.getWithItemsList()) {
                    visitor.visit(item, null);
                }
            }
            deparser.visit(select, null);
        } catch (JSQLParserException | TokenMgrException e) {
            throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
        }
    }

    private static final class ReadOnlySelect extends SelectDeParser {
        @Override
        public <S> StringBuilder visit(WithItem<?> item, S context) {
            if (!(item.getParenthesedStatement() instanceof ParenthesedSelect)) {
                throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
            }
            return super.visit(item, context);
        }

        @Override
        public <S> StringBuilder visit(PlainSelect select, S context) {
            if ((select.getIntoTables() != null && !select.getIntoTables().isEmpty())
                    || select.getIntoTempTable() != null) {
                throw new ForgeException(DatasetErrorCode.SQL_NOT_ALLOWED);
            }
            return super.visit(select, context);
        }
    }
}
