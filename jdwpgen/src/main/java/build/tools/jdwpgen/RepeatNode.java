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

import org.jetbrains.annotations.Nullable;

import java.io.*;

class RepeatNode extends TypeNode.AbstractTypeNode {

    Node member = null;

    @Nullable
    String iterVariable() {
        return name.contains(".") ? name.split("\\.")[1] : null;
    }

    @Override
    public String name() {
        return name.split("\\.")[0];
    }

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
        writer.println("for (int i = 0; i < " + writeLabel + ".size(); i++) {");
        indent(writer, depth);
        ((TypeNode)member).genJavaWrite(writer, depth + 1, writeLabel + ".get(i)");
        indent(writer, depth);
        writer.println("}");
    }

    String javaRead() {
        error("Internal - Should not call RepeatNode.javaRead()");
        return "";
    }

    public void genJavaRead(PrintWriter writer, int depth,
                            String readLabel) {
        var variable = readLabel.split(" ")[1];
        String cntLbl = variable + "Count";
        String listLbl = variable + "TmpList";
        indent(writer, depth);
        writer.println("int " + cntLbl + " = ps.readInt();");
        indent(writer, depth);
        writer.println("List<" + member.javaType() + "> " + listLbl + " = new ArrayList<>(" + cntLbl + ");");
        indent(writer, depth);
        writer.println("for (int i = 0; i < " + cntLbl + "; i++) {");
        indent(writer, depth + 1);
        if (iterVariable() != null) { // only support for group nodes currently
            member.genJavaRead(writer, depth, member.javaType() + " tmp", iterVariable());
        } else {
            member.genJavaRead(writer, depth, member.javaType() + " tmp");
        }
        writer.println();
        indent(writer, depth + 1);
        writer.println(listLbl + ".add(tmp);");
        indent(writer, depth);
        writer.println("}");
        indent(writer, depth);
        writer.println(readLabel + " = new ListValue<>(Value.Type.forClass(" + member.javaType().replace("<?>", "") + ".class), " + listLbl + ");");
    }
}
