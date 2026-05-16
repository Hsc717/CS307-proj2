package edu.sustech.cs307.storage.replacer;

import java.util.*;

public class LRUReplacer implements PageReplacer {

    private final int maxSize;
    private final Set<Integer> pinnedFrames = new HashSet<>();
    private final Set<Integer> LRUHash = new HashSet<>();
    private final LinkedList<Integer> LRUList = new LinkedList<>();

    public LRUReplacer(int numPages) {
        this.maxSize = numPages;
    }

    /**
     * Remove and return the least recently used frame (the tail of LRUList).
     * If no evictable frames exist, return -1.
     */
    public int Victim() {
        if (LRUList.isEmpty()) {
            return -1;
        }
        int victim = LRUList.removeLast();
        LRUHash.remove(victim);
        return victim;
    }

    /**
     * Pin a frame, marking it as not evictable.
     * If the frame is already in the LRU list, remove it from the LRU list.
     * If the frame is new (not pinned and not in LRU), check capacity first.
     */
    public void Pin(int frameId) {
        if (pinnedFrames.contains(frameId)) {
            // Already pinned, no-op (duplicate pin)
            return;
        }
        if (LRUHash.contains(frameId)) {
            // Frame is in LRU list: remove it and add to pinned
            LRUHash.remove(frameId);
            LRUList.removeFirstOccurrence(frameId);
            pinnedFrames.add(frameId);
        } else {
            // Frame is new (not tracked at all)
            if (size() >= maxSize) {
                throw new RuntimeException("REPLACER IS FULL");
            }
            pinnedFrames.add(frameId);
        }
    }


    public void Unpin(int frameId) {
        if (!pinnedFrames.contains(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        pinnedFrames.remove(frameId);
        LRUList.addFirst(frameId);
        LRUHash.add(frameId);
    }


    public int size() {
        return LRUList.size() + pinnedFrames.size();
    }
}