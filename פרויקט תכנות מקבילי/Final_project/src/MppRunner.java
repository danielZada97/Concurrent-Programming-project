import java.util.concurrent.atomic.AtomicInteger;

public class MppRunner {

	private static final int[] NUM_THREADS = { 1, 2, 4, 8, 16, 32, 64, 96 };
	private static final int[] num_operations = { 1_000, 10_000, 100_000, 1_000_000 };

	public static void main(String[] args) {

		for (int ops : num_operations) {
			System.out.println("number of operations:" + ops);

			runBenchmark("CBPQ",ops);
			runBenchmark("CBPQ2",ops);
		}
	}

	private static void runBenchmark(String queueType,int num_operations) {
		System.out.printf("\nResults for: %s\n", queueType);
		System.out.printf("%-10s %-15s %-20s%n", "Threads", "Runtime (ms)", "Throughput (ops/sec)");

		for (int numThreads : NUM_THREADS) {
			AtomicInteger operationsCompleted = new AtomicInteger(0);
			Object pq = createQueue(queueType);
			Thread[] threads = new Thread[numThreads];
			long startTime = System.currentTimeMillis();

			for (int i = 0; i < numThreads; i++) {
				threads[i] = new Thread(() -> {
					int insertCount = (int) (num_operations * 0.9 / numThreads);
					int deleteCount = (int) (num_operations * 0.1 / numThreads);

					// הכנסת 70% מהאופרציות
					for (int j = 0; j < insertCount; j++) {
						insertToQueue(pq, j);
					}

					// הוצאת 30% מהאופרציות
					for (int j = 0; j < deleteCount; j++) {
						try {
							deleteMinFromQueue(pq);
						} catch (IllegalStateException e) {
							// התור ריק - מתעלמים
						}
					}

					operationsCompleted.incrementAndGet();
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

			long endTime = System.currentTimeMillis();
			long runtime = endTime - startTime;
			double throughput = (double) num_operations / runtime * 1000;

			System.out.printf("%-10d %-15d %-20.2f%n", numThreads, runtime, throughput);
			
		}
		System.out.println();
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
		}
	}

	private static void deleteMinFromQueue(Object pq) {
		if (pq instanceof CBPQ2) {
			((CBPQ2) pq).deleteMin();
		}
	}
}
