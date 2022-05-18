package tunnel.synth.program;

import jdwp.Reply;
import jdwp.Request;
import jdwp.Value;
import jdwp.util.Pair;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@ToString
@EqualsAndHashCode
public class Scope {

    private final List<Map<String, Value>> scopes;
    private final List<Pair<? extends Request<?>, ? extends Reply>> captured;

    public Scope() {
        this.scopes = new ArrayList<>();
        scopes.add(new HashMap<>());
        this.captured = new ArrayList<>();
    }

    public void set(String variable, Value value) {
        getCurrentScope().put(variable, value);
    }

    public void addCaptured(Pair<? extends Request<?>, ? extends Reply> pair) {
        this.captured.add(pair);
    }

    public Value get(String variable) {
        for (int i = scopes.size() - 1; i > 0; i--) {
            var scope = scopes.get(i);
            if (scope.containsKey(variable)) {
                return scope.get(variable);
            }
        }
        throw new AssertionError();
    }

    public void push() {
        scopes.add(new HashMap<>());
    }

    public Map<String, Value> pop() {
        return scopes.remove(scopes.size() - 1);
    }

    private Map<String, Value> getCurrentScope() {
        return scopes.get(scopes.size() - 1);
    }
}
