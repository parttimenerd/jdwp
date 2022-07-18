package build.tools.jdwpgen

import build.tools.jdwpgen.MetadataNode.*
import com.grosner.kpoet.*
import com.squareup.javapoet.*
import javax.lang.model.element.Modifier

internal object CodeGeneration {

    private fun genCommandNodeJavaClasses(cmd: CommandNode, costFile: CostFile): List<TypeSpec> {
        return if (cmd.isEventNode) listOf(generateEventClass(cmd)) else generateCommandClasses(cmd, costFile)
    }

    private fun generateCommandClasses(cmd: CommandNode, costFile: CostFile): List<TypeSpec> {
        return listOf(genRequestClass(cmd, costFile), genReplyClass(cmd))
    }

    // idea: keep the code at a single place

    private fun Node.findGroupNodes(): List<GroupNode> = components
        .flatMap { (if (it is GroupNode) listOf(it) else emptyList()) + it.findGroupNodes() }

    private fun Node.findSelectNodes(): List<SelectNode> = components
        .flatMap { (if (it is SelectNode) listOf(it) else emptyList()) + it.findSelectNodes() }

    private fun TypeSpec.Builder.genCommon(
        cmd: CommandNode, valType: String, className: String, defaultFlag: Int,
        fields: List<TypeNode.AbstractTypeNode>
    ): TypeSpec.Builder {

        val commandClassName = (cmd.parent as CommandSetNode).name() + "." + className

        addTypes(fields.flatMap { it.findGroupNodes() }.distinctBy { it.name() }
            .map { genGroupClass(it, commandClassName) })

        addTypes(fields.flatMap { it.findSelectNodes() }.flatMap {
            genSelectClass(it, commandClassName)
        })

        `public static final field`(TypeName.INT, "COMMAND") { `=`(cmd.nameNode.value()) }

        for (f in fields) {
            addPublicField(f)
        }

        `public constructor`(
            listOf(param(TypeName.INT, "id"), param(TypeName.SHORT, "flags")) +
                    fields.map { param(it.javaType(), it.name()) }) {
            statement("super($valType, id, flags)")
            for (f in fields) {
                statement("this.\$N = \$N", f.name(), f.name())
            }
            this
        }

        `public constructor`(
            listOf(param(TypeName.INT, "id")) +
                    fields.map { param(it.javaType(), it.name()) }) {
            statement("this(\$N)",
                (listOf("id", "(short)$defaultFlag") + fields.map { it.name() }).joinToString(", ")
            )
        }

        `public constructor`(listOf(param(TypeName.INT, "id"), param(pt("java.util.Map", "String", "Value"), "arguments"))) {
            `@SuppressWarnings`("unchecked")
            statement("this(\$N)",
                (listOf("id", "(short)$defaultFlag") + fields.map {
                    createArgumentsGet(
                        it.javaType(),
                        it.name()
                    )
                }).joinToString(", ")
            )
            this
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

        `public`(String::class, "getCommandName") {
            `@Override`()
            _return(cmd.name().S)
        }

        `public`(String::class, "getCommandSetName") {
            `@Override`()
            _return((cmd.parent as CommandSetNode).rawName().S)
        }

        `public`(bg(className), "withNewId", param(TypeName.INT, "id")) {
            `@Override`()
            _return("new $className(id, flags${fields.joinToString("") { ", " + it.name() }})")
            this
        }

        `public`(TypeName.BOOLEAN, "hasListValuedFields") {
            `@Override`()
            _return(fields.any { f -> f is RepeatNode }.L)
        }

        `public`(pt("List", "String"), "getListValuedFields") {
            `@Override`()
            _return("List.of(${fields.filterIsInstance<RepeatNode>().joinToString(", ") { f -> f.name().S }})")
        }

        genVisitorAccept(allVisitorName)

        return this
    }

    private fun TypeSpec.Builder.addPublicField(f: TypeNode.AbstractTypeNode) {
        `public final field`(f.javaType(), f.name()) {
            addJavadoc(f.comment())
            if (f is RepeatNode) {
                addAnnotation(
                    `@`(
                        bg("EntryClass"),
                        mapFunc = { this["value"] = "${f.member.javaType().split("<")[0]}.class" }).build()
                )
            }
            this
        }
    }

    private fun genRequestClass(cmd: CommandNode, costFile: CostFile): TypeSpec {
        val out = cmd.out
        val fields = out.components.map { t: Node -> t as TypeNode.AbstractTypeNode }
        val requestClassName = cmd.requestClassName
        val replyClassName = cmd.replyClassName
        return `public static class`(requestClassName) {
            extends("AbstractParsedPacket")
            implements(pt("Request", cmd.replyClassName))

            addJavadoc(out.parent.commentList.joinToString("\n"))

            genCommon(cmd, "Type.REQUEST", requestClassName, 0, fields)

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

            genMetadataSetterCode(cmd.metadata, costFile, cmd)
            genMetadataGetterCode(cmd.metadata, null)

            genVisitorAccept(requestVisitorName)
            genReturningVisitorAccept(returningRequestVisitorName)

            `public`(
                TypeName.VOID, "accept", param(requestReplyVisitorName, "visitor"),
                param(bg("Reply"), "reply")
            ) {
                `@Override`()
                `if`("this.id != reply.getId()") {
                    `throw new2`(bg("Reply.IdMismatchException"), "id, reply.getId()")
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

            genCommon(cmd,"Type.REPLY", replyClassName, 0x80, fields)

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
                    _return("new ReplyOrError<>(ps.id(), ps.flags(), ${cmd.requestClassName}.METADATA, ps.errorCode())")
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

            genMetadataGetterCode(cmd.metadata, requestClassName)

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

            genCommon(cmd, "Type.EVENTS", name, 0, fields)

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
            genMetadataSetterCode(cmd.metadata, null, cmd)

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

    private fun genGroupClass(node: GroupNode, commandClassName: String): TypeSpec {
        val fields = node.components.map { it as TypeNode.AbstractTypeNode }
        return `public static class`(node.name()) {
            extends("Value.CombinedValue")

            for (f in fields) {
                addPublicField(f)
            }

            `public constructor`(
                fields.map { param(it.javaType(), it.name()) }) {
                statement("super(Type.OBJECT)")
                for (f in fields) {
                    statement("this.\$N = \$N", f.name(), f.name())
                }
                this
            }

            `public constructor`(listOf(param(pt("java.util.Map", "String", "Value"), "arguments"))) {
                `@SuppressWarnings`("unchecked")
                statement("super(Type.OBJECT)")
                for (f in fields) {
                    statement("this.\$N = \$N", f.name(), createArgumentsGet(f.javaType(), f.name()))
                }
                this
            }

            genCombinedTypeGet(fields)

            genToString(node.name(), fields)
            genEquals(node.name(), fields)
            genHashCode(fields)
            genToCode(commandClassName + "." + node.name(), fields)

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

    private fun genSelectClass(node: SelectNode, commandSetClassName: String): List<TypeSpec> {
        val alts = node.components.map { it as AltNode }
        val kindNode = node.typeNode as TypeNode.AbstractTypeNode
        val className = node.commonBaseClass()
        val instanceName = "${node.name()}Instance"
        val visitorName = "${node.name()}Visitor"
        val commonFields =
            listOf(kindNode) + alts.fold(alts.first().components.filterIsInstance<TypeNode.AbstractTypeNode>())
            { l, n ->
                l.filter { c ->
                    n.components.filterIsInstance<TypeNode.AbstractTypeNode>().any { c2 ->
                        c2.name() == c.name() && c2.javaType() == c.javaType()
                    }
                }
            }
        return listOf(`public static abstract class`(className) {
            extends(instanceName)

            for (f in commonFields) {
                addPublicField(f)
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
                            bg("PacketError.NoSuchSelectKindSetException"),
                            "ps, ${kindNode.name()}.value"
                        )
                    }
                    this
                }
            }

            `public abstract`(TypeName.VOID, "accept", param(bg(visitorName), "visitor"))

        }) + alts.map {
            genAltClass(
                it,
                className,
                visitorName,
                commandSetClassName,
                kindNode,
                commonFields.filter { n -> n != kindNode })
        } +
                genVisitor(visitorName, alts.map { it.name() })
    }

    private fun genAltClass(
        alt: AltNode,
        commonClassName: String,
        visitorClassName: String,
        commandSetClassName: String,
        kindNode: TypeNode.AbstractTypeNode,
        commonFields: List<TypeNode.AbstractTypeNode>
    ): TypeSpec {
        val fields = alt.components
            .filterIsInstance<TypeNode.AbstractTypeNode>()
        val metadataNode = alt.components.filterIsInstance<MetadataNode>().firstOrNull()
        val uncommonFields = fields
            .filter { n -> commonFields.all { it.name() != n.name() } }
        return `public static class`(alt.javaType()) {
            extends(commonClassName)

            `public static final field`(TypeName.BYTE, "KIND") { `=`(alt.nameNode.value()) }

            for (f in uncommonFields) {
                addPublicField(f)
            }

            `public constructor`((commonFields + uncommonFields).map { param(it.javaType(), it.name()) }) {
                statement("super(${(listOf("PrimitiveValue.wrap(KIND)") + commonFields.map { it.name() }).joinToString(", ")})")
                for (f in uncommonFields) {
                    statement("this.\$N = \$N", f.name(), f.name())
                }
                this
            }

            `public constructor`(listOf(param(pt("java.util.Map", "String", "Value"), "arguments"))) {
                `@SuppressWarnings`("unchecked")
                statement("super(${
                    (listOf("PrimitiveValue.wrap(KIND)") +
                            commonFields.map { createArgumentsGet(it.javaType(), it.name()) }).joinToString(", ")
                })"
                )
                for (f in uncommonFields) {
                    statement("this.\$N = \$N", f.name(), createArgumentsGet(f.javaType(), f.name()))
                }
                this
            }

            `public static`(bg(alt.name()), "parse", param("PacketInputStream", "ps")) {
                for (f in fields) {
                    addCode(f.genJavaRead(f.javaType() + " " + f.name()) + "\n")
                }
                _return("new ${alt.name()}(${(commonFields + uncommonFields).joinToString(", ") { it.name }})")
            }

            genCombinedTypeGet(fields)

            metadataNode?.let {
                genMetadataSetterCode(it, null, null)
                genMetadataGetterCode(it, null)
            }

            genToString(alt.name(), fields)
            genToCode(commandSetClassName + "." + alt.name(), fields)
            genEquals(alt.name(), fields)
            genHashCode(fields)
            genWrite(listOf(kindNode) + fields)
            genVisitorAccept(visitorClassName)

            this
        }
    }

    /** get the basic group, important for references */
    private fun getBasicGroup(type: String) = when (type.split(".").last()) {
        "ThreadGroupReference" -> "THREAD_GROUP_REF"
        "ModuleReference" -> "MODULE_REF"
        "FieldReference" -> "FIELD_REF"
        "FrameReference" -> "FRAME_REF"
        "HeapReference", "ArrayReference", "ObjectReference", "NullObjectReference",
        "ClassObjectReference", "TypeReference", "InterfaceTypeReference", "ClassTypeReference",
        "ArrayTypeReference", "TypeObjectReference", "InterfaceReference", "ClassReference" -> "HEAP_REF"
        "MethodReference" -> "METHOD_REF"
        "ClassLoaderReference" -> "CLASSLOADER_REF"
        else -> type
    }

    private fun isReferenceType(type: String) = getBasicGroup(type).endsWith("_REF")

    /** replacement for ($N) Objects.requireNonNull(arguments.get($S)) with reference conversion support */
    private fun createArgumentsGet(type: String, field: String): String {
        val fieldStr = "\"${field}\""
        val argGet = "arguments.get($fieldStr)"
        val inner = "Objects.requireNonNull($argGet)"
        if (isReferenceType(type)) {
            return "Reference.isNotReferenceOrSameClassArgument($type.class, $argGet) ? " +
                    "($type)$argGet : new $type(((Reference)$argGet).value).checkInGroup(((Reference)$argGet).getGroup())"
        }
        return "($type) $inner"
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

    private fun TypeSpec.Builder.genToCode(name: String, fields: List<TypeNode.AbstractTypeNode>) = `public`(String::class, "toCode") {
        `@Override`()
        if (fields.isEmpty()) {
            _return("new $name()".S)
        } else {
            _return("String.format(" +
                    (listOf("\"new $name(${fields.joinToString(", ") { "%s" }})\"") + fields.map { "${it.name()}.toCode()" }).joinToString(", ") + ")"
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
        val vars = listOf("COMMAND", "COMMAND_SET") + fields.map { it.name() }
        _return("Objects.hash(${vars.joinToString(", ")})")
    }

    private fun TypeSpec.Builder.genCombinedTypeGet(fields: List<TypeNode.AbstractTypeNode>) {
        `public`(bg("Value"), "get", param("String", "key")) {
            `@Override`()
            val exCode = { `throw new2`(bg("ValueAccessException.NoSuchFieldException"), "this, key") }
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
    private const val returningRequestVisitorName = "ReturningRequestVisitor"
    private const val replyVisitorName = "ReplyVisitor"
    private const val requestReplyVisitorName = "RequestReplyVisitor"

    private fun genVisitor(name: String, typeNames: List<String>) = `public interface`(name) {
        for (typeName in typeNames) {
            `public`(TypeName.VOID, "visit", param(bg(typeName), typeNameToParameterName(typeName))) {
                addModifiers(Modifier.DEFAULT)
            }
        }
        this
    }

    private fun genReturningVisitor(name: String, typeNames: List<String>) = `public interface`(name) {
        addTypeVariable(TypeVariableName.get("R"))
        for (typeName in typeNames) {
            `public`(bg("R"), "visit", param(bg(typeName), typeNameToParameterName(typeName))) {
                addModifiers(Modifier.DEFAULT)
                _return("null")
            }
        }
        this
    }

    private fun typeNameToParameterName(typeName: String) = typeName.split(".").last().lowercaseFirstCharacter()

    fun String.lowercaseFirstCharacter() = this[0].lowercase() + substring(1, length)

    private fun TypeSpec.Builder.genVisitorAccept(visitorName: String) = `public`(TypeName.VOID, "accept", param(visitorName, "visitor")) {
        `@Override`()
        statement("visitor.visit(this)")
    }

    private fun TypeSpec.Builder.genReturningVisitorAccept(visitorName: String) = `public`(
        bg("R"), "accept",
        param(pt(visitorName, "R"), "visitor")
    ) {
        `@Override`()
        addTypeVariables(listOf(TypeVariableName.get("R")))
        _return("visitor.visit(this)")
    }

    private data class ExtraMetadataField(val typeName: TypeName, val name: String,
                                          val description: String, val methodName: String,
                                          val codeGenerator: (CommandNode?, MetadataNode) -> String)

    private val extraMetadataFields = listOf(
        ExtraMetadataField(TypeName.INT, "commandSet", "", "getCommandSet"
        ) { cmd, md -> if (cmd == null) "-1" else (cmd.parent as CommandSetNode).nameNode.value() },
        ExtraMetadataField(TypeName.INT, "command", "", "getCommand"
        ) { cmd, md -> if (cmd == null) "-1" else cmd.nameNode.value() },
        ExtraMetadataField(TypeName.LONG, "affectsBits", "The bitmask of affected properties",
            "getAffectsBits"
        ) { cmd, md -> "0b0" + (md.get("Affects") as StatePropertySet).bitfield.toString(2) + "L" },
        ExtraMetadataField(TypeName.LONG, "affectedByBits", "The bitmask of affected by properties",
            "getAffectedByBits"
        ) { cmd, md -> "0b0" + (md.get("AffectedBy") as StatePropertySet).bitfield.toString(2) + "L" },
        ExtraMetadataField(TypeName.BOOLEAN, "affectedByTime", "", "isAffectedByTime",
        ) { cmd, md -> (md.get("AffectedBy") as StatePropertySet).properties.contains(StateProperty.TIME).L }
    )

    private fun metadataFields(costFile: CostFile?) = entries.map { entry ->
        ExtraMetadataField(
            entry.typeName, entry.nodeName.lowercaseFirstCharacter(), entry.description,
            (if (entry.typeName == TypeName.BOOLEAN) entry.nodeName.lowercaseFirstCharacter() else "get" + entry.nodeName)
        ) { cmd, md ->
            entry.check(md.get(entry.nodeName), cmd)
            val value = md.get(entry.nodeName) as Any
            if (entry.nodeName.equals("Cost")) {
                (if (costFile == null) "0"
                else if (value == 0) costFile.getCost(
                    Integer.parseInt((cmd!!.parent as CommandSetNode).nameNode.value()),
                    Integer.parseInt(cmd.nameNode.value())
                ).L else value.L) + "f"
            } else if (value is String) value.S
            else if (value is StatePropertySet) {
                if (value.properties.isEmpty()) "Set.of()"
                else "Set.of(${value.properties.joinToString(", ") { "StateProperty.${it.name}" }})"
            } else if (value is ReplyLikeErrorList) {
                if (value.errorConstants.isEmpty()) "List.<Integer>of()"
                else "List.of(${value.errorConstants.joinToString(", ") { "JDWP.Error.${it}" }})"
            } else if (value is PathList) {
                if (value.strings.isEmpty()) "List.<AccessPath>of()"
                else "List.of(${value.strings.joinToString(", ") { "new AccessPath(${it.joinToString(", ") {p -> "\"$p\"" }})" }})"
            } else value.L
        }
    } + extraMetadataFields

    private fun TypeSpec.Builder.genMetadataSetterCode(
        metadata: MetadataNode,
        costFile: CostFile?,
        cmd: CommandNode?
    ): TypeSpec.Builder =
        `public static final field`(bg(METADATA_CLASSNAME), "METADATA") {
            addJavadoc("Metadata for this request")
            `=`(
                "new ${METADATA_CLASSNAME}(${metadataFields(costFile).joinToString(", ") {
                    it.codeGenerator(cmd, metadata)
                }})"
            )
            this
        }

    private fun TypeSpec.Builder.genMetadataGetterCode(
        metadata: MetadataNode,
        cmdClassName: String?
    ): TypeSpec.Builder {

        val metadataFieldName = "${if (cmdClassName != null) "$cmdClassName." else ""}METADATA"

        for (field in metadataFields(null).filterNot { it.methodName.contains("Command") }) {
            `public`(field.typeName, field.methodName) {
                if (field.description.isNotEmpty()) addJavadoc(field.description)
                _return("$metadataFieldName.${field.name}")
            }
        }

        `public`(TypeName.BOOLEAN, "isAffectedBy", param(pt("jdwp.Request"), "other")) {
            `@Override`()
            _return("(getAffectedByBits() & other.getAffectsBits()) != 0")
        }

        `public`(TypeName.BOOLEAN, "isReplyLikeError", param(TypeName.INT, "errorCode")) {
            _return("$metadataFieldName.isReplyLikeError(errorCode)")
        }

        `public`(bg(METADATA_CLASSNAME), "getMetadata") {
            `@Override`()
            _return(metadataFieldName)
        }

        return this
    }

    private fun genMetadataClass() = `public static class`(METADATA_CLASSNAME) {
        implements(bg("ToCode"))
        val fields = metadataFields(null)
        for (field in fields) {
            `public final field`(field.typeName, field.name) {
                if (field.description.isNotEmpty()) addJavadoc(field.description)
                this
            }
            `public`(field.typeName, field.methodName) {
                if (field.description.isNotEmpty()) addJavadoc(field.description)
                _return(field.name)
            }
        }
        `public`(TypeName.BOOLEAN, "isReplyLikeError", param(TypeName.INT, "errorCode")) {
            _return("replyLikeErrors.contains(errorCode)")
        }
        `constructor`(fields.map { param(it.typeName, it.name) }) {
            fields.forEach {
                addStatement("this.${it.name} = ${it.name}")
            }
            this
        }
        `public`(bg("String"), "toCode") {
            `@Override`()
            _return("JDWP.getCommandSetName(commandSet) + \"Cmds.\" + JDWP.getCommandName(commandSet, command) + \"Request.METADATA\"")
        }
        this
    }

    private fun genMetadataGetterInterface() = `public interface`("WithMetadata") {
        val fields = metadataFields(null)
        for (field in fields.filterNot { it.methodName.contains("Command") }) {
            default(field.typeName, field.methodName) {
                _return("getMetadata().${field.name}")
            }
        }
        `public abstract`(bg(METADATA_CLASSNAME), "getMetadata")
        this
    }

    @JvmStatic
    fun genCommandCode(node: CommandSetNode, costFile: CostFile): TypeSpec {
        return `public class`(node.name()) {
            `public static final field`(TypeName.INT, "COMMAND_SET") {
                `=`(node.nameNode.value())
            }

            for (command in node.commandNodes) {
                genCommandNodeJavaClasses(command, costFile).forEach { addType(it) }
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
                    bg("PacketError.NoSuchCommandException"),
                    "ps"
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
                            bg("PacketError.NoSuchCommandException"),
                            "ps"
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
            cs.components.filterIsInstance<CommandNode>().map { c -> "${cs.name()}.${c.name()}" to c }
        }
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
                        case("(byte)${cmdSet.name()}.COMMAND_SET") {
                            _return("${cmdSet.name()}.parse(ps)")
                        }
                    }
                    default {
                        `throw new2`(
                            bg("PacketError.NoSuchCommandSetException"),
                            "ps"
                        )
                    }
                    this
                }
                this
            }

            `private static final field`(pt("Map", "String", "Byte"), "commandSetNameToByte") {
                `=`("Map.ofEntries(${nodes.joinToString(", ") { "Map.entry(\"${it.name}\", (byte)${it.nameNode.value()})" }})")
            }

            `private static final field`(pt("Map", "Byte", "String"), "commandSetByteToName") {
                `=`("Map.ofEntries(${nodes.joinToString(", ") { "Map.entry((byte)${it.nameNode.value()}, \"${it.name}\")" }})")
            }

            `private static final field`(
                ParameterizedTypeName.get(bg("Map"), bg("String"), pt("Map", "String", "Byte")), "commandNameToByte") {
                `=`("Map.ofEntries(${
                    nodes.joinToString(", ") {
                        "Map.entry(\"${it.name}\", Map.ofEntries(${
                            it.components.filterIsInstance<CommandNode>()
                                .joinToString(", ") { c -> "Map.entry(\"${c.name}\", (byte)${c.nameNode.value()})" }
                        }))"
                    }
                })")
            }

            `private static final field`(
                ParameterizedTypeName.get(bg("Map"), bg("Byte"), pt("Map", "Byte", "String")), "commandByteToName") {
                `=`("Map.ofEntries(${
                    nodes.joinToString(", ") {
                        "Map.entry((byte)${it.nameNode.value()}, Map.ofEntries(${
                            it.components.filterIsInstance<CommandNode>()
                                .joinToString(", ") { c -> "Map.entry((byte)${c.nameNode.value()}, \"${c.name}\")" }
                        }))"
                    }
                })")
            }

            `public static`(TypeName.BYTE, "getCommandSetByte", param("String", "commandSetName")) {
                _return("commandSetNameToByte.get(commandSetName)")
            }

            `public static`(bg("String"), "getCommandSetName", param(TypeName.INT, "commandSet")) {
                _return("commandSetByteToName.get((byte)commandSet)")
            }

            `public static`(
                TypeName.BYTE, "getCommandByte", param("String", "commandSetName"),
                param("String", "commandName")
            ) {
                _return("commandNameToByte.get(commandSetName).get(commandName)")
            }

            `public static`(
                bg("String"), "getCommandName", param(TypeName.INT, "commandSet"),
                param(TypeName.INT, "command")
            ) {
                _return("commandByteToName.get((byte)commandSet).get((byte)command)")
            }

            `private static final field`(
                ParameterizedTypeName.get(
                    bg("Map"), bg("Integer"), ParameterizedTypeName.get(
                        bg("Map"), bg("Integer"),
                        bg(METADATA_CLASSNAME)
                    )
                ), "commandToMetadata"
            ) {
                `=`("Map.ofEntries(${
                    nodes.joinToString(", ") {
                        "Map.entry(${it.nameNode.value()}, Map.ofEntries(${
                            it.components.filterIsInstance<CommandNode>().filter { c -> !c.isEventNode }
                                .joinToString(", ") { c ->
                                    "Map.entry(${c.nameNode.value()}, ${c.parent.name()}.${c.requestClassName}.METADATA)"
                                }
                        }))"
                    }
                })")
            }

            `public static`(
                bg(METADATA_CLASSNAME), "getMetadata", param(TypeName.INT, "commandSet"),
                param(TypeName.INT, "command")
            ) {
                _return("commandToMetadata.get(commandSet).get(command)")
            }

            `public static`(
                bg(METADATA_CLASSNAME), "getMetadata", param("String", "commandSetName"),
                param("String", "commandName")
            ) {
                _return("commandToMetadata.get((int)getCommandSetByte(commandSetName)).get((int)getCommandByte(commandSetName, commandName))")
            }

            addType(genMetadataClass())
            addType(genMetadataGetterInterface())

            for (node in root.constantSetNodes) {
                addType(genConstantClass(node))
            }

            addType(genVisitor(allVisitorName, requestNames + replyNames))
            addType(genVisitor(requestVisitorName, requestNames))
            addType(genReturningVisitor(returningRequestVisitorName, requestNames))
            addType(genVisitor(replyVisitorName, replyNames))

            addType(`public interface`(requestReplyVisitorName) {
                for ((requestName, replyName) in requestNames.zip(replyNames)) {
                    `public`(
                        TypeName.VOID, "visit", param(bg(requestName), "request"),
                        param(bg(replyName), "reply")
                    ) {
                        addModifiers(Modifier.DEFAULT)
                    }
                }
                this
            })
            addType(`public static enum`("StateProperty") {
                for (property in StateProperty.values()) {
                    addEnumConstant(
                        property.name, TypeSpec.anonymousClassBuilder("")
                            .addJavadoc(property.description).build()
                    )
                }
                this
            })
            this
        }
    }

    private fun genConstantClass(node: ConstantSetNode) = `public static class`(node.name) {
        for (constant in node.constantNodes) {
            `public static final field`(TypeName.INT, constant.name) {
                addJavadoc(constant.commentList.joinToString(" "))
                `=`(constant.nameNode.value())
            }
        }
        `private static final field`(pt("Map", "Integer", "String"), "CONSTANT_NAMES") {
            `=`("Map.ofEntries(${node.constantNodes.joinToString(", ") { "Map.entry(${it.nameNode.value()}, \"${it.name}\")" }})")
        }
        `private static final field`(pt("Map", "Integer", "String"), "CONSTANT_DESCRIPTIONS") {
            `=`("Map.ofEntries(${node.constantNodes.joinToString(", ") { "Map.entry(${it.nameNode.value()}, \"${it.commentList.joinToString(" ")}\")" }})")
        }
        `public static`(bg("String"), "getConstantName", param(TypeName.INT, "constant")) {
            _return("CONSTANT_NAMES.get(constant)")
        }
        `public static`(bg("String"), "getConstantDescription", param(TypeName.INT, "constant")) {
            _return("CONSTANT_DESCRIPTIONS.get(constant)")
        }
        this
    }
}