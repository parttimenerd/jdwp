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
            error("Command $name must have Out and Reply items or ErrorSet item with OnlyReads item")
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
            genEventClass(evt).toString()
        }
        indentAndPrintMultiline(writer, depth, str)
    }

    // idea: keep the code at a single place

    private val commandClassName: String
        get() = name()
    private val requestClassName: String
        get() = commandClassName + "Request"
    private val replyClassName: String
        get() = commandClassName + "Reply"

    private fun Node.findGroupNodes(): List<GroupNode> = components
        .flatMap { (if (it is GroupNode) listOf(it) else emptyList()) + it.findGroupNodes() }

    private fun Node.findSelectNodes(): List<SelectNode> = components
        .flatMap { (if (it is SelectNode) listOf(it) else emptyList()) + it.findSelectNodes() }

    private fun genJava(out: OutNode, reply: ReplyNode, error: ErrorSetNode, onlyReads: OnlyReadsNode): String {
        return genRequestClass(out, onlyReads.boolean).toString() + "\n" + genReplyClass(reply) + "\n"
    }

    private fun TypeSpec.Builder.genCommon(node: AbstractTypeListNode, valType: String, defaultFlag: Int, fields: List<AbstractTypeNode>): TypeSpec.Builder {

        addTypes(fields.flatMap {  it.findGroupNodes() } .map { genGroupClass(it) })

        addTypes(fields.flatMap {  it.findSelectNodes() } .flatMap { genSelectClass(it) })

        `public static final field`(TypeName.INT, "COMMAND") { `=`(nameNode.value()) }

        for (f in fields) {
            `public final field`(f.javaType(), f.name()) { addJavadoc(f.comment()) }
        }

        `public constructor`(
            mutableListOf(param(TypeName.INT, "id"), param(TypeName.SHORT, "flags")) +
                    fields.map { param(it.javaType(), it.name()) }) {
            statement("super($valType, id, flags)")
            for (f in fields) {
                statement("this.\$N = \$N", f.name(), f.name())
            }
            this
        }

        `public constructor`(
            mutableListOf(param(TypeName.INT, "id")) +
                    fields.map { param(it.javaType(), it.name()) }) {
            statement("this(\$N)",
                (listOf("id", "(short)$defaultFlag") + fields.map { it.name() }).joinToString(", ")
            )
        }

        `public`(
            bg("Packet"), "toPacket",
            param("VM", "vm")
        ) {
            `@Override`()
            statement("PacketStream ps = new PacketStream(vm, COMMAND_SET, COMMAND)")
            for (f in fields) {
                addCode(f.genJavaWrite(f.name()) + ";\n")
            }
            statement("Packet packet = ps.toPacket()")
            statement("packet.id = id")
            statement("packet.flags = flags")
            _return("packet")
        }

        genCombinedTypeGet(fields)

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
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + ";\n")
                }
                statement("return new \$T(\$N)",
                    bg(requestClassName),
                    (listOf("ps.id()", "ps.flags()") + fields.map { it.name() }).joinToString(", ")
                )
            }

            genToString(requestClassName, fields)
            genEquals(requestClassName, fields)
            genHashCode(fields)

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
                        addCode(f.genJavaRead(f.javaType() + " " + f.name()) + ";\n")
                    }
                    statement("return new ReplyOrError<>(ps.id(), ps.flags(), new \$T(\$N))",
                        bg(replyClassName),
                        (listOf("ps.id()", "ps.flags()") + fields.map { it.name() }).joinToString(", ")
                    )
                }.end()
            }

            genToString(replyClassName, fields)
            genEquals(replyClassName, fields)
            genHashCode(fields)
        }
    }

    private fun genEventClass(evt: EventNode): TypeSpec {
        val fields = evt.components.map { t: Node -> t as AbstractTypeNode }
        val name = "Events"
        return `public static class`(name) {
            extends("AbstractParsedPacket")
            implements(bg("EventCollection"))

            addJavadoc(evt.parent.commentList.joinToString("\n"))

            genCommon(evt, "Type.EVENTS", 0, fields)

            `public static`(
                bg(name), "parse",
                param("VM", "vm"),
                param("Packet", "packet")
            ) {
                _return("parse(new PacketStream(vm, packet))")
            }

            `public static`(
                bg(name), "parse",
                param("PacketStream", "ps")
            ) {
                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + ";\n")
                }
                statement("return new \$T(\$N)",
                    bg(name),
                    (listOf("ps.id()", "ps.flags()") + fields.map { it.name() }).joinToString(", ")
                )
            }

            genToString(name, fields)
            genEquals(name, fields)
            genHashCode(fields)

            `public`(TypeName.BOOLEAN, "onlyReads") {
                `@Override`()
                _return(false.L)
            }

            `public`(pt("List", "EventCommon"), "getEvents") {
                `@Override`()
                _return("events.values")
            }

            `public`(TypeName.BYTE, "getSuspendPolicy") {
                `@Override`()
                _return("suspendPolicy.value")
            }
        }
    }

    private fun genGroupClass(node: GroupNode): TypeSpec {
        val fields = node.components.map { it as AbstractTypeNode }
        return `public static class`(node.name()) {
            extends("Value.CombinedValue")

            for (f in fields) {
                `public final field`(f.javaType(), f.name()) { addJavadoc(f.comment()) }
            }

            `public constructor`(
                fields.map { param(it.javaType(), it.name()) }) {
                statement("super(Type.OBJECT)")
                for (f in fields) {
                    statement("this.\$N = \$N", f.name(), f.name())
                }
                this
            }

            genCombinedTypeGet(fields)

            genToString(node.name(), fields)
            genEquals(node.name(), fields)
            genHashCode(fields)

            var parseParams = arrayOf(param("PacketStream", "ps")) +
                    (node.iterVariable()?.let { arrayOf(param("Value", it)) } ?: arrayOf<ParameterSpec.Builder>())

            `public static`(bg(node.name()), "parse", params = parseParams) {

                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + ";\n")
                }
                _return("new ${node.name()}(${fields.joinToString(", ") { it.name() }})")
            }

            genWrite(fields)
        }
    }

    private fun genSelectClass(node: SelectNode): List<TypeSpec> {
        val alts = node.components.map { it as AltNode }
        val kindNode = node.typeNode as AbstractTypeNode
        val className = node.commonBaseClass()
        val instanceName = "${node.name()}Instance"
        val commonFields = listOf(kindNode) + alts.fold(alts.first().components.map { it as AbstractTypeNode })
            {l, n -> l.filter { c -> n.components.any { val c2 = it as AbstractTypeNode
                c2.name() == c.name() && c2.javaType() == c.javaType()} }}
        return listOf(`public static abstract class`(className) {
            extends(instanceName)

            for (f in commonFields) {
                `public final field`(f.javaType(), f.name()) { addJavadoc(f.comment()) }
            }

            `public constructor`(
                commonFields.map { param(it.javaType(), it.name()) }) {
                statement("super(${commonFields.joinToString(", ") { "${it.name()}.value" }})")
                for (f in commonFields) {
                    statement("this.\$N = \$N", f.name(), f.name())
                }
                this
            }

            `public static`(bg(node.commonBaseClass()), "parse", param("PacketStream", "ps")) {
                statement("${kindNode.javaType()} ${kindNode.name()} = ${kindNode.javaType()}.read(ps)")
                switch("${kindNode.name()}.value") {
                   for (alt in alts) {
                       case("${alt.javaType()}.KIND") {
                           _return("${alt.javaType()}.parse(ps)")
                       }
                   }
                   default {
                       `throw new2`(
                           java.util.NoSuchElementException::class,
                           "\"Unknown command \" + ${kindNode.name()}"
                       )
                   }
                   this
               }
            }
        }) + alts.map { genAltClass(it, className, kindNode, commonFields.filter { n -> n != kindNode }) }
    }

    private fun genAltClass(
        alt: AltNode,
        commonClassName: String,
        kindNode: AbstractTypeNode,
        commonFields: List<AbstractTypeNode>
    ): TypeSpec {
        val fields = alt.components
            .map { it as AbstractTypeNode }
        val uncommonFields = fields
            .filter { n -> commonFields.all { it.name() != n.name() } }
        return `public static class`(alt.javaType()) {
            extends(commonClassName)

            `public static final field`(TypeName.BYTE, "KIND") { `=`(alt.nameNode.value()) }

            for (f in uncommonFields) {
                `public final field`(f.javaType(), f.name()) { addJavadoc(f.comment()) }
            }

            `public constructor`((commonFields + uncommonFields).map { param(it.javaType(), it.name()) }) {
                statement("super(${(listOf("PrimitiveValue.wrap(KIND)") + commonFields.map { it.name() }).joinToString(", ")})")
                for (f in uncommonFields) {
                    statement("this.\$N = \$N", f.name(), f.name())
                }
                this
            }

            `public static`(bg(alt.name()), "parse", param("PacketStream", "ps")) {
                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + ";\n")
                }
                _return("new ${alt.name()}(${(commonFields + uncommonFields).joinToString(", ") { it.name }})")
            }

            genCombinedTypeGet(listOf(kindNode) + fields)

            genToString(alt.name(), fields)
            genEquals(alt.name(), fields)
            genHashCode(fields)
            genWrite(listOf(kindNode) + fields)

            this
        }
    }

    private fun TypeSpec.Builder.genWrite(fields: List<AbstractTypeNode>) = `public`(
        TypeName.VOID, "write", param("PacketStream", "ps")
    ) {
        `@Override`()
        for (f in fields) {
            addCode(f.genJavaWrite(f.name()) + ";\n")
        }
        this
    }

    private fun TypeSpec.Builder.genToString(name: String, fields: List<AbstractTypeNode>) = `public`(String::class, "toString") {
        `@Override`()
        if (fields.isEmpty()) {
            _return("$name()".S)
        } else {
            _return("String.format(" +
                    (listOf("\"$name(${fields.joinToString(", ") { "${it.name()}=%s" }})\"") + fields.map { it.name() }).joinToString(", ") + ")"
            )
        }
    }

    private fun TypeSpec.Builder.genEquals(className: String, fields: List<AbstractTypeNode>) = `public`(TypeName.BOOLEAN, "equals", param("Object", "other")) {
        `@Override`()
        `if`("!(other instanceof ${className})") {
            _return("false")
        }.end()
        if (fields.isEmpty()) {
            _return(true.L);
        } else {
            statement("$className otherObj = ($className)other")
            _return(fields.joinToString(" && ") { "${it.name()}.equals(otherObj.${it.name()})"  })
        }
    }

    private fun TypeSpec.Builder.genHashCode(fields: List<AbstractTypeNode>) = `public`(TypeName.INT, "hashCode") {
        `@Override`()
        if (fields.isEmpty()) {
            _return(0.L)
        } else {
            _return("Objects.hash(${fields.joinToString(", ") { it.name() }})")
        }
    }

    private fun TypeSpec.Builder.genCombinedTypeGet(fields: List<AbstractTypeNode>) {
        `public`(bg("Value"), "get", param("String", "key")) {
            `@Override`()
            val exCode = { `throw new2`(java.util.NoSuchElementException::class, "\"Unknown field \" + key") }
            when (fields.size) {
                0 -> exCode()
                1 -> {
                    val first = fields.first().name()
                    `if` ("key.equals(${first.S})") {
                        _return(first)
                    }.end()
                    exCode()
                }
                else -> {
                    switch("key") {
                        for (f in fields) {
                            case(f.name().S) {
                                _return(f.name())
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
            `=`("List.of(${fields.joinToString(", ") { "\"${it.name()}\"" }})")
        }

        `private static final field`(pt("Set", "String"), "KEY_SET") {
            `=`("new HashSet<>(KEYS)")
        }

        `public`(pt("List", "String"), "getKeys") {
            `@Override`()
            _return("KEYS");
        }

        `public`(TypeName.BOOLEAN, "containsKey", param("String", "key")) {
            `@Override`()
            _return("KEY_SET.contains(key)")
        }

        `public`(ParameterizedTypeName.get(bg("List"), pt("Pair", "String", "Value")), "getValues") {
            `@Override`()
            _return("List.of(${fields.joinToString(", ") { "p(${it.name().S}, ${it.name()})" }})");
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

        private fun pt(base: String, type: String, type2: String): ParameterizedTypeName {
            return ParameterizedTypeName.get(bg(base), bg(type), bg(type2))
        }

        /** generate the parse method for every packet */
        @JvmStatic
        fun genAdditionalCommandSetCode(node: CommandSetNode): String {
            val retType = if (node.name == "Event") bg("Events") else pt("Request", "?")
            return `public static`(
                retType, "parse",
                param("VM", "vm"), param("Packet", "packet")
            ) {
                _return("parse(new PacketStream(vm, packet))")
                this
            }.toString() + "\n" +
                    `public static`(
                        retType, "parse",
                        param("PacketStream", "ps")
                    ) {
                        if (node.name == "Event") {
                            `if`("Events.COMMAND == ps.command()") {
                                _return("Events.parse(ps)")
                            }.end()
                            `throw new2`(
                                java.util.NoSuchElementException::class,
                                "\"Unknown command \" + ps.command()"
                            )
                        } else {
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
                                case("${cmdSet.name()}.COMMAND_SET") {
                                    _return("${cmdSet.name()}.parse(ps)")
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