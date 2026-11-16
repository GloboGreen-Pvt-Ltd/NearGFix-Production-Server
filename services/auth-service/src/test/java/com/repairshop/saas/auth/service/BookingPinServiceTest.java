package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.exception.BadRequestException;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingPinServiceTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    final BCryptPasswordEncoder enc = new BCryptPasswordEncoder(4);
    JdbcTemplate jdbc;
    BookingPinService svc;
    final UUID shop = UUID.randomUUID();
    final UUID owner = UUID.randomUUID();
    final UUID emp = UUID.randomUUID();

    Map<String, Object> row(UUID id, String name, String pin) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id); m.put("name", name); m.put("pin_hash", enc.encode(pin)); m.put("locked_until", null);
        return m;
    }

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        svc = new BookingPinService(jdbc, enc, SECRET);
    }

    void staff(Map<String, Object>... rows) {
        when(jdbc.queryForList(anyString(), eq(shop), anyBoolean(), any(UUID.class))).thenReturn(List.of(rows));
        when(jdbc.queryForList(anyString(), argThat((Object o) -> !shop.equals(o)), anyBoolean(), any(UUID.class))).thenReturn(List.of());
    }

    @Test
    void validEmployeePin_returnsNameAndSignedToken_notUsableAsAccessToken() {
        staff(row(emp, "Ravi", "482915"));
        Map<String, Object> out = svc.verify(owner, shop, List.of("SHOP_OWNER"), "482915");
        assertEquals("Ravi", out.get("employeeName"));
        assertEquals("EMPLOYEE", out.get("role"));
        String token = (String) out.get("token");
        Claims c = Jwts.parser().verifyWith(Keys.hmacShaKeyFor((SECRET + BookingPinService.KEY_SUFFIX).getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
        assertEquals(emp.toString(), c.getSubject());
        assertEquals(shop.toString(), c.get("shopId"));
        assertEquals(owner.toString(), c.get("by"));
        assertFalse(out.toString().contains("482915"), "raw PIN never returned");
        // The plain JWT key (access tokens) must reject it.
        assertThrows(Exception.class, () -> Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token));
    }

    @Test
    void ownerOwnPin_attributedAsOwner() {
        staff(row(owner, "Owner Kumar", "730184"));
        assertEquals("OWNER", svc.verify(owner, shop, List.of("SHOP_OWNER"), "730184").get("role"));
    }

    @Test
    void invalidPin_blocked() {
        staff(row(emp, "Ravi", "482915"));
        assertThrows(UnauthorizedException.class, () -> svc.verify(owner, shop, List.of("SHOP_OWNER"), "999991"));
    }

    @Test
    void crossShopPin_blocked() {
        staff(row(emp, "Ravi", "482915"));
        UUID otherShop = UUID.randomUUID(); // other shop's query returns none of this shop's staff
        assertThrows(UnauthorizedException.class, () -> svc.verify(owner, otherShop, List.of("SHOP_OWNER"), "482915"));
    }

    @Test
    void malformedPin_rejected() {
        assertThrows(BadRequestException.class, () -> svc.verify(owner, shop, List.of(), "12ab"));
    }

    @Test
    void duplicatePinInShop_ambiguousRejected() {
        staff(row(emp, "Ravi", "482915"), row(UUID.randomUUID(), "Siva", "482915"));
        assertThrows(BadRequestException.class, () -> svc.verify(owner, shop, List.of("SHOP_OWNER"), "482915"));
    }

    @Test
    void fiveWrongPins_lockCaller_evenForCorrectPin() {
        staff(row(emp, "Ravi", "482915"));
        for (int i = 0; i < 4; i++) assertThrows(UnauthorizedException.class, () -> svc.verify(owner, shop, List.of(), "999991"));
        assertThrows(UnauthorizedException.class, () -> svc.verify(owner, shop, List.of(), "999991"));
        assertThrows(ForbiddenException.class, () -> svc.verify(owner, shop, List.of(), "482915"));
    }
}
