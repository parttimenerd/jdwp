@file:Suppress("FunctionName")

package build.tools.jdwpgen
import com.grosner.kpoet.*
import com.squareup.javapoet.*
import javax.lang.model.element.Modifier
import kotlin.reflect.KClass

// based on KPoet

// field extensions

private fun applyParams(modifiers: Collection<Modifier>,
                        type: TypeName,
                        name: String,
                        vararg params: ParameterSpec.Builder,
                        function: MethodMethod = { this })
        = MethodSpec.methodBuilder(name).addModifiers(*modifiers.toTypedArray())
    .returns(type).addParameters(params.map { it.build() }.toList())
    .function().build()!!

private fun applyFieldParams(modifiers: Collection<Modifier>,
                             type: TypeName,
                             name: String,
                             function: FieldMethod = { this })
        = FieldSpec.builder(type, name).addModifiers(*modifiers.toTypedArray())
    .function().build()!!

private fun applyFieldParams(modifiers: Collection<Modifier>,
                             kClass: KClass<*>,
                             name: String,
                             function: FieldMethod = { this })
        = FieldSpec.builder(kClass.java, name).addModifiers(*modifiers.toTypedArray())
    .function().build()!!

fun TypeSpec.Builder.`public final field`(type: KClass<*>, name: String, codeMethod: FieldMethod = { this })
        = addField(applyFieldParams(listOf(public, final), type, name, codeMethod))!!

fun TypeSpec.Builder.`public final field`(type: TypeName, name: String, codeMethod: FieldMethod = { this })
        = addField(applyFieldParams(listOf(public, final), type, name, codeMethod))!!

fun TypeSpec.Builder.`public final field`(type: String, name: String, codeMethod: FieldMethod = { this })
        = addField(applyFieldParams(listOf(public, final), ClassName.bestGuess(type), name, codeMethod))!!

fun TypeSpec.Builder.`private final field`(type: String, name: String, codeMethod: FieldMethod = { this })
        = addField(applyFieldParams(listOf(private, final), ClassName.bestGuess(type), name, codeMethod))!!

fun TypeSpec.Builder.`private static final field`(type: KClass<*>, name: String, codeMethod: FieldMethod = { this })
        = addField(applyFieldParams(listOf(private, static, final), type, name, codeMethod))!!

fun TypeSpec.Builder.`private static field`(type: TypeName, name: String, codeMethod: FieldMethod = { this })
        = addField(applyFieldParams(listOf(private, static, final), type, name, codeMethod))!!

fun TypeSpec.Builder.`private static field`(type: String, name: String, codeMethod: FieldMethod = { this })
        = addField(applyFieldParams(listOf(private, static, final), ClassName.bestGuess(type), name, codeMethod))!!


// class extensions

inline fun `public static class`(className: String, typeSpecFunc: TypeMethod)
        = TypeSpec.classBuilder(className).typeSpecFunc().modifiers(public, static).build()!!

inline fun `public static abstract class`(className: String, typeSpecFunc: TypeMethod)
        = TypeSpec.classBuilder(className).typeSpecFunc().modifiers(public, static, abstract).build()!!

inline fun `public interface`(className: String, typeSpecFunc: TypeMethod)
        = TypeSpec.interfaceBuilder(className).typeSpecFunc().modifiers(public).build()!!

fun TypeSpec.Builder.extends(type: String) = superclass(ClassName.bestGuess(type))!!

fun TypeSpec.Builder.implements(vararg typeName: String) =
    addSuperinterfaces(typeName.map(ClassName::bestGuess))!!

fun TypeSpec.Builder.constructor(parameters: List<ParameterSpec.Builder>,
                                 methodSpecFunction: MethodMethod = { this })
        = addMethod(methodSpecFunction(MethodSpec.constructorBuilder()).addParameters(parameters.map { it.build() }
    .toMutableList()).build())!!

fun TypeSpec.Builder.`public constructor`(parameters: List<ParameterSpec.Builder>,
                                 methodSpecFunction: MethodMethod = { this })
        = addMethod(methodSpecFunction(MethodSpec.constructorBuilder()).addParameters(parameters.map { it.build() }
    .toMutableList()).addModifiers(public).build())!!

fun TypeSpec.Builder.`public static`(type: TypeName, name: String, vararg params: ParameterSpec.Builder,
                                     codeMethod: MethodMethod = { this })
        = addMethod(applyParams(listOf(public, static), type, name, params = *params, function = codeMethod))!!

fun TypeSpec.Builder.`public abstract`(type: TypeName, name: String, vararg params: ParameterSpec.Builder,
                                     codeMethod: MethodMethod = { this })
        = addMethod(applyParams(listOf(public, abstract), type, name, params = *params, function = codeMethod))!!

fun TypeSpec.Builder.default(type: TypeName, name: String, vararg params: ParameterSpec.Builder,
                             codeMethod: MethodMethod = { this })
        = addMethod(applyParams(listOf(), type, name, params = *params, function = codeMethod))!!

fun param(type: String, name: String, paramMethod: ParamMethod = { this })
        = ParameterSpec.builder(ClassName.bestGuess(type), name).paramMethod()

// code extensions

fun MethodSpec.Builder._return(arg: String) = addStatement("return $arg")!!

fun MethodSpec.Builder.`throw new2`(type: ClassName, arg: String)
        = addStatement("throw new \$T($arg)", type)!!


fun MethodSpec.Builder.`@Override`() = `@`(ClassName.bestGuess("Override"))

fun MethodSpec.Builder.`@SuppressWarnings`(vararg warnings: String): MethodSpec.Builder {
    addAnnotation(`@`(ClassName.bestGuess("SuppressWarnings"),
        mapFunc = {
            this["value"] =
                if (warnings.size == 1)
                    warnings[0].S else
                    "{${warnings.joinToString(", ") { it.S }}}"
        }).build()
    )
    return this
}
