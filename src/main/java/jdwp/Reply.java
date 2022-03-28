package jdwp;

public interface Reply extends ParsedPacket {
    public int getCommand();
    public int getCommandSet();
}
