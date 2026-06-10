package io.konektis.grafana

import com.charleskorn.kaml.Yaml
import java.io.File
import kotlin.test.*

class ProvisioningFilesTest {
    private fun parse(path: String) = Yaml.default.parseToYamlNode(File(path).readText())

    @Test
    fun datasourceYamlIsValidAndNamesTheClickHouseDatasource() {
        val text = File("deploy/grafana/provisioning/datasources/clickhouse.yaml").readText()
        parse("deploy/grafana/provisioning/datasources/clickhouse.yaml") // throws if malformed
        assertTrue(text.contains("uid: ems-clickhouse"), "datasource must pin uid ems-clickhouse")
        assertTrue(text.contains("type: grafana-clickhouse-datasource"))
        assertTrue(text.contains("username: grafana"), "must connect as the read-only user")
        assertTrue(text.contains("\$GRAFANA_CH_PASSWORD"), "password must come from the env var")
    }

    @Test
    fun providerYamlIsValidAndPointsAtDashboards() {
        val text = File("deploy/grafana/provisioning/dashboards/provider.yaml").readText()
        parse("deploy/grafana/provisioning/dashboards/provider.yaml") // throws if malformed
        assertTrue(text.contains("path: /var/lib/grafana/dashboards"))
        assertTrue(text.contains("disableDeletion: true"))
    }
}
