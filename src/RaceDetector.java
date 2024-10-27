import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RaceDetector {

    public static void main(String[] args ) {
        SharedMemory sm = new SharedMemory();

        Thread t1 = new Thread(() -> {
            sm.write(1, 100);
            sm.read(1);
        });

        Thread t2 = new Thread(() -> {
            sm.write(1, 200);
            sm.read(1);
        });

        Thread t3 = new Thread(() -> {
            sm.write(1, 300);
            sm.read(1);
        });

        t1.start();
        t2.start();
        t3.start();

        try {
            t1.join();
            t2.join();
            t3.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        List<RaceReport> races = sm.raceDetector.checkRaces();
        System.out.println("Found: " + races.size() +" race conditions.");
        races.forEach(System.out::println);
    }
    private enum State {
        VIRGIN,
        EXCLUSIVE,
        SHARED,
        MODIFIED
    }

    private static class AccessEntry {
        final long threadID;
        final String accessType;
        final Set<Lock> heldLocks;

        AccessEntry(long threadID, String accessType, Set<Lock> heldLocks) {
            this.threadID  = threadID;
            this.accessType = accessType;
            this.heldLocks = heldLocks;
        }

        @Override
        public String toString() {
            return String.format("Thread-%d: %s with locks %s", threadID, accessType, heldLocks);
        }
    }

    // Maps the address to the state of the memory location
    private final ConcurrentHashMap<Integer, State> states = new ConcurrentHashMap<>();

    // Maps the address to the set of locks that are held by all threads that accessed the address
    private final ConcurrentHashMap<Integer, Set<Lock>> candidateLocksets = new ConcurrentHashMap<>();

    // Maps the address to the thread that first accessed it
    private final ConcurrentHashMap<Integer, Long> firstThreadAccess = new ConcurrentHashMap<>();

    // Maps the address to the list of access entries
    private  final ConcurrentHashMap<Integer, List<AccessEntry>> accessHistory = new ConcurrentHashMap<>();

    private final ReentrantLock detectorLock = new ReentrantLock();

    private static final ThreadLocal<Set<Lock>> threadLocks = ThreadLocal.withInitial(HashSet::new);

    public void lockAcquired(Lock lock) {
        threadLocks.get().add(lock);
    }

    public void lockReleased(Lock lock) {
        threadLocks.get().remove(lock);
    }

    // I implemented the state diagram from here https://courses.cs.vt.edu/cs5204/fall05-gback/presentations/Linford-Eraser/Presentation%20-%20Adobe%20PDF/Eraser%20Presentation.pdf
    private void updateState(int address, long threadID, String accessType) {
        State currentState = states.getOrDefault(address, State.VIRGIN);

        switch (currentState) {
            case VIRGIN:
                states.put(address, State.EXCLUSIVE);
                firstThreadAccess.put(address, threadID);
                break;
            case EXCLUSIVE:
                if (threadID != firstThreadAccess.get(address)) {
                    states.put(address, State.SHARED);
                }
                break;
            case SHARED:
                if (accessType.equals("write")) {
                    states.put(address, State.MODIFIED);
                }
                break;
            default:
                break;
        }
    }

    private void updateLockset(int address) {
        Set<Lock> currentLocks = new HashSet<>(threadLocks.get());

        candidateLocksets.compute(address, (key, existingLockset) -> {
            if (existingLockset == null) {
                return new HashSet<>(currentLocks);
            }else {
                // This is basicly the union, C(V) = C(V) ∩ candidateLockset
                existingLockset.retainAll(currentLocks);
                return existingLockset;
            }
        });
    }

    public Optional<RaceReport> memoryAccess(int address, String accessType) {
        long threadID = Thread.currentThread().getId();
        detectorLock.lock();

        try {
            updateState(address, threadID, accessType);
            updateLockset(address);

            accessHistory.computeIfAbsent(address, k -> new ArrayList<>()).add(new AccessEntry(threadID, accessType, threadLocks.get()));

            if (candidateLocksets.get(address) != null && candidateLocksets.get(address).isEmpty() && (states.get(address) ==State.SHARED || states.get(address) == State.MODIFIED)){
                return Optional.of(generateRaceReport(address));
            }
        } finally {
            detectorLock.unlock();
        }
        return Optional.empty();
    };

    private RaceReport generateRaceReport(int address) {
        return new RaceReport(address, states.get(address), accessHistory.get(address));
    }

    public List<RaceReport> checkRaces() {
        List<RaceReport> races = new ArrayList<>();
        detectorLock.lock();

        try {
            for (Integer address: candidateLocksets.keySet()) {
                Set<Lock> lockset = candidateLocksets.get(address);
                State state  = states.get(address);

                if (lockset != null && lockset.isEmpty() && (state == State.SHARED || state == State.MODIFIED)) {
                    races.add(generateRaceReport(address));
                }
            }
        }finally {
            detectorLock.unlock();
        }
        return races;
    }

    static class RaceReport {
        private final int address;
        private final RaceDetector.State state;
        private final List<RaceDetector.AccessEntry> accessHistory;

        public RaceReport(int address, RaceDetector.State state, List<RaceDetector.AccessEntry> accessHistory) {
            this.address = address;
            this.state = state;
            this.accessHistory = accessHistory;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Race condition detected at address %d%n", address));
            sb.append(String.format("Memory location state: %s%n", state));
            sb.append("Access history:%n");
            accessHistory.forEach(access ->
                    sb.append(String.format("  %s%n", access.toString())));
            return sb.toString();
        }
    }

}
