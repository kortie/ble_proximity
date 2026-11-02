package dev.proximity.core.config

class ConfigException(message: String) : IllegalArgumentException(message)

/**
 * config 디렉터리의 yaml 전용 최소 파서: 최상위 섹션(`name:`) 아래 2칸 들여쓴 `key: value` 스칼라만 읽는다.
 * 런타임 의존을 늘리지 않으려고 직접 둔다 (ADR-001). 범위를 벗어난 형식은 오류로 거절한다.
 */
object SimpleYaml {
    fun parse(text: String): Map<String, Map<String, String>> {
        val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var section: LinkedHashMap<String, String>? = null
        text.lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = stripComment(raw).trimEnd()
            if (line.isBlank()) return@forEachIndexed
            val indent = line.length - line.trimStart().length
            val content = line.trim()
            val colon = content.indexOf(':')
            if (colon <= 0) throw ConfigException("${lineNo}행: `key: value` 형식이 아니다")
            val key = content.substring(0, colon).trim()
            val value = content.substring(colon + 1).trim()
            when (indent) {
                0 -> {
                    if (value.isNotEmpty()) throw ConfigException("${lineNo}행: 최상위에는 섹션만 둔다 ($key)")
                    if (key in result) throw ConfigException("${lineNo}행: 섹션 중복 ($key)")
                    section = LinkedHashMap<String, String>().also { result[key] = it }
                }
                2 -> {
                    val current = section ?: throw ConfigException("${lineNo}행: 섹션 밖의 값 ($key)")
                    if (value.isEmpty()) throw ConfigException("${lineNo}행: 값이 없다 ($key). 3단계 이상은 지원하지 않는다")
                    if (key in current) throw ConfigException("${lineNo}행: 키 중복 ($key)")
                    current[key] = value
                }
                else -> throw ConfigException("${lineNo}행: 들여쓰기는 0 또는 2칸이어야 한다")
            }
        }
        return result
    }

    private fun stripComment(line: String): String {
        val hash = line.indexOf('#')
        return if (hash >= 0) line.substring(0, hash) else line
    }
}
