import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "NontonAnimeID"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://nontonanime.id"
    }
}
