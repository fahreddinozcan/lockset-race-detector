import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

class RaceDetectorTest {
    private SharedMemory sm;
    private RaceDetector detector;

    @BeforeEach
    void setUp() {
        sm = new SharedMemory();
        detector = sm.raceDetector;
    }

    @Test
    void testNoRaceConditionWithSameLock() throws InterruptedException {
        Lock commonLock = new ReentrantLock();

        Thread t1 = new Thread(() -> {
            commonLock.lock();
            detector.lockAcquired(commonLock);

            sm.write(1, 100);
            sm.read(1);

            detector.lockReleased(commonLock);
            commonLock.unlock();
        });

        Thread t2 = new Thread(() -> {
            commonLock.lock();
            detector.lockAcquired(commonLock);

            sm.write(1, 200);
            sm.read(1);

            detector.lockReleased(commonLock);
            commonLock.unlock();
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        List<RaceReport> races = detector.checkRaces();
        System.out.println("Found: " + races.size()+ " race conditions.");
        assertTrue(races.isEmpty(), "No race condition should be detected when using the same lock");
    }

    @Test
    void testRaceConditionWithDifferentLocks() throws InterruptedException {
        Lock lock1 = new ReentrantLock();
        Lock lock2 = new ReentrantLock();

        Thread t1 = new Thread(() -> {
            lock1.lock();
            detector.lockAcquired(lock1);

            sm.write(1, 100);
            sm.read(1);

            detector.lockReleased(lock1);
            lock1.unlock();
        });

        Thread t2 = new Thread(() -> {
            lock2.lock();
            detector.lockAcquired(lock2);

            sm.write(1, 200);
            sm.read(1);

            detector.lockReleased(lock2);
            lock2.unlock();
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        List<RaceReport> races = detector.checkRaces();
        System.out.println("Found: " + races.size()+ " race conditions.");
        assertFalse(races.isEmpty(), "Race condition should be detected when using different locks");
        assertEquals(1, races.size(), "Should detect exactly one race condition");
    }

    @Test
    void testMultipleLockScenario() throws InterruptedException {
        Lock lock1 = new ReentrantLock();
        Lock lock2 = new ReentrantLock();
        Lock lock3 = new ReentrantLock();

        Thread t1 = new Thread(() -> {
            lock1.lock();
            lock2.lock();
            detector.lockAcquired(lock1);
            detector.lockAcquired(lock2);

            sm.write(1, 100);

            detector.lockReleased(lock1);
            lock1.unlock();

            sm.read(1);

            detector.lockReleased(lock2);
            lock2.unlock();
        });

        Thread t2 = new Thread(() -> {
            lock2.lock();
            lock3.lock();
            detector.lockAcquired(lock2);
            detector.lockAcquired(lock3);

            sm.write(1, 200);

            detector.lockReleased(lock2);
            lock2.unlock();

            sm.read(1);

            detector.lockReleased(lock3);
            lock3.unlock();
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        List<RaceReport> races = detector.checkRaces();
        System.out.println("Found: " + races.size()+ " race conditions.");
        assertFalse(races.isEmpty(), "Should detect race conditions when lock sets don't intersect");
    }

    @Test
    void testMultipleRaceConditions() throws InterruptedException {
        Lock lock1 = new ReentrantLock();
        Lock lock2 = new ReentrantLock();
        Lock lock3 = new ReentrantLock();

        // Thread 1: Uses lock1 for address 1, no lock for address 2
        Thread t1 = new Thread(() -> {
            // Access address 1 with lock1
            lock1.lock();
            detector.lockAcquired(lock1);
            sm.write(1, 100);
            detector.lockReleased(lock1);
            lock1.unlock();

            // Access address 2 with no lock
            sm.write(2, 100);
            sm.read(2);
        });

        // Thread 2: Uses lock2 for address 1, lock3 for address 2
        Thread t2 = new Thread(() -> {
            // Access address 1 with lock2 (different from t1)
            lock2.lock();
            detector.lockAcquired(lock2);
            sm.write(1, 200);
            detector.lockReleased(lock2);
            lock2.unlock();

            // Access address 2 with lock3
            lock3.lock();
            detector.lockAcquired(lock3);
            sm.write(2, 200);
            detector.lockReleased(lock3);
            lock3.unlock();
        });

        // Thread 3: Uses both lock1 and lock2 for address 1, no lock for address 2
        Thread t3 = new Thread(() -> {
            // Access address 1 with both locks
            lock1.lock();
            lock2.lock();
            detector.lockAcquired(lock1);
            detector.lockAcquired(lock2);
            sm.read(1);
            detector.lockReleased(lock2);
            detector.lockReleased(lock1);
            lock2.unlock();
            lock1.unlock();

            // Access address 2 with no lock
            sm.read(2);
        });

        // Start all threads
        t1.start();
        t2.start();
        t3.start();

        // Wait for completion
        t1.join();
        t2.join();
        t3.join();

        // Check for race conditions
        List<RaceReport> races = detector.checkRaces();
        System.out.println("\nFound " + races.size() + " race conditions:");
        races.forEach(race -> System.out.println("\n" + race));

        // Verify race conditions were detected
        assertTrue(races.size() >= 2,
                "Should detect at least 2 race conditions (one for each address)");

        // Optional: More specific assertions about the races found
        boolean foundAddress1Race = false;
        boolean foundAddress2Race = false;

        for (RaceReport race : races) {
            if (race.getAddress() == 1) foundAddress1Race = true;
            if (race.getAddress() == 2) foundAddress2Race = true;
        }

        assertTrue(foundAddress1Race, "Should detect race on address 1");
        assertTrue(foundAddress2Race, "Should detect race on address 2");
    }



    @Test
    void testDifferentMemoryLocations() throws InterruptedException {
        Lock lock1 = new ReentrantLock();
        Lock lock2 = new ReentrantLock();

        Thread t1 = new Thread(() -> {
            lock1.lock();
            detector.lockAcquired(lock1);
            sm.write(1, 100);
            detector.lockReleased(lock1);
            lock1.unlock();
        });

        Thread t2 = new Thread(() -> {
            lock2.lock();
            detector.lockAcquired(lock2);
            sm.write(2, 200);  // Different memory location
            detector.lockReleased(lock2);
            lock2.unlock();
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        List<RaceReport> races = detector.checkRaces();
        System.out.println("Found: " + races.size()+ " race conditions.");
        assertTrue(races.isEmpty(), "No race condition should be detected for different memory locations");
    }
}