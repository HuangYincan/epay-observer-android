package dev.epay.observer.sample;

/** Two independent field-read slots, monotonic deadline; no notification or I/O access. */
public final class ComparisonBudget {
    private final long started;
    private boolean stopped;
    private boolean historical;
    private boolean posted;
    public ComparisonBudget(long now) { started = now; }
    public boolean running(long now) {
        return !stopped && now >= started && now - started < 120000;
    }
    public boolean claim(boolean live, long now) {
        if (!running(now) || (live ? posted : historical)) return false;
        if (live) posted = true; else historical = true;
        return true;
    }
    public void stop() { stopped = true; }
    public int remaining(long now) {
        return running(now) ? (int)((120000 - (now - started)) / 1000) : 0;
    }
}
