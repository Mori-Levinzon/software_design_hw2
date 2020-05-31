package il.ac.technion.cs.softwaredesign

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException


// This is a tiny wrapper over assertTimeoutPreemptively which makes the syntax slightly nicer.
fun <T> runWithTimeout(timeout: Duration, executable: () -> T): T =
    assertTimeoutPreemptively(timeout, ThrowingSupplier(executable))

/**
 * Perform [CompletableFuture.join], and if an exception is thrown, unwrap the [CompletionException] and throw the
 * causing exception.
 */
fun <T> CompletableFuture<T>.joinException(): T {
    try {
        return this.join()
    } catch (e: CompletionException) {
        throw e.cause!!
    }
}