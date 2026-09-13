// AGP 9 traz suporte embutido a Kotlin ("built-in Kotlin"): os plugins
// org.jetbrains.kotlin.android e org.jetbrains.kotlin.plugin.compose NÃO devem mais ser
// aplicados — o próprio AGP registra a extensão `kotlin` e o compilador do Compose.
// Ver developer.android.com/build/migrate-to-built-in-kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
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
