import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MovieBox"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://movieboxhd.net"
    }
}
