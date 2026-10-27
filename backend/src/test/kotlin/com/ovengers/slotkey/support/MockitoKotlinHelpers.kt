package com.ovengers.slotkey.support

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers

/** Register the Java matcher while returning the supplied value to Kotlin's non-null call site. */
fun <T> eqNonNull(value: T): T {
    ArgumentMatchers.eq(value)
    return value
}

/** Use only as a Mockito argument: the generic bridge avoids platform-type null checks. */
fun <T> anyNonNull(type: Class<T>): T = ArgumentMatchers.any(type)

fun <T> anyNonNull(): T = ArgumentMatchers.any<T>()

/** The captor records the real invocation; its dummy return is only passed to the mock. */
fun <T> captureNonNull(captor: ArgumentCaptor<T>): T = captor.capture()

/** Preserve an explicit Java null in boundary tests or Mockito calls without changing production types. */
@Suppress("UNCHECKED_CAST")
fun <T> nullValue(): T = null as T
