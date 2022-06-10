/*
 * Copyright (c) 1998, 2020, Oracle and/or its affiliates. All rights reserved.
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

import build.tools.jdwpgen.MetadataNode.EntryNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Parse {

    final StreamTokenizer izer;
    final Map<String, Node> kindMap = new HashMap<>();

    Parse(Reader reader) {
        izer = new StreamTokenizer(new BufferedReader(reader));
        izer.resetSyntax();
        izer.slashStarComments(true);
        izer.slashSlashComments(true);
        izer.wordChars('a', 'z');
        izer.wordChars('A', 'Z');
        izer.wordChars('0', '9');
        izer.wordChars('_', '_');
        izer.wordChars('-', '-');
        izer.wordChars('.', '.');
        izer.whitespaceChars(0, 32);
        izer.quoteChar('"');
        izer.quoteChar('\'');

        kindMap.put("CommandSet", new CommandSetNode());
        kindMap.put("Command", new CommandNode());
        kindMap.put("Out", new OutNode());
        kindMap.put("Reply", new ReplyNode());
        kindMap.put("ErrorSet", new ErrorSetNode());
        kindMap.put("Error", new ErrorNode());
        kindMap.put("Event", new EventNode());
        kindMap.put("Repeat", new RepeatNode());
        kindMap.put("Group", new GroupNode());
        kindMap.put("Select", new SelectNode());
        kindMap.put("Alt", new AltNode());
        kindMap.put("ConstantSet", new ConstantSetNode());
        kindMap.put("Constant", new ConstantNode());
        kindMap.put("int", new TypeNode.IntTypeNode());
        kindMap.put("long", new TypeNode.LongTypeNode());
        kindMap.put("boolean", new TypeNode.BooleanTypeNode());
        kindMap.put("object", new TypeNode.ObjectTypeNode());
        kindMap.put("threadObject", new TypeNode.ThreadObjectTypeNode());
        kindMap.put("threadGroupObject", new TypeNode.ThreadGroupObjectTypeNode());
        kindMap.put("arrayObject", new TypeNode.ArrayObjectTypeNode());
        kindMap.put("stringObject", new TypeNode.StringObjectTypeNode());
        kindMap.put("classLoaderObject", new TypeNode.ClassLoaderObjectTypeNode());
        kindMap.put("classObject", new TypeNode.ClassObjectTypeNode());
        kindMap.put("referenceTypeID", new TypeNode.ReferenceIDTypeNode());
        kindMap.put("classType", new TypeNode.ClassTypeNode());
        kindMap.put("interfaceType", new TypeNode.InterfaceTypeNode());
        kindMap.put("arrayType", new TypeNode.ArrayTypeNode());
        kindMap.put("method", new TypeNode.MethodTypeNode());
        kindMap.put("field", new TypeNode.FieldTypeNode());
        kindMap.put("frame", new TypeNode.FrameTypeNode());
        kindMap.put("string", new TypeNode.StringTypeNode());
        kindMap.put("moduleID", new TypeNode.ModuleTypeNode());
        kindMap.put("value", new TypeNode.ValueTypeNode());
        kindMap.put("byte", new TypeNode.ByteTypeNode());
        kindMap.put("bytes", new TypeNode.BytesTypeNode());
        kindMap.put("location", new TypeNode.LocationTypeNode());
        kindMap.put("tagged-object", new TypeNode.TaggedObjectTypeNode());
        kindMap.put("referenceType", new TypeNode.ReferenceIDTypeNode());
        kindMap.put("typed-sequence", new TypeNode.ArrayRegionTypeNode());
        kindMap.put("untagged-value", new TypeNode.UntaggedValueTypeNode());
        kindMap.put("Metadata", new MetadataNode());
        // some request and reply meta data
        MetadataNode.entries.forEach(e -> {
            if (kindMap.containsKey(e.getNodeName())) {
                throw new IllegalArgumentException("Duplicate entry name: " + e.getNodeName());
            }
            kindMap.put(e.getNodeName(), new EntryNode());
        });
    }

    RootNode items() throws IOException {
        List<Node> list = new ArrayList<>();

        while (izer.nextToken() != StreamTokenizer.TT_EOF) {
            izer.pushBack();
            list.add(item());
        }
        RootNode node =  new RootNode();
        node.set("Root", list, 1);
        return node;
    }

    Node item() throws IOException {
        switch (izer.nextToken()) {
            case StreamTokenizer.TT_EOF:
                error("Unexpect end-of-file");
                return null;

            case StreamTokenizer.TT_WORD: {
                String name = izer.sval;
                if (izer.nextToken() == '=') {
                    int ntok = izer.nextToken();
                    if (ntok == StreamTokenizer.TT_WORD) {
                        return new NameValueNode(name, izer.sval);
                    } else if (ntok == '\'') {
                        return new NameValueNode(name, izer.sval.charAt(0));
                    } else {
                        error("Expected value after: " + name + " =");
                        return null;
                    }
                } else {
                    izer.pushBack();
                    return new NameNode(name);
                }
            }

            case '"':
                return new CommentNode(izer.sval);

            case '(': {
                if (izer.nextToken() == StreamTokenizer.TT_WORD) {
                    String kind = izer.sval;
                    List<Node> list = new ArrayList<>();

                    while (izer.nextToken() != ')') {
                        izer.pushBack();
                        list.add(item());
                    }
                    Node proto = kindMap.get(kind);
                    if (proto == null) {
                        error("Invalid kind: " + kind);
                        return null;
                    } else {
                        try {
                            Node node = proto.getClass().getDeclaredConstructor().newInstance();
                            node.set(kind, list, izer.lineno());
                            return node;
                        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException exc) {
                            error(exc.toString());
                            return null;
                        }
                    }
                } else {
                    error("Expected kind identifier, got " + izer.ttype +
                          " : " + izer.sval);
                    return null;
                }
            }

            default:
                error("Unexpected character: '" + (char)izer.ttype + "'");
                return null;
        }
    }

    void error(String errmsg) {
        System.err.println(Main.specSource + ":" + izer.lineno() +
                           ": " + errmsg);
        throw new RuntimeException("Error: " + errmsg);
    }
}
