package org.blueprintruntime.runtime;

import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.FieldType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueCodecTest {

    private static FieldDefinition field(String id, FieldType type, boolean required, String blankPolicy) {
        return new FieldDefinition(id, id, type, null, false, required, true, false, blankPolicy, null, null, false, null, null);
    }

    @Test
    void decimalFieldsAlwaysUseBigDecimalAtScaleTwo() {
        FieldDefinition price = field("unitPrice", FieldType.DECIMAL_19_2, true, null);
        Object parsed = ValueCodec.parseFormValue(price, "125.5");
        assertEquals(new BigDecimal("125.50"), parsed);
        assertEquals("125.50", ValueCodec.toDisplayString(price, parsed));
    }

    @Test
    void integerFieldNormalizesFormAndJsonInputTheSameWay() {
        FieldDefinition qty = field("quantity", FieldType.INTEGER, true, null);
        assertEquals(7, ValueCodec.parseFormValue(qty, "7"));
        assertEquals(7, ValueCodec.parseJsonValue(qty, new org.blueprintruntime.json.JsonValue.JNumber(new BigDecimal("7"))));
    }

    @Test
    void blankNormalizationRespectsDeclaredPolicy() {
        FieldDefinition normalize = field("email", FieldType.EMAIL, false, "NORMALIZE_TO_NULL");
        assertNull(ValueCodec.parseFormValue(normalize, "   "));

        FieldDefinition keepEmpty = field("nickname", FieldType.STRING, false, null);
        assertEquals("", ValueCodec.parseFormValue(keepEmpty, "   "));
    }

    @Test
    void dateRoundTripsThroughIsoText() {
        FieldDefinition startDate = field("startDate", FieldType.DATE, true, null);
        Object parsed = ValueCodec.parseFormValue(startDate, "2026-09-09");
        assertEquals(LocalDate.of(2026, 9, 9), parsed);
    }

    @Test
    void canonicalEqualityComparesDecimalsByValueNotRepresentation() {
        FieldDefinition amount = field("lineAmount", FieldType.DECIMAL_19_2, true, null);
        assertTrue(ValueCodec.equalsCanonical(amount, new BigDecimal("100"), new BigDecimal("100.00")));
        assertFalse(ValueCodec.equalsCanonical(amount, new BigDecimal("100.00"), new BigDecimal("100.01")));
    }
}
