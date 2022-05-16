package jdwp;

import jdwp.PrimitiveValue.LongValue;
import jdwp.Reference.MethodReference;
import jdwp.Reference.TypeReference;
import jdwp.Value.CombinedValue;
import jdwp.util.Pair;
import lombok.EqualsAndHashCode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static jdwp.util.Pair.p;

@SuppressWarnings("ALL")
@EqualsAndHashCode(callSuper = false)
public class Location extends CombinedValue {

    final TypeReference declaringType;
    final MethodReference methodRef;
    final LongValue codeIndex;

    public Location(TypeReference declaringType, MethodReference methodRef, LongValue codeIndex) {
        super(Type.LOCATION);
        this.declaringType = declaringType;
        this.methodRef = methodRef;
        this.codeIndex = codeIndex;
    }


    @Override
    public void write(PacketOutputStream ps) {
        declaringType.writeTagged(ps);
        methodRef.write(ps);
        codeIndex.write(ps);
    }

    public static Location read(PacketInputStream ps) {
        return new Location(TypeReference.read(ps), MethodReference.read(ps), LongValue.read(ps));
    }

    /* Null location (example: uncaught exception) */
    public boolean isNull() {
        return declaringType.typeTag == 0;
    }

    private static final List<String> KEYS = List.of("declaringType", "methodRef", "codeIndex");
    private static final Set<String> KEY_SET = new HashSet<>(KEYS);

    @Override
    public List<String> getKeys() {
        return KEYS;
    }

    @Override
    public Value get(String key) {
        switch (key){
            case "declaringType":
                return declaringType;
            case "methodRef":
                return methodRef;
            case "codeIndex":
                return codeIndex;
            default:
                return keyError(key);
        }
    }

    @Override
    protected boolean containsKey(String key) {
        return KEYS.contains(key);
    }

    @Override
    public List<Pair<String, Value>> getValues() {
        return List.of(p("declaringType", declaringType), p("methodRef", methodRef), p("codeIndex", codeIndex));
    }
}
