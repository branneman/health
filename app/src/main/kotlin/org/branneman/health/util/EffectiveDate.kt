package org.branneman.health.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

fun effectiveDate(now: LocalDateTime = LocalDateTime.now()): LocalDate =
    if (now.toLocalTime() < LocalTime.of(4, 0)) now.toLocalDate().minusDays(1)
    else now.toLocalDate()

fun effectiveDateFlow(context: Context): Flow<LocalDate> =
    callbackFlow<Unit> {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) { trySend(Unit) }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }.flatMapLatest {
        flow {
            while (true) {
                val now = LocalDateTime.now()
                emit(effectiveDate(now))
                val next4am = if (now.toLocalTime() < LocalTime.of(4, 0)) {
                    now.toLocalDate().atTime(4, 0)
                } else {
                    now.toLocalDate().plusDays(1).atTime(4, 0)
                }
                delay(ChronoUnit.MILLIS.between(now, next4am))
            }
        }
    }
