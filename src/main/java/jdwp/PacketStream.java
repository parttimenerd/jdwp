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

import jdwp.Value.BasicValue;
import jdwp.Value.WritableValue;

import java.io.ByteArrayOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

class PacketStream {
    final VM vm;
    private int inCursor = 0;
    final Packet pkt;
    private final ByteArrayOutputStream dataStream = new ByteArrayOutputStream();

    PacketStream(VM vm, int cmdSet, int cmd) {
        this.vm = vm;
        this.pkt = new Packet();
        pkt.cmdSet = (short)cmdSet;
        pkt.cmd = (short)cmd;
    }

    PacketStream(VM vm, Packet pkt) {
        this.vm = vm;
        this.pkt = pkt;
    }

    public Packet toPacket() {
        pkt.data = dataStream.toByteArray();
        return pkt;
    }

    int id() {
        return pkt.id;
    }

    void writeBoolean(boolean data) {
        if(data) {
            dataStream.write( 1 );
        } else {
            dataStream.write( 0 );
        }
    }

    void writeByte(byte data) {
        dataStream.write( data );
    }

    void writeChar(char data) {
        dataStream.write( (byte)((data >>> 8) & 0xFF) );
        dataStream.write( (byte)((data >>> 0) & 0xFF) );
    }

    void writeShort(short data) {
        dataStream.write( (byte)((data >>> 8) & 0xFF) );
        dataStream.write( (byte)((data >>> 0) & 0xFF) );
    }

    void writeInt(int data) {
        dataStream.write( (byte)((data >>> 24) & 0xFF) );
        dataStream.write( (byte)((data >>> 16) & 0xFF) );
        dataStream.write( (byte)((data >>> 8) & 0xFF) );
        dataStream.write( (byte)((data >>> 0) & 0xFF) );
    }

    void writeLong(long data) {
        dataStream.write( (byte)((data >>> 56) & 0xFF) );
        dataStream.write( (byte)((data >>> 48) & 0xFF) );
        dataStream.write( (byte)((data >>> 40) & 0xFF) );
        dataStream.write( (byte)((data >>> 32) & 0xFF) );

        dataStream.write( (byte)((data >>> 24) & 0xFF) );
        dataStream.write( (byte)((data >>> 16) & 0xFF) );
        dataStream.write( (byte)((data >>> 8) & 0xFF) );
        dataStream.write( (byte)((data >>> 0) & 0xFF) );
    }

    void writeFloat(float data) {
        writeInt(Float.floatToIntBits(data));
    }

    void writeDouble(double data) {
        writeLong(Double.doubleToLongBits(data));
    }

    void writeID(int size, long data) {
        switch (size) {
            case 8:
                writeLong(data);
                break;
            case 4:
                writeInt((int)data);
                break;
            case 2:
                writeShort((short)data);
                break;
            default:
                throw new UnsupportedOperationException("JDWP: ID size not supported: " + size);
        }
    }

    void writeNullObjectRef() {
        writeObjectRef(0);
    }

    void writeObjectRef(long data) {
        writeID(vm.getSizeofObjectRef(), data);
    }

    void writeClassRef(long data) {
        writeID(vm.getSizeofClassRef(), data);
    }

    void writeMethodRef(long data) {
        writeID(vm.getSizeofMethodRef(), data);
    }

    void writeModuleRef(long data) {
        writeID(vm.getSizeofModuleRef(), data);
    }

    void writeFieldRef(long data) {
        writeID(vm.getSizeofFieldRef(), data);
    }

    void writeFrameRef(long data) {
        writeID(vm.getSizeofFrameRef(), data);
    }

    void writeByteArray(byte[] data) {
        dataStream.write(data, 0, data.length);
    }

    void writeString(String string) {
        byte[] stringBytes = string.getBytes(UTF_8);
        writeInt(stringBytes.length);
        writeByteArray(stringBytes);
    }

    void writePrimitiveUntagged(jdwp.PrimitiveValue<?> primitive) {
        primitive.write(this);
    }

    void writeReferenceUntagged(Reference ref) {
        ref.write(this);
    }

    void writeWritableUntagged(WritableValue val) {
        val.write(this);
    }

    void writeValueTagged(jdwp.Reference val) {
        byte tag = (byte) val.type.tag;
        if (isObjectTag(tag)) {
            writeByte((byte)Value.Type.OBJECT.tag);
        } else {
            writeByte((byte) val.type.tag);
        }
        val.write(this);
    }

    void writeValueTagged(jdwp.PrimitiveValue<?> val) {
        writeByte((byte)val.type.tag);
        val.write(this);
    }

    void writeValueTagged(BasicValue<?> val) {
        if (val instanceof jdwp.Reference) {
            writeValueTagged((jdwp.Reference)val);
        } else {
            writeValueTagged((PrimitiveValue<?>) val);
        }
    }

    /**
     * Read byte represented as one bytes.
     */
    byte readByte() {
        byte ret = pkt.data[inCursor];
        inCursor += 1;
        return ret;
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
        int b1, b2;

        b1 = pkt.data[inCursor++] & 0xff;
        b2 = pkt.data[inCursor++] & 0xff;

        return (char)((b1 << 8) + b2);
    }

    /**
     * Read short represented as two bytes.
     */
    short readShort() {
        int b1, b2;

        b1 = pkt.data[inCursor++] & 0xff;
        b2 = pkt.data[inCursor++] & 0xff;

        return (short)((b1 << 8) + b2);
    }

    /**
     * Read int represented as four bytes.
     */
    int readInt() {
        int b1,b2,b3,b4;

        b1 = pkt.data[inCursor++] & 0xff;
        b2 = pkt.data[inCursor++] & 0xff;
        b3 = pkt.data[inCursor++] & 0xff;
        b4 = pkt.data[inCursor++] & 0xff;

        return ((b1 << 24) + (b2 << 16) + (b3 << 8) + b4);
    }

    /**
     * Read long represented as eight bytes.
     */
    long readLong() {
        long b1,b2,b3,b4;
        long b5,b6,b7,b8;

        b1 = pkt.data[inCursor++] & 0xff;
        b2 = pkt.data[inCursor++] & 0xff;
        b3 = pkt.data[inCursor++] & 0xff;
        b4 = pkt.data[inCursor++] & 0xff;

        b5 = pkt.data[inCursor++] & 0xff;
        b6 = pkt.data[inCursor++] & 0xff;
        b7 = pkt.data[inCursor++] & 0xff;
        b8 = pkt.data[inCursor++] & 0xff;

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
        String ret = new String(pkt.data, inCursor, len, UTF_8);
        inCursor += len;
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
    BasicValue<?> readValue() {
        byte tag = readByte();
        return readUntaggedValue(tag);
    }

    BasicValue<?> readUntaggedValue(byte tag) {
       if (isObjectTag(tag)) {
           return Reference.readReference(this, tag);
       }
       return PrimitiveValue.readValue(this, tag);
    }

    byte[] readByteArray(int length) {
        byte[] array = new byte[length];
        System.arraycopy(pkt.data, inCursor, array, 0, length);
        inCursor += length;
        return array;
    }

    int skipBytes(int n) {
        inCursor += n;
        return n;
    }

    byte command() {
        return (byte)pkt.cmd;
    }

    byte commandSet() {
        return (byte)pkt.cmdSet;
    }

    short errorCode() {
        return pkt.errorCode;
    }

    static boolean isObjectTag(byte tag) {
        return (tag == jdwp.JDWP.Tag.OBJECT) ||
               (tag == jdwp.JDWP.Tag.ARRAY) ||
               (tag == jdwp.JDWP.Tag.STRING) ||
               (tag == jdwp.JDWP.Tag.THREAD) ||
               (tag == jdwp.JDWP.Tag.THREAD_GROUP) ||
               (tag == jdwp.JDWP.Tag.CLASS_LOADER) ||
               (tag == jdwp.JDWP.Tag.CLASS_OBJECT);
    }

    public short flags() {
        return pkt.flags;
    }
}
