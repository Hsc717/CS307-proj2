package edu.sustech.cs307;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.Page;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.system.TransactionManager;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;

import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.TerminalBuilder;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class DBEntry {
    public static final String DB_NAME = "CS307-DB";
    // for now, we use 256 * 512 * 4096 bytes = 512MB as the pool size
    public static final int POOL_SIZE = 256 * 512;

    public static void printHelp() {
        Logger.info("============ CS307-DB 使用帮助 ============");
        Logger.info("  exit / exit;              退出程序");
        Logger.info("  help / help;              显示本帮助信息");
        Logger.info("");
        Logger.info("-- DDL (数据定义语言) --");
        Logger.info("  CREATE TABLE t (col type, ...)   创建表");
        Logger.info("    支持类型: INT, CHAR, VARCHAR(N), FLOAT, DOUBLE");
        Logger.info("    示例: CREATE TABLE t (id INT, name VARCHAR(32), gpa DOUBLE)");
        Logger.info("  DROP TABLE t                      删除表");
        Logger.info("  SHOW TABLES                       显示所有表");
        Logger.info("  DESCRIBE t                        查看表结构");
        Logger.info("  ALTER TABLE t ADD col type        添加列");
        Logger.info("    示例: ALTER TABLE t ADD age INT");
        Logger.info("    示例: ALTER TABLE t ADD email VARCHAR(64)");
        Logger.info("  ALTER TABLE t DROP col            删除列");
        Logger.info("");
        Logger.info("-- DML (数据操作语言) --");
        Logger.info("  INSERT INTO t (cols) VALUES (...)  插入数据 (支持多行插入)");
        Logger.info("    示例: INSERT INTO t VALUES (1,'a',3.5), (2,'b',3.6)");
        Logger.info("  UPDATE t SET col=v WHERE ...       更新数据");
        Logger.info("  DELETE FROM t WHERE ...            删除数据");
        Logger.info("  SELECT ... FROM t WHERE ...        查询数据");
        Logger.info("");
        Logger.info("-- 支持的查询功能 --");
        Logger.info("  投影: SELECT col1, col2 FROM t");
        Logger.info("  条件: WHERE col = / > / >= / < / <= / <> / != value");
        Logger.info("  逻辑: AND / OR / NOT");
        Logger.info("  范围: IN (v1,v2,...) / NOT IN (v1,v2,...)");
        Logger.info("  存在: EXISTS (subquery) / NOT EXISTS (subquery)");
        Logger.info("  聚合: COUNT(*) / MAX(col) / MIN(col)");
        Logger.info("  分组: GROUP BY col");
        Logger.info("  排序: ORDER BY col [ASC|DESC]");
        Logger.info("  连接: t1 JOIN t2 ON t1.col = t2.col");
        Logger.info("  计划: EXPLAIN SELECT ...");
        Logger.info("");
        Logger.info("-- 索引 (B+树) --");
        Logger.info("  CREATE INDEX name ON t(col)  创建B+树索引");
        Logger.info("  DROP INDEX name              删除索引");
        Logger.info("  索引加速等值查询和范围查询");
        Logger.info("");
        Logger.info("-- 事务 --");
        Logger.info("  BEGIN / START TRANSACTION    开始事务 (创建快照)");
        Logger.info("  COMMIT                       提交事务 (持久化变更)");
        Logger.info("  ROLLBACK                     回滚事务 (恢复到BEGIN时状态)");
        Logger.info("  SAVEPOINT name               设置保存点");
        Logger.info("  ROLLBACK TO SAVEPOINT name   回滚到指定保存点");
        Logger.info("  RELEASE SAVEPOINT name       释放保存点");
        Logger.info("============================================");
    }

    public static void main(String[] args) throws DBException {
        Logger.getConfiguration().formatPattern("{date: HH:mm:ss.SSS} {level}: {message}").activate();

        Logger.info("Hello, This is CS307-DB!");
        Logger.info("Initializing...");
        DBManager dbManager = null;
        try {
            Map<String, Integer> disk_manager_meta = new HashMap<>(DiskManager.read_disk_manager_meta());
            DiskManager diskManager = new DiskManager(DB_NAME, disk_manager_meta);
            BufferPool bufferPool = new BufferPool(POOL_SIZE, diskManager);
            RecordManager recordManager = new RecordManager(diskManager, bufferPool);
            MetaManager metaManager = new MetaManager(DB_NAME + "/meta");
            dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager);

            // Recovery: check all tables in metadata, recreate data files if missing
            for (String tableName : metaManager.getTableNames()) {
                String dataFile = tableName + "/data";
                String realPath = DB_NAME + "/" + dataFile;
                if (!new File(realPath).exists()) {
                    Logger.info("Recovering missing data file for table: {}", tableName);
                    int recordSize = 0;
                    TableMeta tableMeta = metaManager.getTable(tableName);
                    for (ColumnMeta col : tableMeta.columns_list) {
                        recordSize += col.len;
                    }
                    // Ensure directory exists
                    new File(DB_NAME + "/" + tableName).mkdirs();
                    // Create the data file
                    recordManager.CreateFile(dataFile, recordSize);
                    Logger.info("Recovered data file for table: {}", tableName);
                }
            }
        } catch (DBException e) {
            Logger.error(e.getMessage());
            Logger.error("An error occurred during initializing. Exiting....");
            return;
        }

        String sql = "";
        boolean running = true;
        try {
            while (running) {
                try {
                    LineReader scanner = LineReaderBuilder.builder()
                            .terminal(
                                    TerminalBuilder
                                            .builder()
                                            .dumb(true)
                                            .build()
                            )
                            .build();
                    Logger.info("CS307-DB> ");
                    sql = scanner.readLine();
                    String trimmedSql = sql.trim();
                    // Remove trailing semicolons for command matching
                    while (trimmedSql.endsWith(";")) {
                        trimmedSql = trimmedSql.substring(0, trimmedSql.length() - 1).trim();
                    }
                    if (trimmedSql.equalsIgnoreCase("exit")) {
                        running = false;
                        continue;
                    } else if (trimmedSql.equalsIgnoreCase("help")) {
                        printHelp();
                        continue;
                    }
                    // Use original sql (with semicolon stripped by LogicalPlanner internally)
                } catch (Exception e) {
                    Logger.error(e.getMessage());
                    Logger.error("An error occurred. Exiting....");
                }
                try {
                    LogicalOperator operator = LogicalPlanner.resolveAndPlan(dbManager, sql);
                    if (operator == null) {
                        continue;
                    }
                    PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, operator);
                    if (physicalOperator == null) {
                        Logger.info(operator);
                        continue;
                    }
                    Logger.info(getStartEndLine(physicalOperator.outputSchema().size(), true));
                    Logger.info(getHeaderString(physicalOperator.outputSchema()));
                    Logger.info(getSperator(physicalOperator.outputSchema().size()));
                    physicalOperator.Begin();
                    while (physicalOperator.hasNext()) {
                        physicalOperator.Next();
                        Tuple tuple = physicalOperator.Current();
                        Logger.info(getRecordString(tuple));
                        Logger.info(getSperator(physicalOperator.outputSchema().size()));
                    }
                    physicalOperator.Close();
                    dbManager.getBufferPool().FlushAllPages("");
                } catch (DBException e) {
                    Logger.error(e.getMessage());
                    Logger.error("An error occurred. Please try again.");
                    Logger.error(Arrays.toString(e.getStackTrace()));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // persist runtime state before exit
            try {
                dbManager.persistRuntimeState();
            } catch (DBException ex) {
                Logger.error("Failed to persist state on error: " + ex.getMessage());
            }
            Logger.error("Some error occurred. Exiting after persistdata...");
        }
    }

    private static String getHeaderString(ArrayList<ColumnMeta> columnMetas) {
        StringBuilder header = new StringBuilder("|");
        for (var entry : columnMetas) {
            String tabcol = String.format("%s.%s", entry.tableName, entry.name);
            String centeredText = StringUtils.center(tabcol, 15, ' ');
            header.append(centeredText).append("|");
        }
        return header.toString();
    }

    private static String getRecordString(Tuple tuple) throws DBException {
        StringBuilder tuple_string = new StringBuilder("|");
        for (var entry : tuple.getValues()) {
            String tabCol = String.format("%s", entry);
            String centeredText = StringUtils.center(tabCol, 15, ' ');
            tuple_string.append(centeredText).append("|");
        }
        return tuple_string.toString();
    }

    private static String getSperator(int width) {
        // ───────────────
        StringBuilder line = new StringBuilder("+");
        for (int i = 0; i < width; i++) {
            line.append("───────────────");
            line.append("+");
        }
        return line.toString();
    }

    private static String getStartEndLine(int width, boolean header) {
        StringBuilder end_line;
        if (header) {
            end_line = new StringBuilder("┌");
        } else {
            end_line = new StringBuilder("└");
        }
        for (int i = 0; i < width; i++) {
            end_line.append("───────────────");
            if (header) {
                end_line.append("┐");
            } else {
                end_line.append("┘");
            }
        }
        return end_line.toString();
    }
}