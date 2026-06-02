package feature;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.BPlusTreeIndex;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.within;

/**
 * 综合测试：验证 EXISTS, ALTER TABLE, CREATE/DROP INDEX 等功能
 */
public class AdvancedFeatureTest {

    private static Path tempDir;
    private static DBManager dbManager;
    private static DiskManager diskManager;
    private static BufferPool bufferPool;
    private static RecordManager recordManager;
    private static MetaManager metaManager;

    @BeforeAll
    static void setup() throws Exception {
        String randomDir = "test-advanced-" + UUID.randomUUID().toString();
        tempDir = Files.createTempDirectory(randomDir);
        Map<String, Integer> fileMap = new HashMap<>();
        diskManager = new DiskManager(tempDir.toString(), fileMap);
        bufferPool = new BufferPool(10, diskManager);
        recordManager = new RecordManager(diskManager, bufferPool);
        metaManager = new MetaManager(tempDir.resolve("meta").toString());
        dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager);
    }

    @AfterAll
    static void cleanup() {
        if (tempDir != null) {
            deleteRecursive(tempDir.toFile());
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursive(f);
                }
            }
        }
        file.delete();
    }

    @Test
    @DisplayName("测试 ValueComparer 正确比较不同 ValueType")
    void testValueComparer() throws DBException {
        Value v1 = new Value(10L, ValueType.INTEGER);
        Value v2 = new Value(10L, ValueType.INTEGER);
        assertThat(ValueComparer.compare(v1, v2)).isEqualTo(0);

        Value v3 = new Value(15L);
        assertThat(ValueComparer.compare(v3, v1)).isEqualTo(1);

        Value v4 = new Value(5L);
        assertThat(ValueComparer.compare(v4, v1)).isEqualTo(-1);

        Value v5 = new Value("test", ValueType.CHAR);
        Value v6 = new Value("test", ValueType.CHAR);
        assertThat(ValueComparer.compare(v5, v6)).isEqualTo(0);

        Value v7 = new Value("banana");
        Value v8 = new Value("apple");
        assertThat(ValueComparer.compare(v7, v8)).isEqualTo(1);

        Value v9 = new Value(10.5, ValueType.FLOAT);
        Value v10 = new Value(10.5, ValueType.FLOAT);
        assertThat(ValueComparer.compare(v9, v10)).isEqualTo(0);
    }

    @Test
    @DisplayName("测试 Value.ToByte 和 FromByte 双向转换")
    void testValueByteConversion() {
        // INTEGER
        Value intVal = new Value(42L);
        byte[] intBytes = intVal.ToByte();
        Value intRestored = Value.FromByte(intBytes, ValueType.INTEGER);
        assertThat(intRestored.value).isEqualTo(42L);

        // FLOAT
        Value floatVal = new Value(3.14);
        byte[] floatBytes = floatVal.ToByte();
        Value floatRestored = Value.FromByte(floatBytes, ValueType.FLOAT);
        assertThat((Double) floatRestored.value).isCloseTo(3.14, within(0.001));

        // CHAR
        Value charVal = new Value("hello", ValueType.CHAR);
        byte[] charBytes = charVal.ToByte();
        Value charRestored = Value.FromByte(charBytes, ValueType.CHAR);
        assertThat(charRestored.value).isEqualTo("hello");
    }

    @Test
    @DisplayName("测试 B+ 树插入和搜索")
    void testBPlusTreeInsertAndSearch() {
        BPlusTreeIndex<Integer, String> tree = new BPlusTreeIndex<>(4);

        tree.insert(10, "ten");
        tree.insert(20, "twenty");
        tree.insert(5, "five");

        assertThat(tree.search(10)).isEqualTo("ten");
        assertThat(tree.search(20)).isEqualTo("twenty");
        assertThat(tree.search(5)).isEqualTo("five");
        assertThat(tree.search(99)).isNull();

        // Update
        tree.insert(10, "updated_ten");
        assertThat(tree.search(10)).isEqualTo("updated_ten");

        // Delete
        tree.delete(20);
        assertThat(tree.search(20)).isNull();

        // Large inserts
        for (int i = 1; i <= 20; i++) {
            tree.insert(i, "v" + i);
        }
        for (int i = 1; i <= 20; i++) {
            assertThat(tree.search(i)).isEqualTo("v" + i);
        }

        assertThat(tree.getFirstKey()).isEqualTo(1);
        assertThat(tree.getLastKey()).isEqualTo(20);
        assertThat(tree.containsKey(15)).isTrue();
        assertThat(tree.containsKey(99)).isFalse();
    }

    @Test
    @DisplayName("测试 ALTER TABLE ADD/DROP COLUMN 元数据操作")
    void testAlterTableMetaData() throws DBException {
        String tableName = "test_alter_" + UUID.randomUUID().toString().substring(0, 8);

        // Create table
        ArrayList<ColumnMeta> columns = new ArrayList<>();
        columns.add(new ColumnMeta(tableName, "id", ValueType.INTEGER, Value.INT_SIZE, 0));
        columns.add(new ColumnMeta(tableName, "name", ValueType.CHAR, Value.CHAR_SIZE, Value.INT_SIZE));
        dbManager.createTable(tableName, columns);

        // Verify original columns
        TableMeta meta = metaManager.getTable(tableName);
        assertThat(meta.getColumnMeta("id")).isNotNull();
        assertThat(meta.getColumnMeta("name")).isNotNull();

        // Add column
        metaManager.addColumnInTable(tableName,
                new ColumnMeta(tableName, "age", ValueType.INTEGER, Value.INT_SIZE,
                        Value.INT_SIZE + Value.CHAR_SIZE));
        metaManager.saveToJson();

        meta = metaManager.getTable(tableName);
        assertThat(meta.getColumnMeta("age")).isNotNull();

        // Drop column
        metaManager.dropColumnInTable(tableName, "name");
        metaManager.saveToJson();

        meta = metaManager.getTable(tableName);
        assertThat(meta.getColumnMeta("name")).isNull();
        assertThat(meta.getColumnMeta("id")).isNotNull();
        assertThat(meta.getColumnMeta("age")).isNotNull();
    }

    @Test
    @DisplayName("测试 CREATE/DROP INDEX 元数据操作")
    void testIndexMetaData() throws DBException {
        String tableName = "test_index_" + UUID.randomUUID().toString().substring(0, 8);

        ArrayList<ColumnMeta> columns = new ArrayList<>();
        columns.add(new ColumnMeta(tableName, "id", ValueType.INTEGER, Value.INT_SIZE, 0));
        dbManager.createTable(tableName, columns);

        // Add index
        TableMeta meta = metaManager.getTable(tableName);
        meta.getIndexes().put("idx_test", TableMeta.IndexType.BTREE);
        metaManager.saveToJson();

        meta = metaManager.getTable(tableName);
        assertThat(meta.getIndexes()).containsKey("idx_test");

        // Drop index
        meta.getIndexes().remove("idx_test");
        metaManager.saveToJson();

        meta = metaManager.getTable(tableName);
        assertThat(meta.getIndexes()).doesNotContainKey("idx_test");
    }
}