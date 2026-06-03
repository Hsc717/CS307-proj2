package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import org.pmw.tinylog.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AlterTableExecutor implements DMLExecutor {

    private static final Pattern VARCHAR_LEN_PATTERN = Pattern.compile("(?i)varchar\\s*\\(\\s*(\\d+)\\s*\\)");

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
        String dataTypeLower = dataType.toLowerCase().trim();

        Logger.info("ALTER TABLE ADD: parsing column '{}' with data type: '{}'", columnName, dataType);

        // Determine the total existing record size
        var tableMeta = dbManager.getMetaManager().getTable(tableName);
        int totalLen = 0;
        for (ColumnMeta col : tableMeta.columns_list) {
            totalLen += col.len;
        }

        ValueType valueType;
        int colLen;
        if (dataTypeLower.equals("int") || dataTypeLower.equals("integer")) {
            valueType = ValueType.INTEGER;
            colLen = Value.INT_SIZE;
        } else if (dataTypeLower.equals("char")) {
            valueType = ValueType.CHAR;
            colLen = Value.CHAR_SIZE;
        } else if (dataTypeLower.startsWith("varchar")) {
            // Support VARCHAR(N) syntax
            // JSQLParser may return "VARCHAR (20)" as the full dataType string
            int varcharLen = Value.VARCHAR_DEFAULT_SIZE;

            // First try getArgumentsStringList()
            var args = colDataType.getArgumentsStringList();
            if (args != null && !args.isEmpty()) {
                try {
                    varcharLen = Integer.parseInt(args.get(0).trim());
                } catch (NumberFormatException e) {
                    // Fall through to regex
                }
            }

            // If still default, try regex on the full dataType string
            if (varcharLen == Value.VARCHAR_DEFAULT_SIZE) {
                Matcher m = VARCHAR_LEN_PATTERN.matcher(dataType);
                if (m.find()) {
                    try {
                        varcharLen = Integer.parseInt(m.group(1));
                    } catch (NumberFormatException e) {
                        // Keep default
                    }
                }
            }

            if (varcharLen <= 0 || varcharLen > 1024) {
                throw new DBException(ExceptionTypes.InvalidSQL("ALTER TABLE ADD",
                        String.format("VARCHAR length must be between 1 and 1024, got %d", varcharLen)));
            }
            valueType = ValueType.VARCHAR;
            colLen = varcharLen;
            Logger.info("Adding VARCHAR({}) column {} to table {}", varcharLen, columnName, tableName);
        } else if (dataTypeLower.equals("float")) {
            valueType = ValueType.FLOAT;
            colLen = Value.FLOAT_SIZE;
        } else if (dataTypeLower.startsWith("double")) {
            valueType = ValueType.DOUBLE;
            colLen = Value.DOUBLE_SIZE;
            Logger.info("Adding DOUBLE column {} to table {}", columnName, tableName);
        } else {
            Logger.error("ALTER TABLE ADD: unrecognized data type '{}' for column '{}'", dataType, columnName);
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