plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Tarefa utilitária: `./gradlew apks` mostra os APKs gerados no último build.
tasks.register("apks") {
    group = "mobibrowser"
    description = "Lista os APKs produzidos em app/build/outputs/apk"
    doLast {
        val root = layout.projectDirectory.dir("app/build/outputs/apk").asFile
        if (!root.isDirectory) {
            println("Nenhum APK gerado ainda. Rode ./gradlew assembleUnstable primeiro.")
            return@doLast
        }
        root.walkTopDown()
            .filter { it.isFile && it.extension == "apk" }
            .forEach { println("${it.relativeTo(root)}  ${it.length() / 1024 / 1024} MB") }
    }
}
