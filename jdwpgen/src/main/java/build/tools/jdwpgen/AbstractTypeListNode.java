package build.tools.jdwpgen;

import java.io.PrintWriter;

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
}
