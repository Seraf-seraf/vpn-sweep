package com.seraf.vpnsweep.searchui

enum class SubscriptionError {
    NETWORK, TIMEOUT, HTTP, MALFORMED, INVALID_BASE64, LIMIT_EXCEEDED, NO_SUPPORTED, INVALID_URL, TLS, INTERNAL,
}

internal fun subscriptionErrorText(error: SubscriptionError): String = when (error) {
    SubscriptionError.NETWORK -> "Не удалось загрузить подписку. Проверьте соединение и повторите"
    SubscriptionError.TIMEOUT -> "Время загрузки подписки истекло"
    SubscriptionError.HTTP -> "Сервер подписки вернул ошибку"
    SubscriptionError.MALFORMED -> "Не удалось прочитать данные подписки"
    SubscriptionError.INVALID_BASE64 -> "Некорректный формат Base64 в подписке"
    SubscriptionError.LIMIT_EXCEEDED -> "Превышен допустимый размер или количество записей подписки"
    SubscriptionError.NO_SUPPORTED -> "В подписке нет поддерживаемых серверов"
    SubscriptionError.INVALID_URL -> "Введите корректную ссылку"
    SubscriptionError.TLS -> "Не удалось установить защищённое соединение с сервером подписки"
    SubscriptionError.INTERNAL -> "Не удалось завершить операцию с подпиской"
}
