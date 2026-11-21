package com.repairshop.saas.ticket.controller;

import com.repairshop.saas.ticket.security.TpinGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Shop handover TPIN confirmation guards (POST /shop/pickup-bookings/{id}/receive-at-shop). */
@ExtendWith(MockitoExtension.class)
class ShopHandoverTpinTest {

    @Mock PickupBookingController pickup;
    @Mock JdbcTemplate jdbc;
    @Mock TpinGuard tpinGuard;

    ShopPickupBookingController controller;
    MockHttpServletRequest req;
    final UUID shop = UUID.randomUUID(), otherShop = UUID.randomUUID(), booking = UUID.randomUUID();
    final UUID caller = UUID.randomUUID(), authId = UUID.randomUUID(), actor = UUID.randomUUID();
    final UUID pickupTech = UUID.randomUUID();
    Map<String, Object> current;

    @BeforeEach
    void setUp() {
        controller = new ShopPickupBookingController(pickup, jdbc, tpinGuard);
        req = new MockHttpServletRequest("POST", "/shop/pickup-bookings/" + booking + "/receive-at-shop");
        req.setAttribute("shopId", shop.toString());
        req.setAttribute("userId", caller.toString());
        current = new HashMap<>();
        current.put("status", "REACHED_SHOP");
        current.put("shop_id", shop.toString());
        current.put("booking_number", "CSPQXMISJPWUT");
        current.put("handover_requested_at", new Timestamp(System.currentTimeMillis()));
        current.put("assigned_pickup_person_id", pickupTech.toString());
        lenient().when(jdbc.queryForMap(startsWith("SELECT status, shop_id"), any(Object[].class))).thenAnswer(i -> current);
    }

    private void tpinBy(String actorType, UUID actorShop) {
        req.setAttribute(TpinGuard.APPROVAL_ATTR, authId); // consumed by TpinInterceptor
        Map<String, Object> a = new HashMap<>();
        a.put("actor_type", actorType);
        a.put("actor_id", actor.toString());
        a.put("shop_id", actorShop.toString());
        when(jdbc.queryForMap(startsWith("SELECT actor_type"), any(Object[].class))).thenReturn(a);
    }

    @SuppressWarnings("unchecked")
    private void employeeActive(Boolean active) {
        when(jdbc.query(startsWith("SELECT is_active"), any(ResultSetExtractor.class), any(), any())).thenReturn(active);
    }

    private ResponseEntity<Map<String, Object>> confirm() {
        return controller.receiveAtShop(req, booking, null);
    }

    @Test
    void rejectsWhenHandoverNotRequested() {
        current.put("handover_requested_at", null);
        ResponseEntity<Map<String, Object>> r = confirm();
        assertEquals(409, r.getStatusCode().value());
        assertEquals("HANDOVER_NOT_REQUESTED", r.getBody().get("code"));
        verifyNoInteractions(tpinGuard);
    }

    @Test
    void rejectsWithoutTpin() {
        ResponseEntity<Map<String, Object>> r = confirm();
        assertEquals(403, r.getStatusCode().value());
        assertEquals("TPIN_REQUIRED", r.getBody().get("code"));
    }

    @Test
    void rejectsBookingOfAnotherShop() {
        current.put("shop_id", otherShop.toString());
        assertEquals(403, confirm().getStatusCode().value());
        verifyNoInteractions(tpinGuard);
    }

    @Test
    void rejectsTpinIssuedForAnotherShop() {
        tpinBy("EMPLOYEE", otherShop);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, this::confirm);
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    void rejectsInactiveOrForeignEmployee() {
        tpinBy("EMPLOYEE", shop);
        employeeActive(false);
        assertEquals(403, assertThrows(ResponseStatusException.class, this::confirm).getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsPickupPersonConfirmingOwnHandover() {
        tpinBy("EMPLOYEE", shop);
        employeeActive(true);
        when(jdbc.query(startsWith("SELECT CAST(user_id"), any(ResultSetExtractor.class), any())).thenReturn(actor.toString());
        ResponseStatusException e = assertThrows(ResponseStatusException.class, this::confirm);
        assertEquals(403, e.getStatusCode().value());
        assertTrue(e.getReason().contains("own handover"));
        verify(jdbc, never()).update(startsWith("INSERT INTO pickup_handover_audit"), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnknownActorType() {
        tpinBy("CUSTOMER", shop);
        assertEquals(403, assertThrows(ResponseStatusException.class, this::confirm).getStatusCode().value());
    }

    @Test
    void rejectsDuplicateConfirmation() {
        current.put("status", "RECEIVED_AT_SHOP");
        when(pickup.handoverState(booking)).thenReturn(Map.of("state", "COMPLETED"));
        ResponseEntity<Map<String, Object>> r = confirm();
        assertEquals(409, r.getStatusCode().value());
        assertEquals("ALREADY_CONFIRMED", r.getBody().get("code"));
        verifyNoInteractions(tpinGuard);
    }
}
