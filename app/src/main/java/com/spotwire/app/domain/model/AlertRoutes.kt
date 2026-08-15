package com.spotwire.app.domain.model

/**
 * How an arrival alert is allowed to travel.
 *
 * In the app costs nothing and reaches any country, but only somebody who has an
 * account and has linked. A message reaches anyone, at the cost of the daily
 * text allowance or the sender's own WhatsApp gateway. Both is the default,
 * because being told twice is a smaller failure than not being told.
 */
object AlertRoutes {
    const val BOTH = "both"
    const val IN_APP = "in_app"
    const val MESSAGE = "message"

    fun allowsInApp(value: String) = value != MESSAGE
    fun allowsMessage(value: String) = value != IN_APP

    fun label(value: String): String = when (value) {
        IN_APP -> "In the app only"
        MESSAGE -> "Messages only"
        else -> "Both"
    }
}
