plugins {
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

tasks.wrapper {
    gradleVersion = "9.6.0"
    distributionType = Wrapper.DistributionType.BIN
}
