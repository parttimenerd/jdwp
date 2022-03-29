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
import lombok.Getter;

import java.util.*;

/** id + idSizes, we do not care about capabilities, versions or tracing in this project */
@Getter
class VM {

    public static class NoTagPresentException extends RuntimeException {
        enum Source {
            FIELD, ARRAY
        }
        private final Source source;
        private final long id;
        NoTagPresentException(Source source, long id) {
            super(String.format("No tag present for %s %d", source.name(), id));
            this.source = source;
            this.id = id;
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

    /** field id -> tag */
    private final Map<Long, Byte> fieldTags;
    /** class id -> [field id] */
    private final Map<Long, List<Long>> classFieldIds;
    /** class signature -> class id */
    private Map<String, Long> classForSignature;
    /** array id -> tag */
    private Map<Long, Byte> arrayTags;

    public VM(int id) {
        this.id = id;
        this.fieldTags = new HashMap<>();
        this.classFieldIds = new HashMap<>();
        this.classForSignature = new HashMap<>();
        this.arrayTags = new HashMap<>();
    }

    public void addClass(String signature, long id) {
        classForSignature.put(signature, id);
        classFieldIds.put(id, new ArrayList<>());
    }

    public void addField(long klass, long id, byte tag) {
        classFieldIds.putIfAbsent(klass, new ArrayList<>());
        classFieldIds.get(klass).add(id);
        fieldTags.put(id, tag);
    }

    public void remove(String signature) {
        long id = classForSignature.remove(signature);
        classFieldIds.remove(id).forEach(fieldTags::remove);
    }

    public boolean hasFieldTag(long field) {
        return fieldTags.containsKey(field);
    }

    /** might throw a NoTagPresent exception */
    public byte getFieldTag(long field) {
        if (!hasFieldTag(field)) {
            throw new NoTagPresentException(Source.FIELD, id);
        }
        return fieldTags.get(field);
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
        classFieldIds.clear();
        classForSignature.clear();
        arrayTags.clear();
    }
}
