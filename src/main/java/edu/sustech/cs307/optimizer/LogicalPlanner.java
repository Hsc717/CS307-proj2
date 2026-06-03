package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.create.table.CreateTable;

import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.logicalOperator.ddl.AlterTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.InMemoryOrderedIndex;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.record.RecordPageHandle;
import edu.sustech.cs307.record.BitMap;
import edu.sustech.cs307.storage.Page;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class LogicalPlanner {
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?i)^ROLLBACK(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ROLLBACK_TO_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^ROLLBACK(?:\\s+(?:WORK|TRANSACTION))?\\s+TO(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("(?i)^SHOW\\s+TABLES$");
    private static final Pattern SHOW_INDEX_PATTERN = Pattern.compile("(?i)^SHOW\\s+INDEX\\s+(\\S+)$");

    public static LogicalOperator resolveAndPlan(DBManager dbManager, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        if (handleManualTransactionCommand(dbManager, sql)) {
            return null;
        }
        JSqlParser parser = new CCJSqlParserManager();
        Statement stmt = null;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }
        LogicalOperator operator = null;
        // Query
        if (stmt instanceof Select selectStmt) {
            operator = handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            operator = handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            operator = handleUpdate(dbManager, updateStmt);
        }else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        }
        else if (stmt instanceof Delete deleteStmt) {
            operator = handleDelete(dbManager, deleteStmt);
        }
        // functional
        else if (stmt instanceof CreateTable createTableStmt) {
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            ExplainExecutor explainExecutor = new ExplainExecutor(explainStatement, dbManager);
            explainExecutor.execute();
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(showStatement, dbManager);
            showDatabaseExecutor.execute();
            return null;
        } else if (stmt instanceof DescribeStatement describeStatement) {
            dbManager.descTable(describeStatement.getTable().getName());
            return null;
        } else if (stmt instanceof Drop dropStatement) {
            if (dropStatement.getType().equalsIgnoreCase("INDEX")) {
                String indexName = dropStatement.getName().getName();
                boolean found = false;
                for (String tableName : dbManager.getMetaManager().getTableNames()) {
                    var tableMeta = dbManager.getMetaManager().getTable(tableName);
                    if (tableMeta.getIndexes().containsKey(indexName)) {
                        tableMeta.getIndexes().remove(indexName);
                        dbManager.getMetaManager().saveToJson();
                        org.pmw.tinylog.Logger.info("Successfully dropped index: {}", indexName);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new DBException(ExceptionTypes.UnsupportedCommand("DROP INDEX " + indexName));
                }
                return null;
            } else if (dropStatement.getType().equalsIgnoreCase("TABLE")) {
                dbManager.dropTable(dropStatement.getName().getName());
                return null;
            }
            throw new DBException(ExceptionTypes.UnsupportedCommand(dropStatement.toString()));
        } else if (stmt instanceof Alter alterStmt) {
            AlterTableExecutor alterExecutor = new AlterTableExecutor(alterStmt, dbManager);
            alterExecutor.execute();
            return null;
        } else if (stmt instanceof net.sf.jsqlparser.statement.create.index.CreateIndex createIndexStmt) {
            handleCreateIndex(dbManager, createIndexStmt);
            return null;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
        }
        return operator;
    }

    /**
     * Handle CREATE INDEX: persist metadata AND populate the index with existing table data.
     */
    private static void handleCreateIndex(DBManager dbManager,
                                           net.sf.jsqlparser.statement.create.index.CreateIndex createIndexStmt)
            throws DBException {
        String tableName = createIndexStmt.getTable().getName();
        String indexName = createIndexStmt.getIndex().getName();
        var indexColumns = createIndexStmt.getIndex().getColumnsNames();

        // Determine which column is being indexed
        String indexedColumn = (indexColumns != null && !indexColumns.isEmpty())
                ? indexColumns.get(0).toString()
                : null;

        // Add index metadata
        dbManager.getMetaManager().getTable(tableName).getIndexes().put(indexName,
                edu.sustech.cs307.meta.TableMeta.IndexType.BTREE);
        dbManager.getMetaManager().saveToJson();

        // Populate the index with existing data and cache it in memory
        InMemoryOrderedIndex index = new InMemoryOrderedIndex();
        if (indexedColumn != null && !indexedColumn.isBlank()) {
            scanTableForIndex(dbManager, tableName, indexedColumn, index);
        }

        // Cache the index in memory for query planning
        PhysicalPlanner.cacheIndex(indexName, index);
        org.pmw.tinylog.Logger.info("Successfully created index: {} on table {} with {} unique keys",
                indexName, tableName, index.size());
    }

    /**
     * Scan all records in a table, extract the value of the indexed column,
     * and populate a TreeMap<Value, RID> for the index.
     */
    private static void scanTableForIndex(DBManager dbManager, String tableName,
                                           String indexedColumn, InMemoryOrderedIndex index)
            throws DBException {
        TableMeta tableMeta = dbManager.getMetaManager().getTable(tableName);
        ColumnMeta indexedColMeta = tableMeta.getColumnMeta(indexedColumn);
        if (indexedColMeta == null) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(indexedColumn));
        }

        // OpenFile internally appends "/data", so just pass the table name
        RecordFileHandle fileHandle = dbManager.getRecordManager().OpenFile(tableName);

        try {
            int totalPages = fileHandle.getFileHeader().getNumberOfPages();
            int recordsPerPage = fileHandle.getFileHeader().getNumberOfRecordsPrePage();

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                RecordPageHandle pageHandle = fileHandle.FetchPageHandle(pageNum);
                for (int slotNum = 0; slotNum < recordsPerPage; slotNum++) {
                    if (BitMap.isSet(pageHandle.bitmap, slotNum)) {
                        RID rid = new RID(pageNum, slotNum);
                        Record record = fileHandle.GetRecord(rid);
                        ByteBuf columnValueBuf = record.GetColumnValue(
                                indexedColMeta.getOffset(), indexedColMeta.getLen());
                        Value columnValue = convertByteBufToValue(columnValueBuf, indexedColMeta.type);
                        if (columnValue != null) {
                            index.insert(columnValue, rid);
                        }
                    }
                }
                fileHandle.UnpinPageHandle(pageNum, false);
            }
        } finally {
            dbManager.getRecordManager().CloseFile(fileHandle);
        }
    }

    private static Value convertByteBufToValue(ByteBuf byteBuf, ValueType columnType) throws DBException {
        if (columnType == ValueType.INTEGER) {
            return new Value(byteBuf.getLong(0));
        } else if (columnType == ValueType.CHAR) {
            byte[] bytes = new byte[Value.CHAR_SIZE];
            byteBuf.getBytes(0, bytes);
            return Value.FromByte(bytes, ValueType.CHAR);
        } else if (columnType == ValueType.VARCHAR) {
            byte[] bytes = new byte[byteBuf.readableBytes()];
            byteBuf.getBytes(0, bytes);
            return Value.FromByte(bytes, ValueType.VARCHAR);
        } else if (columnType == ValueType.FLOAT) {
            return new Value(byteBuf.getDouble(0), ValueType.FLOAT);
        } else if (columnType == ValueType.DOUBLE) {
            return new Value(byteBuf.getDouble(0), ValueType.DOUBLE);
        } else {
            throw new DBException(ExceptionTypes.UnsupportedValueType(columnType));
        }
    }


    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
        }
        LogicalOperator root = new LogicalTableScanOperator(tableNameOf(plainSelect.getFromItem()), dbManager);

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(tableNameOf(join.getRightItem()), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        // 在 Join 之后应用 Filter，Filter 的输入是 Join 的结果 (root)
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        root = new LogicalProjectOperator(root, plainSelect.getSelectItems(),
                plainSelect.getGroupBy(), plainSelect.getOrderByElements());
        return root;
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
    }

    private static LogicalOperator handleDelete(DBManager dbManager, Delete deleteStmt) throws DBException {
        String tableName = deleteStmt.getTable().getName();
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);
        return new LogicalDeleteOperator(root, tableName, deleteStmt.getWhere());
    }

    private static String tableNameOf(FromItem item) throws DBException {
        if (item instanceof Table table) {
            return table.getName();
        }
        throw new DBException(ExceptionTypes.UnsupportedCommand(item.toString()));
    }
    private static String normalizeSql(String sql) {
        String normalizedSql = sql == null ? "" : sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }

    private static boolean handleManualTransactionCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (SHOW_TABLES_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.showTables();
            return true;
        }

        if (BEGIN_PATTERN.matcher(normalizedSql).matches() || START_TRANSACTION_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.beginTransaction();
            return true;
        }

        // Check ROLLBACK (not to savepoint)
        Matcher rollbackMatcher = ROLLBACK_PATTERN.matcher(normalizedSql);
        if (rollbackMatcher.matches()) {
            dbManager.getTransactionManager().rollback();
            return true;
        }

        // Check ROLLBACK TO SAVEPOINT
        Matcher rollbackToSpMatcher = ROLLBACK_TO_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (rollbackToSpMatcher.matches()) {
            String savepointName = rollbackToSpMatcher.group(1);
            dbManager.getTransactionManager().rollbackToSavepoint(savepointName);
            return true;
        }

        // Check SAVEPOINT
        Matcher savepointMatcher = SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (savepointMatcher.matches()) {
            String savepointName = savepointMatcher.group(1);
            dbManager.getTransactionManager().savepoint(savepointName);
            return true;
        }

        // Check RELEASE SAVEPOINT
        Matcher releaseSpMatcher = RELEASE_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (releaseSpMatcher.matches()) {
            String savepointName = releaseSpMatcher.group(1);
            dbManager.getTransactionManager().releaseSavepoint(savepointName);
            return true;
        }

        // Check SHOW INDEX <indexName>
        Matcher showIndexMatcher = SHOW_INDEX_PATTERN.matcher(normalizedSql);
        if (showIndexMatcher.matches()) {
            String indexName = showIndexMatcher.group(1);
            InMemoryOrderedIndex index = PhysicalPlanner.indexCache.get(indexName);
            if (index != null) {
                index.printTree();
            } else {
                org.pmw.tinylog.Logger.error("Index '{}' not found in cache. Create it first with CREATE INDEX.", indexName);
            }
            return true;
        }

        return false;
    }


}