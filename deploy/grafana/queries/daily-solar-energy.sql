SELECT
    toDate(ts) AS time,
    ifNull(sumIf(-s, s < 0), 0) / 60.0 / 1000.0 AS solar_produced_kwh
FROM (
    SELECT ts, avgMerge(solar_power) AS s
    FROM ems.power_1m
    WHERE $__timeFilter(ts)
    GROUP BY ts
)
GROUP BY time
ORDER BY time
