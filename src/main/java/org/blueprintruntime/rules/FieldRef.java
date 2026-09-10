package org.blueprintruntime.rules;

/** A {@code table.field} reference as used in a rule's {@code reads}/{@code writes} lists and expression arguments. */
public record FieldRef(String table, String field) {

    public static FieldRef parseDotted(String dotted) {
        int i = dotted.indexOf('.');
        if (i < 0) throw new IllegalArgumentException("Expected 'table.field' reference, got '" + dotted + "'");
        return new FieldRef(dotted.substring(0, i), dotted.substring(i + 1));
    }

    @Override
    public String toString() {
        return table + "." + field;
    }
}
