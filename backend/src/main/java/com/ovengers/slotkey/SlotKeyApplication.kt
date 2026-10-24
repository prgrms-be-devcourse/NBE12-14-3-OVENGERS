package com.ovengers.slotkey

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SlotKeyApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runApplication<SlotKeyApplication>(*args)
        }
    }
}
