import java.util.List;

public class RaceReport {
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