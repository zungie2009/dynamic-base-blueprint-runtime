package org.blueprintruntime.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small recursive-descent parser for JSON-with-Comments (.jsonc): standard JSON
 * grammar plus {@code //} line comments and {@code /* ... *}{@code /} block comments,
 * which the external configuration and rule files use for documentation.
 *
 * <p>This exists so the runtime has zero JSON-library dependency, per the Base
 * Blueprint's "no dependency beyond the reference database" policy.</p>
 */
public final class JsonParser {

    private final String text;
    private int pos;

    private JsonParser(String text) {
        this.text = text;
        this.pos = 0;
    }

    public static JsonValue parse(String jsoncText) {
        JsonParser parser = new JsonParser(jsoncText);
        parser.skipWhitespaceAndComments();
        JsonValue value = parser.parseValue();
        parser.skipWhitespaceAndComments();
        if (!parser.atEnd()) {
            throw new JsonValue.JsonException("Unexpected trailing content at offset " + parser.pos);
        }
        return value;
    }

    private boolean atEnd() {
        return pos >= text.length();
    }

    private char peek() {
        return text.charAt(pos);
    }

    private char advance() {
        return text.charAt(pos++);
    }

    private void expect(char c) {
        if (atEnd() || peek() != c) {
            throw new JsonValue.JsonException("Expected '" + c + "' at offset " + pos + " but found "
                    + (atEnd() ? "end of input" : "'" + peek() + "'"));
        }
        pos++;
    }

    private void skipWhitespaceAndComments() {
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                while (!atEnd() && peek() != '\n') pos++;
            } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '*') {
                pos += 2;
                while (pos + 1 < text.length() && !(text.charAt(pos) == '*' && text.charAt(pos + 1) == '/')) pos++;
                pos = Math.min(pos + 2, text.length());
            } else {
                break;
            }
        }
    }

    private JsonValue parseValue() {
        skipWhitespaceAndComments();
        if (atEnd()) throw new JsonValue.JsonException("Unexpected end of input");
        char c = peek();
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> new JsonValue.JString(parseStringLiteral());
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private JsonValue.JObject parseObject() {
        expect('{');
        Map<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespaceAndComments();
        if (!atEnd() && peek() == '}') {
            pos++;
            return new JsonValue.JObject(members);
        }
        while (true) {
            skipWhitespaceAndComments();
            String key = parseStringLiteral();
            skipWhitespaceAndComments();
            expect(':');
            JsonValue value = parseValue();
            members.put(key, value);
            skipWhitespaceAndComments();
            if (atEnd()) throw new JsonValue.JsonException("Unterminated object");
            char c = advance();
            if (c == ',') {
                continue;
            } else if (c == '}') {
                break;
            } else {
                throw new JsonValue.JsonException("Expected ',' or '}' at offset " + (pos - 1));
            }
        }
        return new JsonValue.JObject(members);
    }

    private JsonValue.JArray parseArray() {
        expect('[');
        List<JsonValue> items = new ArrayList<>();
        skipWhitespaceAndComments();
        if (!atEnd() && peek() == ']') {
            pos++;
            return new JsonValue.JArray(items);
        }
        while (true) {
            JsonValue value = parseValue();
            items.add(value);
            skipWhitespaceAndComments();
            if (atEnd()) throw new JsonValue.JsonException("Unterminated array");
            char c = advance();
            if (c == ',') {
                continue;
            } else if (c == ']') {
                break;
            } else {
                throw new JsonValue.JsonException("Expected ',' or ']' at offset " + (pos - 1));
            }
        }
        return new JsonValue.JArray(items);
    }

    private String parseStringLiteral() {
        skipWhitespaceAndComments();
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (atEnd()) throw new JsonValue.JsonException("Unterminated string literal");
            char c = advance();
            if (c == '"') break;
            if (c == '\\') {
                if (atEnd()) throw new JsonValue.JsonException("Unterminated escape sequence");
                char esc = advance();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw new JsonValue.JsonException("Invalid unicode escape");
                        String hex = text.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw new JsonValue.JsonException("Invalid escape '\\" + esc + "'");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private JsonValue parseBoolean() {
        if (text.startsWith("true", pos)) {
            pos += 4;
            return new JsonValue.JBool(true);
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return new JsonValue.JBool(false);
        }
        throw new JsonValue.JsonException("Invalid literal at offset " + pos);
    }

    private JsonValue parseNull() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return JsonValue.JNull.INSTANCE;
        }
        throw new JsonValue.JsonException("Invalid literal at offset " + pos);
    }

    private JsonValue parseNumber() {
        int start = pos;
        if (!atEnd() && peek() == '-') pos++;
        while (!atEnd() && Character.isDigit(peek())) pos++;
        if (!atEnd() && peek() == '.') {
            pos++;
            while (!atEnd() && Character.isDigit(peek())) pos++;
        }
        if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
            pos++;
            if (!atEnd() && (peek() == '+' || peek() == '-')) pos++;
            while (!atEnd() && Character.isDigit(peek())) pos++;
        }
        if (pos == start) throw new JsonValue.JsonException("Invalid number at offset " + pos);
        String literal = text.substring(start, pos);
        try {
            return new JsonValue.JNumber(new BigDecimal(literal));
        } catch (NumberFormatException e) {
            throw new JsonValue.JsonException("Invalid number literal '" + literal + "' at offset " + start);
        }
    }
}
