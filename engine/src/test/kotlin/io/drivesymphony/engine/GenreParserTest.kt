package io.drivesymphony.engine

import io.drivesymphony.engine.theory.Genre
import io.drivesymphony.engine.theory.GenreParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenreParserTest {
    @Test
    fun koreanAndEnglishPhrases() {
        assertEquals(Genre.JAZZ, GenreParser.parse("재즈로 바꿔줘"))
        assertEquals(Genre.JAZZ, GenreParser.parse("Play some Jazz"))
        assertEquals(Genre.AMBIENT, GenreParser.parse("앰비언트"))
        assertEquals(Genre.LOFI, GenreParser.parse("로파이 틀어줘"))
        assertEquals(Genre.LOFI, GenreParser.parse("lo-fi beats"))
        assertEquals(Genre.LOFI, GenreParser.parse("LOFI"))
        assertEquals(Genre.AMBIENT, GenreParser.parse("Ambient"))
    }

    @Test
    fun unknownTextIsNull() {
        assertNull(GenreParser.parse(null))
        assertNull(GenreParser.parse(""))
        assertNull(GenreParser.parse("클래식"))
    }
}
