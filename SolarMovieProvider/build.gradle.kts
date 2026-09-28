version = 1

cloudstream {
    description = "SolarMovie2 - أفلام ومسلسلات أجنبية مع خوادم متعددة وترجمة عربية"
    authors = listOf("nu2")

    status = 1

    language = "en"

    tvTypes = listOf("Movie", "TvSeries")
}

dependencies {
    // Pure-JVM WebAssembly runtime: runs the rotating vidsrc stream decryptor (vsdec)
    implementation("com.dylibso.chicory:runtime:1.7.5")
}
