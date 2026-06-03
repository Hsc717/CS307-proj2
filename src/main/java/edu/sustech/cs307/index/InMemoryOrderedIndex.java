package edu.sustech.cs307.index;

import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;
import java.util.TreeMap;

import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * In-memory ordered index backed by a TreeMap<Value, List<RID>>.
 * Supports mapping one key to multiple RIDs (e.g. multiple rows with same age).
 */
public class InMemoryOrderedIndex implements Index {

    private TreeMap<Value, List<RID>> indexMap;

    public InMemoryOrderedIndex() {
        this.indexMap = new TreeMap<>();
    }

    public InMemoryOrderedIndex(String persistPath) {
        this.indexMap = new TreeMap<>();

        try {
            File file = new File(persistPath);
            if (file.exists() && file.length() > 0) {
                ObjectMapper objectMapper = new ObjectMapper();
                TypeReference<TreeMap<Value, List<RID>>> typeRef = new TypeReference<>() {};
                TreeMap<Value, List<RID>> loaded = objectMapper.readValue(file, typeRef);
                if (loaded != null) {
                    this.indexMap = loaded;
                }
            }
        } catch (IOException e) {
            Logger.error("Error loading index data: " + e.getMessage());
        }
    }

    /**
     * Insert a key-RID mapping into the index.
     */
    public void insert(Value key, RID rid) {
        indexMap.computeIfAbsent(key, k -> new ArrayList<>()).add(rid);
    }

    /**
     * Get the number of unique keys in the index.
     */
    public int size() {
        return indexMap.size();
    }

    /**
     * Print the B+ tree structure stored in the in-memory index.
     *
     * The underlying storage is a {@code TreeMap<Value, List<RID>>} (a red-black tree),
     * which is logically equivalent to a single-level B+ tree. To make the
     * visualisation closer to a textbook B+ tree we synthesise a 2-level layout:
     *
     *   Level 0 (ROOT/INTERNAL): routing keys = the first key of every leaf except
     *                            the leftmost one.  Search proceeds by binary search
     *                            on these keys.
     *   Level 1 (LEAF):  each leaf holds up to {@code fanout} entries, each entry is
     *                    (key → list of RIDs pointing to records on disk). Leaves
     *                    are linked via the implicit 'next' pointer (because
     *                    {@code indexMap.entrySet()} is already in sorted order).
     */
    public void printTree() {
        System.out.println("================= B+ Tree Index =================");
        System.out.println("Total unique keys:  " + indexMap.size());
        int totalEntries = 0;
        for (List<RID> rids : indexMap.values()) {
            totalEntries += rids.size();
        }
        System.out.println("Total key→RID pairs: " + totalEntries);
        final int fanout = 4;
        System.out.println("Leaf fanout (keys/leaf): " + fanout);
        System.out.println("=================================================");

        if (indexMap.isEmpty()) {
            System.out.println("(empty index)");
            System.out.println("=================================================");
            return;
        }

        List<Map.Entry<Value, List<RID>>> entries = new ArrayList<>(indexMap.entrySet());
        int leafCount = (entries.size() + fanout - 1) / fanout;

        // 1) slice the sorted entry list into leafCount leaf nodes
        List<List<Map.Entry<Value, List<RID>>>> leaves = new ArrayList<>();
        for (int i = 0; i < leafCount; i++) {
            int from = i * fanout;
            int to = Math.min(from + fanout, entries.size());
            leaves.add(new ArrayList<>(entries.subList(from, to)));
        }

        // 2) build the routing keys for the internal/root level
        //    (every leaf's first key except the leftmost one)
        List<Value> routingKeys = new ArrayList<>();
        for (int i = 1; i < leaves.size(); i++) {
            routingKeys.add(leaves.get(i).get(0).getKey());
        }

        // 3) print the internal/root level
        if (routingKeys.isEmpty()) {
            System.out.println("Tree height: 1  (single leaf, acts as root)");
        } else {
            System.out.println("Tree height: 2");
            System.out.println();
            System.out.println("Level 0  ROOT / INTERNAL  (routing keys only):");
            System.out.println("   ┌─────────────────────────────────────┐");
            System.out.println("   │ routing keys: " + routingKeys + " │");
            System.out.println("   └─────────────────────────────────────┘");
        }

        // 4) print the leaf level with the implicit 'next' pointer chain
        System.out.println();
        System.out.println("Level 1  LEAF nodes  (key → list of RIDs on disk, linked by 'next'):");
        for (int i = 0; i < leaves.size(); i++) {
            List<Map.Entry<Value, List<RID>>> leaf = leaves.get(i);
            System.out.println("  ┌── LEAF " + (i + 1) + " ─────────────────────────────────────────");
            for (int j = 0; j < leaf.size(); j++) {
                Map.Entry<Value, List<RID>> e = leaf.get(j);
                String sep = (j == 0) ? " " : " ";
                System.out.println("  │  " + e.getKey() + "  →  " + e.getValue());
            }
            String nextLabel = (i < leaves.size() - 1)
                    ? "next → LEAF " + (i + 2)
                    : "next → null";
            System.out.println("  │  " + nextLabel);
            System.out.println("  └───────────────────────────────────────────────────");
        }
        System.out.println("=================================================");
    }

    @Override
    public RID EqualTo(Value value) {
        if (indexMap == null || value == null) return null;
        List<RID> rids = indexMap.get(value);
        return (rids != null && !rids.isEmpty()) ? rids.get(0) : null;
    }

    /**
     * Returns all RIDs associated with the exact value.
     */
    public List<RID> EqualToList(Value value) {
        if (indexMap == null || value == null) return List.of();
        return indexMap.getOrDefault(value, List.of());
    }

    @Override
    public Iterator<Entry<Value, RID>> LessThan(Value value, boolean isEqual) {
        if (indexMap == null || indexMap.isEmpty()) {
            return java.util.Collections.emptyIterator();
        }
        NavigableMap<Value, List<RID>> subMap = isEqual
                ? indexMap.headMap(value, false)
                : indexMap.headMap(value, false);
        List<Entry<Value, RID>> result = new ArrayList<>();
        for (var entry : subMap.descendingMap().entrySet()) {
            for (RID rid : entry.getValue()) {
                final Value k = entry.getKey();
                final RID v = rid;
                result.add(new Entry<Value, RID>() {
                    public Value getKey() { return k; }
                    public RID getValue() { return v; }
                    public RID setValue(RID v) { return null; }
                });
            }
        }
        return result.iterator();
    }

    @Override
    public Iterator<Entry<Value, RID>> MoreThan(Value value, boolean isEqual) {
        if (indexMap == null || indexMap.isEmpty()) {
            return java.util.Collections.emptyIterator();
        }
        NavigableMap<Value, List<RID>> subMap = isEqual
                ? indexMap.tailMap(value, false)
                : indexMap.tailMap(value, false);
        List<Entry<Value, RID>> result = new ArrayList<>();
        for (var entry : subMap.entrySet()) {
            for (RID rid : entry.getValue()) {
                final Value k = entry.getKey();
                final RID v = rid;
                result.add(new Entry<Value, RID>() {
                    public Value getKey() { return k; }
                    public RID getValue() { return v; }
                    public RID setValue(RID v) { return null; }
                });
            }
        }
        return result.iterator();
    }

    @Override
    public Iterator<Entry<Value, RID>> Range(Value low, Value high, boolean leftEqual, boolean rightEqual) {
        if (indexMap == null || indexMap.isEmpty()) {
            return java.util.Collections.emptyIterator();
        }
        NavigableMap<Value, List<RID>> subMap = indexMap.subMap(low, leftEqual, high, rightEqual);
        List<Entry<Value, RID>> result = new ArrayList<>();
        for (var entry : subMap.entrySet()) {
            for (RID rid : entry.getValue()) {
                final Value k = entry.getKey();
                final RID v = rid;
                result.add(new Entry<Value, RID>() {
                    public Value getKey() { return k; }
                    public RID getValue() { return v; }
                    public RID setValue(RID v) { return null; }
                });
            }
        }
        return result.iterator();
    }
}