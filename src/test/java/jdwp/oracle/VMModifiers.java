/*
 * Copyright (c) 1998, 2017, Oracle and/or its affiliates. All rights reserved.
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

package jdwp.oracle;

public interface VMModifiers {
    int PUBLIC = 0x00000001;        /* visible to everyone */
    int PRIVATE = 0x00000002;       /* visible only to the defining class */
    int PROTECTED = 0x00000004;     /* visible to subclasses */
    int STATIC = 0x00000008;        /* instance variable is static */
    int FINAL = 0x00000010;         /* no further subclassing, overriding */
    int SYNCHRONIZED = 0x00000020;  /* wrap method call in monitor lock */
    int VOLATILE = 0x00000040;      /* can cache in registers */
    int BRIDGE = 0x00000040;        /* Bridge method generated by compiler */
    int TRANSIENT = 0x00000080;     /* not persistant */
    int VARARGS = 0x00000080;       /* Method accepts var. args*/
    int NATIVE = 0x00000100;        /* implemented in C */
    int INTERFACE = 0x00000200;     /* class is an interface */
    int ABSTRACT = 0x00000400;      /* no definition provided */
    int ENUM_CONSTANT = 0x00004000; /* enum constant field*/
    int SYNTHETIC = 0xf0000000;     /* not in source code */
}
