import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class SharedMemory {
    public  final Map<Integer, Integer> data = new HashMap<>();
    public  final Lock lock = new ReentrantLock();
    public final RaceDetector raceDetector = new RaceDetector();

    public void write(int addr, int value) {
        raceDetector.memoryAccess(addr, RaceDetector.AccessType.WRITE);
        data.put(addr, value);
    }

    public Integer read(int addr) {

        raceDetector.memoryAccess(addr, RaceDetector.AccessType.READ);
        return data.get(addr);
    }
}