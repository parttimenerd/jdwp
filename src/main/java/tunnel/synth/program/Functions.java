package tunnel.synth.program;

import jdwp.AccessPath;
import jdwp.PrimitiveValue;
import jdwp.PrimitiveValue.*;
import jdwp.Reference;
import jdwp.Reference.*;
import jdwp.Value;
import jdwp.Value.*;
import jdwp.util.Pair;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import tunnel.synth.program.AST.InnerFunctionCall;
import tunnel.synth.program.AST.Literal;
import tunnel.synth.program.AST.StringLiteral;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static jdwp.util.Pair.p;
import static tunnel.synth.program.AST.ident;
import static tunnel.synth.program.AST.literal;

public class Functions {

    public static final String GET = "get";
    public static final String CONST = "const";
    public static final String WRAP = "wrap";

    private static final Map<String, Pair<Class<?>, java.util.function.Function<Long, ? extends BasicScalarValue<?>>>> integerWrapper = new HashMap<>();
    private static final Map<Class<?>, String> classToWrapperName = new HashMap<>();
    {
        integerWrapper.put("boolean", p(BooleanValue.class, v -> new BooleanValue(v != 0)));
        integerWrapper.put("byte", p(ByteValue.class, v -> new ByteValue((byte)(long)v)));
        integerWrapper.put("char", p(CharValue.class, v -> new CharValue((char)(long)v)));
        integerWrapper.put("short", p(ShortValue.class, v -> new ShortValue((short)(long)v)));
        integerWrapper.put("int", p(IntValue.class, v -> new IntValue((int)(long)v)));
        integerWrapper.put("long", p(LongValue.class, LongValue::new));

        integerWrapper.put("object", p(ObjectReference.class, Reference::object));
        integerWrapper.put("thread", p(ThreadReference.class, Reference::thread));
        integerWrapper.put("thread-group", p(ThreadGroupReference.class, Reference::threadGroup));
        integerWrapper.put("class-type", p(ClassTypeReference.class, Reference::classType));
        integerWrapper.put("interface-type", p(InterfaceTypeReference.class, Reference::interfaceType));
        integerWrapper.put("array-type", p(ArrayTypeReference.class, Reference::arrayType));
        integerWrapper.put("array", p(ArrayReference.class, Reference::array));
        integerWrapper.put("klass", p(ClassReference.class, Reference::klass));
        integerWrapper.put("interface", p(InterfaceReference.class, Reference::interfac));
        integerWrapper.put("classLoader", p(ClassLoaderReference.class, Reference::classLoader));
        integerWrapper.put("method", p(MethodReference.class, Reference::method));
        integerWrapper.put("module", p(ModuleReference.class, Reference::module));
        integerWrapper.put("field", p(FieldReference.class, Reference::field));
        integerWrapper.put("frame", p(FrameReference.class, Reference::frame));
        integerWrapper.put("classObject", p(ClassObjectReference.class, Reference::classObject));

        classToWrapperName.putAll(integerWrapper.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getValue().first, Entry::getKey)));
    }

    private static BasicScalarValue<?> applyIntegerWrapper(String wrapper, Long value) {
        if (integerWrapper.containsKey(wrapper)) {
            return integerWrapper.get(wrapper).second.apply(value);
        }
        if (wrapper.endsWith("-reference")) {
            var refType = wrapper.split("-")[0];
            return Reference.object(Type.valueOf(refType.toUpperCase()), value);
        }
        throw new AssertionError();
    }

    private static String getIntegerWrapperName(BasicScalarValue<?> value) {
        if (value instanceof ObjectReference) {
            if (value.type == Type.OBJECT) {
                return "object";
            }
            return value.type.name().toLowerCase() + "-reference";
        }
        return classToWrapperName.get(value.getClass());
    }

    public static String getWrapperName(BasicValue value) {
        if (value instanceof StringValue) {
            return "string";
        }
        if (value instanceof ByteList) {
            return "bytes";
        }
        return getIntegerWrapperName((BasicScalarValue<?>) value);
    }

    public static BasicValue applyWrapper(String wrapper, Object value) {
        assert value instanceof String || value instanceof Long;
        switch (wrapper) {
            case "string":
                return new StringValue((String)value);
            case "bytes":
                return new ByteList(((String)value).getBytes());
            default:
                if (!(value instanceof Long)) {
                    throw new AssertionError();
                }
                return applyIntegerWrapper(wrapper, (Long)value);
        }
    }

    public static InnerFunctionCall createWrapperFunctionCall(BasicValue value) {
        Literal<?> literal;
        if (value instanceof BasicScalarValue<?> && !(value instanceof StringValue)) {
            literal = literal((Long)((BasicScalarValue<?>) value).value);
        } else if (value instanceof StringValue) {
            literal = literal(((StringValue) value).value);
        } else if (value instanceof ByteList) {
            literal = literal(new String(((ByteList) value).bytes));
        } else {
            throw new AssertionError();
        }
        return new InnerFunctionCall(ident(WRAP), List.of(literal(getWrapperName(value)), literal));
    }

    /**
     * <code>get(value, path...)</code>
     */
    public static final Function GET_FUNCTION = new Function(GET) {
        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.isEmpty()) {
                throw new AssertionError();
            }
            if (arguments.size() == 1) {
                return arguments.get(0);
            }
            if (!arguments.stream().allMatch(a -> a instanceof PrimitiveValue.IntValue || a instanceof StringValue)) {
                throw new AssertionError();
            }
            var obj = arguments.get(0);
            if (!(obj instanceof WalkableValue<?>)) {
                throw new AssertionError();
            }
            var path = new AccessPath(arguments.stream().skip(1).map(s -> ((BasicScalarValue<?>)s).value).toArray());
            return path.access((WalkableValue<?>)obj);
        }
    };

    public static final Function CONST_FUNCTION = new Function(CONST) {
        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.size() != 1) {
                throw new AssertionError();
            }
            return arguments.get(0);
        }
    };

    /** wrap(type string, literal)*/
    public static final Function WRAP_FUNCTION = new Function(WRAP) {

        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.size() != 2) {
                throw new AssertionError();
            }
            if (!(arguments.get(0) instanceof StringValue)) {
                throw new AssertionError();
            }
            if (!(arguments.get(1) instanceof BasicScalarValue<?>)) {
                throw new AssertionError();
            }
            return applyWrapper(((StringValue) arguments.get(0)).value,
                    ((BasicScalarValue<?>) arguments.get(1)).value);
        }
    };

    @Getter
    @EqualsAndHashCode
    public static abstract class Function {
        private final String name;

        private Function(String name) {
            this.name = name;
        }

        public StringLiteral getNameLiteral() {
            return literal(name);
        }

        public abstract Value evaluate(List<Value> arguments);
    }

    public Function getFunction(String name) {
        switch (name) {
            case GET:
                return GET_FUNCTION;
            case CONST:
                return CONST_FUNCTION;
            case WRAP:
                return WRAP_FUNCTION;
            default:
                throw new AssertionError();
        }
    }
}
