package com.repairshop.saas.marketplace.security;

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

/** Default TPIN_ROUTES (application.yml) for fixed-price Buy orders + staged enforcement. */
class TpinRoutePolicyTest {

    static class SpyGuard extends TpinGuard {
        final List<String> consumed = new ArrayList<>();
        SpyGuard() { super(null, null); }
        @Override
        public UUID consume(jakarta.servlet.http.HttpServletRequest req, String actionType, String targetId, String contextHash) {
            consumed.add(actionType);
            return null;
        }
    }

    String actionFor(String uri) throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        Matcher m = Pattern.compile("tpin-routes: \\$\\{TPIN_ROUTES:([^}]*)}").matcher(yml);
        assertTrue(m.find(), "tpin-routes default not found");
        SpyGuard g = new SpyGuard();
        new TpinInterceptor(g, Arrays.stream(m.group(1).split(",")).map(String::trim).toList())
                .preHandle(new MockHttpServletRequest("POST", uri), new MockHttpServletResponse(), null);
        return g.consumed.isEmpty() ? null : g.consumed.get(0);
    }

    @Test
    void staffPickupAtShopAndFinalDeliveryRequireTpin() throws Exception {
        String id = UUID.randomUUID().toString();
        assertEquals("BUY_ORDER", actionFor("/staff/buy-orders/" + id + "/pickup"));
        assertEquals("BUY_ORDER", actionFor("/staff/buy-orders/" + id + "/deliver"));
    }

    @Test
    void staffRoutineStepsDoNotRequireTpin() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String a : List.of("accept", "decline", "start-delivery", "fail")) {
            assertNull(actionFor("/staff/buy-orders/" + id + "/" + a), a);
        }
    }

    @Test
    void shopOwnerActionsUnchanged() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String a : List.of("confirm", "reject", "assign", "handover", "return-received")) {
            assertEquals("BUY_ORDER", actionFor("/shop/buy-orders/" + id + "/" + a), a);
        }
    }

    @Test
    void stagedEnforcement() {
        TpinGuard g = new TpinGuard(null, null);
        MockHttpServletRequest noHeader = new MockHttpServletRequest("POST", "/x");
        assertNull(g.consume(noHeader, "BUY_ORDER", null, "h"));            // default: off
        ReflectionTestUtils.invokeMethod(g, "setEnforcedActions", "buy_order");
        assertThrows(ResponseStatusException.class, () -> g.consume(noHeader, "BUY_ORDER", null, "h"));
    }
}
