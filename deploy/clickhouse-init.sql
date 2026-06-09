-- Applied automatically on first ClickHouse start (mounted into /docker-entrypoint-initdb.d).
CREATE DATABASE IF NOT EXISTS ems;

-- Raw 5-second ticks, retained for 1 year then dropped.
CREATE TABLE IF NOT EXISTS ems.power_raw (
    ts             DateTime,
    grid_power     Nullable(Int32),
    solar_power    Nullable(Int32),
    charger_power  Nullable(Int32),
    heatpump_power Nullable(Int32),
    battery_power  Nullable(Int32),
    battery_charge Nullable(Int32)
) ENGINE = MergeTree()
ORDER BY ts
TTL ts + INTERVAL 1 YEAR DELETE;

-- 1-minute partial aggregates, merged by AggregatingMergeTree, retained indefinitely.
CREATE TABLE IF NOT EXISTS ems.power_1m (
    ts             DateTime,
    grid_power     AggregateFunction(avg, Nullable(Int32)),
    solar_power    AggregateFunction(avg, Nullable(Int32)),
    charger_power  AggregateFunction(avg, Nullable(Int32)),
    heatpump_power AggregateFunction(avg, Nullable(Int32)),
    battery_power  AggregateFunction(avg, Nullable(Int32)),
    battery_charge AggregateFunction(avg, Nullable(Int32))
) ENGINE = AggregatingMergeTree()
ORDER BY ts;

CREATE MATERIALIZED VIEW IF NOT EXISTS ems.power_1m_mv
TO ems.power_1m AS
SELECT
    toStartOfMinute(ts)      AS ts,
    avgState(grid_power)     AS grid_power,
    avgState(solar_power)    AS solar_power,
    avgState(charger_power)  AS charger_power,
    avgState(heatpump_power) AS heatpump_power,
    avgState(battery_power)  AS battery_power,
    avgState(battery_charge) AS battery_charge
FROM ems.power_raw
GROUP BY ts;

-- One-time backfill (run manually only when migrating an already-populated power_raw; no-op on fresh
-- deploy). Uses -State to match the AggregatingMergeTree format:
-- INSERT INTO ems.power_1m
-- SELECT toStartOfMinute(ts), avgState(grid_power), avgState(solar_power), avgState(charger_power),
--        avgState(heatpump_power), avgState(battery_power), avgState(battery_charge)
-- FROM ems.power_raw GROUP BY toStartOfMinute(ts);
