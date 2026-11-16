-- Employee app PIN (auth-service EmployeePinService). One salted bcrypt hash per
-- user; never plaintext. auth-service also creates it if missing at startup.
CREATE TABLE IF NOT EXISTS employee_pins (
    user_id         UUID PRIMARY KEY,
    pin_hash        VARCHAR(100) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until    TIMESTAMP NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
