package build.tools.jdwpgen

import com.grosner.kpoet.*
import com.squareup.javapoet.*
import javax.lang.model.element.Modifier

internal object CodeGeneration {

    private fun genCommandNodeJavaClasses(cmd: CommandNode): List<TypeSpec> {
        return if (cmd.isEventNode) listOf(generateEventClass(cmd)) else generateCommandClasses(cmd)
    }

    private fun generateCommandClasses(cmd: CommandNode): List<TypeSpec> {
        return listOf(genRequestClass(cmd), genReplyClass(cmd))
    }

    // idea: keep the code at a single place

    private fun Node.findGroupNodes(): List<GroupNode> = components
        .flatMap { (if (it is GroupNode) listOf(it) else emptyList()) + it.findGroupNodes() }

    private fun Node.findSelectNodes(): List<SelectNode> = components
        .flatMap { (if (it is SelectNode) listOf(it) else emptyList()) + it.findSelectNodes() }

    private fun TypeSpec.Builder.genCommon(cmd: CommandNode, valType: String, defaultFlag: Int, fields: List<TypeNode.AbstractTypeNode>): TypeSpec.Builder {

        addTypes(fields.flatMap {  it.findGroupNodes() } .map { genGroupClass(it) })

        addTypes(fields.flatMap {  it.findSelectNodes() } .flatMap { genSelectClass(it) })

        `public static final field`(TypeName.INT, "COMMAND") { `=`(cmd.nameNode.value()) }

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
            statement("PacketOutputStream ps = new PacketOutputStream(vm, COMMAND_SET, COMMAND)")
            for (f in fields) {
                addCode(f.genJavaWrite(f.name()))
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

        genVisitorAccept(allVisitorName)

        return this
    }

    private fun genRequestClass(cmd: CommandNode): TypeSpec {
        val out = cmd.out
        val fields = out.components.map { t: Node -> t as TypeNode.AbstractTypeNode }
        val requestClassName = cmd.requestClassName
        val replyClassName = cmd.replyClassName
        return `public static class`(requestClassName) {
            extends("AbstractParsedPacket")
            implements(pt("Request", cmd.replyClassName))

            addJavadoc(out.parent.commentList.joinToString("\n"))

            genCommon(cmd, "Type.REQUEST", 0, fields)

            `public static`(
                bg(requestClassName), "parse",
                param("VM", "vm"),
                param("Packet", "packet")
            ) {
                _return("parse(packet.toStream(vm))")
            }

            `public static`(
                bg(requestClassName), "parse",
                param("PacketInputStream", "ps")
            ) {
                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + "\n")
                }
                statement("return new \$T(\$N)",
                    bg(requestClassName),
                    (listOf("ps.id()", "ps.flags()") + fields.map { it.name() }).joinToString(", ")
                )
            }

            genToString(requestClassName, fields)
            genEquals(requestClassName, fields)
            genHashCode(fields)

            `public`(pt("ReplyOrError", replyClassName), "parseReply", param("PacketInputStream", "ps")) {
                `@Override`()
                `if`("ps.id() != id") {
                    `throw new2`(bg("Reply.IdMismatchException"), "id, ps.id()")
                }.end()
                _return("$replyClassName.parse(ps)")
            }

            `public`(TypeName.BOOLEAN, "onlyReads") {
                `@Override`()
                _return(cmd.onlyReads.L)
            }

            genVisitorAccept(requestVisitorName)

            `public`(
                TypeName.VOID, "accept", param(requestReplyVisitorName, "visitor"),
                param(bg("Reply"), "reply")
            ) {
                `@Override`()
                `if`("this.id != reply.getId()") {
                    `throw new2`(bg("AssertionError"), "wrong id".S)
                }.end()
                statement("visitor.visit(this, (${replyClassName})reply)")
            }
        }
    }

    private fun genReplyClass(cmd: CommandNode): TypeSpec {
        val reply = cmd.reply
        val fields = reply.components.map { t: Node -> t as TypeNode.AbstractTypeNode }
        val requestClassName = cmd.requestClassName
        val replyClassName = cmd.replyClassName
        return `public static class`(replyClassName) {
            extends("AbstractParsedPacket")
            implements("Reply")

            addJavadoc("@see $requestClassName")

            genCommon(cmd,"Type.REPLY", 0x80, fields)

            `public static`(
                pt("ReplyOrError", replyClassName), "parse",
                param("VM", "vm"),
                param("Packet", "packet")
            ) {
                _return("parse(packet.toStream(vm))")
            }

            `public static`(
                pt("ReplyOrError", replyClassName), "parse",
                param("PacketInputStream", "ps")
            ) {
                `if`("ps.errorCode() != 0") {
                    _return("new ReplyOrError<>(ps.id(), ps.flags(), ps.errorCode())")
                }.`else` {
                    for (f in fields) {
                        addCode(f.genJavaRead(f.javaType() + " " + f.name()) + "\n")
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
            genVisitorAccept(replyVisitorName)
        }
    }

    private fun generateEventClass(cmd: CommandNode): TypeSpec {
        val evt = cmd.eventNode
        val fields = evt.components.map { t: Node -> t as TypeNode.AbstractTypeNode }
        val name = "Events"
        return `public static class`(name) {
            extends("AbstractParsedPacket")
            implements(bg("EventCollection"))

            addJavadoc(evt.parent.commentList.joinToString("\n"))

            genCommon(cmd, "Type.EVENTS", 0, fields)

            `public static`(
                bg(name), "parse",
                param("VM", "vm"),
                param("Packet", "packet")
            ) {
                _return("parse(packet.toStream(vm))")
            }

            `public static`(
                bg(name), "parse",
                param("PacketInputStream", "ps")
            ) {
                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + "\n")
                }
                statement("return new \$T(\$N)",
                    bg(name),
                    (listOf("ps.id()", "ps.flags()") + fields.map { it.name() }).joinToString(", ")
                )
            }

            genToString(name, fields)
            genEquals(name, fields)
            genHashCode(fields)
            genVisitorAccept(replyVisitorName)

            `public`(pt("List", "EventCommon"), "getEvents") {
                `@Override`()
                _return("events.values")
            }

            `public`(TypeName.BYTE, "getSuspendPolicy") {
                `@Override`()
                _return("suspendPolicy.value")
            }

            `public`(
                TypeName.VOID, "accept", param(requestReplyVisitorName, "visitor"),
                param(bg("Reply"), "reply")
            ) {
                `@Override`()
            }
        }
    }

    private fun genGroupClass(node: GroupNode): TypeSpec {
        val fields = node.components.map { it as TypeNode.AbstractTypeNode }
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

            val parseParams = arrayOf(param("PacketInputStream", "ps")) +
                    (node.iterVariable()?.let { arrayOf(param("Value", it)) } ?: arrayOf())

            `public static`(bg(node.name()), "parse", params = parseParams) {

                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + "\n")
                }
                _return("new ${node.name()}(${fields.joinToString(", ") { it.name() }})")
            }

            genWrite(fields)
        }
    }

    private fun genSelectClass(node: SelectNode): List<TypeSpec> {
        val alts = node.components.map { it as AltNode }
        val kindNode = node.typeNode as TypeNode.AbstractTypeNode
        val className = node.commonBaseClass()
        val instanceName = "${node.name()}Instance"
        val visitorName = "${node.name()}Visitor"
        val commonFields = listOf(kindNode) + alts.fold(alts.first().components.map { it as TypeNode.AbstractTypeNode })
        {l, n -> l.filter { c -> n.components.any { val c2 = it as TypeNode.AbstractTypeNode
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

            `public static`(bg(node.commonBaseClass()), "parse", param("PacketInputStream", "ps")) {
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

            `public abstract`(TypeName.VOID, "accept", param(bg(visitorName), "visitor"))

        }) + alts.map { genAltClass(it, className, visitorName, kindNode, commonFields.filter { n -> n != kindNode }) } +
                genVisitor(visitorName, alts.map {it.name()})
    }

    private fun genAltClass(
        alt: AltNode,
        commonClassName: String,
        visitorClassName: String,
        kindNode: TypeNode.AbstractTypeNode,
        commonFields: List<TypeNode.AbstractTypeNode>
    ): TypeSpec {
        val fields = alt.components
            .map { it as TypeNode.AbstractTypeNode }
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

            `public static`(bg(alt.name()), "parse", param("PacketInputStream", "ps")) {
                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + "\n")
                }
                _return("new ${alt.name()}(${(commonFields + uncommonFields).joinToString(", ") { it.name }})")
            }

            genCombinedTypeGet(listOf(kindNode) + fields)

            genToString(alt.name(), fields)
            genEquals(alt.name(), fields)
            genHashCode(fields)
            genWrite(listOf(kindNode) + fields)
            genVisitorAccept(visitorClassName)

            this
        }
    }

    private fun TypeSpec.Builder.genWrite(fields: List<TypeNode.AbstractTypeNode>) = `public`(
        TypeName.VOID, "write", param("PacketOutputStream", "ps")
    ) {
        `@Override`()
        for (f in fields) {
            addCode(f.genJavaWrite(f.name()) + "\n")
        }
        this
    }

    private fun TypeSpec.Builder.genToString(name: String, fields: List<TypeNode.AbstractTypeNode>) = `public`(String::class, "toString") {
        `@Override`()
        if (fields.isEmpty()) {
            _return("$name()".S)
        } else {
            _return("String.format(" +
                    (listOf("\"$name(${fields.joinToString(", ") { "${it.name()}=%s" }})\"") + fields.map { it.name() }).joinToString(", ") + ")"
            )
        }
    }

    private fun TypeSpec.Builder.genEquals(className: String, fields: List<TypeNode.AbstractTypeNode>) = `public`(
        TypeName.BOOLEAN, "equals", param("Object", "other")
    ) {
        `@Override`()
        `if`("!(other instanceof ${className})") {
            _return("false")
        }.end()
        if (fields.isEmpty()) {
            _return(true.L)
        } else {
            statement("$className otherObj = ($className)other")
            _return(fields.joinToString(" && ") { "${it.name()}.equals(otherObj.${it.name()})"  })
        }
    }

    private fun TypeSpec.Builder.genHashCode(fields: List<TypeNode.AbstractTypeNode>) = `public`(TypeName.INT, "hashCode") {
        `@Override`()
        if (fields.isEmpty()) {
            _return(0.L)
        } else {
            _return("Objects.hash(${fields.joinToString(", ") { it.name() }})")
        }
    }

    private fun TypeSpec.Builder.genCombinedTypeGet(fields: List<TypeNode.AbstractTypeNode>) {
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
            _return("KEYS")
        }

        `public`(TypeName.BOOLEAN, "containsKey", param("String", "key")) {
            `@Override`()
            _return("KEY_SET.contains(key)")
        }

        `public`(ParameterizedTypeName.get(bg("List"), pt("Pair", "String", "Value")), "getValues") {
            `@Override`()
            _return("List.of(${fields.joinToString(", ") { "p(${it.name().S}, ${it.name()})" }})")
        }
    }

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

    private const val allVisitorName = "CommandVisitor"
    private const val requestVisitorName = "RequestVisitor"
    private const val replyVisitorName = "ReplyVisitor"
    private const val requestReplyVisitorName = "RequestReplyVisitor"

    private fun genVisitor(name: String, typeNames: List<String>) = `interface`(name) {
        for (typeName in typeNames) {
            `public`(TypeName.VOID, "visit", param(bg(typeName), "obj")) {
                addModifiers(Modifier.DEFAULT)
            }
        }
        this
    }

    private fun TypeSpec.Builder.genVisitorAccept(visitorName: String) = `public`(TypeName.VOID, "accept", param(visitorName, "visitor")) {
        `@Override`()
        statement("visitor.visit(this)")
    }

    @JvmStatic
    fun genCommandCode(node: CommandSetNode): TypeSpec {
        return `public class`(node.name()) {
            `public static final field`(TypeName.INT, "COMMAND_SET") {
                `=`(node.nameNode.value())
            }

            for (command in node.commandNodes) {
                genCommandNodeJavaClasses(command).forEach { addType(it) }
            }

            genAdditionalCommandSetCode(node)
        }
    }

    /** generate the parse method for every packet */
    @JvmStatic
    fun TypeSpec.Builder.genAdditionalCommandSetCode(node: CommandSetNode): TypeSpec.Builder {
        val retType = if (node.name == "Event") bg("Events") else pt("Request", "?")
        `public static`(
            retType, "parse",
            param("VM", "vm"), param("Packet", "packet")
        ) {
            _return("parse(packet.toStream(vm))")
            this
        }
        `public static`(
            retType, "parse",
            param("PacketInputStream", "ps")
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
        return this
    }

    /** generate the parse method for every packet */
    @JvmStatic
    fun genRootCode(root: RootNode): TypeSpec {
        val nodes = root.commandSetNodes
        val commands = nodes.flatMap { cs ->
            cs.components.filterIsInstance<CommandNode>().map { c -> "${cs.name()}.${c.name()}" to c } }
        val rrCommands = commands.filter { it.second.components.size > 2 } // filter events
        val requestNames = rrCommands.map { it.first + "Request" }
        val replyNames = rrCommands.map { it.first + "Reply" } + listOf("EventCmds.Events")
        return `public class`("JDWP") {
            `public static`(
                pt("Request", "?"), "parse",
                param("VM", "vm"), param("Packet", "packet")
            ) {
                _return("parse(packet.toStream(vm))")
                this
            }

            `public static`(
                pt("Request", "?"), "parse",
                param("PacketInputStream", "ps")
            ) {
                switch("ps.commandSet()") {
                    for (cmdSet in nodes) {
                        case("${cmdSet.name()}.COMMAND_SET") {
                            _return("${cmdSet.name()}.parse(ps)")
                        }
                    }
                    default {
                        `throw new2`(
                            NoSuchElementException::class,
                            "\"Unknown command set \" + ps.commandSet()"
                        )
                    }
                    this
                }
                this
            }

            for (node in root.constantSetNodes) {
                addType(genConstantClass(node))
            }

            addType(genVisitor(allVisitorName, requestNames + replyNames))
            addType(genVisitor(requestVisitorName, requestNames))
            addType(genVisitor(replyVisitorName, replyNames))

            addType(`interface`(requestReplyVisitorName) {
                for ((requestName, replyName) in requestNames.zip(replyNames)) {
                    `public`(TypeName.VOID, "visit", param(bg(requestName), "request"), param(bg(replyName), "reply")) {
                        addModifiers(Modifier.DEFAULT)
                    }
                }
                this
            })
            this
        }
    }

    private fun genConstantClass(node: ConstantSetNode) = `public static class`(node.name) {
        for (constant in node.constantNodes) {
            `public static final field`(TypeName.INT, constant.name) {
                `=`(constant.nameNode.value())
            }
        }
        this
    }
}