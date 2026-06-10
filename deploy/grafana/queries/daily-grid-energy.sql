SELECT
    toDate(ts) AS time,
    ifNull(sumIf(g, g > 0), 0) / 60.0 / 1000.0 AS imported_kwh,
    ifNull(sumIf(-g, g < 0), 0) / 60.0 / 1000.0 AS exported_kwh
FROM (
    SELECT ts, avgMerge(grid_power) AS g
    FROM ems.power_1m
    WHERE $__timeFilter(ts)
    GROUP BY ts
)
GROUP BY time
ORDER BY time
