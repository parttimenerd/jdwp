package build.tools.jdwpgen;

import java.io.PrintWriter;
import java.util.Iterator;

public abstract class AbstractTypeListNode extends AbstractNamedNode {

    void constrainComponent(Context ctx, Node node) {
        if (node instanceof TypeNode) {
            node.constrain(ctx);
        } else {
            error("Expected type descriptor item, got: " + node);
        }
    }

    void document(PrintWriter writer) {
        writer.println("<dt>" + name() + " Data");
        writer.println("<dd>");
        if (components.isEmpty()) {
            writer.println("(None)");
        } else {
            writer.println("<table><tr>");
            writer.println("<th class=\"bold\" style=\"width: 20%\" scope=\"col\">Type");
            writer.println("<th class=\"bold\" style=\"width: 15%\" scope=\"col\">Name");
            writer.println("<th class=\"bold\" style=\"width: 65%\" scope=\"col\">Description");
            writer.println("</tr>");
            for (Node node : components) {
                node.document(writer);
            }
            writer.println("</table>");
        }
        writer.println("</dd>");
    }

    void genJavaClassBodyComponents(PrintWriter writer, int depth) {
        for (Node node : components) {
            TypeNode tn = (TypeNode) node;

            tn.genJavaDeclaration(writer, depth);
        }
    }

    void genJavaReads(PrintWriter writer, int depth) {
        for (Node node : components) {
            TypeNode tn = (TypeNode) node;
            tn.genJavaRead(writer, depth, tn.name());
        }
    }

    void genJavaReadingClassBody(PrintWriter writer, int depth,
                                 String className) {
        genJavaClassBodyComponents(writer, depth);
        writer.println();
        indent(writer, depth);
        if (!context.inEvent()) {
            writer.print("private ");
        }
        writer.println(className +
                "(VirtualMachineImpl vm, PacketStream ps) {");
        genJavaReads(writer, depth + 1);
        indent(writer, depth);
        writer.println("}");
    }

    String javaParams() {
        StringBuffer sb = new StringBuffer();
        for (Iterator<Node> it = components.iterator(); it.hasNext(); ) {
            TypeNode tn = (TypeNode) it.next();
            sb.append(tn.javaParam());
            if (it.hasNext()) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    void genJavaWrites(PrintWriter writer, int depth) {
        for (Node node : components) {
            TypeNode tn = (TypeNode) node;
            tn.genJavaWrite(writer, depth, tn.name());
        }
    }

    void genJavaWritingClassBody(PrintWriter writer, int depth,
                                 String className) {
        genJavaClassBodyComponents(writer, depth);
        writer.println();
        indent(writer, depth);
        writer.println(className + "(" + javaParams() + ") {");
        for (Node node : components) {
            TypeNode tn = (TypeNode) node;
            indent(writer, depth + 1);
            writer.println("this." + tn.name() + " = " + tn.name() + ";");
        }
        indent(writer, depth);
        writer.println("}");
    }
}
