package tunnel.synth.program;

import jdwp.Value;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import tunnel.synth.program.AST.StringLiteral;

import java.util.List;

public abstract class Functions {

    public static final String GET = "get";

    @Getter
    @EqualsAndHashCode
    public static abstract class Function {
        private final String name;

        private Function(String name) {
            this.name = name;
        }

        public StringLiteral getNameLiteral() {
            return AST.literal(name);
        }

        public abstract Value evaluate(List<Value> arguments);
    }

    /**
     * <code>get(value, path...)</code>
     */
    public abstract Function get();

    /**
     * <code>request("command set", "command", args...)</code>
     */
    public abstract Function request();

    public Function constant() {
        return new Function("const") {
            @Override
            public Value evaluate(List<Value> arguments) {
                if (arguments.size() != 1) {
                    throw new AssertionError();
                }
                return arguments.get(0);
            }
        };
    }

    public Function getFunction(String name) {
        switch (name) {
            case GET:
                return get();
            case "request":
                return request();
            case "const":
                return constant();
            default:
                throw new AssertionError();
        }
    }
}
