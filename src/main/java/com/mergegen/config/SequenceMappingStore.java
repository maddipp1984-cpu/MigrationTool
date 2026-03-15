package com.mergegen.config;

import com.mergegen.model.SequenceMapping;

import java.util.Optional;

/**
 * Verwaltet Sequence-Zuordnungen fuer PK-Spalten.
 *
 * Gespeichert in "sequence-mappings.txt" im Arbeitsverzeichnis.
 * Format: TABLE_NAME|PK_COLUMN|SEQUENCE_NAME (eine Zeile pro Eintrag)
 * Zeilen, die mit '#' beginnen, werden als Kommentare ignoriert.
 */
public class SequenceMappingStore extends AbstractPipeStore<SequenceMapping> {

    private static final String FILE_NAME = "config/mergegen/sequence-mappings.txt";

    public SequenceMappingStore() {
        super(FILE_NAME,
            "Sequence-Zuordnungen fuer PK-Spalten",
            "Format: TABLE_NAME|PK_COLUMN|SEQUENCE_NAME");
    }

    @Override
    protected SequenceMapping parseLine(String[] parts) {
        return new SequenceMapping(
            parts[0].trim(),
            parts[1].trim(),
            parts[2].trim()
        );
    }

    @Override
    protected String formatEntry(SequenceMapping m) {
        return m.getTableName() + "|" + m.getPkColumn() + "|" + m.getSequenceName();
    }

    @Override
    protected int minFieldCount() { return 3; }

    /** Fuegt ein neues Mapping hinzu und speichert sofort. */
    public void add(SequenceMapping mapping) {
        entries.add(mapping);
        save();
    }

    /** Fuegt ein Mapping hinzu ohne sofort zu speichern (fuer Batch-Updates). */
    public void addWithoutSave(SequenceMapping mapping) {
        entries.add(mapping);
    }

    /** Entfernt ein Mapping anhand von Tabelle und PK-Spalte und speichert sofort. */
    public void remove(String tableName, String pkColumn) {
        entries.removeIf(e ->
            e.getTableName().equalsIgnoreCase(tableName) &&
            e.getPkColumn().equalsIgnoreCase(pkColumn));
        save();
    }

    /** Entfernt ein Mapping ohne sofort zu speichern (fuer Batch-Updates). */
    public void removeWithoutSave(String tableName, String pkColumn) {
        entries.removeIf(e ->
            e.getTableName().equalsIgnoreCase(tableName) &&
            e.getPkColumn().equalsIgnoreCase(pkColumn));
    }

    /** Sucht ein Mapping fuer die angegebene Tabelle. */
    public Optional<SequenceMapping> findByTable(String tableName) {
        return entries.stream()
            .filter(e -> e.getTableName().equalsIgnoreCase(tableName))
            .findFirst();
    }
}
