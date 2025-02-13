import java.util.concurrent.atomic.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Random;
import java.util.Arrays;

public class CBPQ {
    private static final int MAX_LEVEL = 16;
    private static final double PROB = 0.5;
    private static final int CHUNK_CAPACITY = 10;
    private static final int INITIAL_RANGE = 20;
    private static final int NUM_THREADS = 10;
    private static final int RANGE = 1000;
    private static final int INSERTIONS = 100;
    private static final int DELETIONS = 100;

    private static class SortedChunk {
        final int minKey;
        final int maxKey;
        final AtomicReference<long[]> sortedArray;
        final AtomicBoolean split;
        final boolean isSorted;

        SortedChunk(int minKey, int maxKey, boolean isSorted) {
            this.minKey = minKey;
            this.maxKey = maxKey;
            this.isSorted = isSorted;
            this.sortedArray = new AtomicReference<>(new long[0]);
            this.split = new AtomicBoolean(false);
        }

        boolean canInsert(long key) {
            return key >= minKey && key <= maxKey;
        }

        boolean isFull() {
            return sortedArray.get().length >= CHUNK_CAPACITY;
        }

        boolean contains(long value) {
            long[] arr = sortedArray.get();
            if (isSorted) {
                return Arrays.binarySearch(arr, value) >= 0;
            } else {
                for (long x : arr) {
                    if (x == value)
                        return true;
                }
                return false;
            }
        }

        boolean insert(long value) {
            while (true) {
                long[] arr = sortedArray.get();
                if (arr.length >= CHUNK_CAPACITY)
                    return false;
                if (contains(value))
                    return false;
                long[] newArr = new long[arr.length + 1];
                if (isSorted) {
                    int pos = Arrays.binarySearch(arr, value);
                    int insertPos = ~pos;
                    System.arraycopy(arr, 0, newArr, 0, insertPos);
                    newArr[insertPos] = value;
                    System.arraycopy(arr, insertPos, newArr, insertPos + 1, arr.length - insertPos);
                } else {
                    System.arraycopy(arr, 0, newArr, 0, arr.length);
                    newArr[arr.length] = value;
                }
                if (sortedArray.compareAndSet(arr, newArr))
                    return true;
            }
        }

        long deleteMin() {
            while (true) {
                long[] arr = sortedArray.get();
                if (arr.length == 0)
                    return -1;
                long min;
                int index;
                if (isSorted) {
                    min = arr[0];
                    index = 0;
                } else {
                    min = arr[0];
                    index = 0;
                    for (int i = 1; i < arr.length; i++) {
                        if (arr[i] < min) {
                            min = arr[i];
                            index = i;
                        }
                    }
                }
                long[] newArr = new long[arr.length - 1];
                System.arraycopy(arr, 0, newArr, 0, index);
                System.arraycopy(arr, index + 1, newArr, index, arr.length - index - 1);
                if (sortedArray.compareAndSet(arr, newArr))
                    return min;
            }
        }

        void printChunk() {
            long[] arr = sortedArray.get();
            System.out.println("Chunk [" + minKey + " - " + maxKey + "] (" + (isSorted ? "sorted" : "unsorted") + ") | Values: " + Arrays.toString(arr));
        }
    }

    private static class SkipNode {
        final int minKey;
        final AtomicReference<SortedChunk> chunk;
        final AtomicReference<SkipNode>[] next;

        @SuppressWarnings("unchecked")
        SkipNode(int minKey, SortedChunk chunk, int level) {
            this.minKey = minKey;
            this.chunk = new AtomicReference<>(chunk);
            this.next = new AtomicReference[level];
            for (int i = 0; i < level; i++) {
                this.next[i] = new AtomicReference<>(null);
            }
        }
    }

    private final SkipNode head;
    private final Random random;

    public CBPQ() {
        random = new Random();
        head = new SkipNode(Integer.MIN_VALUE, null, MAX_LEVEL);
    }

    private int randomLevel() {
        int level = 1;
        while (random.nextDouble() < PROB && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }

    public long deleteMin() {
        SkipNode curr = head.next[0].get();
        while (curr != null) {
            SortedChunk chunk = curr.chunk.get();
            if (chunk != null) {
                long value = chunk.deleteMin();
                if (value != -1)
                    return value;
            }
            curr = curr.next[0].get();
        }
        throw new IllegalStateException("Queue is empty");
    }

    public boolean delete(long value) {
        int key = (int) value;
        SkipNode curr = head.next[0].get();
        while (curr != null) {
            SortedChunk chunk = curr.chunk.get();
            if (chunk != null && chunk.canInsert(key)) {
                if (chunk.contains(value)) {
                    if (deleteFromChunk(chunk, value))
                        return true;
                }
            }
            curr = curr.next[0].get();
        }
        return false;
    }

    private boolean deleteFromChunk(SortedChunk chunk, long value) {
        while (true) {
            long[] arr = chunk.sortedArray.get();
            int idx;
            if (chunk.isSorted) {
                idx = Arrays.binarySearch(arr, value);
            } else {
                idx = -1;
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] == value) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx < 0)
                return false;
            long[] newArr = new long[arr.length - 1];
            System.arraycopy(arr, 0, newArr, 0, idx);
            System.arraycopy(arr, idx + 1, newArr, idx, arr.length - idx - 1);
            if (chunk.sortedArray.compareAndSet(arr, newArr))
                return true;
        }
    }

    public boolean insert(long value) {
        int key = (int) value;
        while (true) {
            SkipNode[] update = new SkipNode[MAX_LEVEL];
            SkipNode curr = findInsertionPoint(key, update);
            SortedChunk targetChunk = curr.chunk.get();
            if (targetChunk == null || !targetChunk.canInsert(key)) {
                targetChunk = createNewChunk(key, curr, update, randomLevel());
            } else if (targetChunk.isFull()) {
                if (targetChunk.split.compareAndSet(false, true)) {
                    splitChunk(curr, targetChunk);
                }
                continue;
            }
            if (targetChunk.contains(value))
                return false;
            if (targetChunk.insert(value))
                return true;
        }
    }

    private SkipNode findInsertionPoint(int key, SkipNode[] update) {
        SkipNode curr = head;
        for (int lvl = MAX_LEVEL - 1; lvl >= 0; lvl--) {
            while (true) {
                SkipNode next = curr.next[lvl].get();
                if (next != null && next.minKey <= key) {
                    curr = next;
                } else {
                    break;
                }
            }
            update[lvl] = curr;
        }
        return curr;
    }

    private SortedChunk createNewChunk(int key, SkipNode current, SkipNode[] update, int level) {
        int rangeStart = (key / INITIAL_RANGE) * INITIAL_RANGE;
        int rangeEnd = rangeStart + INITIAL_RANGE - 1;
        boolean sortedFlag = (update[0] == head);
        SortedChunk newChunk = new SortedChunk(rangeStart, rangeEnd, sortedFlag);
        SkipNode newNode = new SkipNode(rangeStart, newChunk, level);
        for (int lvl = 0; lvl < level; lvl++) {
            newNode.next[lvl].set(update[lvl].next[lvl].get());
            while (!update[lvl].next[lvl].compareAndSet(newNode.next[lvl].get(), newNode)) {
                newNode.next[lvl].set(update[lvl].next[lvl].get());
            }
        }
        if (current.chunk.get() == null && current.minKey <= rangeStart) {
            current.chunk.compareAndSet(null, newChunk);
        }
        return newChunk;
    }

    private void splitChunk(SkipNode node, SortedChunk fullChunk) {
        int low = fullChunk.minKey;
        int high = fullChunk.maxKey;
        int rangeWidth = high - low + 1;
        int halfWidth = rangeWidth / 2;
        int mid = low + halfWidth - 1;
        boolean lowerSorted, upperSorted;
        if (fullChunk.isSorted && node == head.next[0].get()) {
            lowerSorted = true;
            upperSorted = false;
        } else {
            lowerSorted = false;
            upperSorted = false;
        }
        SortedChunk lowerChunk = new SortedChunk(low, mid, lowerSorted);
        SortedChunk upperChunk = new SortedChunk(mid + 1, high, upperSorted);
        long[] arr = fullChunk.sortedArray.get();
        for (int i = 0; i < arr.length; i++) {
            long val = arr[i];
            if (val <= mid) {
                lowerChunk.insert(val);
            } else {
                upperChunk.insert(val);
            }
        }
        System.out.println("Chunk [" + fullChunk.minKey + "-" + fullChunk.maxKey + "] split into [" +
                lowerChunk.minKey + "-" + lowerChunk.maxKey + "] (" + (lowerChunk.isSorted ? "sorted" : "unsorted") +
                ") and [" + upperChunk.minKey + "-" + upperChunk.maxKey + "] (" + (upperChunk.isSorted ? "sorted" : "unsorted") + ")");
        node.chunk.set(lowerChunk);
        int level = randomLevel();
        SkipNode newNode = new SkipNode(upperChunk.minKey, upperChunk, level);
        newNode.next[0].set(node.next[0].get());
        while (!node.next[0].compareAndSet(newNode.next[0].get(), newNode)) {
            newNode.next[0].set(node.next[0].get());
        }
        for (int lvl = 1; lvl < level; lvl++) {
            SkipNode pred = findPredecessor(newNode, lvl);
            newNode.next[lvl].set(pred.next[lvl].get());
            while (!pred.next[lvl].compareAndSet(newNode.next[lvl].get(), newNode)) {
                newNode.next[lvl].set(pred.next[lvl].get());
            }
        }
    }

    private SkipNode findPredecessor(SkipNode target, int level) {
        SkipNode pred = head;
        SkipNode curr = pred.next[level].get();
        while (curr != null && curr != target && curr.minKey <= target.minKey) {
            pred = curr;
            curr = curr.next[level].get();
        }
        return pred;
    }

    public void printChunks() {
        SkipNode curr = head.next[0].get();
        if (curr == null) {
            System.out.println("No chunks in queue.");
            return;
        }
        System.out.println("\n---- Current Chunks ----");
        while (curr != null) {
            SortedChunk chunk = curr.chunk.get();
            if (chunk != null) {
                chunk.printChunk();
            }
            curr = curr.next[0].get();
        }
        System.out.println("------------------------\n");
    }

    public static void main(String[] args) {
        final CBPQ pq = new CBPQ();
        Thread[] threads = new Thread[NUM_THREADS];
        int half = NUM_THREADS / 2;
        for (int i = 0; i < half; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < INSERTIONS; j++) {
                    int num = ThreadLocalRandom.current().nextInt(RANGE);
                    boolean inserted = pq.insert(num);
                    if (inserted) {
                        System.out.println(Thread.currentThread().getName() + " Inserted: " + num);
                    } else {
                        System.out.println(Thread.currentThread().getName() + " Duplicate/full, skipped: " + num);
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        for (int i = half; i < NUM_THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < DELETIONS; j++) {
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        try {
                            long min = pq.deleteMin();
                            System.out.println(Thread.currentThread().getName() + " deleteMin: " + min);
                        } catch (IllegalStateException e) {
                            System.out.println(Thread.currentThread().getName() + " Queue empty during deleteMin.");
                        }
                    } else {
                        int num = ThreadLocalRandom.current().nextInt(RANGE);
                        boolean deleted = pq.delete(num);
                        if (deleted) {
                            System.out.println(Thread.currentThread().getName() + " deleted value: " + num);
                        } else {
                            System.out.println(Thread.currentThread().getName() + " delete failed for: " + num);
                        }
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("REACHED END");
        pq.printChunks();
    }
}
