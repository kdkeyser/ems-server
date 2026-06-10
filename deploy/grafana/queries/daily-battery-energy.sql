SELECT
    toDate(ts) AS time,
    ifNull(sumIf(b, b > 0), 0) / 60.0 / 1000.0 AS battery_charged_kwh,
    ifNull(sumIf(-b, b < 0), 0) / 60.0 / 1000.0 AS battery_discharged_kwh
FROM (
    SELECT ts, avgMerge(battery_power) AS b
    FROM ems.power_1m
    WHERE $__timeFilter(ts)
    GROUP BY ts
)
GROUP BY time
ORDER BY time
