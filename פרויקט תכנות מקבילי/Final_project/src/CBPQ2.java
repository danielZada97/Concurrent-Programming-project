import java.util.concurrent.atomic.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.Random;
import java.util.Arrays;

public class CBPQ2 {
    private static final int MAX_LEVEL = 16;
    private static final double PROB = 0.5;
    
    // Instance parameters provided via the constructor.
    private final int chunkCapacity;
    private final int initialRange;
    private final int numThreads;
    private final int range;
    private final int insertions;
    private final int deletions;
    
    private final SkipNode head;
    private final Random random;
    
    // Parameterized constructor.
    public CBPQ2(int numThreads, int insertions, int deletions, int range, int chunkCapacity, int initialRange) {
        this.numThreads = numThreads;
        this.insertions = insertions;
        this.deletions = deletions;
        this.range = range;
        this.chunkCapacity = chunkCapacity;
        this.initialRange = initialRange;
        this.random = new Random();
        head = new SkipNode(Integer.MIN_VALUE, null, MAX_LEVEL);
    }
    
    // Default constructor using default parameters.
    public CBPQ2() {
        this(10, 100, 100, 1000, 10, 20);
    }
    
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
            Backoff backoff = new Backoff(1, 16);
            while (true) {
                Ring<T> tail = tailRing.get();
                long pos = tail.tail.getAndIncrement();
                int index = (int)(pos & MASK);
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
            Backoff backoff = new Backoff(1, 16);
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
                    int index = (int)(currentHead & MASK);
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
                    int index = (int)(pos & MASK);
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
                    int index = (int)(pos & MASK);
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
                    int index = (int)(pos & MASK);
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
    
    // LPRQChunk uses unsorted mode only (no merge/split logic)
    private class LPRQChunk {
        final int minKey;
        final int maxKey;
        final AtomicInteger count;
        final LPRQ<Long> queue;
        
        LPRQChunk(int minKey, int maxKey) {
            this.minKey = minKey;
            this.maxKey = maxKey;
            this.count = new AtomicInteger(0);
            this.queue = new LPRQ<>();
        }
        
        boolean canInsert(long key) {
            return key >= minKey && key <= maxKey;
        }
        
        boolean insert(long value) {
            if (contains(value)) {
                return false;
            }
            queue.enqueue(value);
            count.incrementAndGet();
            return true;
        }
        
        long deleteMin() {
            Long value = queue.dequeue();
            if (value != null) {
                count.decrementAndGet();
                return value;
            }
            return -1;
        }
        
        boolean contains(long value) {
            return queue.contains(value);
        }
        
        void printChunk() {
            System.out.print("Chunk [" + minKey + " - " + maxKey + "] Count: " + count.get() + " | ");
            queue.printQueueContent();
        }
    }
    
    private class SkipNode {
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
            if (chunk != null && chunk.canInsert(key) && chunk.contains(value)) {
                if (chunk.queue.remove(value)) {
                    chunk.count.decrementAndGet();
                    return true;
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
        int rangeStart = (key / initialRange) * initialRange;
        int rangeEnd = rangeStart + initialRange - 1;
        LPRQChunk newChunk = new LPRQChunk(rangeStart, rangeEnd);
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
        CBPQ2 pq = new CBPQ2(10, 100, 100, 1000, 10, 20);
        Thread[] threads = new Thread[pq.numThreads];
        int half = pq.numThreads / 2;
        
        for (int i = 0; i < half; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < pq.insertions; j++) {
                    int num = ThreadLocalRandom.current().nextInt(pq.range);
                    pq.insert(num);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        
        for (int i = half; i < pq.numThreads; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < pq.deletions; j++) {
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        try {
                            pq.deleteMin();
                        } catch (IllegalStateException e) {
                        }
                    } else {
                        int num = ThreadLocalRandom.current().nextInt(pq.range);
                        pq.delete(num);
                    }
                    try {
                        Thread.sleep(100);
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
