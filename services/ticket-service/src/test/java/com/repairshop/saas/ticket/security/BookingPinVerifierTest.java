package com.repairshop.saas.ticket.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingPinVerifierTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    final UUID shop = UUID.randomUUID(), caller = UUID.randomUUID(), emp = UUID.randomUUID();

    String token(String keySuffix, UUID shopId, UUID by, String typ, long ttlSec) {
        return Jwts.builder().subject(emp.toString()).claim("typ", typ).claim("shopId", shopId.toString())
                .claim("by", by.toString()).claim("name", "Ravi").claim("role", "EMPLOYEE")
                .expiration(Date.from(Instant.now().plusSeconds(ttlSec)))
                .signWith(Keys.hmacShaKeyFor((SECRET + keySuffix).getBytes(StandardCharsets.UTF_8))).compact();
    }

    BookingPinVerifier verifier(List<String> activeNames) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any(), anyBoolean(), any())).thenReturn(activeNames);
        return new BookingPinVerifier(SECRET, jdbc);
    }

    @Test
    void validToken_givesBookedBy() {
        var b = verifier(List.of("Ravi")).verify(token(":booking-pin", shop, caller, "booking_pin", 300), shop, caller);
        assertEquals(emp, b.userId());
        assertEquals("Ravi", b.name());
        assertEquals("EMPLOYEE", b.role());
    }

    @Test
    void otherShop_blocked() {
        assertThrows(ResponseStatusException.class, () -> verifier(List.of("Ravi"))
                .verify(token(":booking-pin", UUID.randomUUID(), caller, "booking_pin", 300), shop, caller));
    }

    @Test
    void otherLogin_blocked() {
        assertThrows(ResponseStatusException.class, () -> verifier(List.of("Ravi"))
                .verify(token(":booking-pin", shop, UUID.randomUUID(), "booking_pin", 300), shop, caller));
    }

    @Test
    void expired_blocked() {
        assertThrows(ResponseStatusException.class, () -> verifier(List.of("Ravi"))
                .verify(token(":booking-pin", shop, caller, "booking_pin", -5), shop, caller));
    }

    @Test
    void accessTokenKey_forgedOrReplayed_blocked() {
        assertThrows(ResponseStatusException.class, () -> verifier(List.of("Ravi"))
                .verify(token("", shop, caller, "booking_pin", 300), shop, caller));
    }

    @Test
    void inactiveEmployeeAtSubmit_blocked() {
        assertThrows(ResponseStatusException.class, () -> verifier(List.of())
                .verify(token(":booking-pin", shop, caller, "booking_pin", 300), shop, caller));
    }
}
