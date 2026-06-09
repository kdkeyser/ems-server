package io.konektis.cardata

/**
 * BMW CarData OAuth endpoints and scope (public, account-independent). The MQTT broker host/port live
 * in [CarDataConfig] (defaulted to BMW's customer streaming broker). The MQTT topic for a vehicle is
 * `"<gcid>/<vin>"`.
 */
object CarDataEndpoints {
    const val DEVICE_CODE_URL = "https://customer.bmwgroup.com/gcdm/oauth/device/code"
    const val TOKEN_URL = "https://customer.bmwgroup.com/gcdm/oauth/token"
    const val SCOPE = "authenticate_user openid cardata:streaming:read cardata:api:read"
    const val GRANT_DEVICE_CODE = "urn:ietf:params:oauth:grant-type:device_code"
    const val GRANT_REFRESH = "refresh_token"
    // Confirmed on the BMW i5 — this descriptor carries the HV-battery SoC in %.
    const val SOC_DESCRIPTOR = "vehicle.drivetrain.batteryManagement.header"
}
