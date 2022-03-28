package build.tools.jdwpgen;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class OnlyReadsNode extends Node {

    OnlyReadsNode() {
        this(new ArrayList<Node>());
    }

    OnlyReadsNode(List<Node> components) {
        this.kind = "OnlyReads";
        this.components = components;
    }

    @Override
    void document(PrintWriter writer) {

    }

    void constrain(Context ctx) {
        if (commentList.size() != 1) {
            error("OnlyReads nodes expects a string");
        }
        String param = commentList.get(0);
        if (!param.equals("true") && !param.equals("false")) {
            error("OnlyReads nodes expect \"true\" or \"false\"");
        }
        super.constrain(ctx);
    }

    boolean getBoolean() {
        return Boolean.parseBoolean(commentList.get(0));
    }
}
