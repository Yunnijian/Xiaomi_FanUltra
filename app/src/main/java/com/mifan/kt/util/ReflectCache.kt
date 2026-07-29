package com.mifan.kt.util

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object ReflectCache {
    private data class FieldKey(val owner: Class<*>, val name: String)
    private data class MethodKey(val owner: Class<*>, val name: String, val parameterTypes: List<Class<*>>)

    private val fieldCache = ConcurrentHashMap<FieldKey, Field>()
    private val methodCache = ConcurrentHashMap<MethodKey, Method>()
    private val writeFailureLogged = ConcurrentHashMap.newKeySet<String>()

    fun Class<*>.findCachedField(fieldName: String): Field? {
        val key = FieldKey(this, fieldName)
        fieldCache[key]?.let { return it }
        var current: Class<*>? = this
        while (current != null) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                fieldCache[key] = field
                return field
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    fun Class<*>.findPublicOrDeclaredMethod(name: String, vararg parameterTypes: Class<*>): Method? {
        val key = MethodKey(this, name, parameterTypes.toList())
        methodCache[key]?.let { return it }
        try {
            val method = getMethod(name, *parameterTypes)
            method.isAccessible = true
            methodCache[key] = method
            return method
        } catch (_: NoSuchMethodException) {
        }
        var current: Class<*>? = this
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(name, *parameterTypes)
                method.isAccessible = true
                methodCache[key] = method
                return method
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    fun Class<*>.hasFieldCached(name: String): Boolean = findCachedField(name) != null

    fun Class<*>.hasMethodCached(name: String, vararg parameterTypes: Class<*>): Boolean =
        findPublicOrDeclaredMethod(name, *parameterTypes) != null

    fun Any.readFieldOrNull(fieldName: String): Any? {
        return try {
            javaClass.findCachedField(fieldName)?.get(this)
        } catch (_: Throwable) {
            null
        }
    }

    fun Any.writeFieldIfExists(fieldName: String, value: Any?): Boolean {
        return try {
            javaClass.findCachedField(fieldName)?.set(this, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun writeIntFieldIfExists(target: Any, fieldName: String, value: Int): Boolean {
        return try {
            target.javaClass.findCachedField(fieldName)?.setInt(target, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun shouldLogWriteFailure(owner: Class<*>, fieldName: String, operation: String, throwable: Throwable): Boolean {
        val key = "${owner.name}#$fieldName#$operation#${throwable.javaClass.name}"
        return writeFailureLogged.add(key)
    }

    fun Any.readFirstCharSequenceArrayField(names: Array<String>): Array<CharSequence>? {
        for (name in names) {
            val value = readFieldOrNull(name)
            if (value is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                return value as? Array<CharSequence>
            }
        }
        return null
    }

    fun Array<CharSequence>.append(value: CharSequence): Array<CharSequence> {
        if (any { it.toString() == value.toString() }) return this
        return this + value
    }
}
