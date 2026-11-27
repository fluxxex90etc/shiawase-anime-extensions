import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Ryukomik"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://ryukomik.com"
    }
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
}
