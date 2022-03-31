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

import jdwp.VM.NoTagPresentException.Source;
import jdwp.util.Pair;
import lombok.Getter;

import java.util.*;

/** id + idSizes, we do not care about capabilities, versions or tracing in this project */
@Getter
class VM {

    public static class NoTagPresentException extends RuntimeException {
        enum Source {
            FIELD, ARRAY
        }

        NoTagPresentException(Source source, long id) {
            super(String.format("No tag present for %s %d", source.name(), id));
        }
    }

    final int id;
    
    // VM Level exported variables, these
    // are unique to a given vm
    private int sizeofFieldRef = 8;
    private int sizeofMethodRef = 8;
    private int sizeofObjectRef = 8;
    private int sizeofClassRef = 8;
    private int sizeofFrameRef = 8;
    private int sizeofModuleRef = 8;

    /** class id -> field id -> tag */
    private final Map<Long, Map<Long, Byte>> fieldTags;
    /** obj id -> class id */
    private final Map<Long, Long> classForObj;
    /** class id -> [obj id] */
    private final Map<Long, List<Long>> objForClass;
    /** class signature -> class id */
    private final Map<String, Long> classForSignature;
    /** array id -> tag */
    private final Map<Long, Byte> arrayTags;

    public VM(int id) {
        this.id = id;
        this.fieldTags = new HashMap<>();
        this.classForObj = new HashMap<>();
        this.objForClass = new HashMap<>();
        this.classForSignature = new HashMap<>();
        this.arrayTags = new HashMap<>();
    }

    public void addClass(String signature, long id) {
        assert !classForSignature.containsKey(signature);
        classForSignature.put(signature, id);
        fieldTags.put(id, new HashMap<>());
        objForClass.put(id, new ArrayList<>());
    }

    public void addFieldTag(long klass, long id, byte tag) {
        fieldTags.putIfAbsent(klass, new HashMap<>());
        fieldTags.get(klass).put(id, tag);
    }

    public void setClass(long obj, long klass) {
        classForObj.put(obj, klass);
        objForClass.putIfAbsent(klass, new ArrayList<>());
        objForClass.get(klass).add(obj);
    }

    public long getClass(long obj) {
        return classForObj.get(obj);
    }

    public void remove(String signature) {
        long id = classForSignature.remove(signature);
        fieldTags.remove(id);
        objForClass.get(id).forEach(classForObj::remove);
        objForClass.remove(id);
    }

    public boolean hasClassForObj(long obj) {
        return classForObj.containsKey(obj);
    }

    public boolean hasFieldTagForObj(long obj, long field) {
        return hasClassForObj(obj) && hasFieldTagForClass(classForObj.get(obj), field);
    }

    public boolean hasFieldTagForClass(long klass, long field) {
        return fieldTags.containsKey(klass) && fieldTags.get(klass).containsKey(field);
    }

    /** might throw a NoTagPresent exception */
    public byte getFieldTagForObj(long obj, long field) {
        if (!hasFieldTagForObj(obj, field)) {
            throw new NoTagPresentException(Source.FIELD, id);
        }
        return fieldTags.get(classForObj.get(obj)).get(field);
    }

    /** might throw a NoTagPresent exception */
    public byte getFieldTagForClass(long klass, long field) {
        if (!hasFieldTagForClass(klass, field)) {
            throw new NoTagPresentException(Source.FIELD, id);
        }
        return fieldTags.get(klass).get(field);
    }

    public void addArrayTag(long id, byte tag) {
        arrayTags.put(id, tag);
    }

    public boolean hasArrayTag(long id) {
        return arrayTags.containsKey(id);
    }

    /** might throw a NoTagPresent exception */
    public byte getArrayTag(long id) {
        if (!hasArrayTag(id)) {
            throw new NoTagPresentException(Source.ARRAY, id);
        }
        return arrayTags.get(id);
    }
    
    /*public void setSizes(jdwp.old.JDWP.VirtualMachine.IDSizes idSizes) {
        sizeofFieldRef = idSizes.fieldIDSize;
        sizeofMethodRef = idSizes.methodIDSize;
        sizeofObjectRef = idSizes.objectIDSize;
        sizeofClassRef = idSizes.referenceTypeIDSize;
        sizeofFrameRef = idSizes.frameIDSize;
        sizeofModuleRef = idSizes.objectIDSize;
    }*/

    /** clear tags info and classForSignature */
    public void reset() {
        fieldTags.clear();
        classForSignature.clear();
        arrayTags.clear();
        classForObj.clear();
        objForClass.clear();
    }
}
