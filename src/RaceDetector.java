import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class RaceDetector {

    public enum State {
        VIRGIN,
        EXCLUSIVE,
        SHARED,
        MODIFIED
    }

    public enum AccessType {
        READ,
        WRITE
    }

    public static class AccessEntry {
        final long threadID;
        final AccessType accessType;
        final Set<Lock> heldLocks;

        AccessEntry(long threadID, AccessType accessType, Set<Lock> heldLocks) {
            this.threadID  = threadID;
            this.accessType = accessType;
            this.heldLocks = new HashSet<>(heldLocks);
        }


        @Override
        public String toString() {
            return String.format("Thread-%d: %s with locks %s",
                threadID,
                accessType,
                heldLocks.stream()
                        .map(lock -> String.format("Lock[%s]",
                                Integer.toHexString(System.identityHashCode(lock))))
                        .collect(Collectors.joining(", ", "[", "]")));
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
    private void updateState(int address, long threadID, AccessType accessType) {
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
                if (accessType == AccessType.WRITE) {
                    states.put(address, State.MODIFIED);
                }
                break;
            default:
                break;
        }
    }

    private void updateCandidateLockset(int address) {
        Set<Lock> currentLocks = new HashSet<>(threadLocks.get());
        candidateLocksets.computeIfAbsent(address, k -> new HashSet<>(currentLocks));
        candidateLocksets.compute(address, (key, existingLockset) -> {
            if (existingLockset == null) {
                return new HashSet<>(currentLocks);
            }else {
                existingLockset.retainAll(currentLocks);

                return existingLockset;
            }
        });
    }

    public void memoryAccess(int address, AccessType accessType) {
        long threadID = Thread.currentThread().getId();
        detectorLock.lock();

        try {
            updateState(address, threadID, accessType);
            updateCandidateLockset(address);

            AccessEntry entry = new AccessEntry(threadID, accessType, threadLocks.get());
            accessHistory.computeIfAbsent(address, k -> new ArrayList<>()).add(entry);

            if (candidateLocksets.get(address) != null &&
                    candidateLocksets.get(address).isEmpty() &&
                    (states.get(address) == State.SHARED || states.get(address) == State.MODIFIED)) {
                generateRaceReport(address);
            }
        } finally {
            detectorLock.unlock();
        }
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

    private String formatLock(Lock lock) {
        return String.format("Lock[%s]", Integer.toHexString(System.identityHashCode(lock)));
    }
}
