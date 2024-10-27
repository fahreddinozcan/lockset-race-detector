import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class RaceDetector {

    public static void main(String[] args ) {
        SharedMemory sm = new SharedMemory();

        Lock lock1 = new ReentrantLock();
        Lock lock2 = new ReentrantLock();


        // Thread 1 uses lock1 for all operations
        Thread t1 = new Thread(() -> {
            lock1.lock();
            sm.raceDetector.lockAcquired(lock1);

            sm.write(1, 100);
            sm.read(1);

            sm.raceDetector.lockReleased(lock1);
            lock1.unlock();
        });

        // Thread 2 uses lock2 for all operations on the same memory address
        Thread t2 = new Thread(() -> {
            lock2.lock();
            sm.raceDetector.lockAcquired(lock2);

            sm.write(1, 200);
            sm.read(1);

            sm.raceDetector.lockReleased(lock2);
            lock2.unlock();
        });

        runAndWaitThreads(t1, t2);

        List<RaceReport> races = sm.raceDetector.checkRaces();
        System.out.println("Found: " + races.size() +" race conditions.");
        races.forEach(System.out::println);
    }

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
            this.heldLocks = heldLocks;
        }

//        @Override
//        public String toString() {
//            return String.format("Thread-%d: %s with locks %s", threadID, accessType, heldLocks);
//        }
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
/*        System.out.println("[RaceDetector] Thread-" + Thread.currentThread().getId() +
                " acquired " + formatLock(lock));
        System.out.println("[RaceDetector] Thread-" + Thread.currentThread().getId() +
                " current locks: " + formatLockSet(threadLocks.get()));*/
    }

    public void lockReleased(Lock lock) {
        threadLocks.get().remove(lock);
//        System.out.println("[RaceDetector] Thread-" + Thread.currentThread().getId() +
//                " released " + formatLock(lock));
//        System.out.println("[RaceDetector] Thread-" + Thread.currentThread().getId() +
//                " remaining locks: " + formatLockSet(threadLocks.get()));
    }

    // I implemented the state diagram from here https://courses.cs.vt.edu/cs5204/fall05-gback/presentations/Linford-Eraser/Presentation%20-%20Adobe%20PDF/Eraser%20Presentation.pdf
    private void updateState(int address, long threadID, AccessType accessType) {
        State currentState = states.getOrDefault(address, State.VIRGIN);
//        System.out.println("[RaceDetector] Address " + address + " current state: " + currentState);

        switch (currentState) {
            case VIRGIN:
                states.put(address, State.EXCLUSIVE);
                firstThreadAccess.put(address, threadID);
//                System.out.println("[RaceDetector] Address " + address + " current state: " + states.get(address));
                break;
            case EXCLUSIVE:
                if (threadID != firstThreadAccess.get(address)) {
                    states.put(address, State.SHARED);
                }
//                System.out.println("[RaceDetector] Address " + address + " moved to SHARED state by Thread-" + threadID);
                break;
            case SHARED:
                if (accessType == AccessType.WRITE) {
                    states.put(address, State.MODIFIED);
                }
//                System.out.println("[RaceDetector] Address " + address + " moved to MODIFIED state by Thread-" + threadID + " (WRITE operation)");
                break;
            default:
                break;
        }
    }

    private void updateCandidateLockset(int address) {
        Set<Lock> currentLocks = new HashSet<>(threadLocks.get());
//        System.out.println("[RaceDetector] Updating lockset for address " + address);
//        System.out.println("[RaceDetector] Thread-" + Thread.currentThread().getId() +
//                " current locks: " + formatLockSet(currentLocks));

        candidateLocksets.computeIfAbsent(address, k -> new HashSet<>(currentLocks));

        Set<Lock> previousLockset = candidateLocksets.get(address);
//        System.out.println("[RaceDetector] Previous lockset for address " + address +
//                ": " + formatLockSet(previousLockset));

        candidateLocksets.compute(address, (key, existingLockset) -> {
            if (existingLockset == null) {
                return new HashSet<>(currentLocks);
            }else {
                // This is basicly the union, C(V) = C(V) ∩ candidateLockset
                existingLockset.retainAll(currentLocks);
//                System.out.println("[RaceDetector] Updated lockset for address " + address +
//                        ": " + formatLockSet(existingLockset));
                return existingLockset;
            }
        });

//        System.out.println("THREAD"+Thread.currentThread().getId()+" | Candidate Lockset after: " + candidateLocksets.get(address));

    }

    public Optional<RaceReport> memoryAccess(int address, AccessType accessType) {
        long threadID = Thread.currentThread().getId();
        detectorLock.lock();

//        System.out.println("Thread-" + threadID + " is accessing address " + address + " with " + accessType + " operation.");
        try {
            updateState(address, threadID, accessType);
            updateCandidateLockset(address);

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

    private static void runAndWaitThreads(Thread... threads) {
        for (Thread t: threads) {
            t.start();
        }

        for (Thread t: threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private String formatLock(Lock lock) {
        return String.format("Lock[%s]", Integer.toHexString(System.identityHashCode(lock)));
    }

    private String formatLockSet(Set<Lock> locks) {
        if (locks == null || locks.isEmpty()) return "[]";
        return locks.stream()
                .map(this::formatLock)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
