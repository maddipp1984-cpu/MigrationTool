package com.mergegen.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für TraversalService.toSqlLiteral() – reine String-Konvertierung,
 * keine DB-Abhängigkeit.
 */
class TraversalServiceTest {

    @Test
    void testNumericLiteral() {
        assertEquals("42", TraversalService.toSqlLiteral("42"));
    }

    @Test
    void testNegativeNumber() {
        assertEquals("-7", TraversalService.toSqlLiteral("-7"));
    }

    @Test
    void testLargeNumber() {
        assertEquals("9999999999999", TraversalService.toSqlLiteral("9999999999999"));
    }

    @Test
    void testStringLiteral() {
        assertEquals("'abc'", TraversalService.toSqlLiteral("abc"));
    }

    @Test
    void testSingleQuoteEscaped() {
        assertEquals("'O''Brien'", TraversalService.toSqlLiteral("O'Brien"));
    }

    @Test
    void testMultipleQuotes() {
        assertEquals("'it''s a ''test'''", TraversalService.toSqlLiteral("it's a 'test'"));
    }

    @Test
    void testDecimalIsString() {
        // Long.parseLong scheitert bei Dezimalzahlen → wird als String behandelt
        assertEquals("'3.14'", TraversalService.toSqlLiteral("3.14"));
    }

    @Test
    void testAlphanumericMixed() {
        assertEquals("'ORD-001'", TraversalService.toSqlLiteral("ORD-001"));
    }

    @Test
    void testWhitespaceIsTrimmed() {
        assertEquals("42", TraversalService.toSqlLiteral("  42  "));
    }

    @Test
    void testNullThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> TraversalService.toSqlLiteral(null));
    }

    @Test
    void testEmptyStringThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> TraversalService.toSqlLiteral(""));
    }

    @Test
    void testBlankStringThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> TraversalService.toSqlLiteral("   "));
    }
}
