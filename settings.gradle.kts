rootProject.name = "renderer"

// Composite build: Renderer is the fifth engine of the ecosystem — the materialized-view
// engine that folds SmokeHouse's tail into CSRBT-held aggregates. Including SmokeHouse's
// build transitively includes SuperBeefSort and CSRBT (nested composites); Gradle
// substitutes all published coordinates with the live sibling sources.
includeBuild("../SmokeHouse")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
