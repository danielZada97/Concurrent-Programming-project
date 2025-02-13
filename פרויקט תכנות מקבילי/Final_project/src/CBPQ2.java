import java.util.concurrent.atomic.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.Random;
import java.util.Arrays;

public class CBPQ2 {
    private static final int MAX_LEVEL = 16;
    private static final double PROB = 0.5;
    private static final int CHUNK_CAPACITY = 10;
    private static final int INITIAL_RANGE = 20;
    private static final int NUM_THREADS = 10;
    private static final int RANGE = 1000;
    private static final int INSERTIONS = 100;
    private static final int DELETIONS = 100;

    public static class LPRQ<T> {
        private static final int RING_SIZE = 1024;
        private static final int MASK = RING_SIZE - 1;

        private static class Ring<T> {
            final AtomicReferenceArray<T> items = new AtomicReferenceArray<>(RING_SIZE);
            final AtomicLong head = new AtomicLong(0);
            final AtomicLong tail = new AtomicLong(0);
            volatile Ring<T> next = null;
        }

        private final AtomicReference<Ring<T>> headRing;
        private final AtomicReference<Ring<T>> tailRing;

        public LPRQ() {
            Ring<T> initialRing = new Ring<>();
            headRing = new AtomicReference<>(initialRing);
            tailRing = new AtomicReference<>(initialRing);
        }

        public void enqueue(T item) {
            final Backoff backoff = new Backoff(1, 16);
            while (true) {
                Ring<T> tail = tailRing.get();
                long pos = tail.tail.getAndIncrement();
                int index = (int) (pos & MASK);
                if (tail.items.compareAndSet(index, null, item)) {
                    return;
                }
                backoff.backoff();
                if (pos >= tail.head.get() + RING_SIZE) {
                    expandTail(tail);
                }
            }
        }

        public T dequeue() {
            final Backoff backoff = new Backoff(1, 16);
            while (true) {
                Ring<T> head = headRing.get();
                long currentHead = head.head.get();
                long currentTail = head.tail.get();
                if (currentHead >= currentTail) {
                    if (head.next != null) {
                        headRing.compareAndSet(head, head.next);
                        continue;
                    }
                    return null;
                }
                if (head.head.compareAndSet(currentHead, currentHead + 1)) {
                    int index = (int) (currentHead & MASK);
                    T item;
                    while ((item = head.items.get(index)) == null) {
                        backoff.backoff();
                    }
                    head.items.set(index, null);
                    return item;
                } else {
                    backoff.backoff();
                }
            }
        }

        private void expandTail(Ring<T> tail) {
            Ring<T> newRing = new Ring<>();
            if (tail.next == null) {
                tail.next = newRing;
            }
            tailRing.compareAndSet(tail, tail.next);
        }

        public boolean contains(T item) {
            Ring<T> curr = headRing.get();
            while (curr != null) {
                long headIndex = curr.head.get();
                long tailIndex = curr.tail.get();
                for (long pos = headIndex; pos < tailIndex; pos++) {
                    int index = (int) (pos & MASK);
                    T currItem = curr.items.get(index);
                    if (currItem != null && currItem.equals(item)) {
                        return true;
                    }
                }
                curr = curr.next;
            }
            return false;
        }

        public boolean remove(T item) {
            Ring<T> curr = headRing.get();
            while (curr != null) {
                long headIndex = curr.head.get();
                long tailIndex = curr.tail.get();
                for (long pos = headIndex; pos < tailIndex; pos++) {
                    int index = (int) (pos & MASK);
                    T currItem = curr.items.get(index);
                    if (currItem != null && currItem.equals(item)) {
                        if (curr.items.compareAndSet(index, currItem, null)) {
                            return true;
                        }
                    }
                }
                curr = curr.next;
            }
            return false;
        }

        public void printQueueContent() {
            Ring<T> curr = headRing.get();
            System.out.print("  Items: ");
            while (curr != null) {
                long headIndex = curr.head.get();
                long tailIndex = curr.tail.get();
                for (long pos = headIndex; pos < tailIndex; pos++) {
                    int index = (int) (pos & MASK);
                    T item = curr.items.get(index);
                    if (item != null) {
                        System.out.print(item + " ");
                    }
                }
                curr = curr.next;
            }
            System.out.println();
        }
    }

    private static class Backoff {
        private final int minDelayNanos;
        private final int maxDelayNanos;
        private int currentDelayNanos;
        private static final int MAX_SPINS = 500;

        public Backoff(int minDelayMillis, int maxDelayMillis) {
            this.minDelayNanos = minDelayMillis * 1_000_000;
            this.maxDelayNanos = maxDelayMillis * 1_000_000;
            this.currentDelayNanos = minDelayNanos;
        }

        public void backoff() {
            for (int i = 0; i < MAX_SPINS; i++) {
                Thread.onSpinWait();
            }
            Thread.yield();
            LockSupport.parkNanos(currentDelayNanos);
            currentDelayNanos = Math.min(maxDelayNanos, currentDelayNanos * 2);
        }
    }

    private static class LPRQChunk {
        final int minKey;
        final int maxKey;
        final boolean isSorted;
        final AtomicInteger count;
        final AtomicBoolean split;
        final LPRQ<Long> queue;
        final AtomicReference<long[]> sortedArray;

        LPRQChunk(int minKey, int maxKey, boolean isSorted) {
            this.minKey = minKey;
            this.maxKey = maxKey;
            this.isSorted = isSorted;
            this.count = new AtomicInteger(0);
            this.split = new AtomicBoolean(false);
            if (isSorted) {
                this.sortedArray = new AtomicReference<>(new long[0]);
                this.queue = null;
            } else {
                this.queue = new LPRQ<>();
                this.sortedArray = null;
            }
        }

        LPRQChunk(int minKey, int maxKey) {
            this(minKey, maxKey, false);
        }

        boolean canInsert(long key) {
            return key >= minKey && key <= maxKey;
        }

        boolean isFull() {
            return count.get() >= CHUNK_CAPACITY;
        }

        boolean contains(long value) {
            if (isSorted) {
                long[] arr = sortedArray.get();
                return Arrays.binarySearch(arr, value) >= 0;
            } else {
                return queue.contains(value);
            }
        }

        boolean insert(long value) {
            if (contains(value)) {
                return false;
            }
            if (isFull()) {
                return false;
            }
            if (isSorted) {
                while (true) {
                    long[] arr = sortedArray.get();
                    if (arr.length >= CHUNK_CAPACITY) {
                        return false;
                    }
                    if (Arrays.binarySearch(arr, value) >= 0) {
                        return false;
                    }
                    int pos = ~Arrays.binarySearch(arr, value);
                    long[] newArr = new long[arr.length + 1];
                    System.arraycopy(arr, 0, newArr, 0, pos);
                    newArr[pos] = value;
                    System.arraycopy(arr, pos, newArr, pos + 1, arr.length - pos);
                    if (sortedArray.compareAndSet(arr, newArr)) {
                        count.incrementAndGet();
                        return true;
                    }
                }
            } else {
                queue.enqueue(value);
                count.incrementAndGet();
                return true;
            }
        }

        long deleteMin() {
            if (isSorted) {
                while (true) {
                    long[] arr = sortedArray.get();
                    if (arr.length == 0)
                        return -1;
                    long min = arr[0];
                    long[] newArr = new long[arr.length - 1];
                    System.arraycopy(arr, 1, newArr, 0, arr.length - 1);
                    if (sortedArray.compareAndSet(arr, newArr)) {
                        count.decrementAndGet();
                        return min;
                    }
                }
            } else {
                Long value = queue.dequeue();
                if (value != null) {
                    count.decrementAndGet();
                    return value;
                }
                return -1;
            }
        }

        void printChunk() {
            System.out.print("Chunk [" + minKey + " - " + maxKey + "] Count: " + count.get() + " | ");
            if (isSorted) {
                long[] arr = sortedArray.get();
                System.out.println(Arrays.toString(arr));
            } else {
                queue.printQueueContent();
            }
        }
    }

    private static class SkipNode {
        final int minKey;
        final AtomicReference<LPRQChunk> chunk;
        final AtomicReference<SkipNode>[] next;

        @SuppressWarnings("unchecked")
        SkipNode(int minKey, LPRQChunk chunk, int level) {
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

    public CBPQ2() {
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
            LPRQChunk chunk = curr.chunk.get();
            if (chunk != null) {
                long value = chunk.deleteMin();
                if (value != -1) {
                    return value;
                }
            }
            curr = curr.next[0].get();
        }
        throw new IllegalStateException("Queue is empty");
    }

    public boolean delete(long value) {
        int key = (int) value;
        SkipNode curr = head.next[0].get();
        while (curr != null) {
            LPRQChunk chunk = curr.chunk.get();
            if (chunk != null && chunk.canInsert(key)) {
                if (chunk.contains(value)) {
                    if (chunk.isSorted) {
                        while (true) {
                            long[] arr = chunk.sortedArray.get();
                            int idx = Arrays.binarySearch(arr, value);
                            if (idx < 0) {
                                break;
                            }
                            long[] newArr = new long[arr.length - 1];
                            System.arraycopy(arr, 0, newArr, 0, idx);
                            System.arraycopy(arr, idx + 1, newArr, idx, arr.length - idx - 1);
                            if (chunk.sortedArray.compareAndSet(arr, newArr)) {
                                chunk.count.decrementAndGet();
                                return true;
                            }
                        }
                    } else {
                        if (chunk.queue.remove(value)) {
                            chunk.count.decrementAndGet();
                            return true;
                        }
                    }
                }
            }
            curr = curr.next[0].get();
        }
        return false;
    }

    public boolean insert(long value) {
        int key = (int) value;
        while (true) {
            SkipNode[] update = new SkipNode[MAX_LEVEL];
            SkipNode curr = findInsertionPoint(key, update);
            LPRQChunk targetChunk = curr.chunk.get();
            if (targetChunk == null || !targetChunk.canInsert(key)) {
                targetChunk = createNewChunk(key, curr, update, randomLevel());
            } else if (targetChunk.isFull()) {
                if (targetChunk.split.compareAndSet(false, true)) {
                    splitChunk(curr, targetChunk);
                }
                continue;
            }
            if (targetChunk.contains(value)) {
                return false;
            }
            if (targetChunk.insert(value)) {
                return true;
            }
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

    private LPRQChunk createNewChunk(int key, SkipNode current, SkipNode[] update, int level) {
        int rangeStart = (key / INITIAL_RANGE) * INITIAL_RANGE;
        int rangeEnd = rangeStart + INITIAL_RANGE - 1;
        boolean sortedFlag = (update[0] == head);
        LPRQChunk newChunk = new LPRQChunk(rangeStart, rangeEnd, sortedFlag);
        SkipNode newNode = new SkipNode(rangeStart, newChunk, level);
        for (int lvl = 0; lvl < level; lvl++) {
            newNode.next[lvl].set(update[lvl].next[lvl].get());
            int retries = 0;
            while (!update[lvl].next[lvl].compareAndSet(newNode.next[lvl].get(), newNode)) {
                newNode.next[lvl].set(update[lvl].next[lvl].get());
                retries++;
                if (retries > 1000) break;
            }
        }
        if (current.chunk.get() == null && current.minKey <= rangeStart) {
            current.chunk.compareAndSet(null, newChunk);
        }
        return newChunk;
    }

    private void splitChunk(SkipNode node, LPRQChunk fullChunk) {
        int low = fullChunk.minKey;
        int high = fullChunk.maxKey;
        int rangeWidth = high - low + 1;
        int halfWidth = rangeWidth / 2;
        int mid = low + halfWidth - 1;

        LPRQChunk lowerChunk = new LPRQChunk(low, mid, fullChunk.isSorted);
        LPRQChunk upperChunk = new LPRQChunk(mid + 1, high);

        while (true) {
            long val = fullChunk.deleteMin();
            if (val == -1) break;
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
        int retries = 0;
        while (!node.next[0].compareAndSet(newNode.next[0].get(), newNode)) {
            newNode.next[0].set(node.next[0].get());
            retries++;
            if (retries > 1000) break;
        }
        for (int lvl = 1; lvl < level; lvl++) {
            SkipNode pred = findPredecessor(newNode, lvl);
            newNode.next[lvl].set(pred.next[lvl].get());
            retries = 0;
            while (!pred.next[lvl].compareAndSet(newNode.next[lvl].get(), newNode)) {
                newNode.next[lvl].set(pred.next[lvl].get());
                retries++;
                if (retries > 1000) break;
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
            LPRQChunk chunk = curr.chunk.get();
            if (chunk != null) {
                chunk.printChunk();
            }
            curr = curr.next[0].get();
        }
        System.out.println("------------------------");
    }

    public static void main(String[] args) {
        final CBPQ2 pq = new CBPQ2();
        Thread[] threads = new Thread[NUM_THREADS];
        int half = NUM_THREADS / 2;

        for (int i = 0; i < half; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < INSERTIONS; j++) {
                    int num = ThreadLocalRandom.current().nextInt(RANGE);
                    pq.insert(num);
                }
            });
        }

        for (int i = half; i < NUM_THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < DELETIONS; j++) {
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        try {
                            pq.deleteMin();
                        } catch (IllegalStateException e) {
                        }
                    } else {
                        int num = ThreadLocalRandom.current().nextInt(RANGE);
                        pq.delete(num);
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
