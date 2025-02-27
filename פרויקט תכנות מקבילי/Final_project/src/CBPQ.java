import java.util.concurrent.atomic.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Random;
import java.util.Arrays;

public class CBPQ {
    // Constants for skip list
    private static final int MAX_LEVEL = 16;
    private static final double PROB = 0.5;

    // Parameters supplied via the constructor
    private final int chunkCapacity;    // Capacity for each chunk
    private final int initialRange;     // Range for each chunk
    private final int numThreads;       // Number of threads for the test
    private final int range;            // Upper bound for random numbers
    private final int insertions;       // Number of insertions per producer thread
    private final int deletions;        // Number of deletions per consumer thread

    private final SkipNode head;  // Head of the skip list
    private final Random random;

    // Constructor that receives all parameters
    public CBPQ(int numThreads, int insertions, int deletions, int range, int chunkCapacity, int initialRange) {
        this.numThreads = numThreads;
        this.insertions = insertions;
        this.deletions = deletions;
        this.range = range;
        this.chunkCapacity = chunkCapacity;
        this.initialRange = initialRange;
        this.random = new Random();
        head = new SkipNode(Integer.MIN_VALUE, null, MAX_LEVEL);
    }

    // ------------------ SortedChunk Class ------------------
    // This class represents a chunk that stores values in a sorted (or unsorted) array.
    // It uses the instance field 'chunkCapacity' to determine fullness.
    private class SortedChunk {
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
            return sortedArray.get().length >= chunkCapacity;
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
                if (arr.length >= chunkCapacity)
                    return false;
                if (contains(value))
                    return false;
                long[] newArr = new long[arr.length + 1];
                if (isSorted) {
                    int pos = Arrays.binarySearch(arr, value);
                    int insertPos = (pos < 0) ? ~pos : pos;
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

    // ------------------ SkipNode Class ------------------
    // Each node in the skip list holds a minimum key and a reference to a SortedChunk.
    private class SkipNode {
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

    // ------------------ Core Methods of CBPQ ------------------
    // deleteMin: finds the first chunk and deletes its minimum value.
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

    // delete: delete a specific value from a chunk that can contain it.
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

    // insert: finds the proper chunk via the skip list or creates a new chunk if needed,
    // then inserts the value.
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

    // findInsertionPoint: uses the skip list to find where to insert the new value.
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

    // createNewChunk: creates a new SortedChunk and adds it into the skip list.
    private SortedChunk createNewChunk(int key, SkipNode current, SkipNode[] update, int level) {
        int rangeStart = (key / initialRange) * initialRange;
        int rangeEnd = rangeStart + initialRange - 1;
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

    // splitChunk: splits a full chunk into two; values less than or equal to the midpoint go to the lower chunk.
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
        System.out.println("---- Current Chunks ----");
        while (curr != null) {
            SortedChunk chunk = curr.chunk.get();
            if (chunk != null) {
                chunk.printChunk();
            }
            curr = curr.next[0].get();
        }
        System.out.println("------------------------");
    }

    // Generate a random level for the skip list.
    private int randomLevel() {
        int level = 1;
        while (random.nextDouble() < PROB && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }

    // ------------------ Main Method ------------------
    public static void main(String[] args) {
        // Create a CBPQ instance using the constructor with parameters:
        // numThreads, insertions, deletions, range, chunkCapacity, initialRange.
        CBPQ pq = new CBPQ(10, 100, 100, 1000, 10, 20);

        Thread[] threads = new Thread[pq.numThreads];
        int half = pq.numThreads / 2;

        // Producer threads perform insertions.
        for (int i = 0; i < half; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < pq.insertions; j++) {
                    int num = ThreadLocalRandom.current().nextInt(pq.range);
                    pq.insert(num);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // Consumer threads perform deletions (either deleteMin or random delete).
        for (int i = half; i < pq.numThreads; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < pq.deletions; j++) {
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        try {
                            pq.deleteMin();
                        } catch (IllegalStateException e) {
                            // Ignore if queue is empty.
                        }
                    } else {
                        int num = ThreadLocalRandom.current().nextInt(pq.range);
                        pq.delete(num);
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // Start all threads.
        for (Thread t : threads) {
            t.start();
        }
        // Wait for all threads to finish.
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pq.printChunks();
    }
}
