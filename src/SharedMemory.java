import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class SharedMemory {
    public  final Map<Integer, Integer> data = new HashMap<>();
    public  final Lock lock = new ReentrantLock();
    public final RaceDetector raceDetector = new RaceDetector();

    public void write(int addr, int value) {
//        try {
//            lock.lock();
//            raceDetector.lockAcquired(lock);
//            raceDetector.memoryAccess(addr, RaceDetector.AccessType.WRITE)
//                    .ifPresent(System.out::println);
//            data.put(addr, value);
//        } finally {
//            raceDetector.lockReleased(lock);
//            lock.unlock();
//        }
        raceDetector.memoryAccess(addr, RaceDetector.AccessType.WRITE);
        data.put(addr, value);
    }

    public Integer read(int addr) {

        raceDetector.memoryAccess(addr, RaceDetector.AccessType.READ);
        return data.get(addr);
    }
}