package io.konektis.config

import kotlinx.serialization.Serializable

/**
 * ClickHouse history store config. Optional and disabled by default so existing deployments load
 * unchanged. [host]/[port] address the ClickHouse HTTP interface (8123); on the NAS it is reached
 * as http://clickhouse:8123 over the shared Docker bridge.
 *
 * [user]/[password] authenticate against ClickHouse (HTTP Basic). They default to the `default` user
 * with an empty password — matching a passwordless `default` over the trusted Docker bridge — so set
 * [password] only if you have given `default` (or a dedicated user) a password.
 */
@Serializable
data class ClickHouseConfig(
    val enabled: Boolean = false,
    val host: String = "clickhouse",
    val port: Int = 8123,
    val database: String = "ems",
    val user: String = "default",
    val password: String = "",
)
