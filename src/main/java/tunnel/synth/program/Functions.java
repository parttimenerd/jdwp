package tunnel.synth.program;

import jdwp.*;
import jdwp.Reference.*;
import jdwp.util.Pair;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.DependencyGraph;
import tunnel.synth.program.AST.Expression;
import tunnel.synth.program.AST.FunctionCall;
import tunnel.synth.program.AST.Literal;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.*;
import static jdwp.Value.BasicGroup.BYTE;
import static jdwp.Value.BasicGroup.STRING;
import static jdwp.util.Pair.p;
import static tunnel.synth.program.AST.ident;
import static tunnel.synth.program.AST.literal;
import static tunnel.synth.program.Evaluator.DEFAULT_ID;

public abstract class Functions {

    public static final String GET = "get";
    public static final String CONST = "const";
    public static final String WRAP = "wrap";

    private static final Map<String, Pair<Class<?>, java.util.function.Function<Long, ? extends BasicScalarValue<?>>>> integerWrapper = new HashMap<>();
    private static final Map<Class<?>, String> classToWrapperName = new HashMap<>();

    static {
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
        throw new AssertionError(String.format("No %s wrapper registered", wrapper));
    }

    private static String getIntegerWrapperName(BasicScalarValue<?> value) {
        if (value instanceof ObjectReference) {
            if (value.type == Type.OBJECT) {
                return "object";
            }
            return value.type.name().toLowerCase() + "-reference";
        }
        if (!classToWrapperName.containsKey(value.getClass())) {
            throw new AssertionError(String.format("Cannot find wrapper for %s", value.toCode()));
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
                return new ByteList(Base64.getDecoder().decode((String)value));
            default:
                if (!(value instanceof Long)) {
                    throw new AssertionError(String.format("Integer value is not long: %s", value));
                }
                return applyIntegerWrapper(wrapper, (Long)value);
        }
    }

    public static FunctionCall createWrapperFunctionCall(BasicValue value) {
        Literal<?> literal;
        if (value instanceof BasicScalarValue<?> && !(value instanceof StringValue)) {
            var val = ((BasicScalarValue<?>) value).value;
            if (val instanceof Boolean) {
                literal = literal((Boolean) val ? 1 : 0);
            } else {
                literal = literal(((Number) val).longValue());
            }
        } else if (value instanceof StringValue) {
            literal = literal(((StringValue) value).value);
        } else if (value instanceof ByteList) {
            literal = literal(Base64.getEncoder().encodeToString(((ByteList) value).bytes));
        } else {
            throw new AssertionError(String.format("Unknown basic type for wrapping: %s", value));
        }
        return new FunctionCall(WRAP, WRAP_FUNCTION, List.of(literal(getWrapperName(value)), literal));
    }

    /**
     * <code>get(value, path...)</code>
     */
    public static final Function GET_FUNCTION = new Function(GET) {
        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.isEmpty()) {
                throw new AssertionError("No arguments for get function");
            }
            if (arguments.size() == 1) {
                return arguments.get(0);
            }
            if (!arguments.stream().skip(1).allMatch(a -> a instanceof PrimitiveValue.IntValue || a instanceof StringValue || a instanceof LongValue)) {
                throw new AssertionError(String.format("Invalid path %s", arguments.subList(1, arguments.size())));
            }
            var obj = arguments.get(0);
            if (!(obj instanceof WalkableValue<?>)) {
                throw new AssertionError(String.format(String.format("Base object %s is not walkable", obj)));
            }
            var path = new AccessPath(arguments.stream().skip(1).map(s -> ((BasicScalarValue<?>)s).value).map(s -> s instanceof Long ? (int)(long)s : s).toArray());
            return path.access((WalkableValue<?>)obj);
        }
    };

    public static FunctionCall createGetFunctionCall(String root, AccessPath path) {
        List<Expression> args = new ArrayList<>();
        args.add(ident(root));
        path.stream().map(e -> e instanceof String ? literal((String)e) : literal((int)e)).forEach(args::add);
        return new FunctionCall(GET, args);
    }

    public static final Function CONST_FUNCTION = new Function(CONST) {
        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.size() != 1) {
                throw new AssertionError(String.format("Const function expects one argument, got %s", arguments));
            }
            return arguments.get(0);
        }
    };

    /** wrap(type string, literal)*/
    public static final Function WRAP_FUNCTION = new Function(WRAP) {

        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.size() != 2) {
                throw new AssertionError(String.format("More than two arguments for wrap function: %s", arguments));
            }
            if (!(arguments.get(0) instanceof StringValue)) {
                throw new AssertionError(String.format("First argument %s of wrap function is not a string",
                        arguments.get(0)));
            }
            if (!(arguments.get(1) instanceof BasicScalarValue<?>)) {
                throw new AssertionError(String.format("Second argument %s of wrap function is not a basic scalar " +
                        "value", arguments.get(1)));
            }
            return applyWrapper(((StringValue) arguments.get(0)).value,
                    ((BasicScalarValue<?>) arguments.get(1)).value);
        }
    };

    @SuppressWarnings("unchecked")
    public Value processRequest(
            String commandSet, String command, Stream<TaggedBasicValue<?>> values) {
        try {
            return this.processRequest(
                    (Request<?>)
                            AbstractParsedPacket.createForTagged(
                                    DEFAULT_ID,
                                    (Class<AbstractParsedPacket>)
                                            Class.forName(String.format("jdwp.%sCmds$%sRequest", commandSet, command)),
                                    values));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    protected abstract Value processRequest(Request<?> request);

    @Getter
    @EqualsAndHashCode
    public static abstract class Function {
        private final String name;

        public Function(String name) {
            this.name = name;
        }

        public Value evaluate(VM vm, List<Value> arguments) {
            return evaluate(arguments);
        }

        /**
         * do not call this method, it's called by the other evaluate method
         */
        protected Value evaluate(List<Value> arguments) {
            throw new AssertionError();
        }
    }

    /**
     * functions with a single argument and a checked argument type
     */
    public static abstract class SingleArgumentFunction<T extends Value> extends Function {

        protected final Class<?> expectedType;

        public SingleArgumentFunction(String name, Class<T> expectedType) {
            super(name);
            this.expectedType = expectedType;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Value evaluate(VM vm, List<Value> arguments) {
            if (arguments.size() != 1) {
                throw new AssertionError(String.format("Function %s expected one argument but got %s", this,
                        arguments));
            }
            Value argument = arguments.get(0);
            if (!expectedType.isAssignableFrom(argument.getClass())) {
                throw new AssertionError(String.format("Function %s expected an instance of %s but got %s",
                        this, expectedType, argument));
            }
            return evaluate(vm, (T) argument);
        }

        protected abstract Value evaluate(VM vm, T value);
    }

    /**
     * (argument group, result group) -> transformer
     */
    private static final Map<Pair<BasicGroup, BasicGroup>, Set<BasicValueTransformer<?>>> transformersPerGroups
            = new HashMap<>();
    private static final Map<BasicGroup, Set<BasicValueTransformer<?>>> generalTransformersPerResultGroup = new HashMap<>();
    public static final Map<String, BasicValueTransformer<?>> transformersPerName = new HashMap<>();

    /**
     * transforms a specific basic value into another, used to annotate edges in the
     * {@link DependencyGraph}
     */
    public static abstract class BasicValueTransformer<T extends BasicValue> extends SingleArgumentFunction<T> {

        private final @Nullable BasicGroup argument;
        private final BasicGroup result;

        @SuppressWarnings("unchecked")
        public BasicValueTransformer(String name, @Nullable BasicGroup argument, BasicGroup result) {
            super(name, argument == null ? (Class<T>) BasicValue.class : (Class<T>) argument.getBaseClass());
            this.argument = argument;
            this.result = result;
            if (argument != null) {
                transformersPerGroups.computeIfAbsent(p(argument, result), x -> new HashSet<>()).add(this);
            } else {
                generalTransformersPerResultGroup.computeIfAbsent(result, x -> new HashSet<>()).add(this);
            }
            transformersPerName.put(name, this);
        }

        public boolean isApplicable(BasicValue value) {
            return expectedType.isAssignableFrom(value.getClass());
        }

        /**
         * is this function applicable to multiple value types?
         */
        public boolean isGeneral() {
            return argument == null;
        }
    }

    /**
     * signature -> tag
     */
    public static final BasicValueTransformer<StringValue> GET_TAG_FOR_SIGNATURE =
            new BasicValueTransformer<>("getTagForSignature", STRING, BYTE) {
                @Override
                protected Value evaluate(VM vm, StringValue value) {
                    return wrap(new JNITypeParser(value.getValue()).jdwpTag());
                }
            };

    /**
     * value -> tag of value
     */
    public static final BasicValueTransformer<BasicValue> GET_TAG_FOR_VALUE =
            new BasicValueTransformer<>("getTagForValue", null, BYTE) {
                @Override
                protected Value evaluate(VM vm, BasicValue value) {
                    return wrap((byte) value.type.getTag());
                }
            };

    public static Set<BasicValueTransformer<?>> getTransformers(BasicGroup argument, BasicGroup result) {
        var ret = new HashSet<>(transformersPerGroups.getOrDefault(p(argument, result), Set.of()));
        ret.addAll(generalTransformersPerResultGroup.getOrDefault(result, Set.of()));
        return ret;
    }

    /**
     * return all transformers f with <pre>f(argument) == result</pre>
     */
    @SuppressWarnings("unchecked")
    public static Set<BasicValueTransformer<?>> getTransformers(VM vm, BasicValue argument, BasicValue result) {
        return getTransformers(argument.getGroup(), result.getGroup()).stream()
                .filter(t -> t.isApplicable(argument) && ((BasicValueTransformer<BasicValue>) t)
                        .evaluate(vm, argument).equals(result)).collect(Collectors.toSet());
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
                if (transformersPerName.containsKey(name)) {
                    return transformersPerName.get(name);
                }
                throw new AssertionError(String.format("Unknown function %s", name));
        }
    }
}
