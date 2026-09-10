package org.blueprintruntime.runtime;

import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.TableDefinition;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves REFERENCE fields to their target's human-readable {@code displayField}, for
 * list/view rendering and for populating a reference picker per {@code
 * uiContract.referencePicker}: value = target key, label = target displayField.
 */
public final class RelationshipResolver {

    private final RecordStore recordStore;

    public RelationshipResolver(RecordStore recordStore) {
        this.recordStore = recordStore;
    }

    public record PickerOption(String value, String label) {
    }

    public String displayValue(TableDefinition table, GenericRecord record) {
        FieldDefinition displayField = table.requireField(table.displayField());
        Object value = record.get(displayField.fieldId());
        return value == null ? "" : ValueCodec.toDisplayString(displayField, value);
    }

    public Optional<String> resolveReferenceLabel(Connection connection, TableDefinition referencedTable, Object keyValue) throws SQLException {
        if (keyValue == null) return Optional.empty();
        return recordStore.find(connection, referencedTable.tableId(), keyValue.toString())
                .map(r -> displayValue(referencedTable, r));
    }

    public List<PickerOption> pickerOptions(Connection connection, TableDefinition referencedTable) throws SQLException {
        FieldDefinition pk = referencedTable.requirePrimaryKeyField();
        List<PickerOption> options = new ArrayList<>();
        for (GenericRecord record : recordStore.findAll(connection, referencedTable.tableId())) {
            Object key = record.get(pk.fieldId());
            options.add(new PickerOption(key == null ? "" : key.toString(), displayValue(referencedTable, record)));
        }
        return options;
    }
}
