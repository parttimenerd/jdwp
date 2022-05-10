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

import jdwp.ClassTypeCmds.NewInstanceReply;
import jdwp.ClassTypeCmds.NewInstanceRequest;
import jdwp.EventCmds.Events.*;
import jdwp.JDWP.RequestReplyVisitor;
import jdwp.JDWP.RequestVisitor;
import jdwp.ObjectReferenceCmds.ReferenceTypeReply;
import jdwp.ObjectReferenceCmds.ReferenceTypeRequest;
import jdwp.PrimitiveValue.StringValue;
import jdwp.Reference.FieldReference;
import jdwp.Reference.TypeObjectReference;
import jdwp.Reference.TypeReference;
import jdwp.ReferenceTypeCmds.FieldsReply.FieldInfo;
import jdwp.ReferenceTypeCmds.*;
import jdwp.VM.NoTagPresentException.Source;
import jdwp.VirtualMachineCmds.*;
import jdwp.VirtualMachineCmds.ClassesBySignatureReply.ClassInfo;
import lombok.Getter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * id + idSizes, we do not care about capabilities, versions or tracing in this project
 */
@Getter
public class VM {

    public static class NoTagPresentException extends RuntimeException {
        enum Source {
            FIELD, CLASS, ARRAY
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

    /**
     * class id -> field id -> tag
     */
    private final Map<Long, Map<Long, Byte>> fieldTags;
    /**
     * obj id -> field -> tag,   check this map for objects without a known class too
     */
    private final Map<Long, Map<Long, Byte>> fieldObjTags;
    /**
     * obj id -> class id
     */
    private final Map<Long, Long> classForObj;
    /**
     * class id -> [obj id]
     */
    private final Map<Long, Set<Long>> objForClass;
    /**
     * class signature -> class id
     */
    private final Map<String, Set<Long>> classForSignature;
    /**
     * array id -> tag
     */
    private final Map<Long, Byte> arrayTags;
    /**
     * class id -> tag
     */
    private final Map<Long, Byte> classTags;

    public VM(int id) {
        this.id = id;
        this.fieldTags = new HashMap<>();
        this.classForObj = new HashMap<>();
        this.objForClass = new HashMap<>();
        this.classForSignature = new HashMap<>();
        this.arrayTags = new HashMap<>();
        this.fieldObjTags = new HashMap<>();
        this.classTags = new HashMap<>();
    }

    public void addClass(String signature, long id) {
        classForSignature.putIfAbsent(signature, new HashSet<>());
        classForSignature.get(signature).add(id);
        fieldTags.put(id, new HashMap<>());
        objForClass.put(id, new HashSet<>());
        classTags.put(id, signatureToTag(signature));
    }

    public void addFieldTag(long klass, long id, byte tag) {
        fieldTags.putIfAbsent(klass, new HashMap<>());
        fieldTags.get(klass).put(id, tag);
    }

    public void addFieldObjectTag(long object, long id, byte tag) {
        fieldObjTags.putIfAbsent(object, new HashMap<>());
        fieldObjTags.get(object).put(id, tag);
    }

    public void setClass(long obj, long klass) {
        classForObj.put(obj, klass);
        objForClass.putIfAbsent(klass, new HashSet<>());
        objForClass.get(klass).add(obj);
    }

    public long getClass(long obj) {
        return classForObj.get(obj);
    }

    public void remove(String signature) {
        var ids = classForSignature.remove(signature);
        ids.forEach(id -> {
            fieldTags.remove(id);
            objForClass.get(id).forEach(o -> {
                classForObj.remove(o);
                fieldObjTags.remove(o);
            });
            objForClass.remove(id);
            classTags.remove(id);
        });
    }

    public boolean hasClassForObj(long obj) {
        return classForObj.containsKey(obj);
    }

    private boolean hasObjSpecificFieldTag(long obj, long field) {
        return fieldObjTags.containsKey(obj) && fieldObjTags.get(obj).containsKey(field);
    }

    public boolean hasFieldTagForObj(long obj, long field) {
        return hasObjSpecificFieldTag(obj, field)
                || (hasClassForObj(obj) && hasFieldTagForClass(classForObj.get(obj), field));
    }

    public boolean hasFieldTagForClass(long klass, long field) {
        return fieldTags.containsKey(klass) && fieldTags.get(klass).containsKey(field);
    }

    /**
     * might throw a NoTagPresent exception
     */
    public byte getFieldTagForObj(long obj, long field) {
        if (!hasFieldTagForObj(obj, field)) {
            throw new NoTagPresentException(Source.FIELD, id);
        }
        if (hasObjSpecificFieldTag(obj, field)) {
            return fieldObjTags.get(obj).get(field);
        }
        return fieldTags.get(classForObj.get(obj)).get(field);
    }

    /**
     * might throw a NoTagPresent exception
     */
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

    /**
     * might throw a NoTagPresent exception
     */
    public byte getArrayTag(long id) {
        if (!hasArrayTag(id)) {
            throw new NoTagPresentException(Source.ARRAY, id);
        }
        return arrayTags.get(id);
    }

    public boolean hasClassSpecificTag(long klass) {
        return classTags.containsKey(klass);
    }

    public byte getClassSpecificTag(long klass) {
        if (!hasClassSpecificTag(klass)) {
            throw new NoTagPresentException(Source.CLASS, klass);
        }
        return classTags.get(klass);
    }

    public void addClassSpecificTag(long klass, byte tag) {
        classTags.put(klass, tag);
    }

    public void setSizes(VirtualMachineCmds.IDSizesReply idSizes) {
        sizeofFieldRef = idSizes.fieldIDSize.value;
        sizeofMethodRef = idSizes.methodIDSize.value;
        sizeofObjectRef = idSizes.objectIDSize.value;
        sizeofClassRef = idSizes.referenceTypeIDSize.value;
        sizeofFrameRef = idSizes.frameIDSize.value;
        sizeofModuleRef = idSizes.objectIDSize.value;
    }

    /**
     * clear tags info and classForSignature
     */
    public void reset() {
        fieldTags.clear();
        classForSignature.clear();
        arrayTags.clear();
        classForObj.clear();
        objForClass.clear();
        fieldObjTags.clear();
        classTags.clear();
    }

    public void captureInformation(EventCommon event) {
        event.accept(new EventVisitor() {
            @Override
            public void visit(ClassPrepare obj) {
                addClass(obj.signature.value, obj.typeID.value);
            }

            @Override
            public void visit(FieldAccess obj) {
                if (hasClassSpecificTag(obj.typeID.value)) {
                    addFieldObjectTag(obj.object.value, obj.fieldID.value, getClassSpecificTag(obj.typeID.value));
                }
            }

            @Override
            public void visit(FieldModification obj) {
                addFieldObjectTag(obj.object.value, obj.fieldID.value, (byte) obj.valueToBe.type.tag);
                addClassSpecificTag(obj.typeID.value, (byte) obj.valueToBe.type.tag);
            }
        });
    }

    public void captureInformation(Request<?> request) {
        request.accept(new RequestVisitor() {
            // there seems to be no information that can be gathered
        });
    }

    /**
     * capture information on object, field and array types and idsizes from a reply
     */
    public void captureInformation(Request<?> request, Reply reply) {
        request.accept(new RequestReplyVisitor() {
            public void visit(ArrayReferenceCmds.GetValuesRequest request,
                              ArrayReferenceCmds.GetValuesReply reply) {
                addArrayTag(request.arrayObject.value, (byte) reply.values.entryType.tag);
            }

            public void visit(IDSizesRequest request, IDSizesReply reply) {
                setSizes(reply);
            }

            public void visit(ReferenceTypeCmds.FieldsRequest request, ReferenceTypeCmds.FieldsReply reply) {
                var klass = request.refType;
                for (FieldInfo info : reply.declared.values) {
                    addFieldTag(klass, info.fieldID, info.signature);
                }
            }

            public void visit(ReferenceTypeCmds.FieldsWithGenericRequest request,
                              ReferenceTypeCmds.FieldsWithGenericReply reply) {
                var klass = request.refType;
                for (FieldsWithGenericReply.FieldInfo info : reply.declared.values) {
                    addFieldTag(klass, info.fieldID, info.signature);
                }
            }

            public void visit(ReferenceTypeCmds.GetValuesRequest request,
                              ReferenceTypeCmds.GetValuesReply reply) {
                var klass = request.refType;
                var requestValues = request.fields.values;
                var replyFields = reply.values.values;
                for (int i = 0; i < requestValues.size(); i++) {
                    addFieldObjectTag(klass, requestValues.get(i).fieldID, (byte) replyFields.get(i).type.tag);
                }
            }

            public void visit(ObjectReferenceCmds.GetValuesRequest request,
                              ObjectReferenceCmds.GetValuesReply reply) {
                var object = request.object;
                var requestValues = request.fields.values;
                var replyFields = reply.values.values;
                for (int i = 0; i < requestValues.size(); i++) {
                    addFieldObjectTag(object.value, requestValues.get(i).fieldID.value, (byte) replyFields.get(i).type.tag);
                }
            }

            public void visit(InstancesRequest request, InstancesReply reply) {
                for (Reference instance : reply.instances.values) {
                    setClass(instance.value, request.refType.value);
                }
            }

            public void visit(NewInstanceRequest request, NewInstanceReply reply) {
                if (reply.newObject.value != null) {
                    setClass(reply.newObject.value, request.clazz.value);
                }
            }

            public void visit(ReferenceTypeRequest request, ReferenceTypeReply reply) {
                setClass(request.object.value, reply.typeID.value);
            }

            public void visit(ClassesBySignatureRequest request, ClassesBySignatureReply reply) {
                for (ClassInfo info : reply.classes.values) {
                    addClass(request.signature.value, info.typeID.value);
                }
            }

            public void visit(AllClassesRequest request, AllClassesReply reply) {
                for (AllClassesReply.ClassInfo info : reply.classes.values) {
                    addClass(info.signature.value, info.typeID.value);
                }
            }

            public void visit(AllClassesWithGenericRequest request,
                              AllClassesWithGenericReply reply) {
                for (AllClassesWithGenericReply.ClassInfo info : reply.classes.values) {
                    addClass(info.signature.value, info.typeID.value);
                }
            }

            public void visit(SignatureRequest request, SignatureReply reply) {
                addClass(reply.signature.value, request.refType.value);
            }

            public void visit(SignatureWithGenericRequest request,
                              SignatureWithGenericReply reply) {
                addClass(reply.signature.value, request.refType.value);
            }
        }, reply);
    }

    public void addFieldTag(TypeReference klass, FieldReference field, byte tag) {
        addFieldTag(klass.value, field.value, tag);
    }

    public void addFieldObjectTag(TypeObjectReference obj, FieldReference field, byte tag) {
        addFieldObjectTag(obj.value, field.value, tag);
    }

    public void addFieldTag(Reference klass, FieldReference field, StringValue signature) {
        addFieldTag(klass.value, field.value, signatureToTag(signature));
    }

    private byte signatureToTag(StringValue signature) {
        return signatureToTag(signature.value);
    }

    private byte signatureToTag(String signature) {
        return new JNITypeParser(signature).jdwpTag();
    }
}
