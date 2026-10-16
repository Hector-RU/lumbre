package com.hector.epubreader.epub

/** Stable IDs are shared by Room and portable backups; CSS values never come from a book. */
enum class AnnotationStyle(val id: String, val shaded: Boolean, val underlined: Boolean) {
    SHADE("shade", true, false), UNDERLINE("underline", false, true), BOTH("both", true, true);
    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: BOTH }
}

enum class AnnotationColor(val id: String, val background: String, val ink: String, val line: String, val darkLine: String) {
    RED("red", "#ffc3c5", "#3e1018", "#c2394b", "#ff8f9b"),
    YELLOW("yellow", "#ffe082", "#211a00", "#a86600", "#ffd86a"),
    GREEN("green", "#b6edc9", "#102c1d", "#167542", "#67d793"),
    BLUE("blue", "#bcdcff", "#102b49", "#2068b8", "#79b5ff"),
    PURPLE("purple", "#dfc9ff", "#29153d", "#7b42bc", "#bf98f5");
    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: YELLOW }
}
