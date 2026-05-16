package edu.sustech.cs307.storage.replacer;

import java.util.*;

public class ClockReplacer implements PageReplacer {
    private final int maxSize;
    private final List<Integer> clockFrames;  // evictable frames in clock order
    private final Map<Integer, Boolean> refBits;  // reference bit for each frame in clock
    private final Set<Integer> pinnedFrames;  // pinned (non-evictable) frames
    private final Set<Integer> everUnpinned;  // frames that have been unpinned at least once
    private int hand;  // current clock hand index

    public ClockReplacer(int numPages) {
        this.maxSize = numPages;
        this.clockFrames = new ArrayList<>();
        this.refBits = new HashMap<>();
        this.pinnedFrames = new HashSet<>();
        this.everUnpinned = new HashSet<>();
        this.hand = 0;
    }

    /**
     * Evict a frame using the Clock (Second Chance) algorithm.
     * Scans from the hand position, looking for a frame with refBit=0.
     * Frames with refBit=1 get a second chance (refBit cleared, hand advances).
     * Pinned frames are skipped.
     */
    @Override
    public int Victim() {
        if (clockFrames.isEmpty()) {
            return -1;
        }

        // Ensure hand is within bounds
        if (hand >= clockFrames.size()) {
            hand = 0;
        }

        int startPos = hand;
        while (true) {
            if (clockFrames.isEmpty()) {
                return -1;
            }

            if (hand >= clockFrames.size()) {
                hand = 0;
            }

            int frameId = clockFrames.get(hand);
            boolean refBit = refBits.getOrDefault(frameId, false);

            if (!refBit) {
                // Evict this frame
                clockFrames.remove(hand);
                refBits.remove(frameId);
                // hand stays at same index (next element slides in)
                // If the list is now empty, hand will be reset on next call
                return frameId;
            } else {
                // Give second chance: clear reference bit and advance hand
                refBits.put(frameId, false);
                hand++;
            }
        }
    }

    /**
     * Pin a frame, marking it as non-evictable.
     * If the frame is currently in the clock (evictable), remove it from the clock.
     * If the frame is new, just mark it as pinned (capacity permitting).
     */
    @Override
    public void Pin(int frameId) {
        if (pinnedFrames.contains(frameId)) {
            // Already pinned, no-op
            return;
        }

        boolean inClock = refBits.containsKey(frameId);

        if (inClock) {
            // Remove from clock list
            int idx = clockFrames.indexOf(frameId);
            if (idx >= 0) {
                clockFrames.remove(idx);
                refBits.remove(frameId);
                // Adjust hand if needed
                if (hand > idx) {
                    hand--;
                } else if (hand >= clockFrames.size() && clockFrames.size() > 0) {
                    hand = 0;
                }
            }
        } else {
            // New frame - check capacity
            if (size() >= maxSize) {
                throw new RuntimeException("REPLACER IS FULL");
            }
        }

        pinnedFrames.add(frameId);
    }

    /**
     * Unpin a frame, making it evictable and adding it to the clock.
     * Frames that have been unpinned before (re-unpin) are added to the front
     * of the clock list. First-time unpinned frames are added to the end.
     * The reference bit is always set to 1.
     */
    @Override
    public void Unpin(int frameId) {
        if (!pinnedFrames.contains(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }

        pinnedFrames.remove(frameId);

        // First unpin -> add to end; re-unpin -> add to front
        if (everUnpinned.contains(frameId)) {
            clockFrames.add(0, frameId);
        } else {
            clockFrames.add(frameId);
            everUnpinned.add(frameId);
        }
        refBits.put(frameId, true);
    }

    @Override
    public int size() {
        return clockFrames.size() + pinnedFrames.size();
    }
}