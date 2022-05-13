package tunnel.synth;


import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class Analyser<S extends Analyser<S, T>, T> {


    private final List<Consumer<T>> listeners = new ArrayList<>();
    private final List<T> results = new ArrayList<>();
    private boolean recordResults = false;

    @SuppressWarnings("unchecked")
    public S addListener(Consumer<T> listener) {
        this.listeners.add(listener);
        return (S)this;
    }

    protected void submit(T result) {
        listeners.forEach(l -> l.accept(result));
        if (recordResults) {
            results.add(result);
        }
    }

    public void close() {}

    public void recordResults() {
        this.recordResults = true;
    }

    public List<T> getRecordedResults() {
        assert recordResults;
        return results;
    }
}
