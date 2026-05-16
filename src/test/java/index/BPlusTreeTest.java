package index;

import edu.sustech.cs307.index.BPlusTreeIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BPlusTreeTest {

    private BPlusTreeIndex<Integer, String> tree;

    @BeforeEach
    void setUp() {
        tree = new BPlusTreeIndex<>(4); // degree=4, max 3 keys per node
    }

    @Test
    @DisplayName("插入和搜索基本功能")
    void testInsertAndSearch() {
        tree.insert(10, "ten");
        tree.insert(20, "twenty");
        tree.insert(5, "five");

        assertThat(tree.search(10)).isEqualTo("ten");
        assertThat(tree.search(20)).isEqualTo("twenty");
        assertThat(tree.search(5)).isEqualTo("five");
        assertThat(tree.search(99)).isNull();
    }

    @Test
    @DisplayName("更新已存在键")
    void testUpdateExistingKey() {
        tree.insert(10, "ten");
        tree.insert(10, "updated_ten");

        assertThat(tree.search(10)).isEqualTo("updated_ten");
    }

    @Test
    @DisplayName("插入大量数据触发节点分裂")
    void testInsertCausingSplit() {
        // Insert enough keys to cause multiple leaf splits
        for (int i = 1; i <= 20; i++) {
            tree.insert(i, "value_" + i);
        }

        // Verify all keys are searchable
        for (int i = 1; i <= 20; i++) {
            assertThat(tree.search(i)).isEqualTo("value_" + i);
        }

        tree.printTree(); // Visual inspection: should show a multi-level tree
    }

    @Test
    @DisplayName("删除后重新搜索")
    void testDeleteAndSearch() {
        tree.insert(10, "ten");
        tree.insert(20, "twenty");
        tree.insert(30, "thirty");

        tree.delete(20);
        assertThat(tree.search(20)).isNull();
        assertThat(tree.search(10)).isEqualTo("ten");
        assertThat(tree.search(30)).isEqualTo("thirty");
    }

    @Test
    @DisplayName("删除后插入再删除（节点合并）")
    void testDeleteCausingMerge() {
        // Insert enough to cause splits, then delete to trigger merges
        for (int i = 1; i <= 10; i++) {
            tree.insert(i, "v" + i);
        }

        // Delete some keys
        tree.delete(1);
        tree.delete(2);
        assertThat(tree.search(1)).isNull();
        assertThat(tree.search(3)).isEqualTo("v3");
    }

    @Test
    @DisplayName("键的存在性检查")
    void testContainsKey() {
        tree.insert(42, "answer");
        assertThat(tree.containsKey(42)).isTrue();
        assertThat(tree.containsKey(43)).isFalse();
    }

    @Test
    @DisplayName("获取最小和最大键")
    void testFirstAndLastKey() {
        tree.insert(50, "fifty");
        tree.insert(10, "ten");
        tree.insert(90, "ninety");
        tree.insert(30, "thirty");

        assertThat(tree.getFirstKey()).isEqualTo(10);
        assertThat(tree.getLastKey()).isEqualTo(90);
    }

    @Test
    @DisplayName("获取所有条目（按排序顺序）")
    void testGetAllEntries() {
        tree.insert(3, "c");
        tree.insert(1, "a");
        tree.insert(2, "b");

        List<Map.Entry<Integer, String>> entries = tree.getAllEntries();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getKey()).isEqualTo(1);
        assertThat(entries.get(1).getKey()).isEqualTo(2);
        assertThat(entries.get(2).getKey()).isEqualTo(3);
        assertThat(entries.get(0).getValue()).isEqualTo("a");
    }
}