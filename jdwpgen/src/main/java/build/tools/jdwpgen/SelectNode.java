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

import java.util.*;
import java.io.*;

class SelectNode extends AbstractGroupNode implements TypeNode {

    AbstractSimpleTypeNode typeNode = null;

    void prune() {
        super.prune();
        Iterator<Node> it = components.iterator();

        if (it.hasNext()) {
            Node typeNode = it.next();

            if (typeNode instanceof ByteTypeNode ||
                      typeNode instanceof IntTypeNode) {
                this.typeNode = (AbstractSimpleTypeNode)typeNode;
                it.remove();
            } else {
                error("Select must be based on 'int' or 'byte'");
            }
        } else {
            error("empty");
        }
    }

    void constrain(Context ctx) {
        super.constrain(ctx);
        if (components.size() < 2) {
            error("Select must have at least two options");
        }
    }

    void constrainComponent(Context ctx, Node node) {
        node.constrain(ctx);
        if (!(node instanceof AltNode)) {
            error("Select must consist of selector followed by Alt items");
        }
    }

    void document(PrintWriter writer) {
        typeNode.document(writer);
        super.document(writer);
    }

    String commonBaseClass() {
        return name() + "Common";
    }

    @Override
    String javaType() {
        return commonBaseClass();
    }

    private String commonVar() {
        return " a" + commonBaseClass();
    }

    public void genJavaRead(PrintWriter writer, int depth,
                            String readLabel) {
        indent(writer, depth);
        writer.print(readLabel);
        writer.print(" = " + commonBaseClass() + ".parse(ps);");
    }
}
