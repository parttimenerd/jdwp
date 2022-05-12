package jdwp;

import jdwp.AccessPath.TaggedAccessPath;
import jdwp.ArrayReferenceCmds.GetValuesReply;
import jdwp.PrimitiveValue.IntValue;
import jdwp.Reference.ArrayReference;
import jdwp.Value.*;
import jdwp.VirtualMachineCmds.DisposeObjectsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.*;

public class ValueTest {

    private static Arguments arg(Value val, Value val2, boolean equal) {
        return Arguments.of(val, val2, equal);
    }

    private static Stream<Arguments> equalityTestSource() {
        return Stream.of(
                arg(wrap(1), wrap(1), true),
                arg(wrap(1), wrap(2), false),
                arg(wrap(1), wrap((byte) 1), false),
                arg(wrap((byte) 1), wrap(2), false),
                arg(wrap(1), Reference.thread(1), false),
                arg(Reference.classType(1), Reference.thread(1), false),
                arg(Reference.klass(1), Reference.interfac(1), true),
                arg(Reference.klass(1), Reference.interfac(2), false));
    }

    @ParameterizedTest
    @MethodSource("equalityTestSource")
    public void testValueEquality(Value val, Value val2, boolean equal) {
        assertEquals(equal, val.equals(val2));
        assertEquals(equal, val2.equals(val));
    }

    @ParameterizedTest
    @MethodSource("equalityTestSource")
    public void testValueHashCodeEquality(Value val, Value val2, boolean equal) {
        if (equal) {
            assertEquals(val.hashCode(), val2.hashCode());
        } else {
            assertNotEquals(val.hashCode(), val2.hashCode());
        }
    }

    @Test
    public void testCombinedValueGetContainedValues() {
        var o1 = Reference.object(100);
        var o2 = Reference.object(100000);
        var i1 = wrap(101);
        var i2 = wrap(10);
        var vals = List.of(o1, o2, i1, i2);
        CombinedValue combinedValue = new DisposeObjectsRequest(0, new ListValue<>(
                new DisposeObjectsRequest.Request(o1, i1),
                new DisposeObjectsRequest.Request(o2, i2),
                new DisposeObjectsRequest.Request(o2, i2)));
        var containedValues = combinedValue.getContainedValues();
        assertTrue(vals.stream().allMatch(containedValues::containsBasicValue));

        assertEquals(Set.of(new TaggedAccessPath<>(combinedValue, "requests", 0, "object")), containedValues.getPaths(o1));
    }

    static Object[][] testToCodeMethodSource() {
        return new Object[][] {
                {new IntValue(1), "new IntValue(1)"},
                {new Location(Reference.classType(1), Reference.method(2), wrap(1L)),
                "new Location(new ClassTypeReference(1), new MethodReference(2), new LongValue(1))"},
                {new GetValuesReply(150156, new BasicListValue<>(Type.LIST, List.of(new ArrayReference(1057), new IntValue(0)))),
                "new jdwp.ArrayReferenceCmds.GetValuesReply(150156, new BasicListValue<>(Type.LIST, List.of(new ArrayReference(1057), new IntValue(0))))"}
        };
    }

    @ParameterizedTest
    @MethodSource("testToCodeMethodSource")
    public void testToCodeMethods(Value value, String expectedCode) {
        assertEquals(expectedCode, value.toCode());
    }
}
