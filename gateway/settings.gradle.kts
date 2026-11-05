rootProject.name = "gateway"

include(
    "domain",
    "app",
    "adapters:api",
    "adapters:infra",
    "adapters:persistence",
    "bootstrap",
)
