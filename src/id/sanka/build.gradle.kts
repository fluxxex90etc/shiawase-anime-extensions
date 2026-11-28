import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Sanka"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://www.sankavollerei.web.id"
    }
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
}
