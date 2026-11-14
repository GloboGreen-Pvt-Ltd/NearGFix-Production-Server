package com.repairshop.saas.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Partner App "Create Account" (owner + first shop), sent with the OTP. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartnerSignupRequest {
    @NotBlank(message = "Owner name is required") @Size(max = 255) private String ownerName;
    @NotBlank(message = "Owner mobile is required") private String ownerMobile;
    @NotBlank(message = "Shop name is required") @Size(max = 255) private String shopName;
    private String shopMobile;
    @NotBlank(message = "Shop address is required") private String shopAddress;
    @NotBlank(message = "OTP is required") private String otp;
}
