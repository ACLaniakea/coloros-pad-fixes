package android.os;

/** Compile-only stub for the hidden framework class supplied by Android. */
public abstract class UEventObserver {
    public void startObserving(String match) {}
    public void stopObserving() {}
    public void onUEvent(UEvent event) {}

    public static final class UEvent {
        public String get(String key) { return null; }
        public String get(String key, String defaultValue) { return defaultValue; }
    }
}
