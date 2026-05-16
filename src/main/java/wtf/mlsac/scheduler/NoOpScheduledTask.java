package wtf.mlsac.scheduler;

public final class NoOpScheduledTask implements ScheduledTask {
    public static final NoOpScheduledTask INSTANCE = new NoOpScheduledTask();

    private NoOpScheduledTask() {
    }

    @Override
    public void cancel() {
    }

    @Override
    public boolean isCancelled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return false;
    }
}
