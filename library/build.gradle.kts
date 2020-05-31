val externalLibraryVersion: String? by extra
val fuelVersion: String? by extra
val guiceVersion: String? by extra
val kotlinGuiceVersion: String? by extra

val junitVersion: String? by extra

val hamkrestVersion: String? by extra
val mockkVersion: String? by extra

dependencies {
    implementation("il.ac.technion.cs.softwaredesign", "primitive-storage-layer", externalLibraryVersion)
    implementation("com.github.kittinunf.fuel", "fuel", fuelVersion)
    implementation("com.google.inject", "guice", guiceVersion)
    implementation("dev.misfitlabs.kotlinguice4", "kotlin-guice", kotlinGuiceVersion)

    testCompile("org.junit.jupiter", "junit-jupiter-api", junitVersion)
    testCompile("org.junit.jupiter", "junit-jupiter-params", junitVersion)

    implementation("com.natpryce:hamkrest:$hamkrestVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")

    testImplementation("org.junit.jupiter", "junit-jupiter-engine", junitVersion)

}