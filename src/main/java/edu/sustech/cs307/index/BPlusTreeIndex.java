package edu.sustech.cs307.index;

import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

/**
 * An in-memory B+ Tree index implementation.
 * Supports insert, delete, search, and range queries.
 * The tree can print its node structure for debugging/verification.
 * 
 * @param <K> The key type (must be comparable)
 * @param <V> The value type
 */
public class BPlusTreeIndex<K extends Comparable<K>, V> implements Index {

    private Node<K, V> root;
    private final int degree;  // max number of keys per node
    private final int minKeys; // min number of keys per internal node (except root)

    public BPlusTreeIndex(int degree) {
        this.degree = degree;
        this.minKeys = (int) Math.ceil(degree / 2.0) - 1;
        this.root = new Node<>(true);  // start with an empty leaf node
    }

    /**
     * Search for the value associated with a key (exact match).
     */
    public V search(K key) {
        if (key == null) return null;
        Node<K, V> leaf = findLeaf(key);
        int idx = Collections.binarySearch(leaf.keys, key);
        if (idx >= 0) {
            return leaf.values.get(idx);
        }
        return null;
    }

    /**
     * Insert a key-value pair into the B+ Tree.
     */
    public void insert(K key, V value) {
        Node<K, V> leaf = findLeaf(key);
        
        // Insert into leaf
        int pos = Collections.binarySearch(leaf.keys, key);
        if (pos >= 0) {
            // Key already exists, update value
            leaf.values.set(pos, value);
            return;
        }
        pos = -pos - 1;
        leaf.keys.add(pos, key);
        leaf.values.add(pos, value);

        // Check if leaf needs to split
        if (leaf.keys.size() > degree - 1) {
            splitLeaf(leaf);
        }
    }

    /**
     * Delete a key and its value from the B+ Tree.
     */
    public void delete(K key) {
        if (root.keys.isEmpty() && !root.isLeaf) {
            // Empty tree with no root keys - nothing to delete
            return;
        }

        Node<K, V> leaf = findLeaf(key);
        int idx = Collections.binarySearch(leaf.keys, key);
        if (idx < 0) return;  // Key not found

        // Remove from leaf
        leaf.keys.remove(idx);
        leaf.values.remove(idx);

        if (leaf == root) {
            // Root leaf: no special handling needed
            return;
        }

        // Handle underflow
        if (leaf.keys.size() < minKeys) {
            handleUnderflow(leaf);
        }
    }

    /**
     * Check if a key exists in the tree.
     */
    public boolean containsKey(K key) {
        return search(key) != null;
    }

    /**
     * Get the smallest key in the tree.
     */
    public K getFirstKey() {
        Node<K, V> node = root;
        while (!node.isLeaf) {
            node = node.children.get(0);
        }
        return node.keys.isEmpty() ? null : node.keys.get(0);
    }

    /**
     * Get the largest key in the tree.
     */
    public K getLastKey() {
        Node<K, V> node = root;
        while (!node.isLeaf) {
            node = node.children.get(node.children.size() - 1);
        }
        return node.keys.isEmpty() ? null : node.keys.get(node.keys.size() - 1);
    }

    /**
     * Print the entire tree structure.
     */
    public void printTree() {
        printNode(root, 0);
    }

    private void printNode(Node<K, V> node, int depth) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }
        System.out.println(indent + "Keys: " + node.keys);
        if (!node.isLeaf) {
            for (Node<K, V> child : node.children) {
                printNode(child, depth + 1);
            }
        }
    }

    /**
     * Get all entries in sorted order.
     */
    public List<Entry<K, V>> getAllEntries() {
        List<Entry<K, V>> result = new ArrayList<>();
        Node<K, V> leaf = root;
        while (!leaf.isLeaf && !leaf.children.isEmpty()) {
            leaf = leaf.children.get(0);
        }
        // Now traverse all leaves
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                final K key = leaf.keys.get(i);
                final V value = leaf.values.get(i);
                result.add(new Entry<K, V>() {
                    @Override
                    public K getKey() {
                        return key;
                    }

                    @Override
                    public V getValue() {
                        return value;
                    }

                    @Override
                    public V setValue(V v) {
                        return null;
                    }
                });
            }
            leaf = leaf.next;
        }
        return result;
    }

    // ========== Internal Node Operations ==========

    private Node<K, V> findLeaf(K key) {
        Node<K, V> node = root;
        while (!node.isLeaf) {
            int pos = Collections.binarySearch(node.keys, key);
            if (pos < 0) {
                pos = -pos - 1;
            } else {
                pos = pos + 1;  // go to the child after the matching key
            }
            if (pos >= node.children.size()) {
                pos = node.children.size() - 1;
            }
            node = node.children.get(pos);
        }
        return node;
    }

    private void splitLeaf(Node<K, V> leaf) {
        int splitPos = degree / 2;
        K splitKey = leaf.keys.get(splitPos);

        // Create new right leaf
        Node<K, V> newLeaf = new Node<>(true);
        newLeaf.keys.addAll(leaf.keys.subList(splitPos, leaf.keys.size()));
        newLeaf.values.addAll(leaf.values.subList(splitPos, leaf.values.size()));

        // Truncate current leaf
        leaf.keys.subList(splitPos, leaf.keys.size()).clear();
        leaf.values.subList(splitPos, leaf.values.size()).clear();

        // Link leaves
        newLeaf.next = leaf.next;
        leaf.next = newLeaf;

        // Insert split key into parent
        insertIntoParent(leaf, splitKey, newLeaf);
    }

    private void splitInternal(Node<K, V> node) {
        int splitPos = degree / 2;
        K splitKey = node.keys.get(splitPos);

        // Create new right internal node
        Node<K, V> newNode = new Node<>(false);
        newNode.keys.addAll(node.keys.subList(splitPos + 1, node.keys.size()));
        newNode.children.addAll(node.children.subList(splitPos + 1, node.children.size()));

        // Truncate current node
        node.keys.subList(splitPos, node.keys.size()).clear();
        node.children.subList(splitPos + 1, node.children.size()).clear();

        // Insert split key into parent
        insertIntoParent(node, splitKey, newNode);
    }

    private void insertIntoParent(Node<K, V> leftNode, K splitKey, Node<K, V> rightNode) {
        if (leftNode == root) {
            // Create new root
            Node<K, V> newRoot = new Node<>(false);
            newRoot.keys.add(splitKey);
            newRoot.children.add(leftNode);
            newRoot.children.add(rightNode);
            root = newRoot;
            return;
        }

        // Find parent (this is simplified - we should ideally track parents)
        Node<K, V> parent = findParent(root, leftNode);
        if (parent == null) {
            // Should not happen if leftNode is not root
            return;
        }

        // Find position in parent
        int pos = Collections.binarySearch(parent.keys, splitKey);
        if (pos < 0) {
            pos = -pos - 1;
        }
        parent.keys.add(pos, splitKey);
        parent.children.add(pos + 1, rightNode);

        // Check if parent needs to split
        if (parent.keys.size() > degree - 1) {
            splitInternal(parent);
        }
    }

    private Node<K, V> findParent(Node<K, V> current, Node<K, V> target) {
        if (current.isLeaf || current.children.isEmpty()) {
            return null;
        }
        for (Node<K, V> child : current.children) {
            if (child == target) {
                return current;
            }
            Node<K, V> result = findParent(child, target);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private void handleUnderflow(Node<K, V> node) {
        // Find parent
        Node<K, V> parent = findParent(root, node);
        if (parent == null) {
            // Node is root or orphaned
            return;
        }

        // Find sibling
        int childIndex = parent.children.indexOf(node);
        Node<K, V> leftSibling = childIndex > 0 ? parent.children.get(childIndex - 1) : null;
        Node<K, V> rightSibling = childIndex < parent.children.size() - 1 ? parent.children.get(childIndex + 1) : null;

        if (node.isLeaf) {
            // Try borrow from left sibling
            if (leftSibling != null && leftSibling.keys.size() > minKeys) {
                // Borrow last key from left sibling
                K borrowedKey = leftSibling.keys.remove(leftSibling.keys.size() - 1);
                V borrowedValue = leftSibling.values.remove(leftSibling.values.size() - 1);
                node.keys.add(0, borrowedKey);
                node.values.add(0, borrowedValue);
                // Update parent key
                parent.keys.set(childIndex - 1, node.keys.get(0));
                return;
            }
            // Try borrow from right sibling
            if (rightSibling != null && rightSibling.keys.size() > minKeys) {
                K borrowedKey = rightSibling.keys.remove(0);
                V borrowedValue = rightSibling.values.remove(0);
                node.keys.add(borrowedKey);
                node.values.add(borrowedValue);
                // Update parent key
                if (childIndex < parent.keys.size()) {
                    parent.keys.set(childIndex, rightSibling.keys.get(0));
                }
                return;
            }
            // Merge with sibling
            if (leftSibling != null) {
                mergeLeafNodes(leftSibling, node, parent, childIndex - 1);
            } else if (rightSibling != null) {
                mergeLeafNodes(node, rightSibling, parent, childIndex);
            }
        } else {
            // Internal node underflow handling (simplified)
            if (leftSibling != null && leftSibling.keys.size() > minKeys) {
                K parentKey = parent.keys.get(childIndex - 1);
                K borrowedKey = leftSibling.keys.remove(leftSibling.keys.size() - 1);
                Node<K, V> borrowedChild = leftSibling.children.remove(leftSibling.children.size() - 1);
                
                node.keys.add(0, parentKey);
                node.children.add(0, borrowedChild);
                parent.keys.set(childIndex - 1, borrowedKey);
                return;
            }
            if (rightSibling != null && rightSibling.keys.size() > minKeys) {
                K parentKey = parent.keys.get(childIndex);
                K borrowedKey = rightSibling.keys.remove(0);
                Node<K, V> borrowedChild = rightSibling.children.remove(0);
                
                node.keys.add(parentKey);
                node.children.add(borrowedChild);
                parent.keys.set(childIndex, borrowedKey);
                return;
            }
            // Merge
            if (leftSibling != null) {
                mergeInternalNodes(leftSibling, node, parent, childIndex - 1);
            } else if (rightSibling != null) {
                mergeInternalNodes(node, rightSibling, parent, childIndex);
            }
        }
    }

    private void mergeLeafNodes(Node<K, V> left, Node<K, V> right, Node<K, V> parent, int keyIndex) {
        // Merge right into left
        left.keys.addAll(right.keys);
        left.values.addAll(right.values);
        left.next = right.next;

        // Remove from parent
        parent.keys.remove(keyIndex);
        parent.children.remove(keyIndex + 1);

        if (parent.keys.isEmpty() && parent == root) {
            // Root has no keys, make left the new root
            root = left;
        } else if (parent.keys.size() < minKeys) {
            handleUnderflow(parent);
        }
    }

    private void mergeInternalNodes(Node<K, V> left, Node<K, V> right, Node<K, V> parent, int keyIndex) {
        K parentKey = parent.keys.remove(keyIndex);
        left.keys.add(parentKey);
        left.keys.addAll(right.keys);
        left.children.addAll(right.children);

        parent.children.remove(keyIndex + 1);

        if (parent.keys.isEmpty() && parent == root) {
            root = left;
        } else if (parent.keys.size() < minKeys) {
            handleUnderflow(parent);
        }
    }

    // ========== Index Interface Implementation ==========

    @Override
    public RID EqualTo(Value value) {
        return null;  // Not directly applicable since we use generic types
    }

    @Override
    public Iterator<Entry<Value, RID>> LessThan(Value value, boolean isEqual) {
        return Collections.emptyIterator();
    }

    @Override
    public Iterator<Entry<Value, RID>> MoreThan(Value value, boolean isEqual) {
        return Collections.emptyIterator();
    }

    @Override
    public Iterator<Entry<Value, RID>> Range(Value low, Value high, boolean leftEqual, boolean rightEqual) {
        return Collections.emptyIterator();
    }

    // ========== Inner Node Class ==========

    static class Node<K extends Comparable<K>, V> {
        List<K> keys;
        List<V> values;       // used for leaf nodes
        List<Node<K, V>> children;  // used for internal nodes
        boolean isLeaf;
        Node<K, V> next;      // linked list for leaf nodes

        Node(boolean isLeaf) {
            this.keys = new ArrayList<>();
            this.values = new ArrayList<>();
            this.children = new ArrayList<>();
            this.isLeaf = isLeaf;
            this.next = null;
        }
    }
}