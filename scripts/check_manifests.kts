import java.io.File

fun main() {
    val manifests = File(".").walkTopDown()
        .filter { it.name == "AndroidManifest.xml" }
        .filterNot { it.path.contains("${File.separator}build${File.separator}") }
    manifests.forEach {
        println("\uD83D\uDD0D Checking: ${it.path}")
        val text = it.readText()
        if (!text.contains("<application"))
            println("\u26A0\uFE0F Missing <application> tag in ${it.path}")
        if (text.contains("android.intent.category.LAUNCHER"))
            println("\uD83D\uDE80 MAIN/LAUNCHER found in ${it.path}")
        if (text.contains("io.texne.g1.basis"))
            println("\u26A0\uFE0F Legacy package reference in ${it.path}")
    }
}

main()
