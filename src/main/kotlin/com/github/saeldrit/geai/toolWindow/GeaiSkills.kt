package com.github.saeldrit.geai.toolWindow

/** A one-click preset shown on the welcome screen. Clicking fills the composer with [prompt]. */
data class GeaiSkill(
    val id: String,
    val icon: String,
    val title: String,
    val badge: String?,
    val prompt: String,
)

/** Curated presets that map to geai's real capabilities. */
object GeaiSkills {

    fun all(): List<GeaiSkill> = listOf(
        GeaiSkill(
            "debug", "🐞", "Автономная отладка с агентом", "NEW",
            "Продебажь функционал: <опиши проблему>. Поставь брейкпоинты по пути данных, запусти дебаг, " +
                "отследи где данные теряются или портятся, и предложи фикс с указанием file:line.",
        ),
        GeaiSkill(
            "explain", "🔍", "Объяснить код и найти использования", null,
            "Объясни, как работает <класс/функция>, и покажи где он используется (file:line).",
        ),
        GeaiSkill(
            "feature", "✏️", "Реализация фичей и фиксов", null,
            "Реализуй: <что нужно>. Сначала изучи затронутый код, затем внеси минимальные правки в стиле проекта.",
        ),
        GeaiSkill(
            "refactor", "♻️", "Систематический рефакторинг", null,
            "Проведи рефакторинг <область>: убери дублирование, улучши имена, сохрани поведение. Покажи план перед правками.",
        ),
        GeaiSkill(
            "tests", "🧪", "Сгенерировать юнит-тесты", null,
            "Сгенерируй юнит-тесты для <класс/функция>, покрывая основные и граничные случаи, в стиле существующих тестов.",
        ),
        GeaiSkill(
            "review", "🔀", "Ревью кода", null,
            "Сделай ревью текущих изменений: корректность, баги, безопасность, упрощения. Дай findings с file:line.",
        ),
        GeaiSkill(
            "agents", "⚙️", "Создать AGENTS.md для проекта", null,
            "Изучи проект и создай AGENTS.md: структура, команды сборки/тестов, конвенции, ключевые модули.",
        ),
        GeaiSkill(
            "security", "🛡️", "Security-анализ", "BETA",
            "Проверь проект на уязвимости (инъекции, секреты, авторизация, небезопасная десериализация) и предложи исправления.",
        ),
    )
}
