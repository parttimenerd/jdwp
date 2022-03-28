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
package build.tools.jdwpgen

import build.tools.jdwpgen.TypeNode.AbstractTypeNode
import com.grosner.kpoet.*
import com.squareup.javapoet.*
import java.io.PrintWriter
import java.util.function.Consumer


// written in kotlin to be able to use KPoet

internal class CommandNode : AbstractCommandNode() {
    public override fun constrain(ctx: Context) {
        if (components.size == 4) {
            val out = components[0]
            val reply = components[1]
            val error = components[2]
            val onlyReads = components[3]
            if (out !is OutNode) {
                error("Expected 'Out' item, got: $out")
            }
            if (reply !is ReplyNode) {
                error("Expected 'Reply' item, got: $reply")
            }
            if (error !is ErrorSetNode) {
                error("Expected 'ErrorSet' item, got: $error")
            }
            if (onlyReads !is OnlyReadsNode) {
                error("Expected 'OnlyReads' item, got: $error")
            }
        } else if (components.size == 1) {
            val evt = components[0]
            if (evt !is EventNode) {
                error("Expected 'Event' item, got: $evt")
            }
        } else {
            error("Command must have Out and Reply items or ErrorSet item")
        }
        super.constrain(ctx)
    }

    public override fun genJavaClassSpecifics(writer: PrintWriter, depth: Int) {
    }

    public override fun genJava(writer: PrintWriter, depth: Int) {
        genJavaClassSpecifics(writer, depth)
        var str = ""
        str = if (components.size == 4) {
            val out = components[0] as OutNode
            val reply = components[1] as ReplyNode
            val error = components[2] as ErrorSetNode
            val onlyReads = components[3] as OnlyReadsNode
            genJava(out, reply, error, onlyReads)
        } else {
            val evt = components[0] as EventNode
            genJava(evt)
        }
        indentAndPrintMultiline(writer, depth, str)
    }

    // idea: keep the code at a single place
    private fun genJava(evt: EventNode): String {
        return ""
    }

    private val commandClassName: String
        get() = name
    private val requestClassName: String
        get() = commandClassName + "Request"
    private val replyClassName: String
        get() = commandClassName + "Reply"

    private fun genJava(out: OutNode, reply: ReplyNode, error: ErrorSetNode, onlyReads: OnlyReadsNode): String {
        val fields = out.components.map { t: Node -> t as AbstractTypeNode }
        val replyFields = reply.components.map { t: Node -> t as AbstractTypeNode }
        return genRequestClass(fields, onlyReads.boolean).toString() + "\n" + genReplyClass(replyFields)
    }

    fun TypeSpec.Builder.genCommon(valType: String, fields: List<AbstractTypeNode>): TypeSpec.Builder {

        `public static final field`(TypeName.INT, "COMMAND") { `=`(nameNode.value()) }

        `public final field`(TypeName.INT, "id");
        `public final field`(TypeName.SHORT, "flags");

        for (f in fields) {
            `public final field`(f.javaType(), f.name)
        }

        `public constructor`(
            mutableListOf(param(TypeName.INT, "id"), param(TypeName.SHORT, "flags")) +
                    fields.map { param(it.javaType(), it.name()) }) {
            statement("super(Type.REQUEST)")
            statement("this.id = id")
            statement("this.flags = flags")
            for (f in fields) {
                statement("this.\$N = \$N", f.name(), f.name())
            }
            this
        }

        `public`(
            bg("Packet"), "toPacket",
            param("VM", "vm")
        ) {
            statement("PacketStream ps = new PacketStream(vm, COMMAND_SET, COMMAND)")
            for (f in fields) {
                statement(f.genJavaWrite(f.name))
            }
            statement("Packet packet = ps.toPacket()")
            statement("packet.id = id")
            statement("packet.flags = flags")
            _return("packet")
        }

        `public`(bg("Value"), "get", param("String", "key")) {
            `@`(Override::class)
            switch("key") {
                for (f in fields) {
                    case(f.name.S) {
                        _return(f.name)
                    }
                }
                default {
                    `throw new2`(java.util.NoSuchElementException::class, "\"Unknown field \" + key")
                }
                this
            }
        }

        `private static final field`(pt("List", "String"), "KEYS") {
            `=`("Arrays.asList(${fields.joinToString(",") { "\"${it.name}\"" }})")
        }

        `public`(pt("List", "String"), "getKeys") {
            `@`(Override::class)
            _return("KEYS");
        }

        `public`(TypeName.INT, "getCommand") {
            `@`(Override::class)
            _return("COMMAND")
        }

        `public`(TypeName.INT, "getCommandSet") {
            `@`(Override::class)
            _return("COMMAND_SET")
        }

        `public`(TypeName.INT, "getId") {
            `@`(Override::class)
            _return("id")
        }

        `public`(TypeName.SHORT, "getFlags") {
            `@`(Override::class)
            _return("flags")
        }
        return this
    }

    private fun genRequestClass(fields: List<AbstractTypeNode>, onlyReads: Boolean): TypeSpec {
        return `public static class`(requestClassName) {
            extends("Value.CombinedValue")
            implements(pt("Request", replyClassName))

            genCommon("Type.REQUEST", fields)

            `public static`(
                bg(requestClassName), "parse",
                param("VM", "vm"),
                param("Packet", "packet")
            ) {
                _return("parse(new PacketStream(vm, packet))")
            }

            `public static`(
                bg(requestClassName), "parse",
                param("PacketStream", "ps")
            ) {
                for (f in fields) {
                    statement(f.genJavaRead(f.javaType() + " " + f.name))
                }
                statement("return new \$T(ps.id(), ps.flags(), \$N)",
                    bg(requestClassName),
                    fields.joinToString(", ") { it.name }
                )
            }

            `public`(String::class, "toString") {
                `@`(Override::class)
                _return("String.format(\"$replyClassName(${fields.joinToString(", ") { "${it.name}=%s" }})\", " +
                        fields.joinToString(", ") { it.name } + ")");
            }

            `public`(pt("ReplyOrError", replyClassName), "parseReply", param("PacketStream", "ps")) {
                `@`(Override::class)
                _return("$replyClassName.parse(ps)")
            }

            `public`(TypeName.BOOLEAN, "onlyReads") {
                `@`(Override::class)
                _return(onlyReads.L)
            }
        }
    }

    private fun genReplyClass(fields: List<AbstractTypeNode>): TypeSpec {
        return `public static class`(replyClassName) {
            extends("Value.CombinedValue")
            implements("Reply")

            genCommon("Type.REPLY", fields)

            `public static`(
                pt("ReplyOrError", replyClassName), "parse",
                param("VM", "vm"),
                param("Packet", "packet")
            ) {
                _return("parse(new PacketStream(vm, packet))")
            }

            `public static`(
                pt("ReplyOrError", replyClassName), "parse",
                param("PacketStream", "ps")
            ) {
                `if`("ps.errorCode() != 0") {
                    _return("new ReplyOrError<>(ps.id(), ps.flags(), ps.errorCode())")
                }.`else` {
                    for (f in fields) {
                        statement(f.genJavaRead(f.javaType() + " " + f.name))
                    }
                    statement("return new ReplyOrError<>(ps.id(), ps.flags(), new \$T(ps.id(), ps.flags(), \$N))",
                        bg(replyClassName),
                        fields.joinToString(", ") { it.name }
                    )
                }.end()
            }

            `public`(String::class, "toString") {
                `@`(Override::class)
                _return("String.format(\"$replyClassName(${fields.joinToString(", ") { "${it.name}=%s" }})\", " +
                        fields.joinToString(", ") { it.name } + ")");
            }
        }
    }

    companion object {

        private fun bg(name: String): ClassName {
            return ClassName.bestGuess(name)
        }

        private fun pt(base: String, type: String = "?"): ParameterizedTypeName {
            if (type == "?") {
                return ParameterizedTypeName.get(bg(base), WildcardTypeName.subtypeOf(Any::class.java))
            }
            return ParameterizedTypeName.get(bg(base), bg(type))
        }

        /** generate the parse method for every packet */
        @JvmStatic
        fun genAdditionalCommandSetCode(node: CommandSetNode): String {
            return `public static`(
                pt("Request", "?"), "parse",
                param("VM", "vm"), param("Packet", "packet")
            ) {
                _return("parse(new PacketStream(vm, packet))")
                this
            }.toString() + "\n" +
                    `public static`(
                        pt("Request", "?"), "parse",
                        param("PacketStream", "ps")
                    ) {
                        switch("ps.command()") {
                            for (cmd in node.components.map { it as CommandNode }) {
                                case("${cmd.commandClassName}Request.COMMAND") {
                                    _return("${cmd.commandClassName}Request.parse(ps)")
                                }
                                default {
                                    `throw new2`(
                                        java.util.NoSuchElementException::class,
                                        "\"Unknown command \" + ps.command()"
                                    )
                                }
                            }
                            this
                        }
                        this
                    }
        }

        /** generate the parse method for every packet */
        @JvmStatic
        fun genAdditionalRootCode(nodes: List<CommandSetNode>): String {
            return `public static`(
                pt("Request", "?"), "parse",
                param("VM", "vm"), param("Packet", "packet")
            ) {
                _return("parse(new PacketStream(vm, packet))")
                this
            }.toString() + "\n" +
                    `public static`(
                        pt("Request", "?"), "parse",
                        param("PacketStream", "ps")
                    ) {
                        switch("ps.command()") {
                            for (cmdSet in nodes) {
                                case("${cmdSet.name}.COMMAND_SET") {
                                    _return("${cmdSet.name}.parse(ps)")
                                }
                                default {
                                    `throw new2`(
                                        java.util.NoSuchElementException::class,
                                        "\"Unknown command set \" + ps.commandSet()"
                                    )
                                }
                            }
                            this
                        }
                        this
                    }
        }
    }
}