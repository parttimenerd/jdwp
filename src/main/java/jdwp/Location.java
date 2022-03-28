package jdwp;

import jdwp.PrimitiveValue.LongValue;
import jdwp.Reference.MethodReference;
import jdwp.Reference.TypeReference;
import jdwp.Value.CombinedValue;
import lombok.EqualsAndHashCode;

import java.util.Arrays;
import java.util.List;

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
    public void write(PacketStream ps) {
        declaringType.writeTagged(ps);
        methodRef.write(ps);
        codeIndex.write(ps);
    }

    public static Location read(PacketStream ps) {
        return new Location(TypeReference.read(ps), MethodReference.read(ps), LongValue.read(ps));
    }

    /* Null location (example: uncaught exception) */
    public boolean isNull() {
        return declaringType.typeTag == 0;
    }

    private static final List<String> keys = Arrays.asList("declaringType", "methodRef", "codeIndex");

    @Override
    List<String> getKeys() {
        return keys;
    }

    @Override
    Value get(String key) {
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
}
