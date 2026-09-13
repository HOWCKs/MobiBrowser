# GeckoView é carregado com muita reflexão e callbacks de delegate: manter nomes.
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.gecko.**

# Delegados anônimos definidos em Kotlin (instanciados pelo motor via reflexão de bundle).
-keep class app.mobibrowser.core.engine.** { *; }
-keep class app.mobibrowser.core.ext.** { *; }

# JS das extensões empacotado em assets: nunca minificar/remover.
-keep class org.json.** { *; }
