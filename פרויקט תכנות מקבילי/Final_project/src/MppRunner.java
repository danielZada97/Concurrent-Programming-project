import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class MppRunner {

	private static final int NUM_OPERATIONS = 1000000;
	private static final int[] NUM_THREADS = {1, 2, 4, 8, 16, 32, 64, 128, 256 };

	public static void main(String[] args) {
		runBenchmark("CBPQ");

		runBenchmark("CBPQ2");
	}

	private static void runBenchmark(String queueType) {
		System.out.printf("\nResults for: %s\n", queueType);
		System.out.printf("%-10s %-15s %-20s%n", "Threads", "Runtime (ms)", "Throughput (ops/sec)");

		for (int numThreads : NUM_THREADS) {
			AtomicInteger operationsCompleted = new AtomicInteger(0);

			Object pq = createQueue(queueType);

			Thread[] threads = new Thread[numThreads];
			long startTime = System.currentTimeMillis();

			for (int i = 0; i < numThreads; i++) {
				threads[i] = new Thread(() -> {
					while (operationsCompleted.get() < NUM_OPERATIONS) {
						if (ThreadLocalRandom.current().nextBoolean()) {
							int value = ThreadLocalRandom.current().nextInt(1000);
							insertToQueue(pq, value);
						} else {
							try {
								deleteMinFromQueue(pq);
							} catch (IllegalStateException e) {
							}
						}
						operationsCompleted.incrementAndGet();
					}
				});
			}

			for (Thread t : threads) {
				t.start();
			}

			// המתנה עד שכל התהליכונים יסיימו
			for (Thread t : threads) {
				try {
					t.join();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}

			long endTime = System.currentTimeMillis();
			long runtime = endTime - startTime;
			double throughput = (double) NUM_OPERATIONS / runtime * 1000;

			System.out.printf("%-10d %-15d %-20.2f%n", numThreads, runtime, throughput);
		}
	}

	private static Object createQueue(String queueType) {
		if ("CBPQ".equals(queueType)) {
			return new CBPQ2();
		} else if ("CBPQ2".equals(queueType)) {
			return new CBPQ2();
		} else {
			throw new IllegalArgumentException("Unknown queue type: " + queueType);
		}
	}

	private static void insertToQueue(Object pq, int value) {
		if (pq instanceof CBPQ2) {
			((CBPQ2) pq).insert(value);
		} else if (pq instanceof CBPQ2) {
			((CBPQ2) pq).insert(value);
		}
	}

	private static void deleteMinFromQueue(Object pq) {
		if (pq instanceof CBPQ2) {
			((CBPQ2) pq).deleteMin();
		} else if (pq instanceof CBPQ2) {
			((CBPQ2) pq).deleteMin();
		}
	}
}