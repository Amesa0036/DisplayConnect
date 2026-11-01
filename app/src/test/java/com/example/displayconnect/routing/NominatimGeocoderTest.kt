package com.example.displayconnect.routing

import org.junit.Assert.*
import org.junit.Test

class NominatimGeocoderTest {
    private fun rx(tokens: List<String>) = Regex(NominatimGeocoder.buildNameRegex(tokens), RegexOption.IGNORE_CASE)

    @Test fun plazaCatalunyaMatchesCatalanVariants() {
        val r = rx(listOf("plaza", "catalunya"))
        assertTrue(r.containsMatchIn("Plaça Catalunya"))
        assertTrue(r.containsMatchIn("Plaça de Catalunya"))
        assertTrue(r.containsMatchIn("Placa Catalunya"))
        assertFalse(r.containsMatchIn("Carrer de Catalunya"))
        assertFalse(r.containsMatchIn("Avinguda Meritxell"))
    }

    @Test fun calleTokenExpandsToCarrer() {
        val r = rx(listOf("calle", "major"))
        assertTrue(r.containsMatchIn("Carrer Major"))
        assertTrue(r.containsMatchIn("Calle Mayor") == false) // mayor != major, fine: not required
        assertTrue(rx(listOf("carrer", "major")).containsMatchIn("Carrer Major"))
    }

    @Test fun sanAndSantVariants() {
        assertTrue(rx(listOf("sant", "joan")).containsMatchIn("Plaça de Sant Joan"))
        assertTrue(rx(listOf("san", "joan")).containsMatchIn("Plaça de Sant Joan"))
    }

    @Test fun singleTokenMatchesCedillaVariant() {
        val r = rx(listOf("plaza"))
        assertTrue(r.containsMatchIn("Plaça Major"))
        assertTrue(r.containsMatchIn("plaza mayor"))
        assertFalse(r.containsMatchIn("Carrer Major"))
    }
}
