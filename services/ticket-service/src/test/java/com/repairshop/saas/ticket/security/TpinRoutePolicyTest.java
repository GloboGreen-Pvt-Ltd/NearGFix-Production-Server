package com.repairshop.saas.ticket.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Default TPIN_ROUTES (application.yml) + staged enforcement. No database needed. */
class TpinRoutePolicyTest {

    /** Records which action the interceptor asked the guard to consume. */
    static class SpyGuard extends TpinGuard {
        final List<String> consumed = new ArrayList<>();
        SpyGuard() { super(null, null); }
        @Override
        public UUID consume(jakarta.servlet.http.HttpServletRequest req, String actionType, String targetId, String contextHash) {
            consumed.add(actionType);
            return null;
        }
    }

    static List<String> defaultRoutes() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        Matcher m = Pattern.compile("tpin-routes: \\$\\{TPIN_ROUTES:([^}]*)}").matcher(yml);
        assertTrue(m.find(), "tpin-routes default not found");
        return Arrays.stream(m.group(1).split(",")).map(String::trim).toList();
    }

    String actionFor(String method, String uri) throws Exception {
        SpyGuard g = new SpyGuard();
        new TpinInterceptor(g, defaultRoutes()).preHandle(new MockHttpServletRequest(method, uri), new MockHttpServletResponse(), null);
        return g.consumed.isEmpty() ? null : g.consumed.get(0);
    }

    @Test
    void protectedEmployeeActionsRequireTpin() throws Exception {
        assertEquals("PICKUP_DELIVERY", actionFor("POST", "/technicians/me/repair-deliveries/" + UUID.randomUUID() + "/complete"));
        assertEquals("PAYMENT", actionFor("POST", "/technicians/me/sell-pickups/" + UUID.randomUUID() + "/payment"));
        assertEquals("TICKET_STATUS", actionFor("PATCH", "/tickets/" + UUID.randomUUID() + "/status"));
        assertEquals("PICKUP_DELIVERY", actionFor("POST", "/shop/pickup-bookings/" + UUID.randomUUID() + "/receive-at-shop"));
        assertEquals("PICKUP_DELIVERY", actionFor("POST", "/tickets/" + UUID.randomUUID() + "/repair-delivery/release"));
    }

    @Test
    void routineEmployeeStepsDoNotRequireTpin() throws Exception {
        String id = UUID.randomUUID().toString();
        // Pickup status (Start Journey, Arrived, …): Received at Shop is refused on this route
        // and completed only through the shop's TPIN-protected receive-at-shop.
        assertNull(actionFor("PATCH", "/technicians/me/pickup-bookings/" + id + "/status"));
        for (String a : List.of("accept", "decline", "collect", "start", "arrive", "fail", "return")) {
            assertNull(actionFor("POST", "/technicians/me/repair-deliveries/" + id + "/" + a), a);
        }
    }

    @Test
    void stagedEnforcementRejectsMissingTpinOnlyForListedActions() {
        TpinGuard g = new TpinGuard(null, null);
        ReflectionTestUtils.invokeMethod(g, "setEnforcedActions", " check_in , PAYMENT ,");
        MockHttpServletRequest noHeader = new MockHttpServletRequest("POST", "/x");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> g.consume(noHeader, "CHECK_IN", null, "h"));
        assertEquals(403, e.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> g.consume(noHeader, "PAYMENT", null, "h"));
        assertNull(g.consume(noHeader, "TICKET_STATUS", null, "h"));   // not listed, global switch off
    }

    @Test
    void globalSwitchStillEnforcesEverything() {
        TpinGuard g = new TpinGuard(null, null);
        ReflectionTestUtils.setField(g, "enforced", true);
        assertTrue(g.required("TICKET_STATUS"));
        assertThrows(ResponseStatusException.class, () -> g.consume(new MockHttpServletRequest("POST", "/x"), "TICKET_STATUS", null, "h"));
    }

    @Test
    void defaultsEnforceNothingWithoutHeader() {
        TpinGuard g = new TpinGuard(null, null);
        assertFalse(g.required("CHECK_IN"));
        assertNull(g.consume(new MockHttpServletRequest("POST", "/x"), "CHECK_IN", null, "h"));
    }
}
