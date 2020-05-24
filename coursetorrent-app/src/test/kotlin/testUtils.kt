package il.ac.technion.cs.softwaredesign

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration


// This is a tiny wrapper over assertTimeoutPreemptively which makes the syntax slightly nicer.
fun <T> runWithTimeout(timeout: Duration, executable: () -> T): T =
    assertTimeoutPreemptively(timeout, ThrowingSupplier(executable))