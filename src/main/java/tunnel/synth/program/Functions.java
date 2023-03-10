package tunnel.synth.program;

import jdwp.*;
import jdwp.Reference.*;
import jdwp.exception.TunnelException.FunctionsException;
import jdwp.exception.TunnelException.UnsupportedOperationException;
import jdwp.util.Pair;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.DependencyGraph;
import tunnel.synth.program.Evaluator.MapCallResultEntry;
import tunnel.synth.program.Visitors.ReturningExpressionVisitor;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.*;
import static jdwp.Value.BasicGroup.BYTE;
import static jdwp.Value.BasicGroup.STRING;
import static jdwp.util.Pair.p;
import static tunnel.synth.program.AST.*;

public abstract class Functions {

    public static final String GET = "get";
    public static final String CONST = "const";
    public static final String WRAP = "wrap";
    public static final String OBJECT = "object";

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
        integerWrapper.put("void", p(VoidValue.class, x -> VoidValue.VALUE));

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
        throw new FunctionsException(String.format("No %s wrapper registered", wrapper));
    }

    private static String getIntegerWrapperName(BasicScalarValue<?> value) {
        if (value instanceof ObjectReference) {
            if (value.type == Type.OBJECT) {
                return "object";
            }
            return value.type.name().toLowerCase() + "-reference";
        }
        if (!classToWrapperName.containsKey(value.getClass())) {
            throw new FunctionsException(String.format("Cannot find wrapper for %s", value.toCode()));
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
                    throw new FunctionsException(String.format("Integer value is not long: %s", value));
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
                literal = literal(val instanceof Character ? (char)val : ((Number)val).longValue());
            }
        } else if (value instanceof StringValue) {
            literal = literal(((StringValue) value).value);
        } else if (value instanceof ByteList) {
            literal = literal(Base64.getEncoder().encodeToString(((ByteList) value).bytes));
        } else {
            throw new FunctionsException(String.format("Unknown basic type for wrapping: %s", value));
        }
        return new FunctionCall(WRAP, WRAP_FUNCTION, List.of(literal(getWrapperName(value)), literal));
    }

    public static class GetFunction extends Function {

        private GetFunction() {
            super(GET);
        }

        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.isEmpty()) {
                throw new FunctionsException("No arguments for get function");
            }
            if (arguments.size() == 1) {
                return arguments.get(0);
            }
            if (!arguments.stream().skip(1).allMatch(a -> a instanceof PrimitiveValue.IntValue || a instanceof StringValue || a instanceof LongValue)) {
                throw new FunctionsException(String.format("Invalid path %s", arguments.subList(1, arguments.size())));
            }
            var obj = arguments.get(0);
            if (!(obj instanceof WalkableValue<?>)) {
                throw new FunctionsException(String.format(String.format("Base object %s is not walkable", obj)));
            }
            var path =
                    new AccessPath(arguments.stream().skip(1).map(s -> ((BasicScalarValue<?>) s).value).map(s -> s instanceof Long ? (int) (long) s : s).toArray());
            return path.access((WalkableValue<?>) obj);
        }

        public FunctionCall createCall(String root, AccessPath path) {
            return createCall(ident(root), path);
        }

        public FunctionCall createCall(Expression root, AccessPath path) {
            List<Expression> args = new ArrayList<>();
            args.add(root);
            path.stream().map(e -> e instanceof String ? literal((String) e) : literal((int) e)).forEach(args::add);
            var call = new FunctionCall(getName(), args);
            call.setFunction(this);
            return call;
        }

        public String getAccessedVariable(FunctionCall getCall) {
            assert getCall.getFunction() == this;
            return ((StringLiteral) getCall.getArguments().get(0)).value;
        }

        public AccessPath getAccessPath(FunctionCall getCall) {
            assert getCall.getFunction() == this;
            return new AccessPath(getCall.getArguments().stream().skip(1).map(s -> ((Literal<?>) s).value)
                    .map(s -> s instanceof IntegerLiteral ? (int) (long) s : ((StringLiteral) s).value)
                    .toArray(Object[]::new));
        }
    }

    public static FunctionCall createGetFunctionCall(String root, AccessPath path) {
        return GET_FUNCTION.createCall(root, path);
    }

    /**
     * <code>get(value, path...)</code>
     */
    public static final GetFunction GET_FUNCTION = new GetFunction();

    public static final Function CONST_FUNCTION = new Function(CONST) {
        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.size() != 1) {
                throw new FunctionsException(String.format("Const function expects one argument, got %s", arguments));
            }
            return arguments.get(0);
        }
    };

    /** wrap(type string, literal)*/
    public static final Function WRAP_FUNCTION = new Function(WRAP) {

        @Override
        public Value evaluate(List<Value> arguments) {
            if (arguments.size() != 2) {
                throw new FunctionsException(String.format("More than two arguments for wrap function: %s", arguments));
            }
            if (!(arguments.get(0) instanceof StringValue)) {
                throw new FunctionsException(String.format("First argument %s of wrap function is not a string",
                        arguments.get(0)));
            }
            if (!(arguments.get(1) instanceof BasicScalarValue<?>)) {
                throw new FunctionsException(String.format("Second argument %s of wrap function is not a basic scalar " +
                        "value", arguments.get(1)));
            }
            return applyWrapper(((StringValue) arguments.get(0)).value,
                    ((BasicScalarValue<?>) arguments.get(1)).value);
        }
    };

    private static Map<Object, Value>
    groupByPathIndexAndCombine(List<TaggedValue<?>> values, int index) {
        return values.stream().collect(Collectors.groupingBy(v -> v.getPath().get(index),
                Collectors.collectingAndThen(Collectors.toList(), vals -> {
                    if (vals.stream().anyMatch(v -> v.getPath().size() > index + 1)) {
                        return createListOrCombined(vals, index + 1);
                    }
                    if (vals.size() == 1) {
                        return vals.get(0).getValue();
                    } else {
                        throw new FunctionsException(String.format("Multiple values for path index %s", index));
                    }
                })));
    }

    private static WalkableValue<?> createListOrCombined(List<TaggedValue<?>> values, int index) {
        Map<Object, Value> grouped;
        if (values.isEmpty()) {
            grouped = Map.of();
        } else if (values.stream().allMatch(v -> index >= v.getPath().size() - 1)) {
            grouped = values.stream().collect(Collectors.toMap(v -> v.getPath().get(index), TaggedValue::getValue));
        } else {
            grouped = groupByPathIndexAndCombine(values, index);
        }
        if (values.isEmpty() || AccessPath.isListAccess(values.get(0).getPath().get(index))) {
            return createList(grouped);
        }
        return createCombined(grouped);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CombinedValue createCombined(Map<Object, Value> values) {
        if (values.entrySet().stream().anyMatch(e -> AccessPath.isListAccess(e.getKey()))) {
            throw new FunctionsException(
                    String.format("Expected field accesss for combined value creation, but got %s", values.keySet()));
        }
        return new MapCallResultEntry((Map<String, Value>)(Map)values);
    }

    private static ListValue<?> createList(Map<Object, Value> values) {
        if (values.keySet().stream().anyMatch(k -> !AccessPath.isListAccess(k))) {
            throw new FunctionsException(
                    String.format("Expected list access for combined value creation, but got %s", values.keySet()));
        }
        int length = values.keySet().stream().mapToInt(v -> (int) v).max().orElse(0) + 1;
        var sorted =
                values.entrySet().stream().sorted(Comparator.comparing(e -> (int) e.getKey()))
                        .map(Entry::getValue).collect(Collectors.toList());
        if (sorted.size() != length) {
            throw new FunctionsException(String.format("Expected %s values for list, got %s", length, sorted.size()));
        }
        var type = Type.OBJECT;
        if (length > 0 && sorted.stream().allMatch(v -> v.type == sorted.get(0).type)) {
            type = sorted.get(0).type;
        }
        return new ListValue<>(type, sorted);
    }

    /** function with call property arguments to construct nested objects and lists */
    public static final Function OBJECT_FUNCTION = new Function(OBJECT) {

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override
        public List<Value> evaluateArguments(Scopes<Value> scope, List<Expression> arguments, Evaluator evaluator,
                                             ReturningExpressionVisitor<Value> expressionEvaluator) {
            if (arguments.stream().anyMatch(a -> !(a instanceof CallProperty))) {
                throw new AssertionError("Expected call property arguments for object function");
            }
            return List.of(createListOrCombined(evaluator.evaluateCallProperties(scope,
                    (List<CallProperty>) (List) arguments).collect(Collectors.toList()), 0));
        }

        @Override
        protected Value evaluate(List<Value> arguments) {
            return arguments.get(0);
        }
    };

    public static FunctionCall createObjectFunctionCall(WalkableValue<?> value) {
        return new FunctionCall(OBJECT, OBJECT_FUNCTION, value.getTaggedValues()
                .map(t -> new CallProperty(t.getPath(), createWrapperFunctionCall(t.getValue())))
                .collect(Collectors.toList()));
    }

    public static FunctionCall createWrappingFunctionCall(Value value) {
        if (value instanceof BasicValue) {
            return createWrapperFunctionCall((BasicValue) value);
        } else if (value instanceof WalkableValue) {
            return createObjectFunctionCall((WalkableValue<?>) value);
        } else {
            throw new FunctionsException(String.format("Unexpected value type %s", value.getClass()));
        }
    }

    public Optional<Value> processRequest(RequestCall call, Request<?> request) {
        return processRequest(request);
    }

    protected Optional<Value> processRequest(Request<?> request) {
        return Optional.empty();
    }

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
            throw new UnsupportedOperationException();
        }

        public List<Value> evaluateArguments(Scopes<Value> scope, List<Expression> arguments,
                                      Evaluator evaluator, ReturningExpressionVisitor<Value> expressionEvaluator) {
            return arguments.stream().map(e -> e.accept(expressionEvaluator)).collect(Collectors.toList());
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
                throw new FunctionsException(String.format("Function %s expected one argument but got %s", this,
                        arguments));
            }
            Value argument = arguments.get(0);
            if (!expectedType.isAssignableFrom(argument.getClass())) {
                throw new FunctionsException(String.format("Function %s expected an instance of %s but got %s",
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

    public static final Map<String, BasicValueTransformer<?>> basicTransformersPerName = new HashMap<>();

    public static abstract class ValueTransformer<T extends Value> extends SingleArgumentFunction<T>
            implements Comparable<ValueTransformer<?>> {
        final int id;
        static int normalStartId = 0;
        static int generalStartId = 1000;
        public ValueTransformer(String name, Class<T> expectedType, int id) {
            super(name, expectedType);
            this.id = id;
        }

        public boolean isBasic() {
            return false;
        }

        @Override
        public int compareTo(@NotNull ValueTransformer<?> o) {
            return Integer.compare(id, o.id);
        }

        public boolean isApplicable(BasicValue value) {
            return expectedType.isAssignableFrom(value.getClass());
        }

        public FunctionCall createCall(Expression argument) {
            var call = new FunctionCall(getName(), Collections.singletonList(argument));
            call.setFunction(this);
            return call;
        }

        @Override
        public String toString() {
            return String.format("%s(%s) -> Value", getName(), expectedType.getSimpleName());
        }
    }

    /**
     * transforms a specific basic value into another, used to annotate edges in the
     * {@link DependencyGraph}
     */
    public static abstract class BasicValueTransformer<T extends BasicValue> extends ValueTransformer<T> {

        private final @Nullable BasicGroup argument;
        private final BasicGroup result;

        @SuppressWarnings("unchecked")
        public BasicValueTransformer(String name, @Nullable BasicGroup argument, BasicGroup result) {
            super(name, argument == null ? (Class<T>) BasicValue.class : (Class<T>) argument.getBaseClass(),
                    argument != null ? normalStartId++ : generalStartId++);
            this.argument = argument;
            this.result = result;
            if (argument != null) {
                transformersPerGroups.computeIfAbsent(p(argument, result), x -> new HashSet<>()).add(this);
            } else {
                generalTransformersPerResultGroup.computeIfAbsent(result, x -> new HashSet<>()).add(this);
            }
            basicTransformersPerName.put(name, this);
        }

        /**
         * is this function applicable to multiple value types?
         */
        public boolean isGeneral() {
            return argument == null;
        }

        @Override
        public String toString() {
            return String.format("%s(%s) -> %s", getName(), argument, result);
        }

        public BasicValue transform(VM vm, BasicValue value) {
            return (BasicValue)evaluate(vm, List.of(value));
        }

        public boolean returnsTag() {
            return result == BYTE;
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

                @Override
                public boolean isApplicable(BasicValue value) {
                    if (!(value instanceof StringValue)) {
                        return false;
                    }
                    var string = ((StringValue) value).getValue();
                    try {
                        if (string.length() > 0 && JNITypeParser.checkSignature(string)){
                            new JNITypeParser(string).jdwpTag();
                            return true;
                        }
                    } catch (IllegalArgumentException e) {
                    }
                    return false;
                }

                @Override
                public boolean returnsTag() {
                    return true;
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

                @Override
                public boolean returnsTag() {
                    return true;
                }
            };

    public static Set<BasicValueTransformer<?>> getTransformers(BasicGroup argument, BasicGroup result) {
        var ret = new HashSet<>(transformersPerGroups.getOrDefault(p(argument, result), Set.of()));
        ret.addAll(generalTransformersPerResultGroup.getOrDefault(result, Set.of()));
        return ret;
    }

    public static Set<BasicValueTransformer<?>> getTransformers(BasicGroup argument) {
        return basicTransformersPerName.values().stream()
                .filter(t -> t.argument == null || t.argument == argument)
                .collect(Collectors.toSet());
    }

    public static Set<BasicValueTransformer<?>> getTransformers(BasicValue argument) {
        return basicTransformersPerName.values().stream()
                .filter(t -> t.isApplicable(argument))
                .collect(Collectors.toSet());
    }

    /**
     * return all transformers f with <pre>f(argument) == result</pre>
     */
    @SuppressWarnings("unchecked")
    public static List<BasicValueTransformer<?>> getTransformers(VM vm, BasicValue argument, BasicValue result) {
        return getTransformers(argument.getGroup(), result.getGroup()).stream()
                .filter(t -> t.isApplicable(argument) && ((BasicValueTransformer<BasicValue>) t)
                        .evaluate(vm, argument).equals(result)).collect(Collectors.toList());
    }

    public Function getFunction(String name) {
        switch (name) {
            case GET:
                return GET_FUNCTION;
            case CONST:
                return CONST_FUNCTION;
            case WRAP:
                return WRAP_FUNCTION;
            case OBJECT:
                return OBJECT_FUNCTION;
            default:
                if (basicTransformersPerName.containsKey(name)) {
                    return basicTransformersPerName.get(name);
                }
                throw new AssertionError(String.format("Unknown function %s", name));
        }
    }
}
