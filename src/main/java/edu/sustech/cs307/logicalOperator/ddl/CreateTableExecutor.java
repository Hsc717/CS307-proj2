package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import org.pmw.tinylog.Logger;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CreateTableExecutor implements DMLExecutor {

    private static final Pattern VARCHAR_LEN_PATTERN = Pattern.compile("(?i)varchar\\s*\\(\\s*(\\d+)\\s*\\)");

    private final CreateTable createTableStmt;
    private final DBManager dbManager;
    private final String sql;

    public CreateTableExecutor(CreateTable createTable, DBManager dbManager, String sql) {
        this.createTableStmt = createTable;
        this.dbManager = dbManager;
        this.sql = sql;
    }

    @Override
    public void execute() throws DBException {
        String table = createTableStmt.getTable().getName();
        ArrayList<ColumnMeta> colMapping = new ArrayList<>();
        int offset = 0;
        if (null == createTableStmt.getColumnDefinitions()) {
            throw new DBException(ExceptionTypes.TableHasNoColumn(table));
        }
        for (var col : createTableStmt.getColumnDefinitions()) {
            String colName = col.getColumnName();
            if (colName.isEmpty() || colName.length() > 10) {
                throw new DBException(
                        ExceptionTypes.InvalidSQL(sql, String.format("INVALID COLUMN NAME = %s", colName)));
            }
            ColDataType colType = col.getColDataType();
            String dataType = colType.getDataType();
            String dataTypeLower = dataType.toLowerCase().trim();

            Logger.info("Parsing column '{}' with data type: '{}'", colName, dataType);

            if (dataTypeLower.equals("char")) {
                colMapping.add(new ColumnMeta(table, colName, ValueType.CHAR, Value.CHAR_SIZE, offset));
                offset += Value.CHAR_SIZE;
            } else if (dataTypeLower.startsWith("varchar")) {
                // Support VARCHAR(N) syntax
                // JSQLParser may return "VARCHAR (32)" as the full dataType string
                // or just "VARCHAR" with arguments in getArgumentsStringList()
                int varcharLen = Value.VARCHAR_DEFAULT_SIZE;

                // First try to parse from getArgumentsStringList()
                var args = colType.getArgumentsStringList();
                if (args != null && !args.isEmpty()) {
                    try {
                        varcharLen = Integer.parseInt(args.get(0).trim());
                    } catch (NumberFormatException e) {
                        // Fall through to regex parsing
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
                    throw new DBException(ExceptionTypes.InvalidSQL(sql,
                            String.format("VARCHAR length must be between 1 and 1024, got %d", varcharLen)));
                }
                colMapping.add(new ColumnMeta(table, colName, ValueType.VARCHAR, varcharLen, offset));
                offset += varcharLen;
                Logger.info("Column {} defined as VARCHAR({})", colName, varcharLen);
            } else if (dataTypeLower.equals("int") || dataTypeLower.equals("integer")) {
                colMapping.add(new ColumnMeta(table, colName, ValueType.INTEGER, Value.INT_SIZE, offset));
                offset += Value.INT_SIZE;
            } else if (dataTypeLower.equals("float")) {
                colMapping.add(new ColumnMeta(table, colName, ValueType.FLOAT, Value.FLOAT_SIZE, offset));
                offset += Value.FLOAT_SIZE;
            } else if (dataTypeLower.startsWith("double")) {
                colMapping.add(new ColumnMeta(table, colName, ValueType.DOUBLE, Value.DOUBLE_SIZE, offset));
                offset += Value.DOUBLE_SIZE;
                Logger.info("Column {} defined as DOUBLE (double precision)", colName);
            } else {
                Logger.error("Unrecognized data type '{}' for column '{}'", dataType, colName);
                throw new DBException(ExceptionTypes.UnsupportedCommand(
                        String.format("CREATE TABLE %s: unsupported type '%s' for column '%s'", table, dataType, colName)));
            }
        }
        dbManager.createTable(table, colMapping);
        Logger.info("Successfully created table: {}", table);
    }
}