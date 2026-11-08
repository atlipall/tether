package tether.report

/**
 * This text cut to its first [max] characters, followed by how many were left out, for lines written to the IDE log
 * that later go into bug reports. Text no longer than [max] is returned as it is.
 */
fun String.clipped(max: Int): String =
    if (length <= max) this else "${take(max)}… (${length - max} more characters)"
