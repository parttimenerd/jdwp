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
            error("Command " + name + " must have Out and Reply items or ErrorSet item with OnlyReads item")
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

    private fun Node.findGroupNodes(): List<GroupNode> = components
        .flatMap { (if (it is GroupNode) listOf(it) else emptyList()) + it.findGroupNodes() }

    private fun genJava(out: OutNode, reply: ReplyNode, error: ErrorSetNode, onlyReads: OnlyReadsNode): String {
        return genRequestClass(out, onlyReads.boolean).toString() + "\n" + genReplyClass(reply) + "\n"
    }

    fun TypeSpec.Builder.genCommon(node: AbstractTypeListNode, valType: String, defaultFlag: Int, fields: List<AbstractTypeNode>): TypeSpec.Builder {

        addTypes(fields.flatMap {  it.findGroupNodes() } .map { genGroupClass(it) })

        `public static final field`(TypeName.INT, "COMMAND") { `=`(nameNode.value()) }

        for (f in fields) {
            `public final field`(f.javaType(), f.name) { addJavadoc(f.comment()) }
        }

        `public constructor`(
            mutableListOf(param(TypeName.INT, "id"), param(TypeName.SHORT, "flags")) +
                    fields.map { param(it.javaType(), it.name()) }) {
            statement("super(Type.REQUEST, id, flags)")
            for (f in fields) {
                statement("this.\$N = \$N", f.name(), f.name())
            }
            this
        }

        `public constructor`(
            mutableListOf(param(TypeName.INT, "id")) +
                    fields.map { param(it.javaType(), it.name()) }) {
            statement("this(\$N)",
                (listOf("id", "(short)$defaultFlag") + fields.map { it.name }).joinToString(", ")
            )
        }

        `public`(
            bg("Packet"), "toPacket",
            param("VM", "vm")
        ) {
            `@Override`()
            statement("PacketStream ps = new PacketStream(vm, COMMAND_SET, COMMAND)")
            for (f in fields) {
                addCode(f.genJavaWrite(f.name) + ";\n")
            }
            statement("Packet packet = ps.toPacket()")
            statement("packet.id = id")
            statement("packet.flags = flags")
            _return("packet")
        }

        `public`(bg("Value"), "get", param("String", "key")) {
            `@Override`()
            val exCode = { `throw new2`(java.util.NoSuchElementException::class, "\"Unknown field \" + key") }
            when (fields.size) {
                0 -> exCode()
                1 -> {
                    val first = fields.first().name
                    `if` ("key.equals(${first.S})") {
                        _return(first)
                    }.end()
                    exCode()
                }
                else -> {
                    switch("key") {
                        for (f in fields) {
                            case(f.name.S) {
                                _return(f.name)
                            }
                        }
                        default {
                            exCode()
                        }
                        this
                    }
                }
            }
        }

        `private static final field`(pt("List", "String"), "KEYS") {
            `=`("List.of(${fields.joinToString(", ") { "\"${it.name}\"" }})")
        }

        `public`(pt("List", "String"), "getKeys") {
            `@Override`()
            _return("KEYS");
        }

        `public`(TypeName.INT, "getCommand") {
            `@Override`()
            _return("COMMAND")
        }

        `public`(TypeName.INT, "getCommandSet") {
            `@Override`()
            _return("COMMAND_SET")
        }
        return this
    }

    private fun genRequestClass(out: OutNode, onlyReads: Boolean): TypeSpec {
        val fields = out.components.map { t: Node -> t as AbstractTypeNode }
        return `public static class`(requestClassName) {
            extends("AbstractParsedPacket")
            implements(pt("Request", replyClassName))

            addJavadoc(out.parent.commentList.joinToString("\n"))

            genCommon(out, "Type.REQUEST", 0, fields)

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
                    addCode(f.genJavaRead(f.javaType() + " " + f.name) + ";\n")
                }
                statement("return new \$T(\$N)",
                    bg(requestClassName),
                    (listOf("ps.id()", "ps.flags()") + fields.map { it.name }).joinToString(", ")
                )
            }

            `public`(String::class, "toString") {
                `@Override`()
                _return("String.format(" +
                        (listOf("\"$requestClassName(${fields.joinToString(", ") { "${it.name}=%s" }})\"") + fields.map { it.name }).joinToString(", ") + ")");
            }

            `public`(pt("ReplyOrError", replyClassName), "parseReply", param("PacketStream", "ps")) {
                `@Override`()
                `if`("ps.pkt.id != id") {
                    `throw new2`(bg("Reply.IdMismatchException"), "id, ps.pkt.id")
                }.end()
                _return("$replyClassName.parse(ps)")
            }

            `public`(TypeName.BOOLEAN, "onlyReads") {
                `@Override`()
                _return(onlyReads.L)
            }
        }
    }

    private fun genReplyClass(reply: ReplyNode): TypeSpec {
        val fields = reply.components.map { t: Node -> t as AbstractTypeNode }
        return `public static class`(replyClassName) {
            extends("AbstractParsedPacket")
            implements("Reply")

            addJavadoc("@see $requestClassName")

            genCommon(reply,"Type.REPLY", 0x8, fields)

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
                        addCode(f.genJavaRead(f.javaType() + " " + f.name) + ";\n")
                    }
                    statement("return new ReplyOrError<>(ps.id(), ps.flags(), new \$T(\$N))",
                        bg(replyClassName),
                        (listOf("ps.id()", "ps.flags()") + fields.map { it.name }).joinToString(", ")
                    )
                }.end()
            }

            `public`(String::class, "toString") {
                `@Override`()
                _return("String.format(" +
                        (listOf("\"$replyClassName(${fields.joinToString(", ") { "${it.name}=%s" }})\"") + fields.map { it.name }).joinToString(", ") + ")");
            }
        }
    }

    private fun genGroupClass(node: GroupNode): TypeSpec {
        val fields = node.components.map { it as AbstractTypeNode }
        return return `public static class`(node.name) {
            extends("Value.CombinedValue")

            for (f in fields) {
                `public final field`(f.javaType(), f.name) { addJavadoc(f.comment()) }
            }

            `public constructor`(
                fields.map { param(it.javaType(), it.name()) }) {
                statement("super(Type.OBJECT)")
                for (f in fields) {
                    statement("this.\$N = \$N", f.name(), f.name())
                }
                this
            }

            `public`(bg("Value"), "get", param("String", "key")) {
                `@Override`()
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
                `=`("List.of(${fields.joinToString(", ") { "\"${it.name}\"" }})")
            }

            `public`(pt("List", "String"), "getKeys") {
                `@Override`()
                _return("KEYS");
            }

            `public static`(bg(node.name), "parse", param("PacketStream", "ps")) {
                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + ";\n")
                }
                _return("new ${node.name}(${fields.joinToString(", ") { it.name }})")
            }

            `public`(
                TypeName.VOID, "write", param("PacketStream", "ps")
            ) {
                `@Override`()
                for (f in fields) {
                    addCode(f.genJavaWrite(f.name) + ";\n")
                }
                this
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
                            }
                            default {
                                `throw new2`(
                                    java.util.NoSuchElementException::class,
                                    "\"Unknown command \" + ps.command()"
                                )
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
                        switch("ps.commandSet()") {
                            for (cmdSet in nodes) {
                                case("${cmdSet.name}.COMMAND_SET") {
                                    _return("${cmdSet.name}.parse(ps)")
                                }
                            }
                            default {
                                `throw new2`(
                                    java.util.NoSuchElementException::class,
                                    "\"Unknown command set \" + ps.commandSet()"
                                )
                            }
                            this
                        }
                        this
                    }
        }
    }
}