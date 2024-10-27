import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class SharedMemory {
    public  final Map<Integer, Integer> data = new HashMap<>();
    public  final Lock lock = new ReentrantLock();
    public final RaceDetector raceDetector = new RaceDetector();

    public void write(int addr, int value) {
        try {
            lock.lock();
            raceDetector.lockAcquired(lock);
            raceDetector.memoryAccess(addr, "write")
                    .ifPresent(System.out::println);
            data.put(addr, value);
        } finally {
            raceDetector.lockReleased(lock);
            lock.unlock();
        }
    }

    public Integer read(int addr) {
        try {
            lock.lock();
            raceDetector.lockAcquired(lock);
            raceDetector.memoryAccess(addr, "read")
                    .ifPresent(System.out::println);
            return data.get(addr);
        } finally {
            raceDetector.lockReleased(lock);
            lock.unlock();
        }
    }
}