package com.repairshop.saas.auth.controller;

import com.repairshop.saas.auth.service.DeviceSecurityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Device sessions, MPIN/TPIN and one-time TPIN authorization (migration 123). */
@RestController
@RequestMapping("/auth/security")
@RequiredArgsConstructor
@Tag(name = "Security", description = "Single-device sessions, MPIN, TPIN, transaction authorization")
public class SecurityController {

    private static final String AUTH = "Authorization";
    private final DeviceSecurityService svc;

    /** Body: deviceId, deviceName, platform, devicePublicKey?, replaceExisting. Bearer = fresh OTP-login JWT. */
    @PostMapping("/session/start")
    public ResponseEntity<Map<String, Object>> start(@RequestHeader(AUTH) String auth, @RequestBody Map<String, Object> b) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7).trim() : auth;
        Map<String, Object> res = svc.startSession(token, str(b, "deviceId"), str(b, "deviceName"), str(b, "platform"),
                str(b, "devicePublicKey"), Boolean.TRUE.equals(b.get("replaceExisting")));
        return ResponseEntity.status("DEVICE_LIMIT".equals(res.get("status")) ? HttpStatus.CONFLICT : HttpStatus.OK).body(res);
    }

    /** Employee app login (no OTP). Body: mobile, mpin, deviceId, deviceName, platform. */
    @PostMapping("/employee/login")
    public ResponseEntity<Map<String, Object>> employeeLogin(@RequestBody Map<String, Object> b) {
        Map<String, Object> res = svc.employeeLogin(str(b, "mobile"), str(b, "mpin"), str(b, "deviceId"), str(b, "deviceName"), str(b, "platform"));
        return ResponseEntity.status("DEVICE_APPROVAL_REQUIRED".equals(res.get("status")) ? HttpStatus.ACCEPTED : HttpStatus.OK).body(res);
    }

    /** Owner: is the employee's login MPIN set (never returns the MPIN). */
    @GetMapping("/employees/{employeeId}/mpin")
    public Map<String, Object> employeeMpinStatus(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable java.util.UUID employeeId) {
        return svc.employeeMpinStatus(auth, employeeId);
    }

    /** Owner: set / reset the employee's login MPIN. Body: pin. */
    @PutMapping("/employees/{employeeId}/mpin")
    public Map<String, Object> setEmployeeMpin(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable java.util.UUID employeeId,
                                               @RequestBody Map<String, Object> b) {
        return svc.ownerSetEmployeeMpin(auth, employeeId, str(b, "pin"));
    }

    /** Owner: pending employee new-device requests. */
    @GetMapping("/device-approvals")
    public java.util.List<Map<String, Object>> deviceApprovals(@RequestHeader(value = AUTH, required = false) String auth) {
        return svc.pendingDeviceApprovals(auth);
    }

    /** Owner: approve / deny an employee new-device request. */
    @PostMapping("/device-approvals/{requestId}/{decision:approve|deny}")
    public Map<String, Object> decideDeviceApproval(@RequestHeader(value = AUTH, required = false) String auth,
                                                    @PathVariable java.util.UUID requestId, @PathVariable String decision) {
        return svc.decideDeviceApproval(auth, requestId, "approve".equals(decision));
    }

    /** Body: refreshToken, accessToken (the expired one). */
    @PostMapping("/session/refresh")
    public Map<String, Object> refresh(@RequestBody Map<String, Object> b) {
        return svc.refresh(str(b, "refreshToken"), str(b, "accessToken"));
    }

    @GetMapping("/session")
    public Map<String, Object> status(@RequestHeader(value = AUTH, required = false) String auth) { return svc.status(auth); }

    @PostMapping("/session/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = AUTH, required = false) String auth) { svc.logout(auth); }

    @PostMapping("/biometric")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void biometric(@RequestHeader(value = AUTH, required = false) String auth, @RequestBody Map<String, Object> b) {
        svc.biometricPreference(auth, Boolean.TRUE.equals(b.get("enabled")));
    }

    @GetMapping("/pin")
    public Map<String, Object> pinStatus(@RequestHeader(value = AUTH, required = false) String auth) { return svc.pinStatus(auth); }

    /** {type} = MPIN | TPIN. Body: pin. */
    @PostMapping("/pin/{type}")
    public Map<String, Object> createPin(@RequestHeader(value = AUTH, required = false) String auth,
                                         @PathVariable String type, @RequestBody Map<String, Object> b) {
        return svc.createPin(auth, type, str(b, "pin"));
    }

    @PostMapping("/mpin/verify")
    public Map<String, Object> verifyMpin(@RequestHeader(value = AUTH, required = false) String auth, @RequestBody Map<String, Object> b) {
        return svc.verifyMpin(auth, str(b, "pin"));
    }

    /** Body: otpToken (fresh JWT from existing OTP login), newPin, verificationMethod (OTP | OTP_BIOMETRIC). */
    @PostMapping("/pin/{type}/reset")
    public Map<String, Object> resetPin(@RequestHeader(value = AUTH, required = false) String auth,
                                        @PathVariable String type, @RequestBody Map<String, Object> b) {
        return svc.resetPin(auth, type, str(b, "otpToken"), str(b, "newPin"), str(b, "verificationMethod"));
    }

    /** Body: pin, actionType, targetType?, targetId?, contextHash, idempotencyKey?. */
    @PostMapping("/tpin/authorize")
    public Map<String, Object> authorize(@RequestHeader(value = AUTH, required = false) String auth, @RequestBody Map<String, Object> b) {
        return svc.authorizeTransaction(auth, str(b, "pin"), str(b, "actionType"), str(b, "targetType"),
                str(b, "targetId"), str(b, "contextHash"), str(b, "idempotencyKey"));
    }

    /** Called by the executing service with the user's bearer. Body: authorizationToken, actionType, targetId?, contextHash. */
    @PostMapping("/tpin/consume")
    public Map<String, Object> consume(@RequestHeader(value = AUTH, required = false) String auth, @RequestBody Map<String, Object> b) {
        return svc.consume(auth, str(b, "authorizationToken"), str(b, "actionType"), str(b, "targetId"), str(b, "contextHash"));
    }

    private static String str(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
