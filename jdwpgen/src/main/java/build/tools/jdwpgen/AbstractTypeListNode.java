package build.tools.jdwpgen;

import java.io.PrintWriter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AbstractTypeListNode extends AbstractNamedNode {

    void constrainComponent(Context ctx, Node node) {
        if (node instanceof TypeNode) {
            node.constrain(ctx);
        } else {
            if (node instanceof MetadataNode) {
                node.constrain(ctx);
            } else {
                error("Expected type descriptor item, got: " + node);
            }
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

    public List<List<String>> getNonListPaths() {
        return components.stream().filter(n -> n instanceof TypeNode)
                .flatMap(x -> ((TypeNode) x).getNonListPaths().stream()
                        .map(n -> Stream.concat(Stream.of(x.name()), n.stream()).collect(Collectors.toList())))
                .collect(Collectors.toList());
    }
}
