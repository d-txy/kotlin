/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:kotlin.jvm.JvmName("TuplesKt")

package kotlin


/**
 * Represents a generic pair of two values.
 *
 * There is no meaning attached to values in this class, it can be used for any purpose.
 * Pair exhibits value semantics, i.e. two pairs are equal if both components are equal.
 *
 * An example of decomposing it into values:
 * @sample samples.misc.Tuples.pairDestructuring
 *
 * @param A type of the first value.
 * @param B type of the second value.
 * @property first First value.
 * @property second Second value.
 * @constructor Creates a new instance of Pair.
 */
public data class Pair<out A, out B>(
    public val first: A,
    public val second: B
) : Serializable {

    /**
     * Returns string representation of the [Pair] including its [first] and [second] values.
     */
    public override fun toString(): String = "($first, $second)"
}

/**
 * Creates a tuple of type [Pair] from this and [that].
 *
 * This can be useful for creating [Map] literals with less noise, for example:
 * @sample samples.collections.Maps.Instantiation.mapFromPairs
 */
public infix fun <A, B> A.to(that: B): Pair<A, B> = Pair(this, that)

/**
 * Converts this pair into a list.
 * @sample samples.misc.Tuples.pairToList
 */
public fun <T> Pair<T, T>.toList(): List<T> = listOf(first, second)

/**
 * Represents a triad of values
 *
 * There is no meaning attached to values in this class, it can be used for any purpose.
 * Triple exhibits value semantics, i.e. two triples are equal if all three components are equal.
 * An example of decomposing it into values:
 * @sample samples.misc.Tuples.tripleDestructuring
 *
 * @param A type of the first value.
 * @param B type of the second value.
 * @param C type of the third value.
 * @property first First value.
 * @property second Second value.
 * @property third Third value.
 */
public data class Triple<out A, out B, out C>(
    public val first: A,
    public val second: B,
    public val third: C
) : Serializable {

    /**
     * Returns string representation of the [Triple] including its [first], [second] and [third] values.
     */
    public override fun toString(): String = "($first, $second, $third)"
}

/**
 * Converts this triple into a list.
 * @sample samples.misc.Tuples.tripleToList
 */
public fun <T> Triple<T, T, T>.toList(): List<T> = listOf(first, second, third)

public class Tuple<Ts>(size: Int = 0) : Iterable<Any?> {
    private val elements: Array<Any?> = arrayOfNulls(size)
    val size: Int
        get() = elements.size

    operator fun get(index: Int): Any? = elements[index]
    operator fun set(index: Int, value: Any?) {
        elements[index] = value
    }

    fun clone(): Tuple<Ts> {
        val newTuple = Tuple<Ts>(size)
        for (i in 0..elements.lastIndex) {
            newTuple[i] = get(i)
        }
        return newTuple
    }

    override fun iterator(): Iterator<Any?> {
        return object : Iterator<Any?> {
            private var currentIndex = 0
            override fun next() = get(currentIndex++)
            override fun hasNext() = currentIndex < size
        }
    }
}

public operator fun <Ts> Tuple<Ts>.component1(): Any? = get(0)
public operator fun <Ts> Tuple<Ts>.component2(): Any? = get(1)
public operator fun <Ts> Tuple<Ts>.component3(): Any? = get(2)
public operator fun <Ts> Tuple<Ts>.component4(): Any? = get(3)
public operator fun <Ts> Tuple<Ts>.component5(): Any? = get(4)
