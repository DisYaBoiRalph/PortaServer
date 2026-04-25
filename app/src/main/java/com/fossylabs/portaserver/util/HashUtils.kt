package com.fossylabs.portaserver.util

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
