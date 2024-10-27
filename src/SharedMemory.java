import java.util.*;

class SharedMemory {
    public  final Map<Integer, Integer> data = new HashMap<>();
    public final RaceDetector raceDetector = new RaceDetector();

    public void write(int addr, int value) {
        raceDetector.memoryAccess(addr, RaceDetector.AccessType.WRITE);
        data.put(addr, value);
    }

    public void read(int addr) {
        raceDetector.memoryAccess(addr, RaceDetector.AccessType.READ);
        data.get(addr);
    }
}