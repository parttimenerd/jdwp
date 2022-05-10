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

import java.io.ByteArrayOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@SuppressWarnings("ALL")
class PacketOutputStream {
    private final VM vm;
    private int inCursor = 0;
    final Packet pkt;
    private final ByteArrayOutputStream dataStream = new ByteArrayOutputStream();

    /** request packet */
    PacketOutputStream(VM vm, int commandSet, int command) {
        this.vm = vm;
        this.pkt = new Packet();
        pkt.cmdSet = (short)commandSet;
        pkt.cmd = (short)command;
        pkt.flags = 0;
    }

    /** reply packet */
    PacketOutputStream(VM vm, int errorCode) {
        this.vm = vm;
        this.pkt = new Packet();
        pkt.flags = 0x80;
        pkt.errorCode = (short)errorCode;
    }

    PacketOutputStream(VM vm, Packet pkt) {
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
        dataStream.write(data);
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

    void writePrimitiveUntagged(PrimitiveValue<?> primitive) {
        primitive.write(this);
    }

    void writeReferenceUntagged(Reference ref) {
        ref.write(this);
    }

    void writeWritableUntagged(Value val) {
        val.write(this);
    }

    void writeValueTagged(Reference val) {
        byte tag = (byte) val.type.tag;
        /*if (isObjectTag(tag)) {
            writeByte((byte)Value.Type.OBJECT.tag);
        } else {*/
            writeByte((byte) val.type.tag);
        //}
        val.write(this);
    }

    void writeValueTagged(PrimitiveValue<?> val) {
        writeByte((byte)val.type.tag);
        val.write(this);
    }

    void writeValueTagged(BasicValue val) {
        if (val instanceof Reference) {
            writeValueTagged((Reference)val);
        } else {
            writeValueTagged((PrimitiveValue<?>) val);
        }
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
        return (tag == JDWP.Tag.OBJECT) ||
               (tag == JDWP.Tag.ARRAY) ||
               (tag == JDWP.Tag.STRING) ||
               (tag == JDWP.Tag.THREAD) ||
               (tag == JDWP.Tag.THREAD_GROUP) ||
               (tag == JDWP.Tag.CLASS_LOADER) ||
               (tag == JDWP.Tag.CLASS_OBJECT);
    }

    public short flags() {
        return pkt.flags;
    }

    public PacketInputStream toPacketInputStream() {
        return toPacket().toStream(vm);
    }
}
