/*
 * Copyright (c) 1998, 2013, Oracle and/or its affiliates. All rights reserved.
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

package build.tools.jdwpgen;


import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;

import java.util.*;
import java.io.*;

interface TypeNode {

    String name();

    void genJavaWrite(PrintWriter writer, int depth,  String writeLabel);

    void genJavaRead(PrintWriter writer, int depth, String readLabel);

    void genJavaDeclaration(PrintWriter writer, int depth);

    String javaParam();

    abstract class AbstractTypeNode extends AbstractNamedNode
            implements TypeNode {

        abstract String docType();

        public abstract void genJavaWrite(PrintWriter writer, int depth,
                                          String writeLabel);

        public String genJavaWrite(String writeLabel) {
            StringWriter writer = new StringWriter();
            genJavaWrite(new PrintWriter(writer), 0, writeLabel);
            return writer.toString();
        }

        abstract String javaRead();

        void document(PrintWriter writer) {
            writer.println("<tr>");
            writer.println("<td>" + indentElement(structIndent, docType()));
            writer.println("<th scope=\"row\"><i>" + name() + "</i>");
            writer.println("<td>" + comment() + "&nbsp;");
            writer.println("</tr>");
        }

        String javaType() {
            return docType(); // default
        }

        public String genJavaRead(String readLabel) {
            StringWriter writer = new StringWriter();
            genJavaRead(new PrintWriter(writer), 0, readLabel);
            return writer.toString().trim();
        }

        public void genJavaRead(PrintWriter writer, int depth,
                                String readLabel) {
            indent(writer, depth);
            writer.print(readLabel);
            writer.print(" = ");
            writer.print(javaRead());
            //genJavaDebugRead(writer, depth, readLabel, debugValue(readLabel));
        }

        public void genJavaDeclaration(PrintWriter writer, int depth) {
            writer.println();
            genJavaComment(writer, depth);
            indent(writer, depth);
            writer.print("final ");
            writer.print(javaType());
            writer.print(" " + name);
            writer.println(";");
        }

        public String javaParam() {
            return javaType() + " " + name;
        }
    }

    abstract class AbstractSimpleTypeNode extends AbstractTypeNode {

        void constrain(Context ctx) {
            context = ctx;
            nameNode.constrain(ctx);
            if (components.size() != 0) {
                error("Extraneous content: " + components.get(0));
            }
        }

        public void genJavaWrite(PrintWriter writer, int depth,
                                 String writeLabel) {
            indent(writer, depth);
            writer.println(writeLabel + ".write(ps);");
        }

        String javaRead() {
            return javaType() + ".read(ps);";
        }
    }

    class RepeatNode extends TypeNode.AbstractTypeNode {

        Node member = null;

        void constrain(Context ctx) {
            super.constrain(ctx);
            if (components.size() != 1) {
                error("Repeat must have exactly one member, use Group for more");
            }
            member = components.get(0);
            if (!(member instanceof TypeNode)) {
                error("Repeat member must be type specifier");
            }
        }

        void document(PrintWriter writer) {
            writer.println("<tr>");
            writer.println("<td>" + indentElement(structIndent, "int"));
            writer.println("<th scope=\"row\"><i>" + name() + "</i>");
            writer.println("<td>" + comment() + "&nbsp;");
            writer.println("</tr>");

            writer.println("<tr>");
            writer.println("<th colspan=\"3\" scope=\"rowgroup\">"
                    + indentElement(structIndent, "Repeated <i>" + name() + "</i> times:"));
            writer.println("</tr>");

            ++structIndent;
            member.document(writer);
            --structIndent;
        }

        String docType() {
            return "-BOGUS-"; // should never call this
        }

        String javaType() {
            return "ListValue<" + member.javaType() + ">";
        }

        public void genJavaWrite(PrintWriter writer, int depth,
                                 String writeLabel) {
            indent(writer, depth);
            writer.println("ps.writeInt(" + writeLabel + ".size());");
            indent(writer, depth);
            writer.println("for (int i = 0; i < " + writeLabel + ".size(); i++) {;");
            ((TypeNode)member).genJavaWrite(writer, depth+1, writeLabel + ".get(i)");
            indent(writer, depth);
            writer.println("}");
        }

        String javaRead() {
            error("Internal - Should not call RepeatNode.javaRead()");
            return "";
        }

        public void genJavaRead(PrintWriter writer, int depth,
                                String readLabel) {
            String cntLbl = readLabel + "Count";
            indent(writer, depth);
            writer.println("int " + cntLbl + " = ps.readInt();");
            indent(writer, depth);
            writer.println(readLabel + " = new " + javaType() + "(Value.typeForClass(" + javaType() + ".class), " + cntLbl +")");
            indent(writer, depth);
            writer.println("for (int i = 0; i < " + cntLbl + "; i++) {;");
            String readLbl = readLabel + "Tmp";
            writer.println(member.javaType() + " " + readLbl + " = null;");
            member.genJavaRead(writer, depth+1, readLbl);
            writer.println(readLabel + ".set(i, " + readLbl + ")");
            indent(writer, depth);
            writer.println("}");
        }
    }

    class ArrayObjectTypeNode extends ObjectTypeNode {

        String docType() {
            return "arrayID";
        }

        String javaType() {
            return "Reference.ArrayReference";
        }
    }

    class ArrayRegionTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "arrayregion";
        }

        String javaType() {
            return "BasicListValue<?>";
        }

        String javaRead() {
            return "BasicListValue.read(ps)";
        }
    }

    class ArrayTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "arrayTypeID";
        }

        String javaType() {
            return "Reference.ArrayTypeReference";
        }
    }

    class BooleanTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "boolean";
        }

        @Override
        String javaType() {
            return "BooleanValue";
        }
    }

    class ByteTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "byte";
        }

        @Override
        String javaType() {
            return "ByteValue";
        }
    }

    class ClassLoaderObjectTypeNode extends ObjectTypeNode {

        String docType() {
            return "classLoaderID";
        }

        String javaType() {
            return "Reference.ClassLoaderReference";
        }
    }

    class ClassObjectTypeNode extends ObjectTypeNode {

        String docType() {
            return "classObjectID";
        }

        String javaType() {
            return "Reference.ClassObjectReference";
        }
    }

    class ClassTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "classID";
        }

        String javaType() {
            return "Reference.ClassTypeReference";
        }
    }

    class FieldTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "fieldID";
        }

        String javaType() {
            return "Reference.FieldReference";
        }
    }

    class FrameTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "frameID";
        }

        String javaType() {
            return "Reference.FrameReference";
        }
    }

    class InterfaceTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "interfaceID";
        }

        String javaType() {
            return "Reference.InterfaceTypeReference";
        }
    }

    class IntTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "int";
        }

        @Override
        String javaType() {
            return "IntValue";
        }
    }

    class LocationTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "location";
        }

        String javaType() {
            return "Location";
        }
    }

    class LongTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "long";
        }

        @Override
        String javaType() {
            return "LongValue";
        }
    }

    class MethodTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "methodID";
        }

        String javaType() {
            return "Reference.MethodReference";
        }
    }

    class ModuleTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "moduleID";
        }

        String javaType() {
            return "Reference.ModuleReference";
        }
    }

    class ThreadObjectTypeNode extends ObjectTypeNode {

        String docType() {
            return "threadID";
        }

        String javaType() {
            return "Reference.ThreadReference";
        }
    }

    class StringTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "string";
        }

        String javaType() {
            return "StringValue";
        }
    }

    class ThreadGroupObjectTypeNode extends ObjectTypeNode {

        String docType() {
            return "threadGroupID";
        }

        String javaType() {
            return "Reference.ThreadGroupReference";
        }
    }


    class ObjectTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "objectID";
        }

        String javaType() {
            return "Reference.ObjectReference";
        }
    }

    class TaggedObjectTypeNode extends ObjectTypeNode {

        String docType() {
            return "tagged-objectID";
        }

        public void genJavaWrite(PrintWriter writer, int depth,
                                 String writeLabel) {
            writer.println(String.format("ps.writeValueTagged(%s);", writeLabel));
        }

        String javaRead() {
            return "Reference.ObjectReference.readTagged(ps)";
        }
    }

    class ValueTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "value";
        }

        String javaType() {
            return "Value.BasicValue<?>";
        }

        public void genJavaWrite(PrintWriter writer, int depth,
                                 String writeLabel) {
            indent(writer, depth);
            writer.println("ps.writeValueTagged(" + writeLabel + ");");
        }

        String javaRead() {
            return "ps.readValue();";
        }
    }

    class UntaggedValueTypeNode extends ValueTypeNode {

        String docType() {
            return "untagged-value";
        }

        public void genJavaWrite(PrintWriter writer, int depth,
                                 String writeLabel) {
            indent(writer, depth);
            writer.println(writeLabel + ".write(ps);");
        }

        String javaRead() {
            // we add special cases for all appearing untagged values
            // this is far easier than generalizing it
            // and there are currently only two different cases
            if (parent.javaType().equals("FieldValue")) {
                // we know here that the result is a basic value
                String fieldVarName = ((FieldTypeNode) this.parent.components.get(0)).name;
                // we get the actual type by using the vm and the field id
                return String.format("ps.readUntaggedFieldValue(%s)", fieldVarName);
            }
            if (((ArrayObjectTypeNode)this.parent.parent.components.get(0)).name().equals("arrayObject")) {
                return String.format("ps.readUntaggedArrayValue(arrayObject);");
            }
            throw new AssertionError();
        }
    }

    class StringObjectTypeNode extends ObjectTypeNode {

        String docType() {
            return "stringID";
        }

        String javaType() {
            return "Reference.ObjectReference";
        }

        String javaRead() {
            return "Reference.ObjectReference.read((byte)JDWP.Tag.STRING, ps)";
        }
    }

    class ReferenceIDTypeNode extends AbstractSimpleTypeNode {

        String docType() {
            return "referenceTypeID";
        }

        String javaType() {
            return "Reference.ClassReference";
        }
    }
}
