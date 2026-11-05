Let's imagine we're working at a company that already has clients and a product. We're
growing well. We have a backend with business logic and clients who interact with it through
an API.
Right now, all clients — regardless of their size — can use our API the same way. We want to
change that. We want to introduce tiers:
● Gold tier: Can make a very large number of API calls per day (e.g., 1 million)
● Silver tier: Moderate number
● Bronze tier: A smaller number (e.g., a few hundred per day)
The exact numbers aren't important right now.
Build a working prototype of three things:
1. The backend. Enforces a per-tenant daily quota by tier. The limit resets at midnight (no
sliding windows). If a client hits the limit, return an HTTP 4xx error indicating the limit is
reached.
2. An admin dashboard (React). It should let an operator answer, at a glance and in real
time: how much traffic are we taking right now, which tenants are close to or over their
limit, and how many requests are we rejecting. We expect some kind of live dashboard
that gives the admin the information about service state, tenants, their limits and
possibility to change tier per tenant. Handle the unhappy states: still loading, backend
down, connection dropped and recovered. We will not judge you on visual design, and
you do not need a component library or a design system — but we should be able to
read the screen.
3. A way to generate or simulate traffic, so the dashboard visibly moves when we run it.
The following describes the expected production behavior and should inform your design
decisions. The prototype itself does not need to fully implement or reproduce these
constraints and may use reasonable simplifications where necessary:
● There will be an allowed number of API calls per client (tenant) per day
● The limit resets at midnight (no sliding windows)
● If a client hits the limit, return an HTTP 4xx error indicating the limit is reached
● ~250,000 requests/minute at peak; ~100,000 req/min at off-peak. ~50,000 tenants
total
