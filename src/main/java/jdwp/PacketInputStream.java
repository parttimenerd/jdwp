/*
 * Copyright (c) 1998, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdwp;

import jdwp.Reference.ArrayReference;
import jdwp.Reference.ClassTypeReference;
import jdwp.Reference.ObjectReference;
import jdwp.Value.BasicScalarValue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@SuppressWarnings("ALL")
public
class PacketInputStream {

    private final VM vm;

    // packet specific data
    private final int length;
    private final int id;
    /** if 0x80: reply packet, else command packet */
    private final short flags;

    // request specific data
    private final byte commandSet;
    private final byte command;

    // reply specific data
    private final short errorCode;

    private int cursor;
    private final byte[] data;

    public PacketInputStream(VM vm, byte[] data) {
        this.vm = vm;
        this.data = data;
        this.length = data.length;
        this.cursor = 4;
        this.id = readInt();
        this.flags = (short)(readByte() & 0xff);
        if (isRequest()) {
            this.commandSet = readByte();
            this.command = readByte();
            this.errorCode = 0;
        } else {
            this.commandSet = 0;
            this.command = 0;
            this.errorCode = readShort();
        }
    }

    private static int readInt(byte[] bytes) {
        int b1,b2,b3,b4;

        b1 = bytes[0] & 0xff;
        b2 = bytes[1] & 0xff;
        b3 = bytes[2] & 0xff;
        b4 = bytes[3] & 0xff;

        return ((b1 << 24) + (b2 << 16) + (b3 << 8) + b4);
    }

    /** throws CloseStreamException if stream has been closed */
    public static PacketInputStream read(VM vm, InputStream input) throws IOException {
        byte[] lengthBytes = input.readNBytes(4);
        if (lengthBytes.length != 4) {
            throw new ClosedStreamException();
        }
        int length = readInt(lengthBytes);
        byte[] data = new byte[length];
        System.arraycopy(lengthBytes, 0, data, 0, 4);
        int readBytes = input.read(data, 4, length - 4);
        if (readBytes != length - 4) {
            throw new ClosedStreamException();
        }
        return new PacketInputStream(vm, data);
    }

    public static PacketInputStream read(VM vm, Packet packet) {
        return packet.toStream(vm);
    }

    /**
     * Read byte represented as one byte.
     */
    byte readByte() {
        return data[cursor++];
    }

    int readByteAsInt() {
        return readByte();
    }

    /**
     * Read boolean represented as one byte.
     */
    boolean readBoolean() {
        byte ret = readByte();
        return (ret != 0);
    }

    /**
     * Read char represented as two bytes.
     */
    char readChar() {
        return (char)readShort();
    }

    /**
     * Read short represented as two bytes.
     */
    short readShort() {
        int b1, b2;

        b1 = readByte() & 0xff;
        b2 = readByte() & 0xff;

        return (short)((b1 << 8) + b2);
    }

    /**
     * Read int represented as four bytes.
     */
    int readInt() {
        int b1,b2,b3,b4;

        b1 = readByte() & 0xff;
        b2 = readByte() & 0xff;
        b3 = readByte() & 0xff;
        b4 = readByte() & 0xff;

        return ((b1 << 24) + (b2 << 16) + (b3 << 8) + b4);
    }

    /**
     * Read long represented as eight bytes.
     */
    long readLong() {
        long b1,b2,b3,b4;
        long b5,b6,b7,b8;

        b1 = readByte() & 0xff;
        b2 = readByte() & 0xff;
        b3 = readByte() & 0xff;
        b4 = readByte() & 0xff;

        b5 = readByte() & 0xff;
        b6 = readByte() & 0xff;
        b7 = readByte() & 0xff;
        b8 = readByte() & 0xff;

        return ((b1 << 56) + (b2 << 48) + (b3 << 40) + (b4 << 32)
                + (b5 << 24) + (b6 << 16) + (b7 << 8) + b8);
    }

    /**
     * Read float represented as four bytes.
     */
    float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    /**
     * Read double represented as eight bytes.
     */
    double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    /**
     * Read string represented as four byte length followed by
     * characters of the string.
     */
    String readString() {
        int len = readInt();
        String ret = new String(data, cursor, len, UTF_8);
        cursor += len;
        return ret;
    }

    private long readID(int size) {
        switch (size) {
          case 8:
              return readLong();
          case 4:
              return readInt();
          case 2:
              return readShort();
          default:
              throw new UnsupportedOperationException("JDWP: ID size not supported: " + size);
        }
    }

    /**
     * Read object represented as vm specific byte sequence.
     */
    long readObjectRef() {
        return readID(vm.getSizeofObjectRef());
    }

    long readClassRef() {
        return readID(vm.getSizeofClassRef());
    }

    /**
     * Read method reference represented as vm specific byte sequence.
     */
    long readMethodRef() {
        return readID(vm.getSizeofMethodRef());
    }

    /**
     * Read module reference represented as vm specific byte sequence.
     */
    long readModuleRef() {
        return readID(vm.getSizeofModuleRef());
    }

    /**
     * Read field reference represented as vm specific byte sequence.
     */
    long readFieldRef() {
        return readID(vm.getSizeofFieldRef());
    }

    /**
     * Read frame represented as vm specific byte sequence.
     */
    long readFrameRef() {
        return readID(vm.getSizeofFrameRef());
    }

    /**
     * Read a value, first byte describes type of value to read.
     */
    BasicScalarValue<?> readValue() {
        byte tag = readByte();
        return readUntaggedValue(tag);
    }

    BasicScalarValue<?> readUntaggedValue(byte tag) {
       if (isObjectTag(tag)) {
           return Reference.readReference(this, tag);
       }
       return PrimitiveValue.readValue(this, tag);
    }

    public BasicScalarValue<?> readUntaggedFieldValue(ObjectReference instance, Reference.FieldReference field) {
        return readUntaggedValue(vm.getFieldTagForObj(instance.value, field.value));
    }

    public BasicScalarValue<?> readUntaggedFieldValue(ClassTypeReference klass, Reference.FieldReference field) {
        return readUntaggedValue(vm.getFieldTagForClass(klass.value, field.value));
    }

    public BasicScalarValue<?> readUntaggedArrayValue(ArrayReference arrayObject) {
        return readUntaggedValue(vm.getArrayTag(arrayObject.value));
    }

    byte[] readByteArray(int length) {
        byte[] array = new byte[length];
        System.arraycopy(data, cursor, array, 0, length);
        cursor += length;
        return array;
    }

    int skipBytes(int n) {
        cursor += n;
        return n;
    }

    byte command() {
        return command;
    }

    byte commandSet() {
        return commandSet;
    }

    short errorCode() {
        return errorCode;
    }

    public boolean isReply() {
        return flags == Packet.REPLY_FLAG;
    }

    boolean isRequest() {
        return flags != Packet.REPLY_FLAG;
    }

    static boolean isObjectTag(byte tag) {
        return (tag == JDWP.Tag.OBJECT) ||
               (tag == JDWP.Tag.ARRAY) ||
               (tag == JDWP.Tag.STRING) ||
               (tag == JDWP.Tag.THREAD) ||
               (tag == JDWP.Tag.THREAD_GROUP) ||
               (tag == JDWP.Tag.CLASS_LOADER) ||
               (tag == JDWP.Tag.CLASS_OBJECT);
    }

    public short flags() {
        return flags;
    }

    public VM vm() {
        return vm;
    }

    public int id() {
        return id;
    }

    public byte[] data() {
        return data;
    }

    public int length() {
        return length;
    }

    public InputStream toInputStream() {
        return new ByteArrayInputStream(data);
    }
}
