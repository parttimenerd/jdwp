package build.tools.jdwpgen;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class CostNode extends Node {

    CostNode() {
        this(new ArrayList<>());
    }

    CostNode(List<Node> components) {
        this.kind = "Cost";
        this.components = components;
    }

    @Override
    void document(PrintWriter writer) {

    }

    void constrain(Context ctx) {
        if (components.size() != 1) {
            error("Cost node expects an integer");
        }
        Integer.parseInt(((NameNode) components.get(0)).name);
        super.constrain(ctx);
    }

    int getInteger() {
        return Integer.parseInt(((NameNode) components.get(0)).name);
    }
}
