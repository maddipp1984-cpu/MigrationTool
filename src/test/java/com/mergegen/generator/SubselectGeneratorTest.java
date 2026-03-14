package com.mergegen.generator;

import com.mergegen.model.ColumnInfo;
import com.mergegen.model.TableRow;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SubselectGeneratorTest {

    private MergeScriptGenerator gen = new MergeScriptGenerator();

    private TableRow buildEmployeeRow() {
        TableRow row = new TableRow("HR", "EMPLOYEES");
        row.addValue(new ColumnInfo("EMPLOYEE_ID", "NUMBER", false, true), "100");
        row.addValue(new ColumnInfo("LAST_NAME", "VARCHAR2", false, false), "'King'");
        row.addValue(new ColumnInfo("DEPARTMENT_ID", "NUMBER", true, false), "90");
        return row;
    }

    @Test
    void subselectErsetztFkWert() {
        TableRow row = buildEmployeeRow();
        Map<String, String> subselectMap = Map.of(
            "DEPARTMENT_ID",
            "(SELECT DEPARTMENT_ID FROM DEPARTMENTS WHERE DEPARTMENT_NAME = 'Executive')"
        );
        String sql = gen.generate(row, null, "EMPLOYEES", null, null, subselectMap, false);
        assertTrue(sql.contains(
            "(SELECT DEPARTMENT_ID FROM DEPARTMENTS WHERE DEPARTMENT_NAME = 'Executive') AS DEPARTMENT_ID"),
            "Subselect muss im USING-SELECT stehen");
        assertFalse(sql.contains("90 AS DEPARTMENT_ID"),
            "Originalwert 90 darf nicht mehr vorkommen");
    }

    @Test
    void subselectPrioritaet_colVarVorSubselect() {
        TableRow row = buildEmployeeRow();
        Map<String, String> colVarSubs = Map.of("DEPARTMENT_ID", "v_DEPT_1");
        String sql = gen.generate(row, null, "EMPLOYEES", null, null, colVarSubs, false);
        assertTrue(sql.contains("v_DEPT_1 AS DEPARTMENT_ID"));
    }

    @Test
    void ohneSubselect_originalwertBleibt() {
        TableRow row = buildEmployeeRow();
        String sql = gen.generate(row, null, "EMPLOYEES", null, null, null, false);
        assertTrue(sql.contains("90 AS DEPARTMENT_ID"));
    }
}
