package tether.ui

/** Builds the chat page as one self-contained HTML string from bundled resources. */
object WebAssets {
    private fun read(name: String): String =
        WebAssets::class.java.getResourceAsStream("/webview/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Inline scripts must not contain a literal closing script tag. */
    private fun script(name: String) = read(name).replace("</script", "<\\/script")

    fun page(): String = read("index.html")
        .replace("/*INLINE:chat.css*/", read("chat.css"))
        .replace("/*INLINE:marked*/", script("vendor/marked.min.js"))
        .replace("/*INLINE:purify*/", script("vendor/purify.min.js"))
        .replace("/*INLINE:text-keys.js*/", script("text-keys.js"))
        .replace("/*INLINE:completion.js*/", script("completion.js"))
        .replace("/*INLINE:chat.js*/", script("chat.js"))
}
