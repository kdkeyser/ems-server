SELECT
    toDate(ts) AS time,
    ifNull(sum(c), 0) / 60.0 / 1000.0 AS charger_kwh,
    ifNull(sum(h), 0) / 60.0 / 1000.0 AS heatpump_kwh
FROM (
    SELECT ts, avgMerge(charger_power) AS c, avgMerge(heatpump_power) AS h
    FROM ems.power_1m
    WHERE $__timeFilter(ts)
    GROUP BY ts
)
GROUP BY time
ORDER BY time
