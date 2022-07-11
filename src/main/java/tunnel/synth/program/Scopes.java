package tunnel.synth.program;

import jdwp.exception.TunnelException.UnknownVariableException;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

@Getter
@ToString
@EqualsAndHashCode
public class Scopes<V> {

    @AllArgsConstructor
    @Getter
    public static class BasicScope<T> {
        @Nullable private final BasicScope<T> parent;
        private final Map<String, T> variables;

        public BasicScope() {
            this(null, new HashMap<>());
        }

        public BasicScope(BasicScope<T> parent) {
            this(parent, new HashMap<>());
        }

        public boolean isRoot() {
            return parent == null;
        }

        public T get(String variable) {
            if (!variables.containsKey(variable)) {
                if (parent == null) {
                    throw new UnknownVariableException(variable);
                }
                return parent.get(variable);
            }
            return variables.get(variable);
        }

        public boolean contains(String variable) {
            if (!variables.containsKey(variable)) {
                if (parent == null) {
                    return false;
                }
                return parent.contains(variable);
            }
            return variables.containsKey(variable);
        }

        public void put(String variable, T value) {
            variables.put(variable, value);
        }
    }

    private BasicScope<V> topScope;

    public Scopes() {
        this.topScope = new BasicScope<>();
    }

    public void put(String variable, V value) {
        topScope.put(variable, value);
    }

    public V get(String variable) {
        return topScope.get(variable);
    }

    public void push() {
        topScope = new BasicScope<>(topScope);
    }

    public BasicScope<V> pop() {
        var scope = topScope;
        topScope = topScope.parent;
        return scope;
    }

    public boolean contains(String variable) {
        return topScope.contains(variable);
    }

    public boolean hasParent() {
        return topScope.parent != null;
    }

    public BasicScope<V> getParent() {
        return topScope.parent;
    }
}
