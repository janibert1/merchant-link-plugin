package nl.jdries.merchantlink.data;

public enum LinkKind {
    SUPPLY("Supply"),
    RETURN("Return");

    public final String display;

    LinkKind(String display) {
        this.display = display;
    }
}
