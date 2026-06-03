package edu.sustech.cs307.optimizer;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.physicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.index.InMemoryOrderedIndex;
import edu.sustech.cs307.physicalOperator.IndexScanOperator;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.meta.TabCol;


import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.statement.select.Values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PhysicalPlanner {

    /**
     * In-memory cache for index data: indexName -> InMemoryOrderedIndex.
     * Built at CREATE INDEX time, used at query time.
     */
    static final Map<String, InMemoryOrderedIndex> indexCache = new HashMap<>();

    /**
     * Store a built index in the cache.
     */
    public static void cacheIndex(String indexName, InMemoryOrderedIndex index) {
        indexCache.put(indexName, index);
    }

    /**
     * Remove an index from the cache.
     */
    public static void removeIndex(String indexName) {
        indexCache.remove(indexName);
    }

    /**
     * Clear all cached indexes.
     */
    public static void clearIndexCache() {
        indexCache.clear();
    }

    public static PhysicalOperator generateOperator(DBManager dbManager, LogicalOperator logicalOp) throws DBException {
        if (logicalOp instanceof LogicalTableScanOperator tableScanOperator) {
            return handleTableScan(dbManager, tableScanOperator);
        } else if (logicalOp instanceof LogicalFilterOperator filterOperator) {
            return handleFilter(dbManager, filterOperator);
        } else if (logicalOp instanceof LogicalJoinOperator joinOperator) {
            return handleJoin(dbManager, joinOperator);
        } else if (logicalOp instanceof LogicalProjectOperator projectOperator) {
            return handleProject(dbManager, projectOperator);
        } else if (logicalOp instanceof LogicalInsertOperator insertOperator) {
            return handleInsert(dbManager, insertOperator);
        } else if (logicalOp instanceof LogicalUpdateOperator updateOperator) {
            return handleUpdate(dbManager, updateOperator);
        } else if (logicalOp instanceof LogicalDeleteOperator deleteOperator) {
            return handleDelete(dbManager, deleteOperator);
        }

        else {
            throw new DBException(ExceptionTypes.UnsupportedOperator(logicalOp.getClass().getSimpleName()));
        }
    }

    private static PhysicalOperator handleTableScan(DBManager dbManager, LogicalTableScanOperator logicalTableScanOp) {
        String tableName = logicalTableScanOp.getTableName();
        TableMeta tableMeta;
        try {
            tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            return new SeqScanOperator(tableName, dbManager);
        }

        // Check if index exists for the table AND is cached in memory
        if (tableMeta.getIndexes() != null && !tableMeta.getIndexes().isEmpty()) {
            String indexName = tableMeta.getIndexes().keySet().iterator().next();
            InMemoryOrderedIndex index = indexCache.get(indexName);

            if (index != null && index.size() > 0) {
                // Use the cached index for scan via IndexScanOperator
                java.util.Iterator<java.util.Map.Entry<Value, RID>> allEntries = index.Range(
                        new Value(Long.MIN_VALUE, ValueType.INTEGER),
                        new Value(Long.MAX_VALUE, ValueType.INTEGER),
                        true, true);
                return new IndexScanOperator(dbManager, tableName, index, allEntries);
            } else {
                // Index metadata exists but no cached data — fall back to SeqScan
                org.pmw.tinylog.Logger.info("Index {} is empty (not cached), falling back to SeqScan for table {}",
                        indexName, tableName);
                return new SeqScanOperator(tableName, dbManager);
            }
        } else {
            return new SeqScanOperator(tableName, dbManager);
        }
    }

    private static PhysicalOperator handleFilter(DBManager dbManager, LogicalFilterOperator logicalFilterOp)
            throws DBException {
        PhysicalOperator inputOp = generateOperator(dbManager, logicalFilterOp.getChild());
        return new FilterOperator(inputOp, logicalFilterOp.getWhereExpr());
    }

    private static PhysicalOperator handleJoin(DBManager dbManager, LogicalJoinOperator logicalJoinOp)
            throws DBException {
        PhysicalOperator leftOp = generateOperator(dbManager, logicalJoinOp.getLeftInput());
        PhysicalOperator rightOp = generateOperator(dbManager, logicalJoinOp.getRightInput());
        PhysicalOperator joinOp = new NestedLoopJoinOperator(leftOp, rightOp, logicalJoinOp.getJoinExprs());

        Collection<Expression> joinFilters = logicalJoinOp.getJoinExprs();
        if (joinFilters == null || joinFilters.isEmpty()) {
            return joinOp;
        }
        return new FilterOperator(joinOp, joinFilters);
    }

    private static PhysicalOperator handleProject(DBManager dbManager, LogicalProjectOperator logicalProjectOp)
            throws DBException {
        PhysicalOperator inputOp = generateOperator(dbManager, logicalProjectOp.getChild());
        return new ProjectOperator(inputOp, logicalProjectOp.getSelectItems(),
                logicalProjectOp.getGroupBy(), logicalProjectOp.getOrderByElements());
    }

    @SuppressWarnings("deprecation")
    private static PhysicalOperator handleInsert(DBManager dbManager, LogicalInsertOperator logicalInsertOp)
            throws DBException {
        var tableMeta = dbManager.getMetaManager().getTable(logicalInsertOp.tableName);
        List<String> columns = new ArrayList<>();
        if (logicalInsertOp.columns != null) {
            if (tableMeta.columns.size() != logicalInsertOp.columns.size()) {
                throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
            }
            for (int i = 0; i < logicalInsertOp.columns.size(); i++) {
                String colName = logicalInsertOp.columns.get(i).getColumnName();
                if (tableMeta.getColumnMeta(colName) == null) {
                    throw new DBException(ExceptionTypes.ColumnDoesNotExist(colName));
                }
                if (!tableMeta.columns_list.get(i).name.equals(colName)) {
                    throw new DBException(ExceptionTypes.InsertColumnNameMismatch());
                }
                columns.add(colName);
            }
        } else {
            for (ColumnMeta columnMeta : tableMeta.columns_list) {
                columns.add(columnMeta.name);
            }
        }
        if (!(logicalInsertOp.values instanceof Values)) {
            throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Values must be an expression list"));
        }
        ExpressionList<?> valuesList = ((Values) logicalInsertOp.values).getExpressions();
        if (columns.size() != valuesList.size()) {
            var element = valuesList.get(0);
            if (element instanceof ParenthesedExpressionList<?> parenthesed) {
                for (Expression expr : valuesList) {
                    if (expr instanceof ParenthesedExpressionList<?> expressionList) {
                        if (expressionList.getExpressions().size() != columns.size()) {
                            throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
                        }
                    } else {
                        throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
                    }
                }
            } else {
                throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
            }
        }

        List<Value> values = new ArrayList<>();
        parseValue(values, valuesList, tableMeta);

        return new InsertOperator(logicalInsertOp.tableName, columns,
                values, dbManager);
    }

    @SuppressWarnings("deprecation")
    private static void parseValue(List<Value> values, ExpressionList<?> valuesList, TableMeta tableMeta)
            throws DBException {
        for (int i = 0; i < valuesList.size(); i++) {
            var expr = valuesList.getExpressions().get(i);
            // 如果遇到的是子表达式列表 (即多行 INSERT 中的某一行),
            // 递归展开, 不要再把它当作单值去对应 tableMeta 的第 i 列.
            if (expr instanceof ParenthesedExpressionList<?> rowList) {
                parseValue(values, (ExpressionList<?>) rowList, tableMeta);
                continue;
            }
            ValueType expectedType = tableMeta.columns_list.get(i % tableMeta.columns_list.size()).type;
            if (expr instanceof StringValue string_value) {
                // Accept string values for CHAR and VARCHAR columns
                if (expectedType != ValueType.CHAR && expectedType != ValueType.VARCHAR) {
                    throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
                }
                String value_str = string_value.getValue();
                int maxLen = expectedType == ValueType.VARCHAR
                        ? tableMeta.columns_list.get(i).len
                        : Value.CHAR_SIZE;
                if (value_str.length() > maxLen) {
                    value_str = value_str.substring(0, maxLen);
                }
                Value v = new Value(value_str, expectedType);
                // Set columnLen so ToByte() serializes with the correct length
                if (expectedType == ValueType.VARCHAR) {
                    v.columnLen = maxLen;
                }
                values.add(v);
            } else if (expr instanceof DoubleValue double_value) {
                // Accept double values for FLOAT and DOUBLE columns
                if (expectedType != ValueType.FLOAT && expectedType != ValueType.DOUBLE) {
                    throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
                }
                values.add(new Value(double_value.getValue(), expectedType));
            } else if (expr instanceof LongValue long_value) {
                if (expectedType != ValueType.INTEGER) {
                    throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
                }
                values.add(new Value(long_value.getValue()));
            } else if (expr instanceof ParenthesedExpressionList<?> expressionList) {
                parseValue(values, expressionList, tableMeta);
            } else {
                throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Unsupported value type in VALUES clause"));
            }
        }
    }


    private static PhysicalOperator handleUpdate(DBManager dbManager, LogicalUpdateOperator logicalUpdateOp) throws DBException {
        PhysicalOperator scanner = generateOperator(dbManager, logicalUpdateOp.getChild());
        if (logicalUpdateOp.getColumns().size() != 1 ) {
            throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Unsupported expression list"));
        }
        return new UpdateOperator(scanner, logicalUpdateOp.getTableName(), logicalUpdateOp.getColumns().get(0), logicalUpdateOp.getExpression());
    }

    private static PhysicalOperator handleDelete(DBManager dbManager, LogicalDeleteOperator logicalDeleteOp) throws DBException {
        PhysicalOperator scanner = generateOperator(dbManager, logicalDeleteOp.getChild());
        return new DeleteOperator(scanner, logicalDeleteOp.getTableName(), logicalDeleteOp.getWhereExpr());
    }
}