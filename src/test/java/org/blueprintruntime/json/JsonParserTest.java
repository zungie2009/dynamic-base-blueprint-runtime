package org.blueprintruntime.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonParserTest {

    @Test
    void parsesObjectsArraysAndScalars() {
        String text = """
                {
                  "name": "Test",
                  "count": 3,
                  "active": true,
                  "missing": null,
                  "tags": ["a", "b"]
                }
                """;
        JsonValue.JObject root = (JsonValue.JObject) JsonParser.parse(text);
        assertEquals("Test", root.getString("name"));
        assertEquals(3, root.getInt("count", -1));
        assertTrue(root.getBool("active", false));
        assertFalse(root.has("missing"));
        assertEquals(2, root.getArray("tags").size());
        assertEquals("a", root.getArray("tags").get(0).asString());
    }

    @Test
    void skipsLineAndBlockComments() {
        String text = """
                {
                  // a line comment
                  "a": 1, /* a block
                  comment spanning lines */
                  "b": 2
                }
                """;
        JsonValue.JObject root = (JsonValue.JObject) JsonParser.parse(text);
        assertEquals(1, root.getInt("a", -1));
        assertEquals(2, root.getInt("b", -1));
    }

    @Test
    void parsesEscapedStrings() {
        JsonValue.JObject root = (JsonValue.JObject) JsonParser.parse("{\"s\": \"line1\\nline2\\t\\\"quoted\\\"\"}");
        assertEquals("line1\nline2\t\"quoted\"", root.getString("s"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(RuntimeException.class, () -> JsonParser.parse("{ \"a\": }"));
        assertThrows(RuntimeException.class, () -> JsonParser.parse("{ \"a\": 1,, }"));
        assertThrows(RuntimeException.class, () -> JsonParser.parse("not json at all"));
    }

    @Test
    void canonicalJsonSortsObjectKeys() {
        JsonValue.JObject root = (JsonValue.JObject) JsonParser.parse("{\"b\": 1, \"a\": 2}");
        assertEquals("{\"a\":2,\"b\":1}", root.toCanonicalJson());
    }
}
