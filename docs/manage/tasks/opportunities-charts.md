# Task

Create a page with a chart which show number of unique opportunities (by uuid) by the days
and ratio of number of orders per opportunities per day

the page should display chart by last 10 days
page must have a date from-to filter

# Spec

Add an authenticated **Opportunity Activity** page that displays one combined daily chart:

- bars show the number of unique opportunities detected each day;
- a line on a separate Y axis shows the number of orders per opportunity for that day.

The page opens with the last 10 UTC calendar days selected, including today, and provides
an inclusive `from`/`to` date-range filter. Applying the filter reloads the chart. Loading,
empty, and API-error states must be shown consistently with the existing report pages.

Expose `GET /api/arbitrage/opportunities/daily-stats` with optional `startDate` and `endDate`
query parameters in `YYYY-MM-DD` format. When omitted, they default to the same last-10-day
UTC range used by the page. Reject a range where `startDate` is after `endDate` with HTTP 400.

Return a typed DTO for every date in the inclusive range, ordered ascending:

```json
{
  "date": "2026-09-04",
  "opportunityCount": 12,
  "orderCount": 20,
  "ordersPerOpportunity": 1.6667
}
```

For each UTC date:

- `opportunityCount` is `COUNT(DISTINCT uuid)` from `arbitrage_events` for
  `event_type = 'OPPORTUNITY_START'`, grouped by `detected_at` date;
- `orderCount` is the number of `arbitrage_orders` rows, of any status, linked to those
  opportunity UUIDs. Orders belong to the opportunity's detection date, not their own
  `created_at` date;
- `ordersPerOpportunity = orderCount / opportunityCount`, rounded half-up to four decimal
  places and set to `0` when no opportunities exist;
- dates without data are included with zero values so the chart has no missing days.

Add the page to the authenticated router and sidebar, and add a frontend API method for the
new endpoint. Reuse the current Element Plus page layout and the dependency-free SVG chart
approach used by `OrderVolume.vue`.

# Todo Plan

- [x] Add a `DailyOpportunityStatsDto` record and backend report/query code that performs
  the UTC aggregation without returning `Object[]`, maps the ratio with defined precision,
  and fills missing dates with zero-valued DTOs.
- [x] Add `GET /api/arbitrage/opportunities/daily-stats` to `ArbitrageController`, including
  the default 10-day range and invalid-range validation.
- [x] Add authenticated controller integration tests using `AuthenticatedClient` for daily
  counts, duplicate UUID handling, all order statuses, order-to-opportunity day attribution,
  zero-filled dates, default dates, and reversed-range HTTP 400 behavior.
- [x] Add `getDailyOpportunityStats(startDate, endDate)` to the frontend arbitrage API.
- [x] Create the Opportunity Activity Vue view with the default last-10-day UTC range,
  date filter, dual-scale bar/line SVG chart, tooltips/labels, and loading, empty, and error
  states.
- [x] Register the authenticated route and add a sidebar menu item with a unique active
  index.
- [x] Verify with `./gradlew test` (Docker/Testcontainers required) and `npm run build`.
