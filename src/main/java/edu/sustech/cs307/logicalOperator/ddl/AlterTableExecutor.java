package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterExpression.ColumnDataType;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import org.pmw.tinylog.Logger;

import java.util.List;

public class AlterTableExecutor implements DMLExecutor {

    private final Alter alterStmt;
    private final DBManager dbManager;

    public AlterTableExecutor(Alter alterStmt, DBManager dbManager) {
        this.alterStmt = alterStmt;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
        String tableName = alterStmt.getTable().getName();
        if (!dbManager.isTableExists(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }

        var alterExpressions = alterStmt.getAlterExpressions();
        if (alterExpressions == null || alterExpressions.isEmpty()) {
            throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE with no expressions"));
        }

        for (AlterExpression expr : alterExpressions) {
            String operation = expr.getOperation().name();

            switch (operation.toUpperCase()) {
                case "ADD" -> handleAddColumn(tableName, expr);
                case "DROP" -> handleDropColumn(tableName, expr);
                default -> throw new DBException(
                        ExceptionTypes.UnsupportedCommand("ALTER TABLE " + operation));
            }
        }
    }

    private void handleAddColumn(String tableName, AlterExpression expr) throws DBException {
        java.util.List<net.sf.jsqlparser.statement.alter.AlterExpression.ColumnDataType> colDefList = expr.getColDataTypeList();
        if (colDefList == null || colDefList.isEmpty()) {
            throw new DBException(ExceptionTypes.InvalidSQL("ALTER TABLE ADD",
                    "Column data type not specified"));
        }

        net.sf.jsqlparser.statement.alter.AlterExpression.ColumnDataType colDef = colDefList.get(0);
        String columnName = colDef.getColumnName();
        ColDataType colDataType = colDef.getColDataType();
        String dataType = colDataType.getDataType();

        // Determine the total existing record size
        var tableMeta = dbManager.getMetaManager().getTable(tableName);
        int totalLen = 0;
        for (ColumnMeta col : tableMeta.columns_list) {
            totalLen += col.len;
        }

        ValueType valueType;
        int colLen;
        if (dataType.equalsIgnoreCase("int")) {
            valueType = ValueType.INTEGER;
            colLen = Value.INT_SIZE;
        } else if (dataType.equalsIgnoreCase("char") || dataType.equalsIgnoreCase("varchar")) {
            valueType = ValueType.CHAR;
            colLen = Value.CHAR_SIZE;
        } else if (dataType.equalsIgnoreCase("float") || dataType.equalsIgnoreCase("double")) {
            valueType = ValueType.FLOAT;
            colLen = Value.FLOAT_SIZE;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand(
                    "ALTER TABLE ADD unsupported data type: " + dataType));
        }

        ColumnMeta newColumn = new ColumnMeta(tableName, columnName, valueType, colLen, totalLen);
        dbManager.getMetaManager().addColumnInTable(tableName, newColumn);
        Logger.info("Successfully added column {} to table {}", columnName, tableName);
    }

    private void handleDropColumn(String tableName, AlterExpression expr) throws DBException {
        String columnName = expr.getColumnName();
        dbManager.getMetaManager().dropColumnInTable(tableName, columnName);
        Logger.info("Successfully dropped column {} from table {}", columnName, tableName);
    }
}