package jdwp;

import jdwp.ArrayReferenceCmds.GetValuesReply;
import jdwp.EventCmds.Events.VMStart;
import jdwp.EventRequestCmds.SetRequest.ClassExclude;
import jdwp.EventRequestCmds.SetRequest.LocationOnly;
import jdwp.ObjectReferenceCmds.SetValuesRequest.FieldValue;
import jdwp.PrimitiveValue.IntValue;
import jdwp.Reference.ArrayReference;
import jdwp.StackFrameCmds.SetValuesRequest;
import jdwp.StackFrameCmds.SetValuesRequest.SlotInfo;
import jdwp.Value.*;
import jdwp.VirtualMachineCmds.DisposeObjectsRequest;
import jdwp.VirtualMachineCmds.IDSizesReply;
import jdwp.VirtualMachineCmds.IDSizesRequest;
import jdwp.VirtualMachineCmds.RedefineClassesRequest.ClassDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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

        assertEquals(Set.of(new AccessPath("requests", 0, "object")), containedValues.getPaths(o1));
    }

    static Object[][] testToCodeMethodSource() {
        return new Object[][] {
                {wrap(1), "PrimitiveValue.wrap(1)"},
                {new Location(Reference.classType(1), Reference.method(2), wrap(1L)),
                        "new Location(new ClassTypeReference(1L), new MethodReference(2L), PrimitiveValue.wrap((long)1))"},
                {new GetValuesReply(150156, new BasicListValue<>(Type.LIST, List.of(new ArrayReference(1057), new IntValue(0)))),
                        "new jdwp.ArrayReferenceCmds.GetValuesReply(150156, new BasicListValue<>(Type.LIST, List.of(new ArrayReference(1057L), PrimitiveValue.wrap(0))))"},
                {new VMStart(wrap(1), Reference.thread(2)),
                        "new VMStart(PrimitiveValue.wrap(1), new ThreadReference(2L))"}
        };
    }

    @ParameterizedTest
    @MethodSource("testToCodeMethodSource")
    public void testToCodeMethods(Value value, String expectedCode) {
        assertEquals(expectedCode, value.toCode());
    }


    @Test
    public void testToShortString() {
        assertEquals("VirtualMachineCmds.IDSizesRequest(11)", new IDSizesRequest(11).toShortString());
    }

    @Test
    public void testContainedValueOrder() {
        var location = new IDSizesReply(1, wrap(1), wrap(1), wrap(2), wrap(1), wrap(2));
        var containedValues = location.getContainedValues();
        assertEquals(
                new AccessPath("fieldIDSize"), containedValues.getFirstTaggedValue(wrap(1)).getPath());
        assertEquals(
                new AccessPath("objectIDSize"), containedValues.getFirstTaggedValue(wrap(2)).getPath());
    }

    @Test
    public void testReferenceEquality() {
        var classTypeRef = Reference.classType(1);
        var interfaceTypeRef = Reference.interfaceType(1);
        var threadRef = Reference.thread(1);
        assertEquals(classTypeRef.hashCode(), interfaceTypeRef.hashCode());
        assertEquals(classTypeRef, interfaceTypeRef);
        assertNotEquals(classTypeRef, threadRef);
    }

    private static final Location sampleLocation =
            new Location(Reference.classType(1), Reference.method(10), wrap(10L));

    private static class ValueWithList extends CombinedValue {

        @EntryClass(IntValue.class)
        private final ListValue<IntValue> values;

        public ValueWithList(ListValue<IntValue> values) {
            super(Type.OBJECT);
            this.values = values;
        }

        @SuppressWarnings("unchecked")
        public ValueWithList(Map<String, Value> args) {
            this((ListValue<IntValue>) Objects.requireNonNull(args.get("values")));
        }

        @Override
        protected boolean containsKey(String key) {
            return key.equals("values");
        }

        @Override
        public List<String> getKeys() {
            return List.of("values");
        }

        @Override
        public Value get(String key) {
            return values;
        }
    }

    static List<CombinedValue> combinedValueCreateTestSource() {
        return List.of(
                sampleLocation,
                new ClassExclude(wrap("s")),
                new LocationOnly(sampleLocation),
                new FieldValue(Reference.field(10), wrap(1)),
                new ClassDef(Reference.klass(1), new ByteList((byte) 1, (byte) 2, (byte) 4)),
                new ClassDef(Reference.klass(1), new ByteList()),
                new ValueWithList(new ListValue<>(wrap(1))),
                new ValueWithList(new ListValue<>(Type.INT)));
    }

    @ParameterizedTest
    @MethodSource("combinedValueCreateTestSource")
    public void testCombinedValueCreateFromPairs(CombinedValue value) {
        assertEquals(value, CombinedValue.create(value.getClass(), value.getValues()));
    }

    @ParameterizedTest
    @MethodSource("combinedValueCreateTestSource")
    public void testCombinedValueCreateFromTaggedValues(CombinedValue value) {
        assertEquals(value, CombinedValue.createForTagged(value.getClass(), value.getTaggedValues()));
    }

    static List<ListValue<? extends Value>> listValueCreateTestSource() {
        return List.of(
                new ListValue<>(wrap(1)),
                new BasicListValue<>(wrap(1)),
                new ListValue<>(sampleLocation, sampleLocation)
        );
    }

    @ParameterizedTest
    @MethodSource("listValueCreateTestSource")
    @SuppressWarnings("unchecked")
    public void testListValueCreateFromPairs(ListValue<? extends Value> value) {
        assertEquals(
                value, ListValue.create(value.getClass(), value.get(0).getClass(), value.getValues()));
    }

    @ParameterizedTest
    @MethodSource("listValueCreateTestSource")
    @SuppressWarnings("unchecked")
    public void testListValueCreateFromTaggedValues(ListValue<? extends Value> value) {
        Class<Value> elementType = (Class<Value>) value.get(0).getClass();
        assertEquals(value, ListValue.createForTagged(value.getClass(), elementType, value.getTaggedValues()));
    }

    static List<? extends AbstractParsedPacket> abstractPacketCreateTestSource() {
        return List.of(
                new GetValuesReply(1, new BasicListValue<>(wrap(1), wrap(2))),
                new ArrayReferenceCmds.SetValuesRequest(
                        3, Reference.array(1), wrap(1), new ListValue<>(Type.VALUE, wrap(true))),
                new SetValuesRequest(
                        2,
                        Reference.thread(1),
                        Reference.frame(1),
                        new ListValue<>(new SlotInfo(wrap(1), wrap("slot")))));
    }

    @ParameterizedTest
    @MethodSource("abstractPacketCreateTestSource")
    public void testAbstractParsedPacketCreateFromPairs(AbstractParsedPacket value) {
        assertEquals(value, AbstractParsedPacket.create(value.getId(), value.getClass(), value.getValues()));
    }

    @ParameterizedTest
    @MethodSource("abstractPacketCreateTestSource")
    public void testAbstractParsedPacketCreateFromTaggedValues(AbstractParsedPacket value) {
        assertEquals(value, AbstractParsedPacket.createForTagged(value.getId(), value.getClass(), value.getTaggedValues()));
    }
}
