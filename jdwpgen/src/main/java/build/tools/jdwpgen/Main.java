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

import lombok.SneakyThrows;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

    static String specSource;
    static boolean genDebug = true;

    static void usage() {
        System.err.println();
        System.err.println(
            "java Main <spec_input> <options>...");
        System.err.println();
        System.err.println("Options:");
        System.err.println("-doc <doc_output>");
        System.err.println("-jdi <java_output>");
        System.err.println("-include <include_file_output>");
        System.err.println("-costfile <csv file that contains the cmd, cmd set and average milliseconds>");
        System.err.println("-rawcostfile <same as costfile but with multiple entries, overrides the costfile if passed>");
        System.err.println("   use the JDK from https://github.com/parttimenerd/jdk/blob/parttimenerd_jdwp_cost_recorder");
    }

    @SneakyThrows
    public static void main(String[] args) {
        Reader reader = null;
        PrintWriter doc = null;
        Path jdiFile = null;
        PrintWriter jdi = null;
        PrintWriter include = null;
        Path costFile = null;
        Path rawCostFile = null;

        // Parse arguments
        for (int i = 0 ; i < args.length ; ++i) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                String fn = args[++i];
                switch (arg) {
                    case "-doc":
                        doc = new PrintWriter(new FileWriter(fn));
                        break;
                    case "-jdi":
                        jdiFile = Paths.get(fn);
                        jdi = new PrintWriter(new FileWriter(fn));
                        break;
                    case "-include":
                        include = new PrintWriter(new FileWriter(fn));
                        break;
                    case "-costfile":
                        costFile = Paths.get(fn);
                        break;
                    case "-rawcostfile":
                        rawCostFile = Paths.get(fn);
                        break;
                    default:
                        System.err.println("Invalid option: " + arg);
                        usage();
                        return;
                }
            } else {
                specSource = arg;
                reader = new FileReader(specSource);
            }
        }
        if (reader == null) {
            System.err.println("<spec_input> must be specified");
            usage();
            return;
        }
        if (rawCostFile != null && costFile != null) {
            System.out.println("Override costfile");
            CostFile.loadJVMFile(rawCostFile).store(costFile);
        }

        Parse parse = new Parse(reader);
        RootNode root = parse.items();
        root.parentAndExtractComments();
        root.prune();
        root.constrain(new Context());
        if (doc != null) {
            root.document(doc);
            doc.close();
        }
        if (jdi != null) {
            root.genJava(jdi, jdiFile, costFile != null ? CostFile.loadFile(costFile) : CostFile.empty());
            jdi.close();
        }
        if (include != null) {
            root.genCInclude(include);
            include.close();
        }
    }
}
