package com.mergegen.config;

import com.mergegen.model.ForeignKeyRelation;

import java.util.ArrayList;
import java.util.List;

/**
 * Verwaltet manuell definierte ("virtuelle") FK-Beziehungen, die in der Datenbank
 * nicht als Constraint hinterlegt sind.
 *
 * Gespeichert in "virtual-fks.txt" im Arbeitsverzeichnis.
 * Format: CHILD_TABLE|FK_COLUMN|PARENT_TABLE|PARENT_PK_COLUMN (eine Zeile pro Eintrag)
 * Zeilen, die mit '#' beginnen, werden als Kommentare ignoriert.
 *
 * Beim naechsten Traversal wird automatisch geprueft, ob ein virtueller FK inzwischen
 * als echter Constraint in der DB existiert – solche Eintraege werden still entfernt.
 */
public class VirtualFkStore extends AbstractPipeStore<ForeignKeyRelation> {

    private static final String FILE_NAME = "config/mergegen/virtual-fks.txt";

    public VirtualFkStore() {
        super(FILE_NAME,
            "Virtuelle FK-Definitionen",
            "Format: CHILD_TABLE|FK_COLUMN|PARENT_TABLE|PARENT_PK_COLUMN");
    }

    @Override
    protected ForeignKeyRelation parseLine(String[] parts) {
        return new ForeignKeyRelation(
            parts[0].trim().toUpperCase(),
            parts[1].trim().toUpperCase(),
            parts[2].trim().toUpperCase(),
            parts[3].trim().toUpperCase()
        );
    }

    @Override
    protected String formatEntry(ForeignKeyRelation rel) {
        return rel.getChildTable() + "|" +
               rel.getFkColumn()   + "|" +
               rel.getParentTable() + "|" +
               rel.getParentPkColumn();
    }

    @Override
    protected int minFieldCount() { return 4; }

    /**
     * Gibt alle virtuellen FKs zurueck, bei denen parentTable der angegebenen
     * Parent-Tabelle entspricht (case-insensitiv).
     */
    public List<ForeignKeyRelation> getRelationsForParent(String parentTable) {
        List<ForeignKeyRelation> result = new ArrayList<>();
        for (ForeignKeyRelation rel : entries) {
            if (rel.getParentTable().equalsIgnoreCase(parentTable)) {
                result.add(rel);
            }
        }
        return result;
    }

    /**
     * Gibt alle virtuellen FKs zurueck, bei denen die angegebene Tabelle
     * die Child-Tabelle ist (ausgehende Referenzen).
     */
    public List<ForeignKeyRelation> getRelationsForChild(String childTable) {
        List<ForeignKeyRelation> result = new ArrayList<>();
        for (ForeignKeyRelation rel : entries) {
            if (rel.getChildTable().equalsIgnoreCase(childTable)) {
                result.add(rel);
            }
        }
        return result;
    }

    /** Fuegt einen neuen virtuellen FK hinzu und speichert sofort. */
    public void add(ForeignKeyRelation rel) {
        entries.add(rel);
        save();
    }

    /** Entfernt einen virtuellen FK (Vergleich auf alle 4 Felder) und speichert sofort. */
    public void remove(ForeignKeyRelation rel) {
        entries.removeIf(e ->
            e.getChildTable().equalsIgnoreCase(rel.getChildTable()) &&
            e.getFkColumn().equalsIgnoreCase(rel.getFkColumn()) &&
            e.getParentTable().equalsIgnoreCase(rel.getParentTable()) &&
            e.getParentPkColumn().equalsIgnoreCase(rel.getParentPkColumn()));
        save();
    }
}
