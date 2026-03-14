package com.mergegen.generator;

import com.mergegen.generator.LineDiff.Line;
import com.mergegen.generator.LineDiff.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineDiffTest {

    @Test
    void identicalFiles() {
        String[] lines = {"SELECT 1", "FROM DUAL;"};
        List<Line> diff = LineDiff.diff(lines, lines);

        assertEquals(2, diff.size());
        assertTrue(diff.stream().allMatch(l -> l.getType() == Type.SAME));
    }

    @Test
    void completelyDifferent() {
        String[] old = {"AAA", "BBB"};
        String[] neu = {"XXX", "YYY"};
        List<Line> diff = LineDiff.diff(old, neu);

        long removed = diff.stream().filter(l -> l.getType() == Type.REMOVED).count();
        long added   = diff.stream().filter(l -> l.getType() == Type.ADDED).count();
        assertEquals(2, removed);
        assertEquals(2, added);
    }

    @Test
    void lineAdded() {
        String[] old = {"A", "C"};
        String[] neu = {"A", "B", "C"};
        List<Line> diff = LineDiff.diff(old, neu);

        assertEquals(3, diff.size());
        assertEquals(Type.SAME,  diff.get(0).getType());
        assertEquals(Type.ADDED, diff.get(1).getType());
        assertEquals("B",       diff.get(1).getText());
        assertEquals(Type.SAME,  diff.get(2).getType());
    }

    @Test
    void lineRemoved() {
        String[] old = {"A", "B", "C"};
        String[] neu = {"A", "C"};
        List<Line> diff = LineDiff.diff(old, neu);

        assertEquals(3, diff.size());
        assertEquals(Type.SAME,    diff.get(0).getType());
        assertEquals(Type.REMOVED, diff.get(1).getType());
        assertEquals("B",         diff.get(1).getText());
        assertEquals(Type.SAME,    diff.get(2).getType());
    }

    @Test
    void emptyOld() {
        String[] old = {};
        String[] neu = {"X", "Y"};
        List<Line> diff = LineDiff.diff(old, neu);

        assertEquals(2, diff.size());
        assertTrue(diff.stream().allMatch(l -> l.getType() == Type.ADDED));
    }

    @Test
    void emptyNew() {
        String[] old = {"X", "Y"};
        String[] neu = {};
        List<Line> diff = LineDiff.diff(old, neu);

        assertEquals(2, diff.size());
        assertTrue(diff.stream().allMatch(l -> l.getType() == Type.REMOVED));
    }

    @Test
    void bothEmpty() {
        List<Line> diff = LineDiff.diff(new String[]{}, new String[]{});
        assertTrue(diff.isEmpty());
    }
}
