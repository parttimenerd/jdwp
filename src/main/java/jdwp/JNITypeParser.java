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

package jdwp;

import jdwp.exception.TunnelException.JNITypeSignatureException;

import java.util.ArrayList;
import java.util.List;

public class JNITypeParser {

    static final char SIGNATURE_ENDCLASS = ';';
    static final char SIGNATURE_FUNC = '(';
    static final char SIGNATURE_ENDFUNC = ')';

    private final String signature;
    private List<String> typeNameList;
    private List<String> signatureList;
    private int currentIndex;

    public JNITypeParser(String signature) {
        this.signature = signature;
    }

    public static String typeNameToSignature(String typeName) {
        StringBuilder sb = new StringBuilder();
        int firstIndex = typeName.indexOf('[');
        int index = firstIndex;
        while (index != -1) {
            sb.append('[');
            index = typeName.indexOf('[', index + 1);
        }

        if (firstIndex != -1) {
            typeName = typeName.substring(0, firstIndex);
        }

        switch (typeName) {
            case "boolean":
                sb.append('Z');
                break;
            case "byte":
                sb.append('B');
                break;
            case "char":
                sb.append('C');
                break;
            case "short":
                sb.append('S');
                break;
            case "int":
                sb.append('I');
                break;
            case "long":
                sb.append('J');
                break;
            case "float":
                sb.append('F');
                break;
            case "double":
                sb.append('D');
                break;
            default:
                sb.append('L');
                index = typeName.indexOf("/");   // check if it's a hidden class

                if (index < 0) {
                    sb.append(typeName.replace('.', '/'));
                } else {
                    sb.append(typeName.substring(0, index).replace('.', '/'));
                    sb.append(".");
                    sb.append(typeName.substring(index + 1));
                }
                sb.append(';');
                break;
        }

        return sb.toString();
    }

    public String typeName() {
        return typeNameList().get(typeNameList().size()-1);
    }

    public List<String> argumentTypeNames() {
        return typeNameList().subList(0, typeNameList().size() - 1);
    }

    public String signature() {
        return signatureList().get(signatureList().size()-1);
    }

    public List<String> argumentSignatures() {
        return signatureList().subList(0, signatureList().size() - 1);
    }

    public int dimensionCount() {
        int count = 0;
        String signature = signature();
        while (signature.charAt(count) == '[') {
            count++;
        }
        return count;
    }

    public byte jdwpTag() {
        return (byte) signature.charAt(0);
    }

    public String componentSignature(int level) {
        assert level <= dimensionCount();
        return signature().substring(level);
    }

    public String componentSignature() {
        assert isArray();
        return componentSignature(1);
    }

    public boolean isArray() {
        return jdwpTag() == JDWP.Tag.ARRAY;
    }

    public boolean isVoid() {
        return jdwpTag() == JDWP.Tag.VOID;
    }

    public boolean isBoolean() {
        return jdwpTag() == JDWP.Tag.BOOLEAN;
    }

    public boolean isReference() {
        byte tag = jdwpTag();
        return tag == JDWP.Tag.ARRAY ||
                tag == JDWP.Tag.OBJECT;
    }

    public boolean isPrimitive() {
        switch (jdwpTag()) {
            case (JDWP.Tag.BOOLEAN):
            case (JDWP.Tag.BYTE):
            case (JDWP.Tag.CHAR):
            case (JDWP.Tag.SHORT):
            case (JDWP.Tag.INT):
            case (JDWP.Tag.LONG):
            case (JDWP.Tag.FLOAT):
            case (JDWP.Tag.DOUBLE):
                return true;
        }
        return false;
    }

    public static String convertSignatureToClassname(String classSignature) {
        assert classSignature.startsWith("L") && classSignature.endsWith(";");

        // trim leading "L" and trailing ";"
        String name = classSignature.substring(1, classSignature.length() - 1);
        int index = name.indexOf(".");  // check if it is a hidden class
        if (index < 0) {
            return name.replace('/', '.');
        } else {
            // map the type descriptor from: "L" + N + "." + <suffix> + ";"
            // to class name: N.replace('/', '.') + "/" + <suffix>
            return name.substring(0, index).replace('/', '.')
                    + "/" + name.substring(index + 1);
        }
    }

    private synchronized List<String> signatureList() {
        if (signatureList == null) {
            signatureList = new ArrayList<>(10);
            String elem;

            currentIndex = 0;

            while(currentIndex < signature.length()) {
                elem = nextSignature();
                signatureList.add(elem);
            }
            if (signatureList.size() == 0) {
                throw new JNITypeSignatureException("Invalid JNI signature '" +
                                                   signature + "'");
            }
        }
        return signatureList;
    }

    public static boolean checkSignature(String signature) {
        JNITypeParser parser = new JNITypeParser(signature);
        try {
            int currentIndex = 0;
            while(currentIndex < 10) {
                if (parser.currentIndex >= parser.signature.length()) {
                    return true;
                }
                if (getTagForFirstSignatureChar(parser.signature.charAt(parser.currentIndex)) == -1) {
                    return false;
                }
                parser.nextSignature();
                currentIndex++;
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private synchronized List<String> typeNameList() {
        if (typeNameList == null) {
            typeNameList = new ArrayList<>(10);
            String elem;

            currentIndex = 0;

            while(currentIndex < signature.length()) {
                elem = nextTypeName();
                typeNameList.add(elem);
            }
            if (typeNameList.size() == 0) {
                throw new JNITypeSignatureException("Invalid JNI signature '" +
                                                   signature + "'");
            }
        }
        return typeNameList;
    }

    private String nextSignature() {
        char key = signature.charAt(currentIndex++);

        switch(key) {
            case (JDWP.Tag.ARRAY):
                return  key + nextSignature();

            case (JDWP.Tag.OBJECT):
                int endClass = signature.indexOf(SIGNATURE_ENDCLASS,
                                                 currentIndex);
                String retVal = signature.substring(currentIndex - 1,
                                                    endClass + 1);
                currentIndex = endClass + 1;
                return retVal;

            case (JDWP.Tag.VOID):
            case (JDWP.Tag.BOOLEAN):
            case (JDWP.Tag.BYTE):
            case (JDWP.Tag.CHAR):
            case (JDWP.Tag.SHORT):
            case (JDWP.Tag.INT):
            case (JDWP.Tag.LONG):
            case (JDWP.Tag.FLOAT):
            case (JDWP.Tag.DOUBLE):
                return String.valueOf(key);

            case SIGNATURE_ENDFUNC:
            case SIGNATURE_FUNC:
                return nextSignature();

            default:
                throw new JNITypeSignatureException(
                    "Invalid JNI signature character '" + key + "'");

        }
    }

    /** -1 if invalid */
    public static int getTagForFirstSignatureChar(char c) {
        switch (c) {
            case (JDWP.Tag.ARRAY):
            case (JDWP.Tag.OBJECT):
            case (JDWP.Tag.VOID):
            case (JDWP.Tag.BOOLEAN):
            case (JDWP.Tag.BYTE):
            case (JDWP.Tag.CHAR):
            case (JDWP.Tag.SHORT):
            case (JDWP.Tag.INT):
            case (JDWP.Tag.LONG):
            case (JDWP.Tag.FLOAT):
            case (JDWP.Tag.DOUBLE):
                return c;
        }
        return -1;
    }

    private String nextTypeName() {
        char key = signature.charAt(currentIndex++);

        switch(key) {
            case (JDWP.Tag.ARRAY):
                return  nextTypeName() + "[]";

            case (JDWP.Tag.BYTE):
                return "byte";

            case (JDWP.Tag.CHAR):
                return "char";

            case (JDWP.Tag.OBJECT):
                int endClass = signature.indexOf(SIGNATURE_ENDCLASS,
                                                 currentIndex);
                String retVal = signature.substring(currentIndex,
                                                    endClass);
                int index = retVal.indexOf(".");
                if (index < 0) {
                    retVal = retVal.replace('/', '.');
                } else {
                    // hidden class
                    retVal = retVal.substring(0, index).replace('/', '.')
                                + "/" + retVal.substring(index + 1);
                }
                currentIndex = endClass + 1;
                return retVal;

            case (JDWP.Tag.FLOAT):
                return "float";

            case (JDWP.Tag.DOUBLE):
                return "double";

            case (JDWP.Tag.INT):
                return "int";

            case (JDWP.Tag.LONG):
                return "long";

            case (JDWP.Tag.SHORT):
                return "short";

            case (JDWP.Tag.VOID):
                return "void";

            case (JDWP.Tag.BOOLEAN):
                return "boolean";

            case SIGNATURE_ENDFUNC:
            case SIGNATURE_FUNC:
                return nextTypeName();

            default:
                throw new JNITypeSignatureException(
                    "Invalid JNI signature character '" + key + "'");
        }
    }
}
