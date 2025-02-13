import java.util.concurrent.atomic.*;

public class LPRQ<T> {
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
        while (true) {
            Ring<T> tail = tailRing.get();
            long pos = tail.tail.getAndIncrement();
            int index = (int) (pos & MASK);
            if (tail.items.compareAndSet(index, null, item)) {
                System.out.println(Thread.currentThread().getName() + " Enqueued: " + item);
                return;
            }
            Thread.yield();
            if (pos >= tail.head.get() + RING_SIZE) {
                expandTail(tail);
            }
        }
    }

    public T dequeue() {
        while (true) {
            Ring<T> head = headRing.get();
            long currentHead = head.head.get();
            long currentTail = head.tail.get();
            if (currentHead >= currentTail) {
                if (head.next != null) {
                    headRing.compareAndSet(head, head.next);
                    continue;
                }
                System.out.println(Thread.currentThread().getName() + " Queue empty");
                return null;
            }
            if (head.head.compareAndSet(currentHead, currentHead + 1)) {
                int index = (int) (currentHead & MASK);
                T item;
                while ((item = head.items.get(index)) == null) {
                    Thread.onSpinWait();
                }
                head.items.set(index, null);
                System.out.println(Thread.currentThread().getName() + " Dequeued: " + item);
                return item;
            } else {
                Thread.yield();
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

    public static void main(String[] args) {
        final LPRQ<Integer> queue = new LPRQ<>();
        final int NUM_PRODUCERS = 3;
        final int NUM_CONSUMERS = 3;
        final int OPERATIONS_PER_THREAD = 10;

        Thread[] producers = new Thread[NUM_PRODUCERS];
        Thread[] consumers = new Thread[NUM_CONSUMERS];

        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int threadNum = i;
            producers[i] = new Thread(() -> {
                for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                    queue.enqueue(threadNum * 100 + j);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {}
                }
            }, "Producer-" + i);
        }

        for (int i = 0; i < NUM_CONSUMERS; i++) {
            consumers[i] = new Thread(() -> {
                int consumed = 0;
                while (consumed < OPERATIONS_PER_THREAD) {
                    Integer item = queue.dequeue();
                    if (item != null) {
                        consumed++;
                    } else {
                        try {
                            Thread.sleep(15);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }, "Consumer-" + i);
        }

        for (Thread producer : producers) {
            producer.start();
        }
        for (Thread consumer : consumers) {
            consumer.start();
        }

        try {
            for (Thread producer : producers) {
                producer.join();
            }
            for (Thread consumer : consumers) {
                consumer.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("All operations completed.");
    }
}
