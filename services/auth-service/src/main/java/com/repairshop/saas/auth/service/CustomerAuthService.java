package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.dto.CustomerAuthResponse;
import com.repairshop.saas.auth.dto.CustomerLoginRequest;
import com.repairshop.saas.auth.dto.CustomerRegisterRequest;
import com.repairshop.saas.auth.entity.CustomerSignupOtp;
import com.repairshop.saas.auth.entity.CustomerUser;
import com.repairshop.saas.auth.exception.BadRequestException;
import com.repairshop.saas.auth.exception.ConflictException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import com.repairshop.saas.auth.repository.CustomerSignupOtpRepository;
import com.repairshop.saas.auth.repository.CustomerUserRepository;
import com.repairshop.saas.auth.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerAuthService {

    private static final List<String> CUSTOMER_ROLES = List.of("CUSTOMER");
    private static final String DEFAULT_MOBILE_OTP = "123456";
    private static final SecureRandom RNG = new SecureRandom();
    // Sign-up codes are short-lived: the whole flow is "type number, read code,
    // type code" on one screen, so anything longer is just a wider window for a
    // guessed code to still be live.
    private static final int SIGNUP_OTP_TTL_MINUTES = 10;
    // Wrong tries allowed on one issued code before it must be resent.
    private static final int SIGNUP_OTP_MAX_ATTEMPTS = 5;

    // Exact wording the customer app shows in its toast, so the copy lives in
    // one place instead of being re-invented per client.
    public static final String MOBILE_TAKEN_MESSAGE = "This mobile number is already registered.";

    private final CustomerUserRepository customerUserRepository;
    private final CustomerSignupOtpRepository customerSignupOtpRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    // Best-effort email delivery (Resend). Reused for the customer OTP flow;
    // if unconfigured the code is still surfaced as devOtp in the response.
    private final EmailService emailService;
    // Direct JDBC: customers + customer_user_addresses etc. live in tables
    // owned by sibling services. We only need a single UPDATE to walk-in
    // rows so a full JPA mapping would be overkill.
    private final JdbcTemplate jdbc;
    // Needed to record a FAILED OTP attempt: the failure is signalled by
    // throwing, which rolls the surrounding transaction back — including the
    // counter increment. See burnSignupAttempt.
    private final PlatformTransactionManager txManager;

    @Transactional
    public CustomerAuthResponse register(CustomerRegisterRequest request) {
        String mobile = request.getMobile() != null ? request.getMobile().trim() : null;
        String email = request.getEmail() != null && !request.getEmail().isBlank()
                ? request.getEmail().trim().toLowerCase()
                : null;

        if (mobile == null || mobile.isBlank())
            throw new BadRequestException("Mobile is required");
        if (customerUserRepository.existsByMobile(mobile))
            throw new ConflictException(MOBILE_TAKEN_MESSAGE);
        if (email != null && customerUserRepository.existsByEmail(email))
            throw new ConflictException("This email is already registered.");

        // The app's Create Account flow is OTP-first and has no password field:
        // it verifies the mobile, then posts the same code here so the account
        // can only be created for a number the caller actually controls. The
        // password branch is kept for the older clients that still send one —
        // there, sign-up remains exactly as it was.
        String otp = request.getOtp() != null ? request.getOtp().trim() : null;
        String password = request.getPassword() != null && !request.getPassword().isBlank()
                ? request.getPassword()
                : null;
        if (otp != null && !otp.isBlank()) {
            consumeSignupOtp(mobile, otp);
        } else if (password == null) {
            throw new BadRequestException("Either otp or password is required");
        }

        CustomerUser user = CustomerUser.builder()
                .fullName(request.getFullName() != null ? request.getFullName().trim() : null)
                .email(email)
                .mobile(mobile)
                // Null when the account was created by OTP. Customers sign in
                // with a code, so there is nothing to set here; "forgot
                // password" fills it in if they ever want one.
                .passwordHash(password != null ? passwordEncoder.encode(password) : null)
                .isActive(true)
                .build();
        user = customerUserRepository.save(user);

        // Auto-link any walk-in customers rows that match this mobile so the
        // shop's prior tickets for this person show up in My Orders immediately.
        linkExistingWalkInRows(user.getId(), mobile);

        String token = jwtService.issueCustomerToken(user.getId(), CUSTOMER_ROLES);
        return toResponse(user, token);
    }

    // UPDATE every customers row with this phone that has no platform_user_id
    // yet, pointing it at the new customer_users.id. Idempotent — re-runs are
    // a no-op once the rows are linked. Errors are swallowed (and logged) so
    // a missing customers table doesn't block sign-up in dev.
    private void linkExistingWalkInRows(UUID customerUserId, String mobile) {
        if (mobile == null || mobile.isBlank()) return;
        try {
            int updated = jdbc.update(
                    "UPDATE customers SET platform_user_id = ? "
                    + "WHERE phone = ? AND platform_user_id IS NULL",
                    customerUserId, mobile);
            if (updated > 0) {
                log.info("Linked {} walk-in customers row(s) to customer_users {} (mobile {})",
                        updated, customerUserId, mobile);
            }
        } catch (Exception e) {
            log.warn("Walk-in customers auto-link failed for mobile {}: {}", mobile, e.getMessage());
        }
    }

    @Transactional
    public CustomerAuthResponse login(CustomerLoginRequest request) {
        String mobile = request.getMobile() != null ? request.getMobile().trim() : null;
        String email = request.getEmail() != null && !request.getEmail().isBlank()
                ? request.getEmail().trim().toLowerCase()
                : null;

        if ((mobile == null || mobile.isBlank()) && (email == null || email.isBlank()))
            throw new BadRequestException("Either mobile or email is required");

        boolean usingOtp = request.getOtp() != null && !request.getOtp().isBlank();
        boolean usingPwd = request.getPassword() != null && !request.getPassword().isBlank();
        if (!usingOtp && !usingPwd)
            throw new BadRequestException("Either password or otp is required");

        CustomerUser user;
        if (mobile != null && !mobile.isBlank()) {
            user = customerUserRepository.findByMobile(mobile)
                    .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        } else {
            user = customerUserRepository.findByEmail(email)
                    .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        }

        if (!Boolean.TRUE.equals(user.getIsActive()))
            throw new UnauthorizedException(AuthService.INACTIVE_ACCOUNT_MESSAGE);

        if (usingOtp) {
            if (!OtpPolicy.mobileMatch(mobile, user.getOtpCode(), request.getOtp()))
                throw new UnauthorizedException("Invalid OTP");
        } else {
            if (user.getPasswordHash() == null
                    || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash()))
                throw new UnauthorizedException("Invalid credentials");
        }

        // Defensive heal: walk-in customers rows added after the user signed
        // up don't get linked at register time; pick them up on login too.
        linkExistingWalkInRows(user.getId(), user.getMobile());

        String token = jwtService.issueCustomerToken(user.getId(), CUSTOMER_ROLES);
        return toResponse(user, token);
    }

    @Transactional(readOnly = true)
    public CustomerAuthResponse me(UUID customerUserId) {
        CustomerUser user = customerUserRepository.findById(customerUserId)
                .orElseThrow(() -> new UnauthorizedException("Customer not found"));
        return toResponse(user, null);
    }

    // ---- Sign-up OTP (customer_signup_otps) -----------------------------------

    /**
     * Step 1 of Create Account: issue an OTP for a mobile number that must NOT
     * already have an account. Conflicts with an existing customer are a 409 so
     * the app can show "already registered" and send the user to sign-in,
     * instead of having to string-match a generic 400.
     *
     * There is no SMS gateway yet, so the code is the platform-wide default for
     * mobile identifiers - the same one /auth/customer/otp/send hands out for
     * sign-in. When a gateway lands, only the code generation and the send call
     * below change; the stored-code contract stays as it is.
     */
    @Transactional
    public Map<String, Object> sendSignupOtp(String mobile) {
        String m = mobile == null ? "" : mobile.trim();
        if (m.isBlank())
            throw new BadRequestException("Mobile number is required");
        if (customerUserRepository.existsByMobile(m))
            throw new ConflictException(MOBILE_TAKEN_MESSAGE);

        String code = DEFAULT_MOBILE_OTP;
        OtpPolicy.issueTestOtp(m);
        // Replace outright rather than update-in-place: a resend must reset the
        // attempt counter and the expiry, which is every mutable field anyway.
        CustomerSignupOtp row = customerSignupOtpRepository.findById(m)
                .orElseGet(() -> CustomerSignupOtp.builder().mobile(m).build());
        row.setOtpCode(code);
        row.setAttempts(0);
        row.setExpiresAt(Instant.now().plus(SIGNUP_OTP_TTL_MINUTES, ChronoUnit.MINUTES));
        customerSignupOtpRepository.save(row);

        Map<String, Object> res = new HashMap<>();
        res.put("channel", "MOBILE");
        res.put("sent", true);
        res.put("target", maskMobile(m));
        res.put("ttlMinutes", SIGNUP_OTP_TTL_MINUTES);
        return res;
    }

    /**
     * Step 2 of Create Account: check the code WITHOUT consuming it, so the app
     * can move on to the name field while /auth/customer-register still gets to
     * re-verify (and consume) the same code when the account is actually made.
     */
    @Transactional
    public Map<String, Object> verifySignupOtp(String mobile, String otp) {
        String m = mobile == null ? "" : mobile.trim();
        checkSignupOtp(m, otp);
        Map<String, Object> res = new HashMap<>();
        res.put("verified", true);
        res.put("mobile", m);
        return res;
    }

    /** Verify + delete. Called from register() once the account is about to be created. */
    private void consumeSignupOtp(String mobile, String otp) {
        checkSignupOtp(mobile, otp);
        customerSignupOtpRepository.deleteById(mobile);
    }

    /**
     * Shared check for both of the above. A wrong code burns an attempt; running
     * out of attempts (or letting the code expire) drops the row, so the user has
     * to request a fresh one rather than keep guessing at the same six digits.
     */
    private void checkSignupOtp(String mobile, String otp) {
        if (mobile == null || mobile.isBlank())
            throw new BadRequestException("Mobile number is required");
        if (otp == null || otp.isBlank())
            throw new BadRequestException("OTP is required");
        if (customerUserRepository.existsByMobile(mobile))
            throw new ConflictException(MOBILE_TAKEN_MESSAGE);

        CustomerSignupOtp row = customerSignupOtpRepository.findById(mobile)
                .orElseThrow(() -> new UnauthorizedException("Request a new code and try again."));
        if (row.getExpiresAt() == null || row.getExpiresAt().isBefore(Instant.now())) {
            burnSignupAttempt(mobile, true);
            throw new UnauthorizedException("That code has expired. Request a new one.");
        }
        if (!OtpPolicy.mobileMatch(mobile, row.getOtpCode(), otp)) {
            int used = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
            boolean exhausted = used >= SIGNUP_OTP_MAX_ATTEMPTS;
            burnSignupAttempt(mobile, exhausted);
            throw new UnauthorizedException(exhausted
                    ? "Too many incorrect attempts. Request a new code."
                    : "Incorrect code. Please check and try again.");
        }
    }

    /**
     * Record a wrong guess (or drop a spent/expired code) in its OWN transaction.
     *
     * This CANNOT be done on the caller's transaction: every failure path here
     * ends in a thrown exception, which rolls that transaction back and takes the
     * counter increment with it — leaving an attempt limit that silently never
     * counts anything. REQUIRES_NEW commits the bookkeeping before the throw
     * unwinds. Plain JDBC rather than the repository so the write can't be
     * confused with the entity the outer (about to roll back) transaction holds.
     */
    private void burnSignupAttempt(String mobile, boolean drop) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            if (drop) {
                jdbc.update("DELETE FROM customer_signup_otps WHERE mobile = ?", mobile);
            } else {
                jdbc.update("UPDATE customer_signup_otps SET attempts = attempts + 1, "
                        + "updated_at = now() WHERE mobile = ?", mobile);
            }
        });
    }

    // ---- OTP send + forgot-password (customer_users) --------------------------

    /**
     * Issue a login / password-reset OTP for a customer. The code is written to
     * customer_users.otp_code so both "sign in with OTP" (via /customer-login)
     * and the reset flow verify against the same stored value. For an EMAIL
     * identifier we generate a random 6-digit code and email it via Resend; for
     * a MOBILE identifier the code is the default 123456 (no SMS gateway). The
     * account must exist. In dev the code is surfaced as devOtp.
     */
    @Transactional
    public Map<String, Object> sendOtp(String identifier) {
        if (identifier == null || identifier.isBlank())
            throw new BadRequestException("Email or mobile number is required");
        String id = identifier.trim();
        boolean isEmail = id.contains("@");
        CustomerUser user = (isEmail
                ? customerUserRepository.findByEmail(id.toLowerCase())
                : customerUserRepository.findByMobile(id))
                .orElseThrow(() -> new BadRequestException("No account found for that email or mobile number."));

        Map<String, Object> res = new HashMap<>();
        if (isEmail) {
            String code = String.format("%06d", RNG.nextInt(1_000_000));
            user.setOtpCode(code);
            customerUserRepository.save(user);
            boolean sent = emailService.sendOtpEmail(id, code, "reset your GGFIX password");
            res.put("channel", "EMAIL");
            res.put("sent", sent);
            res.put("target", maskEmail(id));
            res.put("ttlMinutes", 10);
            OtpPolicy.devHint(res, "devOtp", code); // dev mode only
        } else {
            user.setOtpCode(DEFAULT_MOBILE_OTP);
            customerUserRepository.save(user);
            res.put("channel", "MOBILE");
            res.put("sent", true);
            res.put("target", maskMobile(id));
            OtpPolicy.issueTestOtp(id);
            OtpPolicy.devHint(res, "defaultOtp", DEFAULT_MOBILE_OTP);
        }
        res.put("email", isEmail ? id : user.getEmail());
        return res;
    }

    /**
     * Verify the reset OTP against customer_users.otp_code, set a new bcrypt
     * password, consume the code, and return a fresh customer session (auto
     * sign-in). Both email and mobile OTPs are the value stored by {@link #sendOtp}.
     */
    @Transactional
    public CustomerAuthResponse resetPasswordWithOtp(String identifier, String otp, String newPassword) {
        if (identifier == null || identifier.isBlank())
            throw new BadRequestException("Email or mobile number is required");
        if (otp == null || otp.isBlank())
            throw new BadRequestException("OTP is required");
        if (newPassword == null || newPassword.trim().length() < 8)
            throw new BadRequestException("Password must be at least 8 characters.");
        String id = identifier.trim();
        boolean isEmail = id.contains("@");
        CustomerUser user = (isEmail
                ? customerUserRepository.findByEmail(id.toLowerCase())
                : customerUserRepository.findByMobile(id))
                .orElseThrow(() -> new BadRequestException("No account found."));
        if (!OtpPolicy.mobileMatch(id, user.getOtpCode(), otp))
            throw new UnauthorizedException("Invalid or expired OTP.");

        user.setPasswordHash(passwordEncoder.encode(newPassword.trim()));
        user.setOtpCode(null); // one-time use
        user = customerUserRepository.save(user);

        String token = jwtService.issueCustomerToken(user.getId(), CUSTOMER_ROLES);
        return toResponse(user, token);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at - 1);
    }

    private static String maskMobile(String mobile) {
        String d = mobile.replaceAll("\\D", "");
        if (d.length() < 2) return "***";
        return "***-***-" + d.substring(d.length() - 2);
    }

    private CustomerAuthResponse toResponse(CustomerUser user, String token) {
        return CustomerAuthResponse.builder()
                .accessToken(token)
                .userId(user.getId().toString())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .mobile(user.getMobile())
                .roles(CUSTOMER_ROLES)
                .build();
    }
}
